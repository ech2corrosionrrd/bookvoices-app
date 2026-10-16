package ua.nichnyk.listen.playback

/**
 * Чиста логіка перемотування й вибору стартової глави.
 *
 * Винесено з PlayerManager, бо саме тут межові випадки: перехід через межу глави,
 * невідома тривалість, зниклий файл збереженої глави. Перевірити їх усередині
 * PlayerManager неможливо без живого MediaController і сервісу.
 */

sealed interface SeekAction {
    /** Перемотати в межах поточної глави. */
    data class To(val positionMs: Long) : SeekAction

    /** Вийти за початок глави, довжина попередньої невідома — на початок попередньої. */
    data object PreviousChapter : SeekAction

    /** Вийти за початок глави — у попередню, на [positionMs] від її початку (її кінець мінус залишок кроку). */
    data class ToPrevious(val positionMs: Long) : SeekAction

    /** Вийти за кінець глави — наступна. */
    data object NextChapter : SeekAction
}

/**
 * Куди веде «±15/30 с» від поточної позиції.
 *
 * [chapterDurationMs] <= 0 означає «тривалість ще невідома» — тоді вперед не обмежуємо
 * і не перестрибуємо главу, інакше кнопка «вперед» на початку буферизації
 * випадково гортала б книгу.
 *
 * Крок назад за початок глави віддає **залишок кроку** з кінця попередньої, коли її
 * довжина відома ([previousDurationMs] > 0): «назад 15 с» на п'ятій секунді має
 * дати десять секунд з кінця попередньої, а не всю її — на довгих главах m4b це
 * були б десятки хвилин. Невідома довжина — старий шлях, початок попередньої.
 */
fun seekTarget(
    currentPositionMs: Long,
    chapterDurationMs: Long,
    deltaMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    previousDurationMs: Long = 0L,
): SeekAction {
    val duration = if (chapterDurationMs > 0L) chapterDurationMs else Long.MAX_VALUE
    val target = currentPositionMs + deltaMs
    return when {
        target < 0L && hasPrevious && previousDurationMs > 0L ->
            SeekAction.ToPrevious((previousDurationMs + target).coerceIn(0L, previousDurationMs))
        target < 0L && hasPrevious -> SeekAction.PreviousChapter
        target < 0L -> SeekAction.To(0L)
        target >= duration && hasNext -> SeekAction.NextChapter
        target >= duration -> SeekAction.To(duration)
        else -> SeekAction.To(target)
    }
}

/**
 * З якої глави й позиції починати, якщо частина файлів недоступна.
 *
 * [playable] — доступність глав у тому ж порядку, що й самі глави.
 * Повертає null, коли грати нічого.
 *
 * Позиція зберігається лише для тієї глави, яку справді просили: після переходу
 * на іншу главу стара позиція безглузда (і легко опиниться за межами файла).
 */
fun resolvePlayableStart(
    requestedIndex: Int,
    requestedPositionMs: Long,
    playable: List<Boolean>,
): Pair<Int, Long>? {
    if (playable.isEmpty() || playable.none { it }) return null
    val wanted = requestedIndex.coerceIn(0, playable.lastIndex)
    if (playable[wanted]) return wanted to requestedPositionMs.coerceAtLeast(0L)
    val fallback = playable.indexOfFirst { it }
    return fallback to 0L
}

/**
 * Підпис на кнопці «Повернутися на 01:23:45».
 *
 * Формат тут свій, не [ua.nichnyk.listen.data.formatClock]: на кнопці потрібні саме
 * дві цифри хвилин, щоб напис не смикався по ширині, поки книга грає.
 *
 * Окремою функцією — бо жила всередині приватного `recordJumpBack`, і перевірити
 * її можна було тільки з живим MediaController. Тест від того був не тестом:
 * він переписував ці ж три рядки в себе й порівнював їх із собою.
 */
fun jumpBackLabel(positionMs: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val safe = positionMs.coerceAtLeast(0L)
    val seconds = (safe / 1000) % 60
    val minutes = (safe / (1000 * 60)) % 60
    val hours = safe / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format(locale, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(locale, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Чи треба забути «останню книгу», коли зі сховища зникла [clearedBookId].
 *
 * Лише якщо це саме вона. Видалення, доливання файлів чи перепривʼязка іншої книги
 * не мають стирати місце, де слухач зупинився в цій.
 */
fun shouldForgetLastBook(lastBookId: String?, clearedBookId: String): Boolean =
    lastBookId != null && lastBookId == clearedBookId

/**
 * Швидкість, яку книга «обрала» сама, або `null`, якщо їй іде значення з налаштувань.
 *
 * Книга, яку ще жодного разу не вмикали і в якої стоїть рівно 1.0, свою швидкість не
 * обирала. У всіх інших випадках виграє швидкість книги. Спільне для телефона
 * (PlayerManager) і для Android Auto / голосу (PlaybackService).
 */
fun bookChosenSpeed(playbackSpeed: Float, lastPlayedAt: Long?): Float? =
    playbackSpeed.takeIf { it > 0f && (lastPlayedAt != null || it != 1f) }
