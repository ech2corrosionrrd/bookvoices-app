package ua.nichnyk.listen

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.DailyListeningEntity
import ua.nichnyk.listen.data.ListeningStats
import ua.nichnyk.listen.data.StatsPeriod
import ua.nichnyk.listen.playback.nextSleepTickDelay
import ua.nichnyk.listen.playback.sleepBadgeMinutes

/**
 * Дати статистики й арифметика таймера сну.
 *
 * Головне, що тут перевіряється, — що «сьогодні» залежить від переданого моменту,
 * а не від того, коли створили потік: саме через це застосунок, відкритий через
 * північ, показував учорашню цифру як сьогоднішню.
 */
class ListeningStatsTest {

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val uk: Locale = Locale.forLanguageTag("uk")

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(kyiv).toInstant().toEpochMilli()

    private fun day(date: String, ms: Long) = DailyListeningEntity(date, ms)

    @Test
    fun dateKeyUsesLocalCalendarDay() {
        assertEquals("2026-03-14", ListeningStats.dateKey(at(2026, 3, 14, 23, 59), kyiv))
        assertEquals("2026-03-15", ListeningStats.dateKey(at(2026, 3, 15, 0, 1), kyiv))
    }

    @Test
    fun todayFollowsTheGivenMomentNotTheStoredRows() {
        val rows = listOf(day("2026-03-14", 600_000L), day("2026-03-15", 60_000L))

        val onThe14th = ListeningStats.summarize(rows, completedBooks = 0, now = at(2026, 3, 14, 22), zone = kyiv)
        assertEquals(600_000L, onThe14th.todayMs)

        // Той самий набір рядків через дві години — уже інший день.
        val afterMidnight = ListeningStats.summarize(rows, completedBooks = 0, now = at(2026, 3, 15, 0, 30), zone = kyiv)
        assertEquals(60_000L, afterMidnight.todayMs)
    }

    @Test
    fun weekCoversSevenDaysIncludingToday() {
        val today = LocalDate.of(2026, 3, 15)
        val rows = (0..9).map { back ->
            day(today.minusDays(back.toLong()).toString(), 1000L)
        }
        val stats = ListeningStats.summarize(rows, completedBooks = 0, now = at(2026, 3, 15), zone = kyiv)
        assertEquals(7 * 1000L, stats.weekMs)
    }

    @Test
    fun futureRowsAreNotCountedIntoTheWeek() {
        // Годинник міг стрибнути назад після синхронізації з NTP.
        val rows = listOf(day("2026-03-15", 1000L), day("2026-03-20", 5000L))
        val stats = ListeningStats.summarize(rows, completedBooks = 0, now = at(2026, 3, 15), zone = kyiv)
        assertEquals(1000L, stats.weekMs)
        assertEquals(1000L, stats.todayMs)
    }

    @Test
    fun brokenDateDoesNotZeroTheRestOfStatistics() {
        val rows = listOf(day("не дата", 9999L), day("2026-03-15", 1000L))
        val stats = ListeningStats.summarize(rows, completedBooks = 3, now = at(2026, 3, 15), zone = kyiv)
        assertEquals(1000L, stats.todayMs)
        assertEquals(3, stats.completedBooks)
    }

    @Test
    fun midnightIsAlwaysAheadAndAtMostADay() {
        val justAfterMidnight = ListeningStats.millisUntilNextMidnight(at(2026, 3, 15, 0, 1), kyiv)
        assertEquals((23 * 60 + 59) * 60_000L, justAfterMidnight)

        val justBefore = ListeningStats.millisUntilNextMidnight(at(2026, 3, 15, 23, 59), kyiv)
        assertEquals(60_000L, justBefore)
    }

    @Test
    fun sleepTickIsOneSecondWhileThereIsPlentyOfTime() {
        assertEquals(1_000L, nextSleepTickDelay(42 * 60_000L + 30_000L))
        assertEquals(1_000L, nextSleepTickDelay(5 * 60_000L))
    }

    @Test
    fun sleepTickNeverOvershootsTheEnd() {
        // Інакше таймер спрацював би пізніше, ніж його ставили.
        assertEquals(200L, nextSleepTickDelay(200L))
        assertEquals(50L, nextSleepTickDelay(0L))
        assertEquals(50L, nextSleepTickDelay(-100L))
    }

    /**
     * Значок і крок мають дожити до кінця разом. Окремо кожен перевірений вище;
     * тут — що відлік справді сходиться в нуль, а не зависає на мінімальному кроці.
     */
    @Test
    fun sleepBadgeAndTickAgreeUntilTheEnd() {
        var remaining = 3 * 60_000L
        var guard = 0
        while (remaining > 0 && guard++ < 10_000) {
            assertTrue(sleepBadgeMinutes(remaining) > 0)
            remaining -= nextSleepTickDelay(remaining)
        }
        assertTrue("відлік мав дійти до нуля, а не зациклитися", remaining <= 0)
    }

    @Test
    fun sleepBadgeRoundsUpSoTheLastMinuteStaysVisible() {
        assertEquals(1, sleepBadgeMinutes(1L))
        assertEquals(1, sleepBadgeMinutes(60_000L))
        assertEquals(2, sleepBadgeMinutes(60_001L))
        assertEquals(0, sleepBadgeMinutes(0L))
    }

    @Test
    fun dailyActivityGeneratesSevenDaysWithTodayFlag() {
        val rows = listOf(
            day("2026-03-14", 120_000L),
            day("2026-03-15", 300_000L),
        )
        val stats = ListeningStats.summarize(rows, completedBooks = 1, now = at(2026, 3, 15), zone = kyiv, locale = uk)
        assertEquals(7, stats.dailyActivity.size)

        val todayActivity = stats.dailyActivity.first { it.isToday }
        assertEquals("2026-03-15", todayActivity.date)
        assertEquals(300_000L, todayActivity.durationMs)
        assertTrue(todayActivity.dayName.isNotBlank())

        val yesterdayActivity = stats.dailyActivity[5]
        assertEquals("2026-03-14", yesterdayActivity.date)
        assertEquals(120_000L, yesterdayActivity.durationMs)
        assertEquals(false, yesterdayActivity.isToday)
    }

    @Test
    fun monthCoversThirtyDaysIncludingToday() {
        val today = LocalDate.of(2026, 3, 15)
        val rows = (0 until 40).map { back ->
            day(today.minusDays(back.toLong()).toString(), 1000L)
        }
        val stats = ListeningStats.summarize(rows, 0, at(2026, 3, 15), StatsPeriod.Month, kyiv, uk)
        assertEquals(30, stats.dailyActivity.size)
        assertEquals(30 * 1000L, stats.weekMs)
    }

    @Test
    fun yearAggregatesTwelveMonths() {
        val rows = listOf(
            day("2026-01-15", 1000L),
            day("2026-02-10", 2000L),
            day("2026-03-15", 3000L),
        )
        val stats = ListeningStats.summarize(rows, 0, at(2026, 3, 15), StatsPeriod.Year, kyiv, uk)
        assertEquals(12, stats.dailyActivity.size)
        assertEquals(6000L, stats.weekMs)
        assertTrue(stats.dailyActivity.last().isToday)
    }
}
