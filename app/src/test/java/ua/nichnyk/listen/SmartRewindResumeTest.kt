package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.nichnyk.listen.data.SmartRewindLogic

class SmartRewindResumeTest {

    private val now = 1_000_000_000L
    private val minute = 60_000L

    @Test
    fun unknownPauseMeansNoRewind() {
        assertEquals(60_000L, SmartRewindLogic.resumePosition(0L, 60_000L, now))
    }

    @Test
    fun shortPauseKeepsPosition() {
        assertEquals(60_000L, SmartRewindLogic.resumePosition(now - 2 * minute, 60_000L, now))
    }

    @Test
    fun longerPauseRewindsMore() {
        assertEquals(55_000L, SmartRewindLogic.resumePosition(now - 10 * minute, 60_000L, now))
        assertEquals(45_000L, SmartRewindLogic.resumePosition(now - 120 * minute, 60_000L, now))
        assertEquals(30_000L, SmartRewindLogic.resumePosition(now - 48 * 60 * minute, 60_000L, now))
    }

    @Test
    fun neverRewindsBelowZeroOrFromStart() {
        assertEquals(0L, SmartRewindLogic.resumePosition(now - 48 * 60 * minute, 10_000L, now))
        assertEquals(0L, SmartRewindLogic.resumePosition(now - 48 * 60 * minute, 0L, now))
    }
}
