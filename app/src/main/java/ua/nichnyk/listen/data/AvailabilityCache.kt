package ua.nichnyk.listen.data

/**
 * Скільки файлів зараз не відкривається — із короткою памʼяттю.
 *
 * Перевірка доступності — це Binder-виклик на кожен файл (близько секунди на
 * 1400 файлів), а Android Auto питає список книг на кожен вхід у вузол. Без
 * памʼяті кожен екран у машині коштував цієї секунди. Хвилина — той самий
 * компроміс, що й для скану полиці: файли, які зникли поза застосунком, ще
 * встигають проявитися, а десяток переходів між екранами — вже ні.
 *
 * Памʼять діє, лише поки набір файлів той самий: додана чи видалена книга
 * змінює ключ, і перевірка робиться наново.
 *
 * [probe] і [clock] — шви для тестів: у застосунку це `repo::inaccessibleUris` і
 * системний годинник.
 */
class AvailabilityCache(
    private val probe: suspend (Collection<String>) -> Set<String>,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val gone: Set<String>, val atMs: Long)

    /**
     * Невеликий LRU за набором файлів: сервіс Auto питає про різні набори (уся
     * полиця, окремий автор, одна книга), і з єдиним записом вони викидали один
     * одного з кешу при кожному кроці по дереву.
     */
    private val entries = object : LinkedHashMap<Set<String>, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Set<String>, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    /** Ті з [uris], що не відкриваються. */
    suspend fun inaccessible(uris: Collection<String>): Set<String> {
        val key = uris.toSet()
        val cached = synchronized(entries) { entries[key] }
        if (cached != null && clock() - cached.atMs < ttlMs) {
            return cached.gone
        }
        val gone = probe(key)
        synchronized(entries) { entries[key] = Entry(gone, clock()) }
        return gone
    }

    /** Забути результат: після перепривʼязки книги стара відповідь уже неправдива. */
    fun invalidate() {
        synchronized(entries) { entries.clear() }
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L

        /** Скільки різних наборів файлів тримаємо одночасно. */
        const val MAX_ENTRIES = 8
    }
}

/**
 * Книги без жодного відкритого файла прибираємо; з частково втраченими лишаємо —
 * решту розділів слухати можна. Книга без розділів не вважається втраченою:
 * нема чого перевіряти.
 */
fun List<BookWithChapters>.withoutGone(gone: Set<String>): List<BookWithChapters> {
    if (gone.isEmpty()) return this
    return filterNot { item ->
        val uris = item.chapters.map { it.uri }.distinct()
        uris.isNotEmpty() && uris.all { it in gone }
    }
}
