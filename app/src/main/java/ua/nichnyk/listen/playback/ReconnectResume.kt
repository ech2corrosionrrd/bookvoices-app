package ua.nichnyk.listen.playback

import android.media.AudioDeviceInfo

/**
 * Чи продовжити слухання, коли навушники чи машина підключились знову.
 *
 * Чисте рішення без Android-об'єктів: усе, що залежить від годинника й типу
 * пристрою, приходить параметрами, тож його перевіряє звичайний юніт-тест.
 *
 * Принцип один: продовжуємо лише те, що саме обірвав розрив звуку, і лише
 * якщо обрив був **нещодавно**. Підключення Bluetooth посеред ночі або через
 * годину в іншому місці не має вмикати звук — тиша важливіша за зручність.
 */
object ReconnectResume {

    /** Скільки після розриву повернення пристрою ще вважається «той самий сеанс». */
    const val WINDOW_MS = 15 * 60_000L

    /**
     * Чи обірвав розрив звуку саме відтворення, що йшло.
     *
     * Окремо йде «щойно зупинилось»: media3 сам ставить паузу на ACTION_AUDIO_BECOMING_NOISY,
     * і порядок доставки між ним та нашим приймачем не визначений. Але якщо цю зупинку
     * зробив користувач ([lastStopByUser]), вона розривом не є: пауза, а за секунду
     * вийняті навушники не мають вмикати звук при поверненні.
     */
    fun interruptedPlayback(
        isPlaying: Boolean,
        playWhenReady: Boolean,
        msSinceStop: Long,
        lastStopByUser: Boolean,
        graceMs: Long,
    ): Boolean = isPlaying || playWhenReady || (!lastStopByUser && msSinceStop < graceMs)

    /**
     * @param enabled перемикач у налаштуваннях (за замовчуванням вимкнений).
     * @param pausedByDisconnectAtMs коли відтворення зупинив розрив звуку (elapsedRealtime) або `null`.
     * @param nowMs поточний elapsedRealtime.
     * @param alreadyPlaying відтворення вже йде (користувач сам натиснув play).
     * @param addedOutputTypes типи щойно зʼявлених пристроїв виводу.
     */
    fun shouldResume(
        enabled: Boolean,
        pausedByDisconnectAtMs: Long?,
        nowMs: Long,
        alreadyPlaying: Boolean,
        addedOutputTypes: Collection<Int>,
    ): Boolean {
        if (!enabled || alreadyPlaying) return false
        val pausedAt = pausedByDisconnectAtMs ?: return false
        val elapsed = nowMs - pausedAt
        // Від'ємне — годинник пішов назад (перезапуск, інший відлік): не ризикуємо.
        if (elapsed < 0 || elapsed > WINDOW_MS) return false
        return addedOutputTypes.any { isListeningOutput(it) }
    }

    /**
     * Пристрої, якими люди слухають на ходу: Bluetooth (медіа, тобто A2DP і LE), дротові
     * й USB-навушники, слуховий апарат. Вбудований динамік, HDMI та телефонія сюди не
     * належать: їх поява нічого не каже про те, що слухач повернувся.
     *
     * **Bluetooth SCO свідомо немає:** цей канал зʼявляється, коли в гарнітурі починається
     * дзвінок, — книга «продовжилась» би посеред розмови.
     *
     * Константи нових API — це числа, які компілятор підставляє, тож на старших
     * системах вони просто ніколи не зустрінуться, а не зламають запуск.
     */
    fun isListeningOutput(type: Int): Boolean = type in LISTENING_TYPES

    @Suppress("InlinedApi")
    private val LISTENING_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
}
