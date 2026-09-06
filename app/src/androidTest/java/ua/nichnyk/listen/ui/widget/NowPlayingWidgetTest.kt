package ua.nichnyk.listen.ui.widget

import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.playback.PlayerUiState

/**
 * Віджет «зараз грає»: що саме в ньому опиниться.
 *
 * Покласти віджет на робочий стіл із тесту неможливо, а через AppWidgetManager
 * видно лише факт IPC. Тому RemoteViews інфлейтиться тут, у тестовому процесі —
 * і підписи, прогрес та піктограма перевіряються як звичайні View.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingWidgetTest {

    private val app = ApplicationProvider.getApplicationContext<ListenApp>()

    private fun book(
        title: String = "Тіні забутих предків",
        author: String = "Коцюбинський",
        durationMs: Long = 100_000L,
    ) = BookWithChapters(
        book = BookEntity(
            id = "b1",
            title = title,
            author = author,
            coverPath = null,
            addedAt = 0L,
            lastPlayedAt = null,
            durationMs = durationMs,
            positionMs = 0L,
            currentChapterIndex = 0,
            playbackSpeed = 1f,
            completed = false,
        ),
        chapters = listOf(
            ChapterEntity("c0", "b1", 0, "Розділ перший", "file:///a.mp3", 50_000L),
            ChapterEntity("c1", "b1", 1, "Розділ другий", "file:///b.mp3", 50_000L),
        ),
    )

    /** Інфлейт RemoteViews треба робити на головному потоці, як це робить лаунчер. */
    private fun inflate(state: PlayerUiState): View {
        var root: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val views = NowPlayingWidgetProvider.buildViews(app, state)
            root = views.apply(app, FrameLayout(app))
        }
        return root!!
    }

    @Test
    fun showsTitleChapterAndProgressOfCurrentBook() {
        val root = inflate(
            PlayerUiState(
                book = book(),
                chapterIndex = 1,
                positionMs = 25_000L,
                isPlaying = true,
            ),
        )

        assertEquals("Тіні забутих предків", root.findViewById<TextView>(R.id.widget_title).text.toString())
        // Підзаголовок — глава, а не автор: у машині й на екрані блокування
        // важливіше, де саме ти зупинився.
        assertEquals("Розділ другий", root.findViewById<TextView>(R.id.widget_subtitle).text.toString())

        val progress = root.findViewById<ProgressBar>(R.id.widget_progress)
        // 50 000 попередньої глави + 25 000 = 75 % книги.
        assertEquals(75, progress.progress)
        assertEquals(View.VISIBLE, progress.visibility)
    }

    @Test
    fun withoutBookShowsPlaceholderAndHidesProgress() {
        val root = inflate(PlayerUiState())

        assertEquals(
            app.getString(R.string.widget_no_book),
            root.findViewById<TextView>(R.id.widget_title).text.toString(),
        )
        assertEquals("", root.findViewById<TextView>(R.id.widget_subtitle).text.toString())
        assertEquals(View.INVISIBLE, root.findViewById<ProgressBar>(R.id.widget_progress).visibility)
    }

    @Test
    fun buildingViewsWithMissingCoverFileDoesNotThrow() {
        val withCover = book().let { it.copy(book = it.book.copy(coverPath = "/no/such/cover.png")) }
        val root = inflate(PlayerUiState(book = withCover, isPlaying = false))
        // Головне — що не впало й підпис на місці: віджет підставляє значок застосунку.
        assertEquals("Тіні забутих предків", root.findViewById<TextView>(R.id.widget_title).text.toString())
    }

    // --- знімок, який вирішує, чи взагалі перемальовувати -------------------

    /**
     * Тікер оновлює позицію 2,5 рази на секунду. Якщо знімок від цього змінюється,
     * віджет знову малюється щотакту — саме це й правилося.
     */
    @Test
    fun snapshotIgnoresPositionDriftWithinOnePercent() {
        val base = PlayerUiState(book = book(), chapterIndex = 0, positionMs = 10_000L, isPlaying = true)
        val slightlyLater = base.copy(positionMs = 10_400L)

        assertEquals(base.widgetSnapshot(), slightlyLater.widgetSnapshot())
    }

    @Test
    fun snapshotChangesOnPercentChapterAndPlayPause() {
        val base = PlayerUiState(book = book(), chapterIndex = 0, positionMs = 10_000L, isPlaying = true)

        assertNotEquals(base.widgetSnapshot(), base.copy(positionMs = 11_000L).widgetSnapshot())
        assertNotEquals(base.widgetSnapshot(), base.copy(chapterIndex = 1).widgetSnapshot())
        assertNotEquals(base.widgetSnapshot(), base.copy(isPlaying = false).widgetSnapshot())
    }

    @Test
    fun snapshotProgressStaysWithinBounds() {
        val overrun = PlayerUiState(book = book(durationMs = 1L), chapterIndex = 1, positionMs = 999_999L)
        assertTrue(overrun.widgetSnapshot().progressPercent in 0..100)
    }
}
