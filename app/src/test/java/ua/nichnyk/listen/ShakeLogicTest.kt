package ua.nichnyk.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.playback.ShakeLogic

class ShakeLogicTest {

    @Test
    fun singleBumpDoesNotTrigger() {
        val s = ShakeLogic()
        assertFalse(s.onReading(3.5f, 1_000L))
        // Сусідній відлік того самого удару — не другий сплеск.
        assertFalse(s.onReading(3.0f, 1_060L))
    }

    @Test
    fun quietReadingsNeverTrigger() {
        val s = ShakeLogic()
        assertFalse((0..50).any { s.onReading(1.0f, it * 60L) })
    }

    @Test
    fun twoSeparateSpikesWithinWindowTrigger() {
        val s = ShakeLogic()
        assertFalse(s.onReading(3.0f, 1_000L))
        assertTrue(s.onReading(3.0f, 1_300L))
    }

    @Test
    fun spikesFarApartDoNotAddUp() {
        val s = ShakeLogic()
        assertFalse(s.onReading(3.0f, 1_000L))
        assertFalse(s.onReading(3.0f, 5_000L))
    }

    @Test
    fun afterShakeThereIsASlopPeriod() {
        val s = ShakeLogic()
        s.onReading(3.0f, 1_000L)
        assertTrue(s.onReading(3.0f, 1_300L))
        assertFalse(s.onReading(3.0f, 1_500L))
        assertFalse(s.onReading(3.0f, 1_800L))
        // Після паузи серія знову рахується з нуля.
        assertFalse(s.onReading(3.0f, 3_000L))
        assertTrue(s.onReading(3.0f, 3_300L))
    }
}
