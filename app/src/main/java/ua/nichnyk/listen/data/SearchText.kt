package ua.nichnyk.listen.data

import java.text.Normalizer

/**
 * Пошук по тексту: полиця, закладки й голосовий пошук Android Auto.
 *
 * Раніше це був суцільний підрядок без нормалізації. Тому запит «все» не знаходив «Всё»,
 * апостроф `'` не збігався з `’` («Прив'яз» / «Прив’яз»), а «кобзар шевченко» — назва плюс
 * автор — не знаходив нічого, хоч обидва слова є в книги.
 *
 * Тепер текст і запит проходять **ту саму** нормалізацію, і запит збігається, коли кожне його
 * слово є в одному з полів. Нормалізація однакова для обох боків, тож вона може лише розширити
 * збіги, але не зробити їх несиметричними.
 */
object SearchText {

    /** Усі види апострофа й «ґравіса»: прибираємо, а не замінюємо пробілом, щоб слово лишалось цілим. */
    private val APOSTROPHES = setOf('\'', 'ʼ', '’', '‘', '`', '´', '′', 'ʹ')

    private val COMBINING_MARKS = Regex("\\p{M}+")

    /**
     * Нижній регістр, без діакритики (`é`→`e`, `ё`→`е`, `й`→`и`), без апострофів, а будь-які
     * інші не-літери й не-цифри стають одним пробілом. Порожній результат — у тексту не було слів.
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val noMarks = COMBINING_MARKS.replace(decomposed, "")
        val out = StringBuilder(noMarks.length)
        var lastSpace = true
        for (ch in noMarks) {
            when {
                ch in APOSTROPHES -> Unit
                ch.isLetterOrDigit() -> {
                    out.append(ch)
                    lastSpace = false
                }
                !lastSpace -> {
                    out.append(' ')
                    lastSpace = true
                }
            }
        }
        return out.toString().trimEnd()
    }

    /** Слова запиту після нормалізації. */
    fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    /**
     * Чи збігається [query] з однією з [fields]: кожне слово запиту має бути підрядком
     * поля (або разом у склеєному тексті всіх полів, тож «кобзар шевченко» знаходить книгу за
     * назвою й автором). Порожній запит збігається з усім.
     *
     * Запит із самих розділових знаків слів не має: тоді шукаємо його як є, без нормалізації,
     * щоб «?» не показував усю полицю.
     */
    fun matches(query: String, vararg fields: String?): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val words = tokens(trimmed)
        if (words.isEmpty()) return fields.any { it?.contains(trimmed, ignoreCase = true) == true }
        val haystack = fields.filterNotNull().joinToString(" ") { normalize(it) }
        return words.all { haystack.contains(it) }
    }
}
