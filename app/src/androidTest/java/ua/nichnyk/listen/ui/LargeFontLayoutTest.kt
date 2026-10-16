package ua.nichnyk.listen.ui

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.net.toUri
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.MainActivity
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.TestAudio
import ua.nichnyk.listen.data.newId

/**
 * Великий шрифт на вузькому екрані: жодне слово не розривається посеред.
 *
 * Помилка цього класу тиха й безглузда: «BookV / oices», «Sta / rt», «Bookmark / s» — нічого не
 * падає, юніт-тести зелені, а екран виглядає зламаним. До цього тесту її ловили лише очима.
 *
 * Перевірка однакова для всіх екранів: з семантики кожного текстового вузла береться готова
 * розкладка тексту, і для кожного переходу між рядками дивимось, чи стоять по обидва боки літери
 * чи цифри. Перехід на пробілі чи розділовому знаку — нормальний перенос, посеред слова — вада.
 *
 * Масштаб шрифту й щільність виставляються системними налаштуваннями до запуску активності й
 * відновлюються після тесту. 480 dpi на екрані 1080 px дає ширину 360 dp — типовий вузький телефон.
 */
@RunWith(AndroidJUnit4::class)
class LargeFontLayoutTest {

    private class LargeFont : ExternalResource() {
        private var oldScale = "1.0"
        private var changedDensity = false

        override fun before() {
            oldScale = shell("settings get system font_scale").trim()
                .takeIf { it.isNotEmpty() && it != "null" && it != "2.0" } ?: "1.0"
            // Перший «Start» питає дозвіл на сповіщення (API 33+); системний діалог перекриває вікно, і
            // тест бачить порожню ієрархію. На старших версіях команда просто нічого не робить.
            shell("pm grant ${InstrumentationRegistry.getInstrumentation().targetContext.packageName} android.permission.POST_NOTIFICATIONS")
            shell("settings put system font_scale 2.0")
            shell("wm density 480")
            changedDensity = true
            Thread.sleep(1_500)
        }

        override fun after() {
            if (changedDensity) shell("wm density reset")
            shell("settings put system font_scale $oldScale")
            Thread.sleep(500)
        }
    }

    private val activity = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(LargeFont()).around(activity)

    private val compose get() = activity
    private val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<ListenApp>()
    private lateinit var dir: File
    private var bookId = ""

    @Before
    fun setUp() = runBlocking {
        dir = File(app.cacheDir, "font-test").apply { deleteRecursively(); mkdirs() }
        val file = TestAudio.writeWav(File(dir, "1.wav"), seconds = 20)
        bookId = newId()
        val dao = app.container.db.library()
        dao.upsertBook(
            BookEntity(
                id = bookId,
                title = "Главный инженер 2_[Sibiryak]",
                author = "Карелин Сергей, Фарг Вадим",
                coverPath = null,
                addedAt = System.currentTimeMillis(),
                lastPlayedAt = null,
                durationMs = 20_000L,
                positionMs = 0L,
                currentChapterIndex = 0,
                playbackSpeed = 1f,
                completed = false,
            ),
        )
        dao.upsertChapters(listOf(ChapterEntity(newId(), bookId, 0, "Глава 1", file.toUri().toString(), 20_000L)))
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { app.container.player.clearIfCurrent(bookId) }
        runCatching { app.container.db.library().deleteBook(bookId) }
        dir.deleteRecursively()
        Unit
    }

    /** Усі слова, розірвані посеред, на поточному екрані: «текст → як розбито». */
    private fun brokenWords(): List<String> {
        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val broken = mutableListOf<String>()
        compose.runOnUiThread {
            for (node in nodes) {
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val layout = results.firstOrNull() ?: continue
                val text = layout.layoutInput.text.text
                for (line in 0 until layout.lineCount - 1) {
                    val end = layout.getLineEnd(line, visibleEnd = false)
                    if (end <= 0 || end >= text.length) continue
                    if (text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                        val from = (end - 12).coerceAtLeast(0)
                        val to = (end + 12).coerceAtMost(text.length)
                        broken += "«${text.substring(from, end)}|${text.substring(end, to)}» у «${text.take(40)}»"
                    }
                }
            }
        }
        return broken
    }

    private fun assertNoBrokenWords(screen: String) {
        compose.waitForIdle()
        val broken = brokenWords()
        assertTrue("$screen: слова розірвані посеред при великому шрифті:\n${broken.joinToString("\n")}", broken.isEmpty())
    }

    private fun str(id: Int) = app.getString(id)

    /**
     * Чекає, поки на екрані зʼявиться текст. Під час переходів між екранами (і на старті) ієрархії
     * Compose на мить немає, і `fetchSemanticsNodes` кидає виняток замість порожнього списку, тож
     * його ловимо й вважаємо «ще немає».
     */
    private fun awaitText(text: String, timeoutMs: Long = 20_000) {
        try {
            compose.waitUntil(timeoutMs) {
                runCatching { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            // Без списку того, що на екрані, «текст не зʼявився» не відрізнити від «ми не на тому екрані».
            val seen = runCatching {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }
            }.getOrDefault(emptyList())
            throw AssertionError("Не дочекались тексту «$text». На екрані: $seen", e)
        }
    }

    @Test
    fun shelfBookScreenPlayerAndSettingsKeepWholeWordsAtLargeFont() {
        // Полиця: заголовок, фільтри, картка, нижня навігація.
        // Ієрархія Compose зʼявляється не одразу після запуску (на старті ще читається база):
        // до того fetchSemanticsNodes кидає виняток, а не повертає порожній список.
        compose.waitUntil(20_000) {
            runCatching {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        assertNoBrokenWords("Полиця")

        // Екран книги: кнопки «Старт / Далі / Персонажі / Нотатки».
        compose.onNodeWithText("Главный инженер 2_[Sibiryak]").performClick()
        awaitText(str(R.string.btn_start))
        assertNoBrokenWords("Екран книги")

        // Плеєр: підписи нижніх дій, число в стрілках, статуси.
        compose.onNodeWithText(str(R.string.btn_start)).performClick()
        awaitText(str(R.string.chapters))
        assertNoBrokenWords("Плеєр")
        app.container.player.pause()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // Налаштування: довгі підписи й описи перемикачів.
        activity.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.waitForIdle()
        assertNoBrokenWords("Налаштування")

        // Закладки: порожній стан із кнопкою.
        compose.onNodeWithTag("tab_bookmarks").performClick()
        compose.waitForIdle()
        assertNoBrokenWords("Закладки")
    }

    companion object {
        /** Виконує команду оболонки від імені інструментації й дочитує її вивід до кінця (тоді команда точно завершена). */
        private fun shell(cmd: String): String {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}
