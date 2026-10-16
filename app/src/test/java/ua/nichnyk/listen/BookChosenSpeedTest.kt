package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.nichnyk.listen.playback.bookChosenSpeed

/** Яка швидкість іде в плеєр: власна книги чи значення з налаштувань. */
class BookChosenSpeedTest {

    @Test
    fun playedBookKeepsItsSpeed() {
        assertEquals(1.5f, bookChosenSpeed(1.5f, lastPlayedAt = 1L)!!, 0.001f)
        assertEquals(1f, bookChosenSpeed(1f, lastPlayedAt = 1L)!!, 0.001f)
    }

    @Test
    fun unplayedBookWithChangedSpeedKeepsItToo() {
        assertEquals(1.25f, bookChosenSpeed(1.25f, lastPlayedAt = null)!!, 0.001f)
    }

    @Test
    fun unplayedBookAtDefaultOneFallsBackToSettings() {
        assertNull(bookChosenSpeed(1f, lastPlayedAt = null))
    }

    @Test
    fun nonPositiveSpeedFallsBackToSettings() {
        assertNull(bookChosenSpeed(0f, lastPlayedAt = 1L))
        assertNull(bookChosenSpeed(-1f, lastPlayedAt = 1L))
    }
}
