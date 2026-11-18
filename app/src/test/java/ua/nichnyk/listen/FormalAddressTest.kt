package ua.nichnyk.listen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Інтерфейс звертається до слухача однаково: німецька — на «Sie», французька — на «vous».
 *
 * 18 листопада 2026 у німецькій знайшлося шість рядків на «du» (нічний таймер, автокопія,
 * «Повідомити про проблему», зниклі файли) серед ~400 на «Sie»: нові рядки перекладали
 * окремо від старих, і змішування на рев'ю не видно. Тексти для магазину (`fastlane/`) — не
 * інтерфейс, тут не перевіряються.
 */
class FormalAddressTest {

    /** Робочий каталог юніт-тестів Gradle — каталог модуля, тобто `app/`. */
    private fun strings(dir: String) = File("src/main/res/$dir/strings.xml").readLines()

    private fun offenders(dir: String, informal: Regex): List<String> =
        strings(dir).filter { "<string" in it || "<item" in it }.filter { informal.containsMatchIn(it) }

    @Test
    fun germanUsesSie() {
        val du = Regex("""\b(du|dich|dir|dein|deine|deinen|deinem|deiner|deines)\b""", RegexOption.IGNORE_CASE)
        val bad = offenders("values-de", du)
        assertTrue("Рядки на «du» серед інтерфейсу на «Sie»:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun frenchUsesVous() {
        val tu = Regex("""\b(tu|toi|ton|ta|tes)\b""")
        val bad = offenders("values-fr", tu)
        assertTrue("Рядки на «tu» серед інтерфейсу на «vous»:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
