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
import org.junit.Assert.assertNotNull
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
 * Перемотка на паузі: місце зберігається, а «востаннє грали» не рухається.
 *
 * Друге важливе не менше за перше: від `lastPlayedAt` рахується розумний відкат після
 * паузи. Якби дотик до смужки оновлював цей час, відкат після години тиші зникав би.
 */
@RunWith(AndroidJUnit4::class)
class SeekPersistTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val app = ApplicationProvider.getApplicationContext<ListenApp>()
    private val container get() = app.container
    private val player get() = container.player

    private lateinit var dir: File
    private var bookId = ""

    @Before
    fun setUp() = runBlocking {
        dir = File(app.cacheDir, "seek-persist-test").apply { deleteRecursively(); mkdirs() }
        val file = TestAudio.writeWav(File(dir, "1.wav"), seconds = 12)
        bookId = newId()
        val dao = container.db.library()
        dao.upsertBook(
            BookEntity(
                id = bookId, title = "Перемотка", author = "Тест", coverPath = null,
                addedAt = System.currentTimeMillis(), lastPlayedAt = null,
                durationMs = 12_000L, positionMs = 0L,
                currentChapterIndex = 0, playbackSpeed = 1f, completed = false,
            ),
        )
        dao.upsertChapters(listOf(ChapterEntity(newId(), bookId, 0, "Розділ 1", file.toUri().toString(), 12_000L)))
        assertTrue("контролер не підключився", await(15_000) { it.connected })
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { player.clearIfCurrent(bookId) }
        runCatching { container.db.library().deleteBook(bookId) }
        dir.deleteRecursively()
        Unit
    }

    private suspend fun await(timeoutMs: Long = 15_000, condition: (PlayerUiState) -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition(player.state.value)) delay(100)
            true
        } == true

    @Test
    fun seekWhilePausedSavesThePositionButNotTheLastPlayedTime() = runBlocking {
        player.play(container.db.library().getBook(bookId)!!)
        assertTrue("відтворення не почалося", await { it.isPlaying })
        player.playPause()
        assertTrue("плеєр не став на паузу", await { !it.isPlaying })

        // Пауза пише позицію і час примусово; чекаємо, поки це дійде до бази.
        delay(1_000)
        val before = container.db.library().getBook(bookId)!!.book
        assertNotNull("пауза не записала час відтворення", before.lastPlayedAt)

        // Мілісекундна різниця між записами не повинна ховати оновлення: чекаємо помітний проміжок.
        delay(1_500)
        player.seekChapter(8_000L)

        val saved = withTimeoutOrNull(5_000) {
            while (container.db.library().getBook(bookId)!!.book.positionMs < 7_000L) delay(100)
            true
        } == true
        assertTrue("перемотка на паузі не збереглась у базі", saved)

        val after = container.db.library().getBook(bookId)!!.book
        assertEquals(
            "перемотка на паузі зсунула «востаннє грали» — розумний відкат після паузи зламався б",
            before.lastPlayedAt,
            after.lastPlayedAt,
        )
        Unit
    }
}
