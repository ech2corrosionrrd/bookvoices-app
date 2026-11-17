package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.pickResumeBook

class ResumeBookLogicTest {

    private fun book(id: String, lastPlayedAt: Long? = null, completed: Boolean = false) = BookWithChapters(
        BookEntity(
            id = id,
            title = id,
            author = "",
            coverPath = null,
            addedAt = 0L,
            lastPlayedAt = lastPlayedAt,
            durationMs = 0L,
            positionMs = 0L,
            currentChapterIndex = 0,
            playbackSpeed = 1f,
            completed = completed,
        ),
        emptyList(),
    )

    private fun pick(books: List<BookWithChapters>, preferred: String?) =
        pickResumeBook(books, preferred)?.book?.id

    @Test
    fun emptyShelfGivesNothing() {
        assertNull(pick(emptyList(), "x"))
    }

    @Test
    fun preferredUnfinishedBookWins() {
        val shelf = listOf(book("a", 100), book("b", 50))
        assertEquals("b", pick(shelf, "b"))
    }

    @Test
    fun missingPreferredFallsBackToNewestUnfinished() {
        val shelf = listOf(book("a", 100), book("b", 200), book("c", 300, completed = true))
        assertEquals("b", pick(shelf, "gone"))
        assertEquals("b", pick(shelf, null))
    }

    @Test
    fun completedPreferredYieldsToUnfinishedBook() {
        val shelf = listOf(book("a", 100), book("b", 200, completed = true))
        assertEquals("a", pick(shelf, "b"))
    }

    @Test
    fun onlyCompletedBooksKeepPreferred() {
        val shelf = listOf(book("a", 100, completed = true), book("b", 200, completed = true))
        assertEquals("a", pick(shelf, "a"))
        assertEquals("b", pick(shelf, null))
    }

    @Test
    fun neverPlayedShelfFallsBackToFirstBook() {
        val shelf = listOf(book("a"), book("b"))
        assertEquals("a", pick(shelf, null))
    }
}
