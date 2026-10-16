package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.playback.pageOf

/** Сторінки списку для Android Auto: межі й переповнення. */
class BrowsePagingTest {

    private val items = (1..10).toList()

    @Test
    fun ordinaryPagesSplitTheListInOrder() {
        assertEquals(listOf(1, 2, 3, 4), items.pageOf(0, 4))
        assertEquals(listOf(5, 6, 7, 8), items.pageOf(1, 4))
        assertEquals(listOf(9, 10), items.pageOf(2, 4))
        assertTrue(items.pageOf(3, 4).isEmpty())
    }

    @Test
    fun nonPositivePageSizeMeansNoPaging() {
        assertEquals(items, items.pageOf(0, 0))
        assertEquals(items, items.pageOf(5, -1))
    }

    @Test
    fun negativePageIsTreatedAsTheFirst() {
        assertEquals(listOf(1, 2), items.pageOf(-3, 2))
    }

    @Test
    fun hugePageSizeReturnsEverythingOnTheFirstPage() {
        assertEquals(items, items.pageOf(0, Int.MAX_VALUE))
        assertTrue(items.pageOf(1, Int.MAX_VALUE).isEmpty())
    }

    @Test
    fun largePageNumbersTimesLargePageSizesDoNotOverflowIntoANegativeIndex() {
        // 3 * 2^30 > Int.MAX_VALUE: раніше ставало від'ємним індексом і кидало виняток.
        assertTrue(items.pageOf(3, 1 shl 30).isEmpty())
        assertTrue(items.pageOf(Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(items.pageOf(2, Int.MAX_VALUE / 2 + 1).isEmpty())
    }

    @Test
    fun emptyListStaysEmpty() {
        assertTrue(emptyList<Int>().pageOf(0, 5).isEmpty())
        assertTrue(emptyList<Int>().pageOf(0, 0).isEmpty())
    }
}
