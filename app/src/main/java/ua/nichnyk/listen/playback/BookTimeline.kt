package ua.nichnyk.listen.playback

import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.ChapterEntity

/**
 * Книга як одна шкала часу поверх розділів — для повзунка «уся книга» і «Перейти до часу».
 *
 * Тривалості беремо з бази, а поточного розділу — з плеєра, якщо він уже знає свою:
 * імпорт інколи лишає нуль, а ExoPlayer рахує точно. Якщо хоч одна тривалість невідома,
 * шкала книги ненадійна (позиція «стрибала» б), і режим «уся книга» не пропонується.
 */
object BookTimeline {

    /** Тривалості розділів за порядком; поточний — із плеєра, якщо той знає. */
    fun durations(chapters: List<ChapterEntity>, currentIndex: Int, liveDurationMs: Long): List<Long> =
        chapters.mapIndexed { i, ch -> if (i == currentIndex && liveDurationMs > 0L) liveDurationMs else ch.durationMs }

    /** Шкалою книги є сенс користуватися: кілька розділів і всі тривалості відомі. */
    fun isUsable(durations: List<Long>): Boolean = durations.size > 1 && durations.all { it > 0L }

    fun total(durations: List<Long>): Long = durations.sum()

    /** Позиція в усій книзі. */
    fun position(durations: List<Long>, chapterIndex: Int, positionMs: Long): Long =
        durations.take(chapterIndex.coerceIn(0, durations.size)).sum() + positionMs.coerceAtLeast(0L)

    /**
     * Розділ і позиція в ньому для місця в усій книзі. Межа розділів належить
     * наступному розділу (його нулю); кінець книги — кінцю останнього.
     */
    fun locate(durations: List<Long>, bookPositionMs: Long): Pair<Int, Long> {
        if (durations.isEmpty()) return 0 to 0L
        var rest = bookPositionMs.coerceIn(0L, total(durations))
        for (i in durations.indices) {
            if (rest < durations[i] || i == durations.lastIndex) return i to rest.coerceAtMost(durations[i])
            rest -= durations[i]
        }
        return durations.lastIndex to durations.last()
    }
}

/** Розбір часу, який людина ввела в «Перейти до часу». */
object TimeInput {

    /**
     * «1:23:45» — години, хвилини, секунди; «23:45» — хвилини й секунди (хвилин може
     * бути й більше 59: «90:00»); одне число — хвилини, бо в аудіокнизі так думають
     * («на 40-й хвилині»). Крапку й пробіл приймаємо як «:» — так набирають на
     * цифровій клавіатурі. null — не вдалося розібрати.
     */
    fun parse(text: String): Long? {
        val parts = text.trim().split(Regex("""\s*[:.\s]\s*""")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 3 || parts.any { p -> p.length > 4 || !p.all(Char::isDigit) }) return null
        val n = parts.map { it.toLong() }
        val sec = when (n.size) {
            1 -> n[0] * 60
            2 -> if (n[1] < 60) n[0] * 60 + n[1] else return null
            else -> if (n[1] < 60 && n[2] < 60) n[0] * 3600 + n[1] * 60 + n[2] else return null
        }
        return sec * 1000L
    }
}

/** Наступна книга того самого циклу — для «Далі в циклі» після кінця книги. */
object SeriesNext {

    /**
     * Найближча за номером недослухана книга того ж циклу з більшим номером. Цикл
     * порівнюємо без регістру й крайніх пробілів (як фільтр циклу на полиці). Без
     * номера в поточної книги — null: «наступна» тоді нічого не означає.
     */
    fun find(current: BookEntity, all: List<BookEntity>): BookEntity? {
        val series = current.series?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val order = current.seriesOrder ?: return null
        return all
            .filter { b ->
                val o = b.seriesOrder
                b.id != current.id && !b.completed && o != null && o > order &&
                    b.series?.trim().equals(series, ignoreCase = true)
            }
            .minWithOrNull(compareBy<BookEntity>({ it.seriesOrder }, { it.title }))
    }
}
