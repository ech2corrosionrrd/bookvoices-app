package ua.nichnyk.listen.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Зводить стерео в моно: обидва канали отримують півсуму. Потрібно тому, що
 * начитку часто слухають одним навушником, а в стерео половина запису тоді
 * просто зникає.
 *
 * **Чому процесор активний завжди, а не лише коли ввімкнено.**
 * [onConfigure] викликається один раз — коли конвеєр дізнається формат, тобто
 * на початку файла. Якби «увімкнено» перевірялося там, перемикач у
 * налаштуваннях не робив би нічого до наступної глави: на книзі з годинними
 * главами користувач тиснув би тумблер і не чув жодної різниці.
 *
 * Тому рішення ухвалюється в [queueInput], на кожному буфері, і зміна чутна
 * через кілька десятків мілісекунд. Ціна — memcpy у вимкненому стані:
 * 48 кГц × 2 канали × 2 байти ≈ 190 КБ/с. На тлі декодування це ніщо.
 *
 * Береться лише стерео. Формати з більшою кількістю каналів пропускаємо повз
 * себе: заявити на виході 2 канали означало б зводити 5.1 і тим, хто моно не
 * вмикав, а в аудіокнигах такий звук не трапляється.
 */
@OptIn(UnstableApi::class)
class MonoDownmixAudioProcessor : BaseAudioProcessor() {

    /** Пишеться з потоку налаштувань, читається в аудіопотоці. */
    @Volatile
    var isDownmixEnabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining < BYTES_PER_FRAME) {
            // Не «return», а саме споживання: media3 повторно подає той самий
            // буфер, доки в ньому лишається хоч байт, тож хвіст, який прийшов
            // сам по собі, зупинив би відтворення замість того, щоб зникнути.
            inputBuffer.position(inputBuffer.limit())
            return
        }

        // Неповний кадр лишати в буфері не можна: конвеєр media3 крутить
        // queueInput, доки вхід не спорожніє, і хвіст у 1–3 байти зациклив би його.
        val frames = remaining / BYTES_PER_FRAME
        val usableBytes = frames * BYTES_PER_FRAME
        val buffer = replaceOutputBuffer(usableBytes)

        if (isDownmixEnabled) {
            for (i in 0 until frames) {
                val left = inputBuffer.short.toInt()
                val right = inputBuffer.short.toInt()
                val mixed = ((left + right) / 2).toShort()
                buffer.putShort(mixed)
                buffer.putShort(mixed)
            }
        } else {
            // Гуртом, а не побайтово: це той шлях, яким іде звук у всіх, хто
            // моно не вмикав.
            val savedLimit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + usableBytes)
            buffer.put(inputBuffer)
            inputBuffer.limit(savedLimit)
        }

        // Хвіст (менший за кадр) споживаємо, не використовуючи: інакше він
        // лишиться на вході назавжди.
        inputBuffer.position(inputBuffer.limit())
        buffer.flip()
    }

    private companion object {
        /** Стерео, PCM 16 біт: 2 канали × 2 байти. */
        const val BYTES_PER_FRAME = 4
    }
}
