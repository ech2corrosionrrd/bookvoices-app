package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.nichnyk.listen.data.ChapterOrdering
import ua.nichnyk.listen.data.ChapterOrdering.Entry

/**
 * Порядок глав за тегами лише там, де імена файлів його не дають.
 * Хибний тег гірший за порядок за іменами, тож кожне «не чіпати» має власний тест.
 */
class ChapterOrderingTest {

    private fun e(name: String, track: Int?, disc: Int? = null) = Entry(name, disc, track)

    @Test
    fun namedByContentButTaggedByTrackIsReorderedByTag() {
        // Імена за алфавітом: Вступ, Втеча, Місто. Справжній порядок — з тегів.
        val entries = listOf(e("Вступ.mp3", 1), e("Втеча.mp3", 3), e("Місто.mp3", 2))
        // Порядок за іменами (вхід): [Вступ, Втеча, Місто] → за тегами: Вступ(1), Місто(2), Втеча(3)
        assertEquals(listOf(0, 2, 1), ChapterOrdering.byTags(entries))
    }

    @Test
    fun namesThatAllStartWithANumberWinOverTags() {
        // Так було завжди; теги можуть бути зіпсовані (усі «1», або з іншого видання).
        val entries = listOf(e("01 Вступ.mp3", 9), e("02 Місто.mp3", 1), e("03 Втеча.mp3", 5))
        assertNull(ChapterOrdering.byTags(entries))
    }

    @Test
    fun oneNameWithoutANumberIsEnoughToConsultTheTags() {
        val entries = listOf(e("01 Вступ.mp3", 2), e("Епілог.mp3", 1))
        assertEquals(listOf(1, 0), ChapterOrdering.byTags(entries))
    }

    @Test
    fun anyMissingTrackNumberMeansTrustTheNames() {
        assertNull(ChapterOrdering.byTags(listOf(e("а.mp3", 2), e("б.mp3", null), e("в.mp3", 1))))
        assertNull(ChapterOrdering.byTags(listOf(e("а.mp3", 0), e("б.mp3", 1))))
    }

    @Test
    fun duplicateTrackNumbersMeanTheTagsAreUnreliable() {
        // Типовий випадок: у всіх файлів тег «1».
        assertNull(ChapterOrdering.byTags(listOf(e("в.mp3", 1), e("а.mp3", 1), e("б.mp3", 1))))
    }

    @Test
    fun alreadyInTagOrderChangesNothing() {
        assertNull(ChapterOrdering.byTags(listOf(e("а.mp3", 1), e("б.mp3", 2), e("в.mp3", 3))))
    }

    @Test
    fun discNumberComesBeforeTrackNumber() {
        // Доріжка 1 диска 2 іде після доріжки 5 диска 1, а не перед нею.
        val entries = listOf(e("a.mp3", 1, disc = 2), e("b.mp3", 5, disc = 1), e("c.mp3", 1, disc = 1))
        assertEquals(listOf(2, 1, 0), ChapterOrdering.byTags(entries))
    }

    @Test
    fun sameTrackOnDifferentDiscsIsNotADuplicate() {
        val entries = listOf(e("a.mp3", 1, disc = 2), e("b.mp3", 1, disc = 1))
        assertEquals(listOf(1, 0), ChapterOrdering.byTags(entries))
    }

    @Test
    fun fewerThanTwoChaptersHaveNothingToOrder() {
        assertNull(ChapterOrdering.byTags(emptyList()))
        assertNull(ChapterOrdering.byTags(listOf(e("а.mp3", 3))))
    }

    @Test
    fun tagNumbersAreParsedLeniently() {
        assertEquals(3, ChapterOrdering.parseNumber("3"))
        assertEquals(3, ChapterOrdering.parseNumber("3/12"))
        assertEquals(3, ChapterOrdering.parseNumber(" 03 "))
        assertNull(ChapterOrdering.parseNumber("0"))
        assertNull(ChapterOrdering.parseNumber("abc"))
        assertNull(ChapterOrdering.parseNumber(""))
        assertNull(ChapterOrdering.parseNumber(null))
    }
}
