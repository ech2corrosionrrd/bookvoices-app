package ua.nichnyk.listen.playback

/**
 * Стан повтору фрагмента A–B у межах поточного розділу.
 *
 * Поки [endMs] == null, користувач зафіксував точку A й слухає до точки B.
 * Коли задано обидві точки ([endMs] != null), відтворення зациклюється між
 * [startMs] та [endMs].
 */
data class AbLoopState(
    val chapterIndex: Int,
    val startMs: Long,
    val endMs: Long? = null,
    /** Тривалість розділу петлі на момент, коли поставили B; 0 — невідома. */
    val chapterDurationMs: Long = 0L,
) {
    val isComplete: Boolean
        get() = endMs != null

    /**
     * B стоїть у самому кінці розділу: ExoPlayer може встигнути перейти на наступний,
     * перш ніж тік побачить позицію ≥ B.
     */
    val endsAtChapterEnd: Boolean
        get() = endMs != null && chapterDurationMs > 0L &&
            endMs >= chapterDurationMs - AbLoopLogic.CHAPTER_END_SLACK_MS
}

data class AbLoopTransition(
    val state: AbLoopState?,
    val seekToMs: Long? = null,
)

object AbLoopLogic {
    /** Мінімальна довжина петлі (1 секунда), щоб подвійний тап не створював мікроцикл. */
    const val MIN_LOOP_MS = 1_000L

    /** B ближче за цей відрізок до кінця розділу вважається «в кінці розділу». */
    const val CHAPTER_END_SLACK_MS = 1_500L

    /**
     * Наступний стан після натискання кнопки A–B у позиції [positionMs] розділу [chapterIndex].
     */
    fun nextOnToggle(
        current: AbLoopState?,
        chapterIndex: Int,
        positionMs: Long,
        chapterDurationMs: Long = 0L,
    ): AbLoopTransition {
        val pos = positionMs.coerceAtLeast(0L)
        if (current == null || current.chapterIndex != chapterIndex) {
            return AbLoopTransition(AbLoopState(chapterIndex = chapterIndex, startMs = pos))
        }
        if (current.endMs == null) {
            return when {
                pos - current.startMs >= MIN_LOOP_MS -> {
                    val next = current.copy(endMs = pos, chapterDurationMs = chapterDurationMs.coerceAtLeast(0L))
                    AbLoopTransition(next, seekToMs = next.startMs)
                }
                current.startMs - pos >= MIN_LOOP_MS -> {
                    val next = AbLoopState(
                        chapterIndex = chapterIndex,
                        startMs = pos,
                        endMs = current.startMs,
                        chapterDurationMs = chapterDurationMs.coerceAtLeast(0L),
                    )
                    AbLoopTransition(next, seekToMs = next.startMs)
                }
                else -> AbLoopTransition(null)
            }
        }
        return AbLoopTransition(null)
    }

    /**
     * Повертає цільове місце `(chapterIndex, startMs)`, якщо плеєр дійшов до кінця петлі B
     * (або щойно автоматично перейшов на початок наступного розділу, бо B була в кінці глави).
     *
     * Перехід на наступний розділ ловимо **лише** коли B у кінці розділу. Інакше «наступний
     * розділ» зі шторки, гарнітури чи Auto повертав слухача в A, і вийти з петлі можна
     * було тільки з екрана плеєра (знайдено 18 листопада 2026).
     */
    fun shouldLoopBack(
        loop: AbLoopState?,
        currentChapterIndex: Int,
        currentPositionMs: Long,
    ): Pair<Int, Long>? {
        val end = loop?.endMs ?: return null
        if (currentChapterIndex == loop.chapterIndex && currentPositionMs >= end) {
            return loop.chapterIndex to loop.startMs
        }
        if (loop.endsAtChapterEnd && currentChapterIndex == loop.chapterIndex + 1 && currentPositionMs < 3_000L) {
            return loop.chapterIndex to loop.startMs
        }
        return null
    }

    /**
     * Петля належить іншому розділу, ніж той, що грає, і це не автоматичний перехід
     * через B у кінці розділу ([shouldLoopBack] уже повернув null) — петлю слід скинути.
     * Так перехід на інший розділ будь-яким способом (шторка, гарнітура, Auto, перемотка
     * через межу) скидає цикл, як і перехід зі списку розділів.
     */
    fun isStale(loop: AbLoopState?, currentChapterIndex: Int): Boolean =
        loop != null && currentChapterIndex >= 0 && currentChapterIndex != loop.chapterIndex

    /**
     * Чи тримати петлю з B у кінці розділу, коли плеєр уже на наступному.
     *
     * Тримаємо лише поки відтворення саме хоче грати ([playWhenReady]): інакше
     * короткий буфер на межі розділу скинув би петлю раніше за повернення в A.
     * На паузі «наступний розділ» — це вибір слухача, і петлю треба зняти, інакше
     * екран її ховає, а наступне відтворення стрибає в A.
     */
    fun holdsEndRollover(
        loop: AbLoopState?,
        currentChapterIndex: Int,
        currentPositionMs: Long,
        playWhenReady: Boolean,
    ): Boolean = playWhenReady &&
        shouldLoopBack(loop, currentChapterIndex, currentPositionMs) != null
}
