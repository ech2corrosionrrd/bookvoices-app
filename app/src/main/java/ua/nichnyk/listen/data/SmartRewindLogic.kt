package ua.nichnyk.listen.data

object SmartRewindLogic {

    private const val TEN_MINUTES_MS = 10 * 60 * 1000L
    private const val ONE_HOUR_MS = 60 * 60 * 1000L
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Розраховує тривалість відмотування назад залежно від того, як довго
     * книга стояла на паузі.
     *
     *  - менше 10 хв: 0 с (пауза була короткою)
     *  - 10 хв .. 1 год: 5 с
     *  - 1 год .. 24 год: 15 с
     *  - понад 24 год: 30 с
     */
    fun calculateRewindMs(pausedDurationMs: Long): Long = when {
        pausedDurationMs < TEN_MINUTES_MS -> 0L
        pausedDurationMs < ONE_HOUR_MS -> 5_000L
        pausedDurationMs < ONE_DAY_MS -> 15_000L
        else -> 30_000L
    }

    /**
     * Позиція, з якої продовжити книгу після паузи. Закладка й явний стрибок
     * до глави сюди не йдуть — лише відновлення збереженого місця.
     */
    fun applyToPosition(
        lastPlayedAt: Long?,
        currentPositionMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Long {
        if (lastPlayedAt == null || currentPositionMs <= 0L) return currentPositionMs
        val elapsed = (now - lastPlayedAt).coerceAtLeast(0L)
        return (currentPositionMs - calculateRewindMs(elapsed)).coerceAtLeast(0L)
    }
}
