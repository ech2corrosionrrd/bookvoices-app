package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.playback.BookTimeline
import ua.nichnyk.listen.playback.SeriesNext
import ua.nichnyk.listen.playback.TimeInput

/** Повзунок «уся книга», «Перейти до часу» і «Далі в циклі» (план 1.19, 20 листопада 2026). */
class BookTimelineTest {

    private fun ch(i: Int, ms: Long) = ChapterEntity("c$i", "b", i, "Ch $i", "u$i", ms)

    @Test
    fun durationsTakeLiveValueForCurrentChapterOnly() {
        val chapters = listOf(ch(0, 1000), ch(1, 0), ch(2, 3000))
        assertEquals(listOf(1000L, 2000L, 3000L), BookTimeline.durations(chapters, 1, 2000))
        // Плеєр ще не знає тривалості — лишається значення з бази.
        assertEquals(listOf(1000L, 0L, 3000L), BookTimeline.durations(chapters, 1, 0))
    }

    @Test
    fun usableOnlyWithSeveralChaptersAndAllDurationsKnown() {
        assertTrue(BookTimeline.isUsable(listOf(1000L, 2000L)))
        assertFalse(BookTimeline.isUsable(listOf(1000L)))
        assertFalse(BookTimeline.isUsable(listOf(1000L, 0L)))
        assertFalse(BookTimeline.isUsable(emptyList()))
    }

    @Test
    fun positionAndLocateAreInverse() {
        val d = listOf(1000L, 2000L, 3000L)
        assertEquals(6000L, BookTimeline.total(d))
        assertEquals(1500L, BookTimeline.position(d, 1, 500))
        assertEquals(1 to 500L, BookTimeline.locate(d, 1500))
        for (ms in listOf(0L, 999L, 1000L, 2999L, 3000L, 5999L)) {
            val (i, p) = BookTimeline.locate(d, ms)
            assertEquals(ms, BookTimeline.position(d, i, p))
        }
    }

    @Test
    fun locateBoundariesAndOutOfRange() {
        val d = listOf(1000L, 2000L)
        assertEquals(1 to 0L, BookTimeline.locate(d, 1000)) // межа — початок наступного
        assertEquals(1 to 2000L, BookTimeline.locate(d, 3000)) // кінець книги
        assertEquals(1 to 2000L, BookTimeline.locate(d, 99_000)) // за кінцем — кінець
        assertEquals(0 to 0L, BookTimeline.locate(d, -5)) // до початку — початок
        assertEquals(0 to 0L, BookTimeline.locate(emptyList(), 500))
    }

    @Test
    fun timeInputParsesCommonForms() {
        assertEquals(5_025_000L, TimeInput.parse("1:23:45"))
        assertEquals(1_425_000L, TimeInput.parse("23:45"))
        assertEquals(5_400_000L, TimeInput.parse("90:00")) // хвилин може бути більше 59
        assertEquals(2_400_000L, TimeInput.parse("40")) // одне число — хвилини
        assertEquals(5_025_000L, TimeInput.parse(" 1.23.45 ")) // крапки з цифрової клавіатури
        assertEquals(5_025_000L, TimeInput.parse("1 23 45"))
        assertEquals(0L, TimeInput.parse("0:00"))
    }

    @Test
    fun timeInputRejectsGarbage() {
        assertNull(TimeInput.parse(""))
        assertNull(TimeInput.parse("abc"))
        assertNull(TimeInput.parse("1:60")) // секунд 60 не буває
        assertNull(TimeInput.parse("1:60:00"))
        assertNull(TimeInput.parse("1:2:3:4"))
        assertNull(TimeInput.parse("-5"))
        assertNull(TimeInput.parse("99999"))
    }

    private fun book(
        id: String,
        series: String?,
        order: Float?,
        completed: Boolean = false,
        title: String = id,
    ) = BookEntity(
        id = id, title = title, author = "A", coverPath = null, addedAt = 0L, lastPlayedAt = null,
        durationMs = 1000L, positionMs = 0L, currentChapterIndex = 0, playbackSpeed = 1f,
        completed = completed, series = series, seriesOrder = order,
    )

    @Test
    fun seriesNextPicksNearestUnfinishedHigherNumber() {
        val cur = book("b1", "Артефактор", 1f, completed = true)
        val all = listOf(
            cur,
            book("b3", "Артефактор", 3f),
            book("b2", " артефактор ", 2f), // регістр і пробіли не важать
            book("b10", "Артефактор", 10f),
            book("x2", "Інший цикл", 2f),
        )
        assertEquals("b2", SeriesNext.find(cur, all)?.id)
    }

    @Test
    fun seriesNextSkipsFinishedAndUnnumbered() {
        val cur = book("b1", "S", 1f)
        val all = listOf(cur, book("b2", "S", 2f, completed = true), book("bx", "S", null), book("b3", "S", 3f))
        assertEquals("b3", SeriesNext.find(cur, all)?.id)
    }

    @Test
    fun seriesNextNullWithoutSeriesOrNumberOrSuccessor() {
        assertNull(SeriesNext.find(book("a", null, 1f), listOf(book("b", null, 2f))))
        assertNull(SeriesNext.find(book("a", "S", null), listOf(book("b", "S", 2f))))
        assertNull(SeriesNext.find(book("a", "S", 5f), listOf(book("b", "S", 2f))))
        assertNull(SeriesNext.find(book("a", "  ", 1f), listOf(book("b", "  ", 2f))))
    }
}
