package ua.nichnyk.listen.data

/**
 * Чи є ця книга вже на полиці.
 *
 * Дві незалежні ознаки, і кожна ловить свій випадок:
 *  - **той самий набір URI** — користувач указав ту саму теку вдруге. Найнадійніша
 *    ознака, працює навіть коли книгу вже перейменували вручну;
 *  - **однакова назва + однаковий відбиток глав** — ті самі файли, скопійовані в інше
 *    місце (URI відрізняються, вміст ні).
 *
 * Винесено з LibraryRepository без змін у поведінці: логіка не залежала ні від Room,
 * ні від Context, але жила методом класу, який без них не створити.
 */
object DuplicateDetector {

    /**
     * Перша книга з [books], яку слід вважати тією самою, або null.
     *
     * [books] — уже прочитаний знімок полиці: імпорт теки додає книги пачкою, і читати
     * полицю заново на кожну з них означало б квадратичну роботу на рівному місці.
     */
    fun findIn(
        books: List<BookWithChapters>,
        title: String,
        chapterTitles: List<String>,
        durations: List<Long>,
        uris: List<String>,
    ): BookWithChapters? {
        val incomingUris = uris.toSet()
        val incomingPrint = fingerprint(chapterTitles, durations)
        val durationsKnown = durations.isNotEmpty() && durations.all { it > 0 }
        return books.firstOrNull { book ->
            val existingUris = book.chapters.map { it.uri }.toSet()
            if (incomingUris.isNotEmpty() && existingUris == incomingUris) return@firstOrNull true
            // За номером: Room віддає глави без гарантованого порядку.
            val chapters = book.chapters.sortedBy { it.index }
            chapters.size == chapterTitles.size &&
                book.book.title.equals(title, ignoreCase = true) &&
                (
                    fingerprint(chapters.map { it.title }, chapters.map { it.durationMs }) == incomingPrint ||
                        // Глави можна перейменувати на одному пристрої, і назви розійдуться.
                        // Однакові відомі тривалості всіх глав тієї самої книги — досить.
                        (durationsKnown && chapters.map { it.durationMs } == durations)
                    )
        }
    }

    /**
     * Чи вже є на полиці книга з будь-яким із цих URI (повний збіг набору або
     * хоча б один спільний файл — наприклад, після доливання розділів).
     */
    fun overlapsUris(books: List<BookWithChapters>, uris: List<String>): Boolean {
        val incoming = uris.toSet()
        if (incoming.isEmpty()) return true
        return books.any { book ->
            val existing = book.chapters.map { it.uri }.toSet()
            existing == incoming || incoming.any { it in existing }
        }
    }

    /** Назви глав із тривалостями, нормалізовані до порівнюваного рядка. */
    fun fingerprint(titles: List<String>, durations: List<Long>): String =
        titles.zip(durations).joinToString("\n") { (t, d) -> "${t.trim().lowercase()}|$d" }
}
