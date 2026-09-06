package ua.nichnyk.listen.playback

import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.MainActivity
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.TestAudio
import ua.nichnyk.listen.data.newId

/**
 * Відтворення через справжній PlaybackService і живий MediaController.
 *
 * Використовується власний PlayerManager застосунку, а не окремий: інакше на одну
 * медіасесію припадало б два контролери, вони ділили б один ExoPlayer,
 * і тест конкурував би сам із собою.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackTest {

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

    private lateinit var audio: File
    private var bookId: String = ""

    @Before
    fun setUp() = runBlocking {
        audio = TestAudio.writeWav(File(app.cacheDir, "playback-test/track.wav"), seconds = 30)
        bookId = insertBook(audio.toUri().toString())
        awaitConnected()
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { player.clearIfCurrent(bookId) }
        runCatching { container.db.library().deleteBook(bookId) }
        audio.parentFile?.deleteRecursively()
        Unit
    }

    private suspend fun insertBook(uri: String, durationMs: Long = 30_000L): String {
        val id = newId()
        val dao = container.db.library()
        dao.upsertBook(
            BookEntity(
                id = id,
                title = "Тестова книга",
                author = "Тест",
                coverPath = null,
                addedAt = System.currentTimeMillis(),
                lastPlayedAt = null,
                durationMs = durationMs,
                positionMs = 0L,
                currentChapterIndex = 0,
                playbackSpeed = 1f,
                completed = false,
            ),
        )
        dao.upsertChapters(
            listOf(
                ChapterEntity(newId(), id, 0, "Розділ 1", uri, durationMs),
            ),
        )
        return id
    }

    private suspend fun book(): BookWithChapters = container.db.library().getBook(bookId)!!

    private suspend fun awaitConnected() {
        val ok = withTimeoutOrNull(15_000) {
            while (!player.state.value.connected) delay(100)
            true
        }
        assertTrue("контролер не підключився до PlaybackService", ok == true)
    }

    /** Чекає на умову, опитуючи стан; повертає false за таймаутом. */
    private suspend fun await(timeoutMs: Long = 15_000, condition: (PlayerUiState) -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition(player.state.value)) delay(100)
            true
        } == true

    @Test
    fun playsBookAndAdvancesPosition() = runBlocking {
        player.play(book())

        assertTrue("відтворення не почалося", await { it.isPlaying })
        assertEquals(bookId, player.state.value.book?.book?.id)

        val started = player.state.value.positionMs
        assertTrue("позиція не рухається", await { it.positionMs > started + 500 })

        // Тривалість глави має прийти від самого плеєра, а не лишитися нулем.
        assertTrue("тривалість не визначена", player.state.value.durationMs > 0L)
        player.pause()
        Unit
    }

    @Test
    fun pausePersistsProgressToDatabase() = runBlocking {
        player.play(book())
        assertTrue(await { it.isPlaying })
        assertTrue(await { it.positionMs > 1_000 })

        player.pause()
        assertTrue("плеєр не зупинився", await { !it.isPlaying })

        // Позиція записується у БД без відтворення до кінця — інакше
        // закриття застосунку посеред глави втрачало б місце.
        val saved = withTimeoutOrNull(10_000) {
            var b = book()
            while (b.book.positionMs <= 0L) {
                delay(200)
                b = book()
            }
            b
        }
        assertNotNull("прогрес не збережено", saved)
        assertTrue(saved!!.book.positionMs > 0L)
        assertNotNull("книга не позначена як слухана", saved.book.lastPlayedAt)
        Unit
    }

    @Test
    fun skipForwardMovesPositionWithinChapter() = runBlocking {
        player.play(book())
        assertTrue(await { it.isPlaying })
        assertTrue(await { it.positionMs > 500 })
        player.pause()
        assertTrue(await { !it.isPlaying })

        val before = player.state.value.positionMs
        player.skipForward()

        // У книзі одна глава на 30 с, тож +30 с упирається в її кінець, а не гортає далі.
        assertTrue(
            "позиція не змінилася після перемотування вперед",
            await { it.positionMs > before },
        )
        assertEquals(0, player.state.value.chapterIndex)
        Unit
    }

    @Test
    fun playingBookWithMissingFileFails() = runBlocking {
        val ghostId = insertBook(File(app.cacheDir, "ghost/none.wav").toUri().toString())
        try {
            val ghost = container.db.library().getBook(ghostId)!!
            val failure = runCatching { player.play(ghost) }.exceptionOrNull()

            // Помилка має прийти одразу з prepare, а не тишею в плеєрі.
            assertNotNull("відтворення неіснуючого файла не дало помилки", failure)
            assertFalse(player.state.value.isPlaying)
        } finally {
            container.db.library().deleteBook(ghostId)
        }
        Unit
    }

    /**
     * Стерео через справжній аудіоконвеєр — з увімкненим моно й без нього.
     *
     * Решта тестів тут грає моно-файл, на якому [MonoDownmixAudioProcessor]
     * повертає NOT_SET і до роботи не береться. Тобто до цього тесту жоден
     * прогін не проходив звук крізь процесор, хоча той стоїть у власному
     * DefaultAudioSink застосунку й чіпає кожен буфер стереокниги — а стерео
     * в аудіокнигах саме й переважає.
     *
     * Перевіряємо не звучання (емулятор його не чує), а те, що конвеєр не
     * ламається: позиція рухається в обох станах перемикача, і перемикання
     * посеред відтворення не зупиняє й не рве потік.
     */
    @Test
    fun playsStereoWithMonoDownmixOnAndOff() = runBlocking {
        val stereo = TestAudio.writeWav(
            File(app.cacheDir, "playback-test/stereo.wav"),
            seconds = 30,
            channels = 2,
        )
        val stereoId = insertBook(stereo.toUri().toString())
        val wasMono = container.prefs.settings.first().monoAudio
        try {
            container.prefs.setMonoAudio(false)
            player.play(container.db.library().getBook(stereoId)!!)
            assertTrue("стерео не заграло", await { it.isPlaying })

            val beforeToggle = player.state.value.positionMs
            assertTrue("позиція стоїть до вмикання моно", await { it.positionMs > beforeToggle + 500 })

            // Перемикач має діяти на вже налаштованому конвеєрі: onConfigure
            // більше не викликається, бо формат той самий.
            container.prefs.setMonoAudio(true)
            val afterToggle = player.state.value.positionMs
            assertTrue("позиція стала після вмикання моно", await { it.positionMs > afterToggle + 500 })
            assertTrue("відтворення урвалося на перемиканні", player.state.value.isPlaying)

            container.prefs.setMonoAudio(false)
            val afterOff = player.state.value.positionMs
            assertTrue("позиція стала після вимикання моно", await { it.positionMs > afterOff + 500 })
            assertTrue("відтворення урвалося на вимиканні", player.state.value.isPlaying)

            player.pause()
        } finally {
            container.prefs.setMonoAudio(wasMono)
            runCatching { player.clearIfCurrent(stereoId) }
            container.db.library().deleteBook(stereoId)
            stereo.delete()
        }
        Unit
    }

    @Test
    fun deletingCurrentBookStopsPlayback() = runBlocking {
        player.play(book())
        assertTrue(await { it.isPlaying })

        container.repo.deleteBook(bookId)

        // Видалення книги, яка грає, має гасити плеєр і ховати міні-плеєр,
        // інакше в шторці лишається сповіщення про неіснуючу книгу.
        assertTrue("плеєр не зупинився після видалення", await { !it.isPlaying && it.book == null })
        Unit
    }
}
