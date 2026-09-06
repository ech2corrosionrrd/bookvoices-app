package ua.nichnyk.listen.playback

import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.MainActivity
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.TestAudio
import ua.nichnyk.listen.data.newId

/**
 * Таймер сну на живому плеєрі.
 *
 * Розділи навмисне короткі: «до кінця глави» — єдиний режим таймера, який можна
 * перевірити за секунди, а не за хвилини, і саме в ньому найбільше рухомих частин
 * (опитування позиції, згасання гучності, скидання стану).
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerTest {

    /**
     * Активність на передньому плані — умова, без якої Android 15 не дає фокус звуку.
     *
     * З Android 15 у AudioService зʼявився HardeningEnforcer: запит фокусу від застосунку,
     * чий процес не на передньому плані, відхиляється. У логах це видно як
     * `Focus request DENIED ... procState:4` від `androidx.media3.exoplayer.AudioFocusManager`.
     * ExoPlayer при відмові пригнічує відтворення: файл буферизується повністю, але стан
     * лишається PAUSED зі швидкістю 0 — тест бачить лише «відтворення не почалося».
     *
     * У застосунку ця умова виконується сама: кнопку «грати» натискають з відкритого
     * екрана. Тести ж смикають PlayerManager напряму, без жодної Activity, — тобто
     * перевіряли сценарій, якого в житті не буває. На API 30 і 34 це минало безкарно,
     * на 35 перестало.
     *
     * Другого MediaController це не створює: MainActivity бере той самий PlayerManager
     * з AppContainer, а не піднімає власний.
     */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val app = ApplicationProvider.getApplicationContext<ListenApp>()
    private val container get() = app.container
    private val player get() = container.player

    private lateinit var dir: File
    private var bookId: String = ""

    @Before
    fun setUp() = runBlocking {
        dir = File(app.cacheDir, "sleep-test").apply { deleteRecursively(); mkdirs() }
        val first = TestAudio.writeWav(File(dir, "1.wav"), seconds = 4)
        val second = TestAudio.writeWav(File(dir, "2.wav"), seconds = 4)
        bookId = insertBook(listOf(first, second))
        assertTrue(
            "контролер не підключився",
            await(15_000) { it.connected },
        )
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { player.cancelSleep() }
        runCatching { player.clearIfCurrent(bookId) }
        runCatching { container.db.library().deleteBook(bookId) }
        dir.deleteRecursively()
        Unit
    }

    private suspend fun insertBook(files: List<File>): String {
        val id = newId()
        val dao = container.db.library()
        dao.upsertBook(
            BookEntity(
                id = id, title = "Сон", author = "Тест", coverPath = null,
                addedAt = System.currentTimeMillis(), lastPlayedAt = null,
                durationMs = 4_000L * files.size, positionMs = 0L,
                currentChapterIndex = 0, playbackSpeed = 1f, completed = false,
            ),
        )
        dao.upsertChapters(
            files.mapIndexed { i, f ->
                ChapterEntity(newId(), id, i, "Розділ ${i + 1}", f.toUri().toString(), 4_000L)
            },
        )
        return id
    }

    private suspend fun await(timeoutMs: Long = 30_000, condition: (PlayerUiState) -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition(player.state.value)) delay(100)
            true
        } == true

    private suspend fun startPlaying() {
        player.play(container.db.library().getBook(bookId)!!)
        assertTrue("відтворення не почалося", await(15_000) { it.isPlaying })
    }

    @Test
    fun sleepUntilChapterEndPausesBeforeNextChapter() = runBlocking {
        startPlaying()
        player.startSleepUntilChapterEnd()

        // Позначка режиму потрібна UI: у плеєрі замість хвилин показується інший бейдж.
        assertTrue("режим не увімкнувся", await(10_000) { it.isSleepEndOfChapter })

        // Розділ на 4 с; таймер спрацьовує за секунду до кінця, далі ще згасання гучності.
        assertTrue("плеєр не зупинився наприкінці глави", await(40_000) { !it.isPlaying })

        val state = player.state.value
        assertNull("залишок часу не скинуто", state.sleepRemainingMs)
        assertFalse("режим не скинуто", state.isSleepEndOfChapter)
        // Головне — зупинка на межі глави, а не в глибині наступної: інакше
        // слухач, який заснув, наступного разу продовжить із середини нової глави.
        val onTarget = state.chapterIndex == 0
        val atNextChapterStart = state.chapterIndex == 1 && state.positionMs < 1_500L
        assertTrue(
            "зупинка не на межі глави: глава ${state.chapterIndex}, позиція ${state.positionMs}",
            onTarget || atNextChapterStart,
        )
        Unit
    }

    @Test
    fun sleepTimerReportsRemainingTimeAndCancels() = runBlocking {
        startPlaying()
        player.startSleep(minutes = 1)

        assertTrue("залишок часу не зʼявився", await(10_000) { it.sleepRemainingMs != null })
        val first = player.state.value.sleepRemainingMs
        assertNotNull(first)
        assertTrue("залишок більший за задану хвилину", first!! <= 60_000L)
        // Хвилинний таймер — не «до кінця глави»; від цього залежить бейдж у плеєрі.
        assertFalse(player.state.value.isSleepEndOfChapter)
        // Заведене значення, а не залишок: саме за ним шторка сну підсвічує чіп.
        // Поки цього поля не було, чіп вибирався за sleepRemainingMs — і «15 хв»
        // через десять хвилин показувалися як обрані «5 хв».
        assertEquals(1, player.state.value.sleepPresetMinutes)

        assertTrue("залишок не зменшується", await(10_000) { (it.sleepRemainingMs ?: Long.MAX_VALUE) < first })

        player.cancelSleep()

        assertTrue("скасування не скинуло таймер", await(10_000) { it.sleepRemainingMs == null })
        assertNull("скасування лишило вибір хвилин", player.state.value.sleepPresetMinutes)
        // Скасування гасить таймер, але не відтворення.
        assertTrue("скасування таймера зупинило відтворення", player.state.value.isPlaying)
        Unit
    }

    /**
     * Пауза зупиняє відлік, а не тільки звук.
     *
     * Доти хвилинний таймер жив за стінним годинником: завів 15 хвилин, зупинив
     * книгу на 20 — таймер догорів у тиші, `fadeAndPause` спрацював на вже
     * зупиненому плеєрі, стан скинувся. Слухач вважав таймер заведеним, а його
     * вже не було. Два інші режими рахують по відтворенню, і цей має так само.
     */
    @Test
    fun minutesTimerFreezesWhilePlaybackIsPaused() = runBlocking {
        startPlaying()
        player.startSleep(minutes = 1)

        assertTrue("залишок часу не зʼявився", await(10_000) { it.sleepRemainingMs != null })
        val started = player.state.value.sleepRemainingMs!!
        // Спершу переконуємося, що відлік справді пішов: інакше «не змінився на
        // паузі» нічого не доводить — він міг не рухатися взагалі.
        assertTrue("залишок не зменшується", await(10_000) { (it.sleepRemainingMs ?: Long.MAX_VALUE) < started })

        player.playPause()
        assertTrue("плеєр не став на паузу", await(10_000) { !it.isPlaying })

        val frozen = player.state.value.sleepRemainingMs
        assertNotNull("таймер зник на паузі", frozen)
        delay(4_000)

        assertEquals("таймер тікав, поки книга стояла", frozen, player.state.value.sleepRemainingMs)
        assertEquals("режим таймера скинувся на паузі", 1, player.state.value.sleepPresetMinutes)
        Unit
    }

    @Test
    fun cancelSleepBeforeItFiresKeepsPlaying() = runBlocking {
        startPlaying()
        player.startSleepUntilChapterEnd()
        assertTrue(await(10_000) { it.isSleepEndOfChapter })

        player.cancelSleep()
        delay(2_000)

        val state = player.state.value
        assertNull(state.sleepRemainingMs)
        assertFalse(state.isSleepEndOfChapter)
        assertTrue("плеєр зупинився попри скасування таймера", state.isPlaying)
        Unit
    }
}
