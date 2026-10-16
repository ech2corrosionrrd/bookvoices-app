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
    private class Entry(val uris: Set<String>, val gone: Set<String>, val atMs: Long)

    @Volatile
    private var last: Entry? = null

    /** Ті з [uris], що не відкриваються. */
    suspend fun inaccessible(uris: Collection<String>): Set<String> {
        val key = uris.toSet()
        val cached = last
        if (cached != null && cached.uris == key && clock() - cached.atMs < ttlMs) {
            return cached.gone
        }
        val gone = probe(key)
        last = Entry(key, gone, clock())
        return gone
    }

    /** Забути результат: після перепривʼязки книги стара відповідь уже неправдива. */
    fun invalidate() {
        last = null
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L
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
