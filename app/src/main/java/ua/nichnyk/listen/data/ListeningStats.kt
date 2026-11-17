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
    private val ISO_TIME_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Скільки днів (разом із сьогоднішнім) входить у «за тиждень». */
    const val WEEK_DAYS = 7

    /** Скільки останніх днів показує місячний графік. */
    const val MONTH_DAYS = 30

    /** Скільки місяців у річному графіку. */
    const val YEAR_MONTHS = 12

    /** Стандартні пресети денної цілі в хвилинах (0 — вимкнено). */
    val DAILY_GOAL_PRESETS = listOf(0, 15, 30, 45, 60, 90, 120)

    /** Частка виконання денної цілі 0..1 (0, якщо ціль вимкнена). */
    fun dailyGoalProgress(todayMs: Long, goalMinutes: Int): Float {
        if (goalMinutes <= 0) return 0f
        return (todayMs.coerceAtLeast(0L).toFloat() / (goalMinutes * 60_000f)).coerceIn(0f, 1f)
    }

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
        val (currentStreak, longestStreak) = computeStreaks(dayMap, today)
        val totalDays = dayMap.count { (d, ms) -> ms > 0L && !d.isAfter(today) }

        val base = when (period) {
            StatsPeriod.Week -> summarizeWeek(dayMap, today, todayMs, completedBooks, locale)
            StatsPeriod.Month -> summarizeMonth(dayMap, today, todayMs, completedBooks, locale)
            StatsPeriod.Year -> summarizeYear(dayMap, today, todayMs, completedBooks, locale)
        }
        return base.copy(
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            totalDays = totalDays,
        )
    }

    /**
     * Обчислює поточну й найдовшу серію днів прослуховування поспіль.
     *
     * Поточна серія не обнуляється зранку, поки користувач ще не встиг увімкнути
     * книгу сьогодні: якщо сьогодні `0`, але вчора слухали, серія триває від учора.
     */
    fun computeStreaks(dayMap: Map<LocalDate, Long>, today: LocalDate): Pair<Int, Int> {
        val activeDates = dayMap.asSequence()
            .filter { (d, ms) -> ms > 0L && !d.isAfter(today) }
            .map { it.key }
            .sorted()
            .toList()
        if (activeDates.isEmpty()) return 0 to 0

        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in activeDates) {
            run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1
            if (run > longest) longest = run
            prev = d
        }

        val activeSet = activeDates.toHashSet()
        val startDay = when {
            today in activeSet -> today
            today.minusDays(1) in activeSet -> today.minusDays(1)
            else -> null
        }
        var current = 0
        var cursor = startDay
        while (cursor != null && cursor in activeSet) {
            current++
            cursor = cursor.minusDays(1)
        }
        return current to longest
    }

    /**
     * Експортує історію сесій і поденну статистику в CSV (RFC 4180).
     *
     * Сесії старше 90 днів прибираються з `listening_sessions`, але їхній сумарний
     * час назавжди залишається в `daily_listening`. Щоб експорт не губив давні дні,
     * для кожної дати, де сума в `daily_listening` перевищує суму збережених сесій,
     * додається підсумковий рядок за день без привʼязки до книги.
     */
    fun exportHistoryCsv(
        sessions: List<ListeningSessionEntity>,
        daily: List<DailyListeningEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        data class CsvRow(
            val date: String,
            val time: String,
            val book: String,
            val author: String,
            val durationMs: Long,
        )

        val rows = mutableListOf<CsvRow>()
        val sessionMsByDate = mutableMapOf<String, Long>()
        var sessionTotalMs = 0L

        for (s in sessions) {
            if (s.durationMs <= 0L) continue
            sessionTotalMs += s.durationMs
            val zdt = Instant.ofEpochMilli(s.timestamp).atZone(zone)
            val dateStr = zdt.toLocalDate().format(ISO_DATE)
            val timeStr = zdt.toLocalTime().format(ISO_TIME_HM)
            // Сесія датується початком, а daily_listening — днем, коли звук ішов. Сесія
            // 23:30–00:30 інакше давала 60 хв першому дню й «невраховані» 30 хв другому —
            // разом 90 замість 60. Тому віднімаємо її час по днях.
            splitByDay(s.timestamp, s.durationMs, zone) { day, ms ->
                sessionMsByDate[day] = (sessionMsByDate[day] ?: 0L) + ms
            }
            rows.add(
                CsvRow(
                    date = dateStr,
                    time = timeStr,
                    book = s.bookTitle,
                    author = s.author,
                    durationMs = s.durationMs,
                ),
            )
        }

        // Сума рядків не має перевищити денні підсумки. Сесія з паузою через північ
        // зберігає лише початок і зіграні хвилини, а splitByDay розкладає їх як
        // суцільний відрізок — і «залишок» другого дня додавався зверху. Бюджет
        // залишків — це те, чого в сесіях уже немає (давні дні після очищення).
        val dailyTotalMs = daily.sumOf { it.durationMs.coerceAtLeast(0L) }
        var remainderBudget = (dailyTotalMs - sessionTotalMs).coerceAtLeast(0L)
        // Першими — дні без жодної сесії (їх уже прибрало очищення історії), від найстаріших:
        // саме там справжній залишок. Дні з сесіями отримують решту бюджету в останню чергу,
        // інакше хибний «залишок» старої сесії через північ забирав би бюджет давнього дня.
        val ordered = daily.sortedWith(
            compareBy<DailyListeningEntity>({ (sessionMsByDate[it.date] ?: 0L) > 0L }, { it.date }),
        )
        for (d in ordered) {
            if (remainderBudget < 1_000L) break
            val raw = d.durationMs - (sessionMsByDate[d.date] ?: 0L)
            if (raw < 1_000L) continue
            val take = minOf(raw, remainderBudget)
            if (take < 1_000L) continue
            remainderBudget -= take
            rows.add(
                CsvRow(
                    date = d.date,
                    time = "",
                    book = "",
                    author = "",
                    durationMs = take,
                ),
            )
        }

        if (rows.isEmpty()) return ""

        rows.sortWith(compareByDescending<CsvRow> { it.date }.thenByDescending { it.time })

        val sb = StringBuilder()
        // BOM: без нього Excel відкриває UTF-8 як ANSI, і кирилиця в назвах книг ламається.
        // Google Таблиці, LibreOffice і pandas (encoding="utf-8-sig") його розуміють.
        sb.append(CSV_BOM)
        sb.appendLine("date,time,book,author,duration_sec,duration_min")
        for (r in rows) {
            val sec = (r.durationMs / 1_000L).coerceAtLeast(1L)
            val min = String.format(Locale.US, "%.1f", r.durationMs / 60_000.0)
            sb.append(csvEscape(r.date)).append(',')
                .append(csvEscape(r.time)).append(',')
                .append(csvEscape(r.book)).append(',')
                .append(csvEscape(r.author)).append(',')
                .append(sec).append(',')
                .appendLine(min)
        }
        return sb.toString()
    }

    /** Символ BOM: у вихідному коді — лише escape, голий символ посеред файла ловить lint. */
    const val CSV_BOM = "\uFEFF"

    /** Розкладає відрізок [startMs, startMs + durationMs) по локальних днях. */
    internal fun splitByDay(startMs: Long, durationMs: Long, zone: ZoneId, onDay: (String, Long) -> Unit) {
        var cursor = startMs
        val end = startMs + durationMs.coerceAtLeast(0L)
        while (cursor < end) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(end, nextDay)
            onDay(date.format(ISO_DATE), sliceEnd - cursor)
            cursor = sliceEnd
        }
    }

    private fun csvEscape(value: String): String {
        if (value.isEmpty()) return ""
        // Excel сприймає = + - @ на початку клітинки як формулу. Апостроф робить її текстом.
        val safe = if (value[0] == '=' || value[0] == '+' || value[0] == '-' || value[0] == '@') {
            "'$value"
        } else {
            value
        }
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else {
            safe
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
