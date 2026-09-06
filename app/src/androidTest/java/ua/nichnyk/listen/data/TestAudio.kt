package ua.nichnyk.listen.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Справжній WAV для інструментальних тестів: його однаково читають і
 * MediaMetadataRetriever (імпорт), і ExoPlayer (відтворення), тож один файл
 * покриває обидва шляхи без бінарників у репозиторії.
 */
object TestAudio {

    const val SAMPLE_RATE = 22050

    /**
     * @param channels 1 — моно, 2 — стерео з різними тонами в каналах.
     *
     * Стерео тут не про повноту заради повноти: аудіоконвеєр застосунку має
     * власний [ua.nichnyk.listen.playback.MonoDownmixAudioProcessor], і той
     * береться до роботи саме на двоканальному звуці. На моно він повертає
     * NOT_SET, тобто моно-файлом його не перевіриш узагалі.
     */
    fun writeWav(
        file: File,
        seconds: Int,
        frequency: Double = 440.0,
        channels: Int = 1,
        rightFrequency: Double = 660.0,
    ): File {
        require(channels == 1 || channels == 2) { "підтримуються лише 1 і 2 канали" }
        file.parentFile?.mkdirs()
        val frames = SAMPLE_RATE * seconds
        val bytesPerFrame = 2 * channels
        val pcm = ByteArray(frames * bytesPerFrame)
        for (i in 0 until frames) {
            val t = i.toDouble() / SAMPLE_RATE
            val left = (sin(2.0 * Math.PI * frequency * t) * 0.3 * Short.MAX_VALUE).toInt()
            val offset = i * bytesPerFrame
            pcm[offset] = (left and 0xFF).toByte()
            pcm[offset + 1] = ((left shr 8) and 0xFF).toByte()
            if (channels == 2) {
                val right = (sin(2.0 * Math.PI * rightFrequency * t) * 0.3 * Short.MAX_VALUE).toInt()
                pcm[offset + 2] = (right and 0xFF).toByte()
                pcm[offset + 3] = ((right shr 8) and 0xFF).toByte()
            }
        }
        val byteRate = SAMPLE_RATE * bytesPerFrame
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(bytesPerFrame.toShort())
        header.putShort(16) // біт на семпл
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        file.outputStream().use {
            it.write(header.array())
            it.write(pcm)
        }
        return file
    }
}
