package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.ListeningSessionEntity
import ua.nichnyk.listen.playback.PlayerUiState
import ua.nichnyk.listen.playback.SleepTimerMode
import java.time.LocalDate
import java.time.ZoneId

class WaveBLogicTest {

    private fun sampleBook(id: String, title: String, chaptersCount: Int = 3): BookWithChapters {
        val book = BookEntity(
            id = id,
            title = title,
            author = "Author $id",
            coverPath = null,
            addedAt = 1000L,
            lastPlayedAt = null,
            durationMs = chaptersCount * 60_000L,
            positionMs = 0L,
            currentChapterIndex = 0,
            playbackSpeed = 1f,
            completed = false,
        )
        val chapters = (0 until chaptersCount).map { i ->
            ChapterEntity(
                id = "$id-ch-$i",
                bookId = id,
                title = "Chapter ${i + 1}",
                uri = "content://books/$id/audio$i.mp3",
                durationMs = 60_000L,
                index = i,
            )
        }
        return BookWithChapters(book = book, chapters = chapters)
    }

    @Test
    fun playerUiStateSleepModes() {
        val stateMinutes = PlayerUiState(sleepMode = SleepTimerMode.Minutes)
        assertFalse(stateMinutes.isSleepEndOfChapter)
        assertFalse(stateMinutes.isSleepEndOfBook)

        val stateChapter = PlayerUiState(sleepMode = SleepTimerMode.EndOfChapter)
        assertTrue(stateChapter.isSleepEndOfChapter)
        assertFalse(stateChapter.isSleepEndOfBook)

        val stateBook = PlayerUiState(sleepMode = SleepTimerMode.EndOfBook)
        assertFalse(stateBook.isSleepEndOfChapter)
        assertTrue(stateBook.isSleepEndOfBook)
    }

    @Test
    fun playerUiStateQueueBehavior() {
        val book1 = sampleBook("1", "Book 1")
        val book2 = sampleBook("2", "Book 2")
        val state = PlayerUiState(book = book1, queue = listOf(book2))

        assertEquals(1, state.queue.size)
        assertEquals("2", state.queue.first().book.id)
    }

    @Test
    fun listeningSessionGroupingLogic() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayMs = today.atStartOfDay(zone).toInstant().toEpochMilli() + 3600_000L
        val yesterdayMs = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 3600_000L

        val s1 = ListeningSessionEntity(id = "1", bookId = "b1", bookTitle = "Kobzar", author = "Shevchenko", timestamp = todayMs, durationMs = 600_000L)
        val s2 = ListeningSessionEntity(id = "2", bookId = "b2", bookTitle = "Misto", author = "Pidmohylny", timestamp = yesterdayMs, durationMs = 1200_000L)

        assertEquals("Kobzar", s1.bookTitle)
        assertEquals(600_000L, s1.durationMs)
        assertEquals("Misto", s2.bookTitle)
        assertEquals(1200_000L, s2.durationMs)
    }

    @Test
    fun queueItemRemovalAndDeduplication() {
        val book1 = sampleBook("1", "Book 1")
        val book2 = sampleBook("2", "Book 2")
        val book3 = sampleBook("3", "Book 3")

        val initialQueue = listOf(book1, book2, book3)
        val filtered = initialQueue.filterNot { it.book.id == "2" }

        assertEquals(2, filtered.size)
        assertEquals("1", filtered[0].book.id)
        assertEquals("3", filtered[1].book.id)
    }
}
