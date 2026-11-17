package ua.nichnyk.listen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рядки поза екранами Compose — лише мовою застосунку.
 *
 * Мову вибирають у налаштуваннях. На API 26–32 `AppCompatDelegate` перевдягає
 * лише активності, а сервіс, віджет, плитка і репозиторій через звичайний
 * `getString` отримують **системну** мову. 17 листопада 2026 так знайшлися
 * кнопки шторки й дерево Auto в `PlaybackService`, а також назви «Розділ N» і
 * «Невідомий автор» в `AudioImporter`. Ці назви ще й записуються в базу, тож
 * хибна мова лишалася в книзі назавжди. Див. [forAppLocale].
 *
 * Тест читає джерела як текст, так само як [BookTitleDisplayTest]: кожне окреме
 * `getString(...)` виглядає доречним і на рев'ю не помітне. Дозволено
 * `forAppLocale().getString(` і `res.getString(`, де `res = ….forAppLocale()`.
 */
class AppLocaleStringsTest {

    /** Робочий каталог юніт-тестів Gradle — каталог модуля, тобто `app/`. */
    private val root = File("src/main/java/ua/nichnyk/listen")

    /** Каталоги й файли, де немає активності, яку AppCompat перевдягнув би. */
    private val scopes = listOf("data", "playback", "ui/widget", "ui/quick", "work", "sync")

    private val rawLookup = Regex("""(?<![\w.])(?:\w+\.)?get(?:String|QuantityString)\(R\.""")

    private fun allowed(match: String) = match.startsWith("res.")

    @Test
    fun stringsOutsideComposeUseAppLocale() {
        val offenders = scopes.map { File(root, it) }.filter { it.exists() }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val bad = rawLookup.findAll(line)
                        .filterNot { allowed(it.value) }
                        // `x.forAppLocale().getString(` — lookbehind бачить `)`, а не крапку перед
                        // `getString`, тож перевіряємо явно.
                        .filterNot { line.substring(0, it.range.first).endsWith("forAppLocale().") }
                        .toList()
                    if (bad.isEmpty()) null else "${file.relativeTo(root)}:${i + 1}: ${line.trim()}"
                }
            }
        assertTrue(
            "Рядки беруться повз forAppLocale() — на Android 8–12 вони вийдуть системною мовою:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun scopesExist() {
        // Якщо пакет перейменують, тест мовчки перестав би щось перевіряти.
        assertTrue(File(root, "playback/PlaybackService.kt").exists())
        assertTrue(File(root, "data/AudioImporter.kt").exists())
    }
}
