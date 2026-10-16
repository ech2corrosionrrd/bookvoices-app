package ua.nichnyk.listen.data

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Один файл із CUE-листа й доріжки в ньому (час від початку саме цього файла). */
data class CueFile(val name: String, val tracks: List<ChapterMark>)

/**
 * CUE-лист поруч з одним великим аудіофайлом: «доріжка» — це глава.
 *
 * Розбирає лише те, що потрібно главам: `FILE`, `TRACK`, `TITLE` доріжки й
 * `INDEX 01`. `INDEX 00` (пауза перед доріжкою), `PERFORMER`, `REM` та решту
 * ігноруємо. Час — `мм:сс:кадри`, 75 кадрів на секунду.
 */
object CueParser {

    /** Символ BOM: у вихідному коді — лише escape, голий символ посеред файла ловить lint. */
    private const val BOM = "\uFEFF"

    private val fileLine = Regex("""^FILE\s+(?:"([^"]*)"|(\S+))""", RegexOption.IGNORE_CASE)
    private val trackLine = Regex("""^TRACK\s+\d+""", RegexOption.IGNORE_CASE)
    private val titleLine = Regex("""^TITLE\s+(?:"(.*)"|(.+))$""", RegexOption.IGNORE_CASE)
    private val indexLine = Regex("""^INDEX\s+(\d+)\s+(\d+):(\d{1,2}):(\d{1,2})""", RegexOption.IGNORE_CASE)

    fun parse(text: String, fallbackTitle: (Int) -> String = { it.toString() }): List<CueFile> {
        val files = ArrayList<CueFile>()
        var fileName: String? = null
        var tracks = ArrayList<ChapterMark>()
        // Поточна доріжка збирається з кількох рядків: назва й старт.
        var inTrack = false
        var trackTitle: String? = null
        var trackStart: Long? = null

        fun closeTrack() {
            if (inTrack && trackStart != null) {
                tracks += ChapterMark(trackTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle(tracks.size + 1), trackStart!!)
            }
            inTrack = false
            trackTitle = null
            trackStart = null
        }

        fun closeFile() {
            closeTrack()
            val name = fileName
            if (name != null && tracks.isNotEmpty()) {
                files += CueFile(name, tracks.sortedBy { it.startMs })
            }
            tracks = ArrayList()
        }

        for (raw in text.removePrefix(BOM).lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val fileMatch = fileLine.find(line)
            if (fileMatch != null) {
                closeFile()
                fileName = fileMatch.groupValues[1].ifEmpty { fileMatch.groupValues[2] }
                continue
            }
            if (trackLine.containsMatchIn(line)) {
                closeTrack()
                inTrack = true
                continue
            }
            if (!inTrack) continue // TITLE до першої доріжки — назва альбому
            val titleMatch = titleLine.find(line)
            if (titleMatch != null) {
                trackTitle = titleMatch.groupValues[1].ifEmpty { titleMatch.groupValues[2] }.trim()
                continue
            }
            val indexMatch = indexLine.find(line)
            if (indexMatch != null && indexMatch.groupValues[1].toIntOrNull() == 1) {
                val min = indexMatch.groupValues[2].toLong()
                val sec = indexMatch.groupValues[3].toLong()
                val frames = indexMatch.groupValues[4].toLong()
                trackStart = (min * 60 + sec) * 1000L + frames * 1000L / 75L
            }
        }
        closeFile()
        return files
    }

    /**
     * Доріжки для [audioName]: файл, названий у CUE так само; а якщо в теці лише один
     * аудіофайл і лише один `FILE` — вважаємо, що це він (ім'я в листі часто лишилось
     * від оригінального rip і не збігається). Менше двох доріжок — не глави.
     */
    fun tracksFor(files: List<CueFile>, audioName: String, singleAudio: Boolean): List<ChapterMark> {
        val wanted = baseName(audioName)
        val match = files.firstOrNull { baseName(it.name).equals(wanted, ignoreCase = true) }
            ?: files.singleOrNull()?.takeIf { singleAudio }
            ?: return emptyList()
        val distinct = match.tracks.distinctBy { it.startMs }
        return if (distinct.size >= 2) distinct else emptyList()
    }

    /** Текст CUE з байтів: BOM UTF-16/UTF-8, строгий UTF-8, інакше windows-1251. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val strict = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
        return strict ?: String(bytes, Charset.forName("windows-1251"))
    }

    private fun baseName(path: String): String =
        path.replace('\\', '/').substringAfterLast('/')
}
