package ua.nichnyk.listen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Повне відображення назв» як перевірка, а не як обіцянка в README.
 *
 * Назви книг показуються повністю — без `maxLines` і трикрапки — на полиці, на
 * екрані книги, в плеєрі, закладках, історії та черзі. Це рішення, а не
 * недогляд: обмежити назву двома рядками здається очевидним поліпшенням
 * (довга назва розсуває смужку міні-плеєра й робить комірки сітки різної
 * висоти), і саме тому воно раз у раз повертається під час чергового розбору
 * коду. Одного разу так і сталося — тихо, у чотирьох місцях одразу, усупереч
 * розділу «Що вміє» в README.
 *
 * Тест читає джерела інтерфейсу як текст — той самий підхід, що в
 * [LocalizationResourcesTest]: розбіжність такого роду не падає на збірці й не
 * видно на рев'ю, бо кожне окреме `maxLines = 2` виглядає доречним.
 *
 * Підзаголовки під назвою (автор, глава, нотатка) обрізати можна й треба —
 * перевіряється рівно назва книги. Віджет на робочому столі теж не рахується:
 * там висота задана лаунчером, і `android:maxLines="1"` у `widget_title` —
 * вимушений, а не обраний.
 *
 * Якщо правило колись вирішать змінити — міняти разом із README, а цей тест
 * видалити свідомо, а не «полагодити».
 */
class BookTitleDisplayTest {

    /** Робочий каталог юніт-тестів Gradle — каталог модуля, тобто `app/`. */
    private val uiDir = File("src/main/java/ua/nichnyk/listen/ui")

    /** Вирази, якими в інтерфейсі малюють саме назву книги. */
    private val bookTitle = Regex("""\bbook\.title\b|\bbookTitle\b""")

    @Test
    fun bookTitleIsNeverTruncated() {
        val sources = uiDir.walkTopDown().filter { it.extension == "kt" }.toList()
        // Інакше зміна розкладки каталогів перетворила б тест на такий, що
        // мовчки не перевіряє нічого.
        assertTrue("не знайдено джерел інтерфейсу в $uiDir", sources.size >= 10)

        val violations = mutableListOf<String>()
        for (file in sources) {
            val text = file.readText()
            for (call in textCalls(text)) {
                if (!bookTitle.containsMatchIn(call.body)) continue
                if ("maxLines" in call.body) {
                    violations += "${file.name}:${lineOf(text, call.start)}"
                }
            }
        }

        assertEquals(
            "назва книги обрізається — див. README, розділ «Що вміє»: " +
                "«Повне відображення назв… без примусового обрізання та трикрапки»",
            emptyList<String>(),
            violations,
        )
    }

    private data class TextCall(val start: Int, val body: String)

    /**
     * Виклики `Text(…)` з тілом до парної дужки.
     *
     * Рядкові літерали пропускаються: у них трапляються непарні дужки
     * («Role (e.g. Protagonist»), і без цього лічильник поїхав би далі за
     * кінець виклику.
     */
    private fun textCalls(source: String): List<TextCall> {
        val calls = mutableListOf<TextCall>()
        val marker = Regex("""(?<![A-Za-z0-9_.])Text\(""")
        for (match in marker.findAll(source)) {
            val open = match.range.last
            val close = matchingParen(source, open) ?: continue
            calls += TextCall(match.range.first, source.substring(open + 1, close))
        }
        return calls
    }

    private fun matchingParen(source: String, openIndex: Int): Int? {
        var depth = 0
        var i = openIndex
        while (i < source.length) {
            when (source[i]) {
                '"' -> i = skipString(source, i)
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** Повертає індекс закривальної лапки; підтримує `"…"` і `"""…"""`. */
    private fun skipString(source: String, start: Int): Int {
        if (source.startsWith("\"\"\"", start)) {
            val end = source.indexOf("\"\"\"", start + 3)
            return if (end == -1) source.length else end + 2
        }
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                '"' -> return i
                '\n' -> return i - 1
            }
            i++
        }
        return source.length
    }

    private fun lineOf(source: String, index: Int): Int =
        source.substring(0, index).count { it == '\n' } + 1
}
