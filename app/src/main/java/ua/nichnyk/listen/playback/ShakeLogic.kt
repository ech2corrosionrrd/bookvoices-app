package ua.nichnyk.listen.playback

/**
 * Розпізнавання струшування без Android-об'єктів.
 *
 * Один поштовх (телефон у кишені під час ходьби, струс у сумці) дає короткий сплеск і
 * один відлік. Справжнє струшування — серія: потрібні [REQUIRED_SPIKES] окремих сплесків
 * за [WINDOW_MS], розділені щонайменше [MIN_SPIKE_GAP_MS], інакше два сусідні відліки
 * одного удару рахувалися б за два. Після спрацювання — пауза [SLOP_MS].
 */
class ShakeLogic(
    private val thresholdG: Float = THRESHOLD_G,
) {
    private var lastSpikeAt = NEVER
    private var spikes = 0
    private var windowStart = 0L
    private var lastShakeAt = NEVER

    /** @return true, якщо цей відлік завершив струшування. */
    fun onReading(gForce: Float, nowMs: Long): Boolean {
        if (gForce <= thresholdG) return false
        if (lastShakeAt != NEVER && nowMs - lastShakeAt < SLOP_MS) return false
        if (lastSpikeAt != NEVER && nowMs - lastSpikeAt < MIN_SPIKE_GAP_MS) return false
        if (spikes == 0 || nowMs - windowStart > WINDOW_MS) {
            spikes = 0
            windowStart = nowMs
        }
        spikes++
        lastSpikeAt = nowMs
        if (spikes < REQUIRED_SPIKES) return false
        spikes = 0
        lastShakeAt = nowMs
        return true
    }

    companion object {
        const val THRESHOLD_G = 2.2f
        const val REQUIRED_SPIKES = 2
        const val WINDOW_MS = 900L
        const val MIN_SPIKE_GAP_MS = 120L
        const val SLOP_MS = 1500L
        private const val NEVER = Long.MIN_VALUE
    }
}
