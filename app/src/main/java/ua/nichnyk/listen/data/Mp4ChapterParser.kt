package ua.nichnyk.listen.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset

data class ChapterMark(
    val title: String,
    val startMs: Long,
)

object Mp4ChapterParser {

    /**
     * Назва для глави без власного заголовка. Парсер не має доступу до ресурсів,
     * тому локалізований варіант передає викликач (AudioImporter); за замовчуванням —
     * просто номер, без української «Розділ N», зашитої в код.
     */
    private val NUMBER_ONLY: (Int) -> String = { n -> n.toString() }

    fun parse(
        channel: FileChannel,
        fileSize: Long,
        durationMs: Long,
        fallbackTitle: (Int) -> String = NUMBER_ONLY,
    ): List<ChapterMark> {
        if (fileSize < 16L) return emptyList()
        val found = mutableListOf<ChapterMark>()
        walk(channel, 0L, fileSize, found, depth = 0, fallbackTitle = fallbackTitle)
        if (found.size < 2) return emptyList()
        val ordered = found.distinctBy { it.startMs }.sortedBy { it.startMs }
        return if (ordered.size >= 2) ordered else emptyList()
    }

    fun parseChplPayload(
        payload: ByteArray,
        fallbackTitle: (Int) -> String = NUMBER_ONLY,
    ): List<ChapterMark> {
        if (payload.size < 16) return emptyList()
        return readChpl(payload, countOffset = 5, countBytes = 3, fallbackTitle = fallbackTitle)
            ?: readChpl(payload, countOffset = 4, countBytes = 4, fallbackTitle = fallbackTitle)
            ?: emptyList()
    }

    private fun readChpl(
        payload: ByteArray,
        countOffset: Int,
        countBytes: Int,
        fallbackTitle: (Int) -> String,
    ): List<ChapterMark>? {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        if (countOffset >= payload.size) return null
        buf.position(countOffset)
        val count = when (countBytes) {
            3 -> {
                if (buf.remaining() < 3) return null
                val a = buf.get().toInt() and 0xFF
                val b = buf.get().toInt() and 0xFF
                val c = buf.get().toInt() and 0xFF
                (a shl 16) or (b shl 8) or c
            }
            4 -> if (buf.remaining() >= 4) buf.int else return null
            else -> return null
        }
        if (count !in 2..400) return null
        val marks = ArrayList<ChapterMark>(count)
        repeat(count) {
            if (buf.remaining() < 9) return null
            val start100ns = buf.long
            val titleLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < titleLen) return null
            val raw = ByteArray(titleLen)
            buf.get(raw)
            val title = decodeTitle(raw).ifBlank { fallbackTitle(marks.size + 1) }
            marks += ChapterMark(title, (start100ns / 10_000L).coerceAtLeast(0L))
        }
        return marks.takeIf { it.size >= 2 }
    }

    fun toRanges(marks: List<ChapterMark>, durationMs: Long): List<Pair<ChapterMark, Long>> {
        if (marks.size < 2) return emptyList()
        val dur = durationMs.coerceAtLeast(marks.last().startMs + 1_000L)
        return marks.mapIndexed { i, mark ->
            val end = marks.getOrNull(i + 1)?.startMs ?: dur
            mark to (end - mark.startMs).coerceAtLeast(0L)
        }
    }

    private fun walk(
        channel: FileChannel,
        start: Long,
        end: Long,
        out: MutableList<ChapterMark>,
        depth: Int,
        fallbackTitle: (Int) -> String,
    ) {
        if (depth > 12 || start + 8 > end) return
        var pos = start
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        while (pos + 8 <= end && out.size < 400) {
            channel.position(pos)
            header.clear()
            header.limit(8)
            val read = channel.read(header)
            if (read < 8) break
            header.flip()
            var size = header.int.toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            header.get(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            var headerSize = 8L
            if (size == 1L) {
                header.clear()
                header.limit(8)
                if (channel.read(header) < 8) break
                header.flip()
                size = header.long
                headerSize = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < headerSize || pos + size > end + 8) break
            val payloadStart = pos + headerSize
            val payloadEnd = pos + size
            when (type) {
                "moov", "udta", "trak", "mdia" ->
                    walk(channel, payloadStart, payloadEnd, out, depth + 1, fallbackTitle)
                "chpl" -> {
                    val len = (payloadEnd - payloadStart).toInt().coerceAtMost(512 * 1024)
                    if (len > 8) {
                        val data = ByteBuffer.allocate(len)
                        channel.position(payloadStart)
                        channel.read(data)
                        out += parseChplPayload(data.array(), fallbackTitle)
                    }
                }
            }
            if (size <= 0L) break
            pos += size
        }
    }

    private fun decodeTitle(raw: ByteArray): String {
        val utf8 = raw.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching {
            raw.toString(Charset.forName("windows-1251")).trim { it <= ' ' || it == '\u0000' }
        }.getOrDefault(utf8)
    }
}
