package ua.nichnyk.listen.data

/**
 * Єдине правило вибору «останньої книги» для відновлення відтворення.
 *
 * Раніше воно було подвійним: сервіс (відновлення після перезапуску, кнопка
 * гарнітури, Auto) брав незавершену книгу з найсвіжішим `lastPlayedAt`, а
 * PlayerManager при старті застосунку — книгу зі збереженого `lastBookId`. Через
 * це телефон і машина могли показувати різні «останні» книги.
 *
 * Порядок:
 *  1. книга з [preferredId] (`lastBookId`), якщо вона є й не дослухана;
 *  2. найсвіжіша за `lastPlayedAt` недослухана книга;
 *  3. книга з [preferredId], навіть дослухана (її відкриють з початку);
 *  4. найсвіжіша з будь-яких слуханих;
 *  5. перша на полиці.
 */
fun pickResumeBook(books: List<BookWithChapters>, preferredId: String?): BookWithChapters? {
    val preferred = preferredId?.let { id -> books.firstOrNull { it.book.id == id } }
    if (preferred != null && !preferred.book.completed) return preferred
    val started = books.filter { it.book.lastPlayedAt != null }
    return started.filter { !it.book.completed }.maxByOrNull { it.book.lastPlayedAt ?: 0L }
        ?: preferred
        ?: started.maxByOrNull { it.book.lastPlayedAt ?: 0L }
        ?: books.firstOrNull()
}
