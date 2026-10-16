package ua.nichnyk.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.UserSettings
import ua.nichnyk.listen.playback.isInNightWindow
import ua.nichnyk.listen.playback.shouldStartNightTimer

/**
 * Нічний таймер сну за розкладом. Помилка тут або вмикає таймер удень посеред
 * дороги (книга замовкає без причини), або не вмикає вночі (слухач засинає під
 * книгу, що грає до ранку) — обидва випадки тихі, тому межі перевіряємо до хвилини.
 */
class NightTimerLogicTest {

    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun windowAcrossMidnightCoversEveningAndEarlyMorning() {
        val start = hm(22)
        val end = hm(6)
        assertTrue(isInNightWindow(hm(22), start, end))      // початок входить
        assertTrue(isInNightWindow(hm(23, 59), start, end))
        assertTrue(isInNightWindow(hm(0), start, end))
        assertTrue(isInNightWindow(hm(5, 59), start, end))
        assertFalse(isInNightWindow(hm(6), start, end))      // кінець не входить
        assertFalse(isInNightWindow(hm(12), start, end))
        assertFalse(isInNightWindow(hm(21, 59), start, end))
    }

    @Test
    fun windowWithinOneDayDoesNotWrap() {
        // Денний сон: 13:00–15:00
        assertTrue(isInNightWindow(hm(13), hm(13), hm(15)))
        assertTrue(isInNightWindow(hm(14, 59), hm(13), hm(15)))
        assertFalse(isInNightWindow(hm(15), hm(13), hm(15)))
        assertFalse(isInNightWindow(hm(12, 59), hm(13), hm(15)))
        assertFalse(isInNightWindow(hm(23), hm(13), hm(15)))
    }

    @Test
    fun equalStartAndEndIsAnEmptyWindowNotAllDay() {
        for (minute in listOf(0, hm(6), hm(12), hm(22), hm(23, 59))) {
            assertFalse("о $minute", isInNightWindow(minute, hm(6), hm(6)))
        }
    }

    @Test
    fun startsOnlyWhenEnabledInsideTheWindowAndNobodyElseSetATimer() {
        val inWindow = hm(23)
        assertTrue(shouldStartNightTimer(true, timerActive = false, nowMinute = inWindow, startMinute = hm(22), endMinute = hm(6)))
        // вимкнено
        assertFalse(shouldStartNightTimer(false, false, inWindow, hm(22), hm(6)))
        // таймер уже заведено користувачем у будь-якому режимі — розклад його не перезаписує
        assertFalse(shouldStartNightTimer(true, true, inWindow, hm(22), hm(6)))
        // вдень
        assertFalse(shouldStartNightTimer(true, false, hm(15), hm(22), hm(6)))
    }

    @Test
    fun defaultsAreOffAndSensible() {
        val d = UserSettings()
        assertFalse("нічний таймер має бути вимкнений за замовчуванням", d.nightTimerEnabled)
        assertTrue(isInNightWindow(hm(23), d.nightTimerStartMinute, d.nightTimerEndMinute))
        assertFalse(isInNightWindow(hm(12), d.nightTimerStartMinute, d.nightTimerEndMinute))
        assertTrue(d.nightTimerMinutes in 15..60)
    }
}
