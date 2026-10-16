package ua.nichnyk.listen.data

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Глави з кадрів CHAP тегу ID3v2.3/2.4 (великі mp3, де одна книга лежить одним файлом).
 *
 * Читає лише теґ на початку файла: він сам каже свою довжину, тож решту mp3
 * чіпати не треба. Версія 2.2 (трисимвольні кадри) глав не має — ігноруємо.
 * Парсер не має доступу до ресурсів, тому назву для глави без заголовка
 * дає викликач, як і в [Mp4ChapterParser].
 */
object Id3ChapterParser {

    private const val HEADER = 10
    private const val MAX_CHAPTERS = 500

    /** Кадр CHAP із назвою — кілька сотень байтів; усе, що більше, — не наш кадр. */
    private const val MAX_CHAP_FRAME_BYTES = 64 * 1024

    /**
     * Розбір просто з потоку: читає лише заголовки кадрів і самі кадри CHAP, а решту
     * (обкладинку на мегабайти, коментарі) перестрибує через `skip`. Для книги з сотні
     * mp3 це різниця між читанням сотні заголовків і сотні мегабайтів.
     *
     * Теґ із прапорцем unsynchronisation потребує цілого тіла: його читаємо (не більше
     * [maxTagBytes]) і віддаємо [parse].
     */
    fun parse(
        input: java.io.InputStream,
        maxTagBytes: Int = 16 * 1024 * 1024,
        fallbackTitle: (Int) -> String = { it.toString() },
    ): List<ChapterMark> {
        val header = ByteArray(HEADER)
        if (!readFully(input, header, 0, HEADER)) return emptyList()
        val total = tagLength(header)
        if (total < 0) return emptyList()
        val major = header[3].toInt()
        val flags = header[5].toInt() and 0xFF
        val bodySize = total - HEADER

        if (flags and 0x80 != 0) {
            if (total > maxTagBytes) return emptyList()
            val all = header.copyOf(total)
            if (!readFully(input, all, HEADER, bodySize)) return emptyList()
            return parse(all, fallbackTitle)
        }

        var remaining = bodySize
        if (flags and 0x40 != 0) {
            // Розширений заголовок: пропускаємо за його власним розміром.
            if (remaining < 4) return emptyList()
            val sizeBytes = ByteArray(4)
            if (!readFully(input, sizeBytes, 0, 4)) return emptyList()
            val ext = if (major == 4) synchsafe(sizeBytes, 0) else readInt(sizeBytes, 0) + 4
            if (ext < 4 || ext > remaining) return emptyList()
            if (!skipFully(input, (ext - 4).toLong())) return emptyList()
            remaining -= ext
        }

        val marks = ArrayList<ChapterMark>()
        val frameHeader = ByteArray(HEADER)
        while (remaining >= HEADER && marks.size < MAX_CHAPTERS) {
            if (!readFully(input, frameHeader, 0, HEADER)) break
            remaining -= HEADER
            if (frameHeader[0].toInt() == 0) break // паддінг
            val id = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) synchsafe(frameHeader, 4) else readInt(frameHeader, 4)
            if (size < 0 || size > remaining) break
            if (id == "CHAP" && size <= MAX_CHAP_FRAME_BYTES) {
                val body = ByteArray(size)
                if (!readFully(input, body, 0, size)) break
                val payload = framePayload(body, major, frameHeader[9].toInt() and 0xFF)
                if (payload != null) {
                    chap(payload, 0, payload.size, major, marks.size + 1, fallbackTitle)?.let { marks += it }
                }
            } else if (!skipFully(input, size.toLong())) {
                break
            }
            remaining -= size
        }

        val ordered = marks.distinctBy { it.startMs }.sortedBy { it.startMs }
        return if (ordered.size >= 2) ordered else emptyList()
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray, off: Int, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, off + read, len - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(input: java.io.InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) {
                left -= n
            } else {
                // skip() вправі повернути 0 і без кінця потоку: перевіряємо читанням одного байта.
                if (input.read() < 0) return false
                left--
            }
        }
        return true
    }

    /**
     * Повний розмір теґу разом із заголовком за першими десятьма байтами
     * або -1, якщо це не ID3v2.3/2.4.
     */
    fun tagLength(header: ByteArray): Int {
        if (header.size < HEADER) return -1
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return -1
        val major = header[3].toInt()
        if (major != 3 && major != 4) return -1
        val size = synchsafe(header, 6)
        if (size < 0) return -1
        return HEADER + size
    }

    fun parse(
        tag: ByteArray,
        fallbackTitle: (Int) -> String = { it.toString() },
    ): List<ChapterMark> {
        val total = tagLength(tag)
        if (total < 0 || tag.size < total) return emptyList()
        val major = tag[3].toInt()
        val flags = tag[5].toInt() and 0xFF
        var body = tag.copyOfRange(HEADER, total)
        // Бит «unsynchronisation»: 0xFF 0x00 → 0xFF. У v2.4 він стоїть у кожного кадру,
        // але на практиці його ставлять на весь теґ; для v2.3 це єдиний варіант.
        if (flags and 0x80 != 0) body = removeUnsynchronisation(body)

        var pos = 0
        if (flags and 0x40 != 0) {
            // Розширений заголовок: v2.3 — розмір без самих чотирьох байтів, v2.4 — з ними.
            if (body.size < 4) return emptyList()
            val ext = if (major == 4) synchsafe(body, 0) else readInt(body, 0) + 4
            if (ext < 0 || ext > body.size) return emptyList()
            pos = ext
        }

        val marks = ArrayList<ChapterMark>()
        while (pos + HEADER <= body.size && marks.size < MAX_CHAPTERS) {
            if (body[pos].toInt() == 0) break // початок паддінгу
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) synchsafe(body, pos + 4) else readInt(body, pos + 4)
            val start = pos + HEADER
            if (size < 0 || start + size > body.size) break
            if (id == "CHAP") {
                val payload = framePayload(body.copyOfRange(start, start + size), major, body[pos + 9].toInt() and 0xFF)
                if (payload != null) {
                    chap(payload, 0, payload.size, major, marks.size + 1, fallbackTitle)?.let { marks += it }
                }
            }
            pos = start + size
        }

        val ordered = marks.distinctBy { it.startMs }.sortedBy { it.startMs }
        return if (ordered.size >= 2) ordered else emptyList()
    }

    private fun chap(
        data: ByteArray,
        from: Int,
        to: Int,
        major: Int,
        number: Int,
        fallbackTitle: (Int) -> String,
    ): ChapterMark? {
        // Ідентифікатор елемента: рядок ISO-8859-1 із завершальним нулем.
        var p = from
        while (p < to && data[p].toInt() != 0) p++
        p++ // нуль
        if (p + 16 > to) return null
        val startMs = readInt(data, p).toLong() and 0xFFFFFFFFL
        p += 16 // start, end, startOffset, endOffset

        var title: String? = null
        while (p + HEADER <= to) {
            if (data[p].toInt() == 0) break
            val id = String(data, p, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) synchsafe(data, p + 4) else readInt(data, p + 4)
            val s = p + HEADER
            if (size < 0 || s + size > to) break
            if (id == "TIT2" || (id == "TIT3" && title == null)) {
                val text = framePayload(data.copyOfRange(s, s + size), major, data[p + 9].toInt() and 0xFF)
                if (text != null) {
                    decodeText(text, 0, text.size)?.takeIf { it.isNotBlank() }?.let { title = it }
                }
            }
            p = s + size
        }
        return ChapterMark(title ?: fallbackTitle(number), startMs)
    }

    /**
     * Тіло кадру без службових надбудов за прапорцями формату [flags] (другий байт прапорців кадру).
     *
     * v2.3: 0x80 стиснення, 0x40 шифрування — такий кадр не читаємо; 0x20 — перед даними
     * байт групи. v2.4: 0x08 стиснення, 0x04 шифрування — не читаємо; 0x40 — байт групи,
     * 0x01 — чотири байти довжини даних, 0x02 — unsynchronisation. Без цього кадр із
     * індикатором довжини зсував розбір на чотири байти — тобто хибні старти глав.
     */
    private fun framePayload(raw: ByteArray, major: Int, flags: Int): ByteArray? {
        var data = raw
        var skip = 0
        if (major == 4) {
            if (flags and 0x0C != 0) return null
            if (flags and 0x40 != 0) skip += 1
            if (flags and 0x01 != 0) skip += 4
            if (flags and 0x02 != 0) data = removeUnsynchronisation(data)
        } else {
            if (flags and 0xC0 != 0) return null
            if (flags and 0x20 != 0) skip += 1
        }
        if (skip > data.size) return null
        return if (skip == 0) data else data.copyOfRange(skip, data.size)
    }

    /** Текстовий кадр: перший байт — кодування, далі текст. */
    private fun decodeText(data: ByteArray, from: Int, to: Int): String? {
        if (from >= to) return null
        val encoding = data[from].toInt()
        val raw = data.copyOfRange(from + 1, to)
        val text = when (encoding) {
            1 -> decodeUtf16WithBom(raw)
            2 -> String(raw, Charsets.UTF_16BE)
            3 -> String(raw, Charsets.UTF_8)
            else -> decodeLegacy(raw)
        }
        return text.trim { it <= ' ' || it == '\u0000' }
    }

    private fun decodeUtf16WithBom(raw: ByteArray): String {
        if (raw.size >= 2) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        }
        return String(raw, Charsets.UTF_16LE)
    }

    /**
     * «ISO-8859-1» у реальних теґах — часто кирилиця в windows-1251, записана як є.
     * Спершу пробуємо строгий UTF-8, потім windows-1251: Latin-1 для кирилиці
     * дав би лише сміття, а чистий ASCII однаковий в усіх трьох.
     */
    private fun decodeLegacy(raw: ByteArray): String {
        if (raw.all { it >= 0 }) return String(raw, Charsets.ISO_8859_1)
        return strictUtf8(raw) ?: String(raw, Charset.forName("windows-1251"))
    }

    private fun strictUtf8(raw: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(raw))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun removeUnsynchronisation(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            out.write(src[i].toInt())
            if (src[i] == 0xFF.toByte() && i + 1 < src.size && src[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    /** Чотири байти по 7 біт; байт зі старшим бітом — не synchsafe. */
    private fun synchsafe(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return -1
        var v = 0
        for (i in 0 until 4) {
            val x = b[off + i].toInt() and 0xFF
            if (x and 0x80 != 0) return -1
            v = (v shl 7) or x
        }
        return v
    }

    private fun readInt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return -1
        return ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    }
}
