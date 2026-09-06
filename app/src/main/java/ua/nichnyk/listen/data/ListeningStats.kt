package ua.nichnyk.listen.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Розкладання рядків `daily_listening` на «сьогодні» й обраний період.
 *
 * Винесено з LibraryRepository окремо і без Room, бо тут вся плутанина з датами:
 * раніше репозиторій обчислював `todayStr` **один раз** — у момент створення потоку,
 * тобто при створенні ViewModel. Застосунок, відкритий через північ, і далі показував
 * учорашню цифру як сьогоднішню, бо дата в запиті вже не збігалася з реальним днем.
 *
 * Тепер дата — параметр [now], а не захоплене значення, і її можна перевірити тестом.
 */
object ListeningStats {

    /** Той самий формат, що писав SimpleDateFormat("yyyy-MM-dd", Locale.US). */
    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Скільки днів (разом із сьогоднішнім) входить у «за тиждень». */
    const val WEEK_DAYS = 7

    /** Скільки останніх днів показує місячний графік. */
    const val MONTH_DAYS = 30

    /** Скільки місяців у річному графіку. */
    const val YEAR_MONTHS = 12

    /** Ключ рядка в daily_listening для моменту [now] у локальному часовому поясі. */
    fun dateKey(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        localDate(now, zone).format(ISO_DATE)

    /**
     * Підсумок за наявними рядками.
     *
     * [WeeklyStats.weekMs] — сумарний час за обраний період (тиждень, місяць або рік).
     */
    fun summarize(
        rows: List<DailyListeningEntity>,
        completedBooks: Int,
        now: Long,
        period: StatsPeriod = StatsPeriod.Week,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): WeeklyStats {
        val today = localDate(now, zone)
        val dayMap = mutableMapOf<LocalDate, Long>()
        for (row in rows) {
            val date = runCatching { LocalDate.parse(row.date, ISO_DATE) }.getOrNull() ?: continue
            dayMap[date] = (dayMap[date] ?: 0L) + row.durationMs
        }
        val todayMs = dayMap[today] ?: 0L

        return when (period) {
            StatsPeriod.Week -> summarizeWeek(dayMap, today, todayMs, completedBooks, locale)
            StatsPeriod.Month -> summarizeMonth(dayMap, today, todayMs, completedBooks, locale)
            StatsPeriod.Year -> summarizeYear(dayMap, today, todayMs, completedBooks, locale)
        }
    }

    private fun summarizeWeek(
        dayMap: Map<LocalDate, Long>,
        today: LocalDate,
        todayMs: Long,
        completedBooks: Int,
        locale: Locale,
    ): WeeklyStats {
        val weekStart = today.minusDays((WEEK_DAYS - 1).toLong())
        var weekMs = 0L
        val daily = mutableListOf<DayActivity>()
        for (i in 0 until WEEK_DAYS) {
            val d = weekStart.plusDays(i.toLong())
            val dur = dayMap[d] ?: 0L
            weekMs += dur
            daily.add(
                DayActivity(
                    date = d.format(ISO_DATE),
                    dayName = shortWeekday(d, locale),
                    durationMs = dur,
                    isToday = d == today,
                ),
            )
        }
        return WeeklyStats(todayMs = todayMs, weekMs = weekMs, completedBooks = completedBooks, dailyActivity = daily)
    }

    private fun summarizeMonth(
        dayMap: Map<LocalDate, Long>,
        today: LocalDate,
        todayMs: Long,
        completedBooks: Int,
        locale: Locale,
    ): WeeklyStats {
        val monthStart = today.minusDays((MONTH_DAYS - 1).toLong())
        var monthMs = 0L
        val daily = mutableListOf<DayActivity>()
        for (i in 0 until MONTH_DAYS) {
            val d = monthStart.plusDays(i.toLong())
            val dur = dayMap[d] ?: 0L
            monthMs += dur
            daily.add(
                DayActivity(
                    date = d.format(ISO_DATE),
                    dayName = d.dayOfMonth.toString(),
                    durationMs = dur,
                    isToday = d == today,
                ),
            )
        }
        return WeeklyStats(todayMs = todayMs, weekMs = monthMs, completedBooks = completedBooks, dailyActivity = daily)
    }

    private fun summarizeYear(
        dayMap: Map<LocalDate, Long>,
        today: LocalDate,
        todayMs: Long,
        completedBooks: Int,
        locale: Locale,
    ): WeeklyStats {
        val currentMonthStart = today.withDayOfMonth(1)
        var yearMs = 0L
        val daily = mutableListOf<DayActivity>()
        for (back in YEAR_MONTHS - 1 downTo 0) {
            val monthStart = currentMonthStart.minusMonths(back.toLong())
            val monthEnd = monthStart.plusMonths(1).minusDays(1)
            var dur = 0L
            for ((date, ms) in dayMap) {
                if (!date.isBefore(monthStart) && !date.isAfter(monthEnd)) dur += ms
            }
            yearMs += dur
            val isCurrentMonth = monthStart.year == today.year && monthStart.month == today.month
            daily.add(
                DayActivity(
                    date = monthStart.format(ISO_DATE),
                    dayName = monthStart.month.getDisplayName(TextStyle.SHORT, locale).take(3),
                    durationMs = dur,
                    isToday = isCurrentMonth,
                ),
            )
        }
        return WeeklyStats(todayMs = todayMs, weekMs = yearMs, completedBooks = completedBooks, dailyActivity = daily)
    }

    private fun shortWeekday(date: LocalDate, locale: Locale): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).take(2)

    /**
     * Скільки лишилося до найближчої локальної півночі.
     */
    fun millisUntilNextMidnight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val nextMidnight = localDate(now, zone)
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        return (nextMidnight - now).coerceAtLeast(1L)
    }

    private fun localDate(now: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
