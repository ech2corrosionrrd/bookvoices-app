package ua.nichnyk.listen

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.DailyListeningEntity
import ua.nichnyk.listen.data.ListeningSessionEntity
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

    @Test
    fun streaksPreserveCurrentStreakBeforeListeningTodayAndTrackLongestRun() {
        val rows = listOf(
            // Минулий рекорд із 4 днів поспіль
            day("2026-03-01", 60_000L),
            day("2026-03-02", 60_000L),
            day("2026-03-03", 60_000L),
            day("2026-03-04", 60_000L),
            // Поточна серія: вчора й позавчора слухали, сьогодні ще ні
            day("2026-03-13", 120_000L),
            day("2026-03-14", 180_000L),
        )
        val morningStats = ListeningStats.summarize(rows, 0, at(2026, 3, 15, 9, 0), StatsPeriod.Week, kyiv, uk)
        assertEquals(2, morningStats.currentStreakDays)
        assertEquals(4, morningStats.longestStreakDays)
        assertEquals(6, morningStats.totalDays)

        // Коли пропустили і вчора, і сьогодні — поточна серія 0, але рекорд зберігається
        val afterGap = ListeningStats.summarize(rows, 0, at(2026, 3, 16, 9, 0), StatsPeriod.Week, kyiv, uk)
        assertEquals(0, afterGap.currentStreakDays)
        assertEquals(4, afterGap.longestStreakDays)
    }

    @Test
    fun exportHistoryCsvIncludesSessionsAndUnaccountedDailyTotalsWithRfc4180Escaping() {
        val sessions = listOf(
            ListeningSessionEntity(
                id = "s1",
                bookId = "b1",
                bookTitle = "Місто, частина 1",
                author = "Валер'ян \"Підмогильний\"",
                timestamp = at(2026, 3, 15, 14, 30),
                durationMs = 90_000L,
            ),
        )
        val daily = listOf(
            day("2026-03-15", 90_000L),
            // Давніший день, сесії якого вже очищені, але підсумок дня лишився
            day("2025-11-10", 300_000L),
        )

        val csv = ListeningStats.exportHistoryCsv(sessions, daily, kyiv)
        assertTrue("BOM для Excel", csv.startsWith(ListeningStats.CSV_BOM))
        val lines = csv.removePrefix(ListeningStats.CSV_BOM).trim().lines()
        assertEquals(3, lines.size)
        assertEquals("date,time,book,author,duration_sec,duration_min", lines[0])
        assertEquals(
            "2026-03-15,14:30,\"Місто, частина 1\",\"Валер'ян \"\"Підмогильний\"\"\",90,1.5",
            lines[1],
        )
        assertEquals("2025-11-10,,,,300,5.0", lines[2])
    }

    @Test
    fun exportHistoryCsvDoesNotDoubleCountSessionAcrossMidnight() {
        // 23:30 15.03 — 00:30 16.03: daily_listening має по 30 хв у кожному дні.
        val sessions = listOf(
            ListeningSessionEntity(
                id = "s1",
                bookId = "b1",
                bookTitle = "Книга",
                author = "Автор",
                timestamp = at(2026, 3, 15, 23, 30),
                durationMs = 60 * 60_000L,
            ),
        )
        val daily = listOf(day("2026-03-15", 30 * 60_000L), day("2026-03-16", 30 * 60_000L))

        val lines = ListeningStats.exportHistoryCsv(sessions, daily, kyiv)
            .removePrefix(ListeningStats.CSV_BOM).trim().lines()
        // Лише заголовок і сама сесія — без «неврахованих» 30 хв другого дня.
        assertEquals(listOf("date,time,book,author,duration_sec,duration_min", "2026-03-15,23:30,Книга,Автор,3600,60.0"), lines)
    }

    @Test
    fun exportHistoryCsvDoesNotInflateSessionPausedAcrossMidnight() {
        // 5 хв до півночі й 15 після, пауза 10 хв: у базі одна сесія на 20 хв від 23:50,
        // а денні підсумки — 5 і 15. Суцільний розклад дав би зайві 5 хв у файлі.
        val sessions = listOf(
            ListeningSessionEntity(
                id = "s1",
                bookId = "b1",
                bookTitle = "Книга",
                author = "Автор",
                timestamp = at(2026, 3, 15, 23, 50),
                durationMs = 20 * 60_000L,
            ),
        )
        val daily = listOf(day("2026-03-15", 5 * 60_000L), day("2026-03-16", 15 * 60_000L))

        val lines = ListeningStats.exportHistoryCsv(sessions, daily, kyiv)
            .removePrefix(ListeningStats.CSV_BOM).trim().lines()
        assertEquals(
            listOf("date,time,book,author,duration_sec,duration_min", "2026-03-15,23:50,Книга,Автор,1200,20.0"),
            lines,
        )
    }

    @Test
    fun exportHistoryCsvGivesRemainderToPrunedDayNotToStaleMidnightSession() {
        // Стара сесія з паузою через північ (до заборони злиття): 20 хв від 23:50, а денні
        // підсумки 5 і 15 — «залишок» 16.03 хибний. Плюс давній день 10.11 без сесій
        // (очищені), 5 хв — справжній залишок. Бюджет 5 хв має дістатися саме йому,
        // навіть якщо база віддала дні в іншому порядку.
        val sessions = listOf(
            ListeningSessionEntity(
                id = "s1",
                bookId = "b1",
                bookTitle = "Книга",
                author = "Автор",
                timestamp = at(2026, 3, 15, 23, 50),
                durationMs = 20 * 60_000L,
            ),
        )
        val daily = listOf(
            day("2026-03-16", 15 * 60_000L),
            day("2026-03-15", 5 * 60_000L),
            day("2025-11-10", 5 * 60_000L),
        )

        val lines = ListeningStats.exportHistoryCsv(sessions, daily, kyiv)
            .removePrefix(ListeningStats.CSV_BOM).trim().lines()
        assertEquals(
            listOf(
                "date,time,book,author,duration_sec,duration_min",
                "2026-03-15,23:50,Книга,Автор,1200,20.0",
                "2025-11-10,,,,300,5.0",
            ),
            lines,
        )
    }

    @Test
    fun exportHistoryCsvNeutralizesFormulaCells() {
        val sessions = listOf(
            ListeningSessionEntity(
                id = "s1",
                bookId = "b1",
                bookTitle = "=1+1",
                author = "+автор",
                timestamp = at(2026, 3, 15, 12, 0),
                durationMs = 60_000L,
            ),
        )
        val csv = ListeningStats.exportHistoryCsv(sessions, listOf(day("2026-03-15", 60_000L)), kyiv)
        assertTrue(csv.contains("'=1+1"))
        assertTrue(csv.contains("'+автор"))
    }

    @Test
    fun dailyGoalProgressHandlesDisabledPartialAndExceededGoals() {
        assertEquals(0f, ListeningStats.dailyGoalProgress(todayMs = 600_000L, goalMinutes = 0), 0.0001f)
        assertEquals(0.5f, ListeningStats.dailyGoalProgress(todayMs = 15 * 60_000L, goalMinutes = 30), 0.0001f)
        assertEquals(1f, ListeningStats.dailyGoalProgress(todayMs = 90 * 60_000L, goalMinutes = 30), 0.0001f)
    }
}
