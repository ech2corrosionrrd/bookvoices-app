package ua.nichnyk.listen.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import ua.nichnyk.listen.R
import kotlin.coroutines.resume
import kotlin.math.sin

data class DemoTrack(
    val file: File,
    val title: String,
    val durationMs: Long,
)

class DemoFactory(private val context: Context) {

    suspend fun createSpokenOrTone(): DemoTrack = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "demo").apply { mkdirs() }
        val spoken = File(dir, "nichnyk-demo.wav")
        val ok = runCatching { synthesize(spoken) }.getOrDefault(false)
        if (ok && spoken.exists() && spoken.length() > 44L) {
            DemoTrack(spoken, context.getString(R.string.demo_track_spoken), wavDurationMs(spoken))
        } else {
            val tone = File(dir, "nichnyk-tone.wav")
            writeWarmTone(tone)
            DemoTrack(tone, context.getString(R.string.demo_track_tone), wavDurationMs(tone))
        }
    }

    private suspend fun synthesize(out: File): Boolean = suspendCancellableCoroutine { cont ->
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resume(false)
                    return@TextToSpeech
                }
                val localTts = tts ?: return@TextToSpeech
                // Мова демо йде за мовою інтерфейсу: сам текст береться з ресурсів,
                // тож озвучувати його українським голосом при англійському UI не варто.
                val uiLocale = Locale.forLanguageTag(context.getString(R.string.demo_tts_language))
                val lang = when {
                    localTts.isLanguageAvailable(uiLocale) >= TextToSpeech.LANG_AVAILABLE -> uiLocale
                    localTts.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE -> Locale.getDefault()
                    else -> Locale.US
                }
                localTts.language = lang
                localTts.setSpeechRate(0.92f)
                localTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        localTts.shutdown()
                        if (cont.isActive) cont.resume(out.exists() && out.length() > 44L)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        localTts.shutdown()
                        if (cont.isActive) cont.resume(false)
                    }
                })
                val result = localTts.synthesizeToFile(context.getString(R.string.demo_spoken_text), null, out, "demo")
                if (result != TextToSpeech.SUCCESS && cont.isActive) {
                    localTts.shutdown()
                    cont.resume(false)
                }
            }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
    }

    private fun writeWarmTone(file: File) {
        val sampleRate = 22050
        val seconds = 18
        val n = sampleRate * seconds
        val pcm = ByteArrayOutputStream()
        val freqs = doubleArrayOf(220.0, 277.2, 329.6, 440.0)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = envelope(t, seconds.toDouble())
            val seg = ((t / seconds) * freqs.size).toInt().coerceAtMost(freqs.lastIndex)
            val sample = (sin(2.0 * Math.PI * freqs[seg] * t) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
            pcm.write(sample.toInt() and 0xFF)
            pcm.write((sample.toInt() shr 8) and 0xFF)
        }
        val data = pcm.toByteArray()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(data.size)
        file.outputStream().use {
            it.write(header.array())
            it.write(data)
        }
    }

    private fun envelope(t: Double, total: Double): Double {
        val attack = 0.4
        val release = 1.6
        val a = (t / attack).coerceIn(0.0, 1.0)
        val r = ((total - t) / release).coerceIn(0.0, 1.0)
        return a * r
    }

    companion object {
        fun wavDurationMs(file: File): Long {
            val parsed = runCatching {
                file.inputStream().use { ins ->
                    val header = ByteArray(44)
                    if (ins.read(header) < 44) return@runCatching 0L
                    val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val byteRate = bb.getInt(28)
                    val dataSize = bb.getInt(40)
                    if (byteRate <= 0) return@runCatching 0L
                    dataSize.toLong() * 1000L / byteRate
                }
            }.getOrDefault(0L)
            return parsed.takeIf { it > 0L } ?: 18_000L
        }
    }
}
