package ua.nichnyk.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.playback.shouldForgetLastBook

/** Видалення чи перепривʼязка однієї книги не стирає «останню книгу» іншої. */
class ForgetLastBookTest {

    @Test
    fun forgetsWhenClearedBookIsTheLastOne() {
        assertTrue(shouldForgetLastBook(lastBookId = "a", clearedBookId = "a"))
    }

    @Test
    fun keepsLastBookWhenAnotherBookIsCleared() {
        assertFalse(shouldForgetLastBook(lastBookId = "a", clearedBookId = "b"))
    }

    @Test
    fun nothingToForgetWhenNoLastBook() {
        assertFalse(shouldForgetLastBook(lastBookId = null, clearedBookId = "a"))
    }
}
