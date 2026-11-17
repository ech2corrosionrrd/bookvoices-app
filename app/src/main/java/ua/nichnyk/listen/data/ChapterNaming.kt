package ua.nichnyk.listen.data

/**
 * Назви глав, які задає слухач.
 *
 * Структуру книги (кількість глав, межі, файли) тут не чіпаємо свідомо: прогрес між
 * пристроями передається номером глави й позицією в ній, а синхронізація не зливає
 * глави наявної книги. Ділення чи об'єднання на одному пристрої зсунуло б номери, і
 * прогрес та закладки на другому вказували б не туди. Назва — лише підпис.
 */
object ChapterNaming {

    /** Межа, щоб випадково вставлений абзац не став назвою глави. */
    const val MAX_LENGTH = 200

    /** Охайна назва або null, якщо після прибирання нічого не лишилось (стару тоді не чіпаємо). */
    fun clean(input: String): String? =
        input.replace(Regex("""\s+"""), " ").trim().take(MAX_LENGTH).trim().ifEmpty { null }

    /**
     * «Пронумерувати»: id глави → «Розділ N» за порядком у книзі.
     * [label] — локалізований шаблон, номер з одиниці.
     */
    fun numbered(chapters: List<ChapterEntity>, label: (Int) -> String): Map<String, String> =
        chapters.sortedBy { it.index }
            .mapIndexed { i, ch -> ch.id to label(i + 1) }
            .toMap()

    /** Лише ті назви, які справді змінюються: менше записів і чесне «нічого не змінилось». */
    fun changes(chapters: List<ChapterEntity>, wanted: Map<String, String>): Map<String, String> {
        val current = chapters.associate { it.id to it.title }
        return wanted.filter { (id, title) -> id in current && current[id] != title }
    }
}
