package ua.nichnyk.listen.playback

import android.media.audiofx.Equalizer
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.data.VoicePreset

/**
 * Підйоми й зрізи смуг як частка діапазону еквалайзера: -1 — до самого низу,
 * +1 — до самого верху, 0 — рівно.
 *
 * Функція чиста й не знає про `android.media.audiofx`: інакше перевірити пресети
 * можна було б лише на пристрої з живим Equalizer, а помилка тут не чутна відразу —
 * вона просто робить пресет тихим no-op.
 *
 * Смуги адресуються **часткою від діапазону**, а не індексом. Кількість смуг
 * залежить від чипсета (буває від 2 до 10), і зашиті `numBands - 2` різали в
 * середину на трисмуговому еквалайзері, а при `numBands < 4` пресети
 * `WARM` і `TREBLE_CUT` не робили взагалі нічого.
 */
fun voiceBandGains(preset: VoicePreset, numBands: Int): FloatArray {
    val gains = FloatArray(maxOf(numBands, 0))
    if (numBands < 2 || preset == VoicePreset.OFF) return gains
    val last = numBands - 1
    for (i in 0 until numBands) {
        val p = i.toFloat() / last
        gains[i] = when (preset) {
            VoicePreset.OFF -> 0f
            // Зрізати гул, підняти середину — саме там розбірливість мови.
            VoicePreset.SPEECH_CLARITY -> when {
                p <= 0.15f -> -0.35f
                p < 0.5f -> 0.20f
                p <= 0.8f -> 0.40f
                else -> 0f
            }
            // Підняти нижню середину (теплий тембр), м'яко прибрати верх.
            VoicePreset.WARM -> when {
                p <= 0.15f -> 0f
                p <= 0.5f -> 0.35f
                p >= 0.85f -> -0.25f
                else -> 0f
            }
            // Зрізати верх: шипіння дикції та шум старих записів.
            VoicePreset.TREBLE_CUT -> when {
                p >= 0.9f -> -0.60f
                p >= 0.6f -> -0.40f
                else -> 0f
            }
        }
    }
    return gains
}

/**
 * Апаратний еквалайзер під голос.
 *
 * Єдиний власник `Equalizer` у процесі. Другий екземпляр на тій самій аудіосесії
 * забирає контроль у першого (AudioEffect віддає його останньому створеному), і
 * `setBandLevel` у того, хто контроль втратив, мовчки не діє — саме так «чіткість
 * мовлення» окремим тумблером глушила пресети, і навпаки.
 */
class VoiceEqualizer {

    private var equalizer: Equalizer? = null
    private var currentSessionId: Int = 0
    private var currentPreset: VoicePreset = VoicePreset.OFF

    fun setAudioSession(sessionId: Int) {
        if (sessionId == currentSessionId) return
        // Нуль — це C.AUDIO_SESSION_ID_UNSET: сесії більше немає. Раніше вихід був
        // спільний із «та сама сесія», тож ефект лишався прив'язаним до мертвої
        // сесії до самого onDestroy — на відміну від LoudnessEnhancer поруч, який
        // звільняється в тому ж колбеку беззастережно.
        release()
        if (sessionId == 0) return
        currentSessionId = sessionId
        try {
            // Локальна змінна, а не поле в `apply`: присвоєння `equalizer = …`
            // відбувається лише після виходу з блоку, тож applyPresetInternal
            // бачив там null (release() рядком вище) і виходив, нічого не зробивши.
            // Пресет через це не застосовувався на жодному старті відтворення.
            val eq = Equalizer(0, sessionId)
            equalizer = eq
            eq.enabled = currentPreset != VoicePreset.OFF
            if (eq.enabled) applyPresetInternal(eq, currentPreset)
        } catch (e: Exception) {
            AppLog.w("VoiceEqualizer: could not create hardware Equalizer for session $sessionId", e)
            equalizer = null
        }
    }

    fun applyPreset(preset: VoicePreset) {
        currentPreset = preset
        val eq = equalizer ?: return
        try {
            eq.enabled = preset != VoicePreset.OFF
            if (eq.enabled) applyPresetInternal(eq, preset)
        } catch (e: Exception) {
            AppLog.w("VoiceEqualizer: failed to apply preset $preset", e)
        }
    }

    private fun applyPresetInternal(eq: Equalizer, preset: VoicePreset) {
        val numBands = eq.numberOfBands.toInt()
        if (numBands < 2) return

        val minBandLevel = eq.bandLevelRange[0]
        val maxBandLevel = eq.bandLevelRange[1]

        voiceBandGains(preset, numBands).forEachIndexed { band, ratio ->
            // minBandLevel відʼємний, тож зріз множимо на нього й отримуємо мінус.
            val level = if (ratio >= 0f) ratio * maxBandLevel else -ratio * minBandLevel
            eq.setBandLevel(band.toShort(), level.toInt().toShort().coerceIn(minBandLevel, maxBandLevel))
        }
    }

    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            AppLog.w("VoiceEqualizer: release exception", e)
        } finally {
            equalizer = null
            currentSessionId = 0
        }
    }
}
