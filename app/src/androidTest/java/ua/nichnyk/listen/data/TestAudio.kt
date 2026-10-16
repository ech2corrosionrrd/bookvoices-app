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

    /**
     * mp3 з номером доріжки (TRCK) і, за потреби, диска (TPOS) у тегу ID3v2.3 та тихими
     * кадрами. Файл справжній для MediaMetadataRetriever: перевіряє, що застосунок читає
     * номер доріжки саме так, як його віддає система.
     */
    fun writeMp3WithTrack(file: File, seconds: Int, track: Int, disc: Int? = null): File {
        file.parentFile?.mkdirs()
        fun frame(id: String, text: String): ByteArray {
            val body = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            return id.toByteArray(Charsets.ISO_8859_1) + ByteBuffer.allocate(4).putInt(body.size).array() +
                byteArrayOf(0, 0) + body
        }
        var frames = frame("TRCK", track.toString())
        if (disc != null) frames += frame("TPOS", disc.toString())
        val size = frames.size
        val synchsafe = byteArrayOf(
            ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
        )
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + synchsafe + frames
        file.outputStream().use {
            it.write(tag)
            it.write(silentMp3Frames(seconds))
        }
        return file
    }

    /** Тихі кадри MPEG-1 Layer III, 128 кбіт/с, 44,1 кГц (417 байтів ≈ 26,1 мс). */
    private fun silentMp3Frames(seconds: Int): ByteArray {
        val frameBytes = 417
        val frameCount = (seconds * 44100.0 / 1152.0).toInt() + 1
        val audio = ByteArray(frameBytes * frameCount)
        for (i in 0 until frameCount) {
            val o = i * frameBytes
            audio[o] = 0xFF.toByte()
            audio[o + 1] = 0xFB.toByte()
            audio[o + 2] = 0x90.toByte()
            audio[o + 3] = 0x00
        }
        return audio
    }

    /**
     * mp3 з тегом ID3v2.3 і кадрами CHAP: тихі кадри MPEG-1 Layer III, 128 кбіт/с,
     * 44,1 кГц (417 байтів ≈ 26,1 мс). Файл справжній для MediaMetadataRetriever,
     * тож перевіряється і читання тривалості, і розбір глав.
     *
     * @param chapters назва → старт у мс.
     */
    fun writeMp3WithChapters(file: File, seconds: Int, chapters: List<Pair<String, Long>>): File {
        file.parentFile?.mkdirs()
        fun int32(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
        fun frame(id: String, body: ByteArray): ByteArray =
            id.toByteArray(Charsets.ISO_8859_1) + int32(body.size) + byteArrayOf(0, 0) + body

        val frames = chapters.mapIndexed { i, (title, startMs) ->
            val tit2 = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + title.toByteArray(Charsets.UTF_16LE)
            val body = "ch$i".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
                int32(startMs.toInt()) + int32(startMs.toInt() + 1000) + int32(-1) + int32(-1) +
                frame("TIT2", tit2)
            frame("CHAP", body)
        }.reduce { a, b -> a + b }

        // ID3: «ID3», версія 2.3.0, прапорці 0, розмір тіла synchsafe.
        val size = frames.size
        val synchsafe = byteArrayOf(
            ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
        )
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + synchsafe + frames

        val frameBytes = 417
        val frameCount = (seconds * 44100.0 / 1152.0).toInt() + 1
        val audio = ByteArray(frameBytes * frameCount)
        for (i in 0 until frameCount) {
            val o = i * frameBytes
            audio[o] = 0xFF.toByte()
            audio[o + 1] = 0xFB.toByte()
            audio[o + 2] = 0x90.toByte()
            audio[o + 3] = 0x00
        }
        file.outputStream().use {
            it.write(tag)
            it.write(audio)
        }
        return file
    }
}
