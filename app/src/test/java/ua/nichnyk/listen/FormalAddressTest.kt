package ua.nichnyk.listen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Застосунок звертається до слухача однаково: німецька — на «Sie», французька — на «vous».
 *
 * 18 листопада 2026 у німецькій знайшлося шість рядків на «du» (нічний таймер, автокопія,
 * «Повідомити про проблему», зниклі файли) серед ~400 на «Sie»: нові рядки перекладали
 * окремо від старих, і змішування на рев'ю не видно.
 *
 * 19 листопада 2026 правило поширено на тексти магазину: опис, короткий опис і «Що нового»
 * (`fastlane/metadata/android/<мова>/`) та відповідний розділ найновішого
 * `docs/play-store/listing-*-all-languages.md`. Сторінка в Play звертається так само, як
 * інтерфейс, який людина побачить після встановлення. Старі `listing-*` — архів уже
 * опублікованого, їх не перевіряємо.
 *
 * Межа слова — через `\p{L}`, а не `\b`: у Java 19+ `\b` вважає «ê» не-літерою, і «êtes»
 * збігалося б із «tes».
 */
class FormalAddressTest {

    /** Робочий каталог юніт-тестів Gradle — каталог модуля, тобто `app/`. */
    private val root = File("..")

    private fun words(vararg w: String, ignoreCase: Boolean) = Regex(
        """(?<!\p{L})(${w.joinToString("|")})(?!\p{L})""",
        if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet(),
    )

    private val du = words("du", "dich", "dir", "dein", "deine", "deinen", "deinem", "deiner", "deines", ignoreCase = true)
    private val tu = words("tu", "toi", "ton", "ta", "tes", ignoreCase = false)

    private fun uiStrings(dir: String): List<String> =
        File("src/main/res/$dir/strings.xml").readLines().filter { "<string" in it || "<item" in it }

    /** Усі тексти магазину однієї мови з `fastlane/`, з назвою файла перед кожним рядком. */
    private fun fastlaneLines(locale: String): List<String> {
        val dir = File(root, "fastlane/metadata/android/$locale")
        assertTrue("Немає $dir", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "txt" }
            .flatMap { f -> f.readLines().map { "${f.relativeTo(dir)}: $it" } }.toList()
    }

    /** Розділ `## <locale>` найновішого `listing-X.Y-all-languages.md`. */
    private fun latestListingSection(locale: String): List<String> {
        val files = File(root, "docs/play-store").listFiles { f -> f.name.matches(Regex("""listing-\d+\.\d+-all-languages\.md""")) }
            .orEmpty()
        val latest = files.maxByOrNull { f ->
            val (major, minor) = Regex("""(\d+)\.(\d+)""").find(f.name)!!.destructured
            major.toInt() * 1000 + minor.toInt()
        } ?: return emptyList()
        val lines = latest.readLines()
        val start = lines.indexOfFirst { it.trim() == "## $locale" }
        assertTrue("У ${latest.name} немає розділу «## $locale»", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start + 1, end).map { "${latest.name}: $it" }
    }

    private fun assertNone(what: String, lines: List<String>, informal: Regex) {
        val bad = lines.filter { informal.containsMatchIn(it) }
        assertTrue("$what:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun germanUsesSie() = assertNone("Рядки на «du» серед інтерфейсу на «Sie»", uiStrings("values-de"), du)

    @Test
    fun frenchUsesVous() = assertNone("Рядки на «tu» серед інтерфейсу на «vous»", uiStrings("values-fr"), tu)

    @Test
    fun germanStoreTextsUseSie() =
        assertNone("Тексти магазину німецькою на «du», а інтерфейс на «Sie»", fastlaneLines("de-DE") + latestListingSection("de-DE"), du)

    @Test
    fun frenchStoreTextsUseVous() =
        assertNone("Тексти магазину французькою на «tu», а інтерфейс на «vous»", fastlaneLines("fr-FR") + latestListingSection("fr-FR"), tu)

    /** Регулярки самі по собі: ловлять звертання і не чіпають схожі слова. */
    @Test
    fun patternsMatchOnlyWholeWords() {
        assertTrue(du.containsMatchIn("Ordner deiner Wahl"))
        assertTrue(du.containsMatchIn("Du kannst"))
        assertTrue(!du.containsMatchIn("Fügen Sie dudelnd Dürer hinzu"))
        assertTrue(tu.containsMatchIn("ton dossier"))
        assertTrue(!tu.containsMatchIn("vous vous êtes arrêté"))
        assertTrue(!tu.containsMatchIn("Lecture automatique, tuile"))
    }
}
