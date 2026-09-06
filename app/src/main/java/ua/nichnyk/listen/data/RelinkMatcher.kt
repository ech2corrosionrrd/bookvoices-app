package ua.nichnyk.listen.data

/**
 * Зіставлення глав книги з файлами в новій теці.
 *
 * Застосунок навмисно не копіює аудіо до себе: файли лишаються там, де їх обрали.
 * Зворотний бік — варто перенести теку, вставити іншу карту пам'яті чи перевстановити
 * систему, і вся полиця стає непридатною. Раніше вихід був один: видалити книгу й
 * імпортувати заново, втративши прогрес, швидкість і всі закладки — на тридцятигодинній
 * книзі це найдорожча можлива втрата.
 *
 * Тут — тільки правило зіставлення, без SAF і без бази, щоб його можна було перевірити.
 */
object RelinkMatcher {

    /** Файл, знайдений у новій теці. */
    data class Candidate(
        val name: String,
        val uri: String,
        /** Шлях відносно кореня SAF-дерева (`CD1/01.mp3`); порожній — якщо невідомий. */
        val relativePath: String = "",
    )

    /**
     * Старий URI глави -> новий.
     *
     * Працюємо з **різними** URI, а не з главами: у m4b усі глави посилаються на один
     * файл, і зіставляти його треба один раз на всю книгу.
     *
     * Дві спроби, у порядку надійності:
     *  1. **збіг імені файла** — тека переїхала, вміст той самий. Так зіставляється
     *     навіть частково перейменована книга;
     *  2. **позиційно** — якщо після першого кроку кількість незіставлених глав точно
     *     дорівнює кількості невикористаних файлів. Це випадок «файли перейменували
     *     скопом» (01.mp3 -> Розділ 01.mp3), і природний порядок тут єдиний орієнтир.
     *
     * Якщо кількості не збігаються, другий крок не робиться взагалі: краще зіставити
     * менше, ніж зшити главу з чужим файлом і мовчки зіпсувати книгу.
     */
    fun match(oldUris: List<String>, candidates: List<Candidate>): Map<String, String> {
        val distinctOld = oldUris.distinct()
        if (distinctOld.isEmpty() || candidates.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, String>()
        val usedUris = mutableSetOf<String>()

        // 1. За іменем файла.
        val byName = LinkedHashMap<String, MutableList<Candidate>>()
        for (candidate in candidates) {
            byName.getOrPut(candidate.name.lowercase()) { mutableListOf() }.add(candidate)
        }
        for (old in distinctOld) {
            val name = fileNameOf(old).lowercase()
            if (name.isEmpty()) continue
            val bucket = byName[name] ?: continue
            val free = bucket.firstOrNull { it.uri !in usedUris } ?: continue
            result[old] = free.uri
            usedUris += free.uri
        }

        // 2. Позиційно — лише за точної відповідності кількостей.
        val unmatched = distinctOld.filter { it !in result }
        val unused = candidates.filter { it.uri !in usedUris }
        if (unmatched.isNotEmpty() && unmatched.size == unused.size) {
            val orderedOld = unmatched.sortedWith { a, b ->
                AudioImporter.naturalCompare(fileNameOf(a), fileNameOf(b))
            }
            val orderedNew = unused.sortedWith { a, b -> AudioImporter.naturalCompare(a.name, b.name) }
            for ((old, candidate) in orderedOld.zip(orderedNew)) {
                result[old] = candidate.uri
            }
        }

        return result
    }

    /**
     * Файли з теки, яких ще немає серед глав книги.
     *
     * Порядок перевірок:
     *  1. точний URI;
     *  2. відносний шлях (`CD1/01.mp3`) — щоб диски з однаковими іменами файлів
     *     не злипалися;
     *  3. basename лише коли він унікальний і серед відомих, і серед кандидатів
     *     (після перепривʼязки URI інший, а файл один).
     *
     * Порядок у результаті — натуральний, як при імпорті.
     */
    fun newFiles(knownUris: List<String>, candidates: List<Candidate>): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val knownUriSet = knownUris.toSet()
        val knownPaths = knownUris.mapNotNull { documentPathOf(it)?.lowercase() }
        val knownNameCounts = knownUris
            .map { fileNameOf(it).lowercase() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
        val candidateNameCounts = candidates
            .map { it.name.lowercase() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
        return candidates
            .filter { candidate ->
                if (candidate.uri in knownUriSet) return@filter false
                val rel = candidate.relativePath.trim().trim('/').lowercase()
                if (rel.isNotEmpty() && knownPaths.any { path -> path == rel || path.endsWith("/$rel") }) {
                    return@filter false
                }
                val name = candidate.name.lowercase()
                if (name.isNotEmpty() &&
                    knownNameCounts[name] == 1 &&
                    candidateNameCounts[name] == 1
                ) {
                    return@filter false
                }
                true
            }
            .sortedWith { a, b ->
                val aKey = a.relativePath.ifBlank { a.name }
                val bKey = b.relativePath.ifBlank { b.name }
                AudioImporter.naturalCompare(aKey, bKey)
            }
    }

    /**
     * Ім'я файла з URI будь-якого вигляду.
     *
     * У SAF останній сегмент — це весь documentId (`primary:Books/Кобзар/01.mp3`),
     * тому різати доводиться і по `/`, і по `:`.
     */
    fun fileNameOf(uri: String): String {
        val withoutQuery = uri.substringBefore('?').substringBefore('#')
        val decoded = runCatching {
            java.net.URLDecoder.decode(withoutQuery, Charsets.UTF_8.name())
        }.getOrDefault(withoutQuery)
        return decoded.substringAfterLast('/').substringAfterLast(':').trim()
    }

    /**
     * Шлях документа всередині сховища: `Books/Author/CD1/01.mp3`.
     * Потрібен для порівняння з [Candidate.relativePath] без плутанини CD1/CD2.
     */
    fun documentPathOf(uri: String): String? {
        val withoutQuery = uri.substringBefore('?').substringBefore('#')
        val decoded = runCatching {
            java.net.URLDecoder.decode(withoutQuery, Charsets.UTF_8.name())
        }.getOrDefault(withoutQuery)
        val afterColon = decoded.substringAfterLast(':', missingDelimiterValue = "")
            .trim()
            .trim('/')
        if (afterColon.isNotEmpty() && afterColon.contains('/')) return afterColon
        if (afterColon.isNotEmpty()) return afterColon
        val filePath = decoded.substringAfter("file://", missingDelimiterValue = "")
            .trim()
            .trim('/')
        return filePath.ifEmpty { null }
    }
}

/** Скільки глав удалося перепривʼязати. */
data class RelinkResult(val matched: Int, val total: Int) {
    val isComplete: Boolean get() = total > 0 && matched == total
    val isEmpty: Boolean get() = matched == 0
}
