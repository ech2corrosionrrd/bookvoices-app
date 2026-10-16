package ua.nichnyk.listen

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.CueParser
import ua.nichnyk.listen.data.Id3ChapterParser

/**
 * Глави, що лежать усередині файла чи поряд із ним: ID3 CHAP і CUE.
 *
 * Помилка тут тиха: хибний час старту не падає, а мовчки відправляє слухача не в
 * те місце книги. Тому перевіряємо точні мілісекунди, а не лише кількість глав.
 */
class EmbeddedChaptersTest {

    // ---------- ID3 ----------

    private fun synchsafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
    )

    private fun int32(n: Int) = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(),
    )

    private fun frame(id: String, body: ByteArray, major: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major == 4) synchsafe(body.size) else int32(body.size))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    private fun text(encoding: Int, bytes: ByteArray) = byteArrayOf(encoding.toByte()) + bytes

    private fun chap(elementId: String, startMs: Int, title: ByteArray?, titleEncoding: Int, major: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(elementId.toByteArray(Charsets.ISO_8859_1))
        body.write(0)
        body.write(int32(startMs))
        body.write(int32(startMs + 1000))
        body.write(int32(-1)) // 0xFFFFFFFF — зміщення не задано
        body.write(int32(-1))
        if (title != null) body.write(frame("TIT2", text(titleEncoding, title), major))
        return frame("CHAP", body.toByteArray(), major)
    }

    private fun tag(major: Int, frames: List<ByteArray>, padding: Int = 0, flags: Int = 0): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { body.write(it) }
        repeat(padding) { body.write(0) }
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(byteArrayOf(major.toByte(), 0, flags.toByte()))
        out.write(synchsafe(body.size()))
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    @Test
    fun v23ChaptersKeepExactStartTimesAndOrder() {
        val t = tag(
            3,
            listOf(
                // Навмисно не по порядку: у файлі кадри йдуть як упало.
                chap("ch2", 90_500, "Two".toByteArray(), 0, 3),
                chap("ch1", 0, "One".toByteArray(), 0, 3),
                chap("ch3", 3_600_000, "Three".toByteArray(), 0, 3),
            ),
            padding = 32,
        )
        val marks = Id3ChapterParser.parse(t)
        assertEquals(listOf("One", "Two", "Three"), marks.map { it.title })
        assertEquals(listOf(0L, 90_500L, 3_600_000L), marks.map { it.startMs })
    }

    @Test
    fun v24UsesSynchsafeFrameSizes() {
        // Тіло понад 127 байтів: звичайне big-endian число дало б інший розмір.
        val long = "A".repeat(200)
        val t = tag(4, listOf(chap("a", 0, long.toByteArray(), 3, 4), chap("b", 5_000, "B".toByteArray(), 3, 4)))
        val marks = Id3ChapterParser.parse(t)
        assertEquals(2, marks.size)
        assertEquals(long, marks[0].title)
        assertEquals(5_000L, marks[1].startMs)
    }

    @Test
    fun cyrillicTitlesInUtf16AndInWindows1251() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Вступ".toByteArray(Charsets.UTF_16LE)
        val cp1251 = "Глава друга".toByteArray(charset("windows-1251"))
        val t = tag(3, listOf(chap("a", 0, utf16, 1, 3), chap("b", 60_000, cp1251, 0, 3)))
        val marks = Id3ChapterParser.parse(t)
        assertEquals(listOf("Вступ", "Глава друга"), marks.map { it.title })
    }

    @Test
    fun chapterWithoutTitleGetsFallbackByNumber() {
        val t = tag(3, listOf(chap("a", 0, null, 0, 3), chap("b", 1_000, "B".toByteArray(), 0, 3)))
        val marks = Id3ChapterParser.parse(t) { n -> "Розділ $n" }
        assertEquals("Розділ 1", marks[0].title)
        assertEquals("B", marks[1].title)
    }

    @Test
    fun singleChapterOrNoChapFramesIsNotAChapterList() {
        assertTrue(Id3ChapterParser.parse(tag(3, listOf(chap("a", 0, "A".toByteArray(), 0, 3)))).isEmpty())
        assertTrue(Id3ChapterParser.parse(tag(3, listOf(frame("TIT2", text(0, "Book".toByteArray()), 3)))).isEmpty())
    }

    @Test
    fun notAnId3TagOrTruncatedTagIsIgnoredWithoutThrowing() {
        assertEquals(-1, Id3ChapterParser.tagLength("RIFFxxxxxxxx".toByteArray()))
        assertTrue(Id3ChapterParser.parse(ByteArray(0)).isEmpty())
        val full = tag(3, listOf(chap("a", 0, "A".toByteArray(), 0, 3), chap("b", 1_000, "B".toByteArray(), 0, 3)))
        assertTrue(Id3ChapterParser.parse(full.copyOf(full.size - 5)).isEmpty())
    }

    @Test
    fun tagLengthIsHeaderPlusSynchsafeSize() {
        val t = tag(3, listOf(chap("a", 0, "A".toByteArray(), 0, 3)), padding = 100)
        assertEquals(t.size, Id3ChapterParser.tagLength(t.copyOf(10)))
    }

    // ---------- ID3: потік, прапорці кадрів ----------

    /** Потік, що рахує лише *прочитані* байти: `skip` не рахується — саме на цьому тримається економія. */
    private class CountingStream(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes) {
        var readBytes = 0L
        override fun read(): Int = super.read().also { if (it >= 0) readBytes++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) readBytes += it }
    }

    private fun frameWithFlags(id: String, payload: ByteArray, major: Int, flags2: Int, prefix: ByteArray = byteArrayOf()): ByteArray {
        val body = prefix + payload
        return id.toByteArray(Charsets.ISO_8859_1) +
            (if (major == 4) synchsafe(body.size) else int32(body.size)) +
            byteArrayOf(0, flags2.toByte()) + body
    }

    private fun chapPayload(elementId: String, startMs: Int, title: String, major: Int): ByteArray =
        elementId.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            int32(startMs) + int32(startMs + 1000) + int32(-1) + int32(-1) +
            frame("TIT2", text(3, title.toByteArray()), major)

    @Test
    fun streamParsingGivesTheSameChaptersAsByteParsing() {
        for (major in listOf(3, 4)) {
            val t = tag(
                major,
                listOf(
                    chap("a", 0, "Вступ".toByteArray(), 3, major),
                    chap("b", 61_000, "Глава".toByteArray(), 3, major),
                ),
                padding = 20,
            )
            val fromBytes = Id3ChapterParser.parse(t)
            val fromStream = Id3ChapterParser.parse(java.io.ByteArrayInputStream(t))
            assertEquals("v2.$major", fromBytes, fromStream)
            assertEquals(2, fromStream.size)
        }
    }

    @Test
    fun streamParsingSkipsHugeCoverArtInsteadOfReadingIt() {
        // Обкладинка на 3 МБ стоїть ПЕРЕД главами, як у реальних файлах.
        val cover = frame("APIC", ByteArray(3 * 1024 * 1024) { 7 }, 3)
        val t = tag(3, listOf(cover, chap("a", 0, "One".toByteArray(), 0, 3), chap("b", 9_000, "Two".toByteArray(), 0, 3)))
        val stream = CountingStream(t)

        val marks = Id3ChapterParser.parse(stream)

        assertEquals(listOf("One", "Two"), marks.map { it.title })
        assertTrue("прочитано ${stream.readBytes} Б — обкладинку мали пропустити", stream.readBytes < 4_096)
    }

    @Test
    fun streamParsingToleratesTruncatedInputWithoutThrowing() {
        val t = tag(3, listOf(chap("a", 0, "A".toByteArray(), 0, 3), chap("b", 1_000, "B".toByteArray(), 0, 3)))
        for (cut in listOf(0, 5, 10, 25, t.size / 2, t.size - 3)) {
            Id3ChapterParser.parse(java.io.ByteArrayInputStream(t.copyOf(cut))) // не має кидати
        }
        assertTrue(Id3ChapterParser.parse(java.io.ByteArrayInputStream(ByteArray(0))).isEmpty())
    }

    @Test
    fun v24FrameWithDataLengthIndicatorDoesNotShiftTheStartTimes() {
        // Прапорець 0x01 у v2.4: перед даними кадру стоять чотири байти довжини.
        val a = chapPayload("a", 0, "A", 4)
        val b = chapPayload("b", 75_000, "B", 4)
        val t = tag(
            4,
            listOf(
                frameWithFlags("CHAP", a, 4, 0x01, prefix = synchsafe(a.size)),
                frameWithFlags("CHAP", b, 4, 0x01, prefix = synchsafe(b.size)),
            ),
        )
        for (marks in listOf(Id3ChapterParser.parse(t), Id3ChapterParser.parse(java.io.ByteArrayInputStream(t)))) {
            assertEquals(listOf(0L, 75_000L), marks.map { it.startMs })
            assertEquals(listOf("A", "B"), marks.map { it.title })
        }
    }

    @Test
    fun compressedOrEncryptedFramesAreSkippedNotMisread() {
        val ok1 = chapPayload("a", 0, "A", 3)
        val ok2 = chapPayload("b", 5_000, "B", 3)
        val garbage = chapPayload("c", 9_000, "C", 3)
        // v2.3: 0x80 — стиснення. Такий кадр не читаємо, але й не ламаємо решту.
        val t = tag(3, listOf(frameWithFlags("CHAP", ok1, 3, 0), frameWithFlags("CHAP", garbage, 3, 0x80), frameWithFlags("CHAP", ok2, 3, 0)))
        assertEquals(listOf("A", "B"), Id3ChapterParser.parse(t).map { it.title })
        assertEquals(listOf("A", "B"), Id3ChapterParser.parse(java.io.ByteArrayInputStream(t)).map { it.title })
    }

    @Test
    fun unsynchronisedTagIsDecodedBeforeParsing() {
        // Старт 0x00FF0000 мс містить байт 0xFF; при unsynchronisation після нього стоїть 0x00.
        val raw = tag(3, listOf(chap("a", 0, "A".toByteArray(), 0, 3), chap("b", 0x00FF0000, "B".toByteArray(), 0, 3)))
        val body = raw.copyOfRange(10, raw.size)
        val unsync = ByteArrayOutputStream()
        for (i in body.indices) {
            unsync.write(body[i].toInt())
            if (body[i] == 0xFF.toByte()) unsync.write(0)
        }
        val ub = unsync.toByteArray()
        val flagged = "ID3".toByteArray() + byteArrayOf(3, 0, 0x80.toByte()) + synchsafe(ub.size) + ub

        assertEquals(listOf(0L, 0x00FF0000L), Id3ChapterParser.parse(flagged).map { it.startMs })
        assertEquals(listOf(0L, 0x00FF0000L), Id3ChapterParser.parse(java.io.ByteArrayInputStream(flagged)).map { it.startMs })
    }

    // ---------- CUE ----------

    @Test
    fun cueTimesAreMinutesSecondsAndSeventyFifthsOfASecond() {
        val files = CueParser.parse(
            """
            PERFORMER "Автор"
            TITLE "Книга"
            FILE "book.mp3" MP3
              TRACK 01 AUDIO
                TITLE "Вступ"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Глава перша"
                INDEX 00 12:29:50
                INDEX 01 12:30:37
            """.trimIndent(),
        )
        assertEquals(1, files.size)
        val tracks = files[0].tracks
        assertEquals(listOf("Вступ", "Глава перша"), tracks.map { it.title })
        // 12:30 і 37 кадрів з 75: 750 с + 37/75 с = 750 493 мс (округлення вниз)
        assertEquals(750_493L, tracks[1].startMs)
        // INDEX 00 (пауза) не повинен ставати стартом глави
        assertTrue(tracks[1].startMs > (12 * 60 + 29) * 1000L + 50 * 1000L / 75)
    }

    @Test
    fun cueWithSeveralFilesKeepsTracksPerFile() {
        val files = CueParser.parse(
            """
            FILE "part1.mp3" MP3
              TRACK 01 AUDIO
                TITLE "A"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "B"
                INDEX 01 05:00:00
            FILE "part2.mp3" MP3
              TRACK 03 AUDIO
                TITLE "C"
                INDEX 01 00:00:00
              TRACK 04 AUDIO
                TITLE "D"
                INDEX 01 07:00:00
            """.trimIndent(),
        )
        assertEquals(listOf("part1.mp3", "part2.mp3"), files.map { it.name })
        assertEquals(listOf("C", "D"), files[1].tracks.map { it.title })
        assertEquals(420_000L, files[1].tracks[1].startMs)
    }

    @Test
    fun cueFileIsMatchedByNameIgnoringCaseAndFolder() {
        val files = CueParser.parse(
            "FILE \"C:\\rips\\Book.MP3\" MP3\nTRACK 01 AUDIO\nTITLE \"A\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \"B\"\nINDEX 01 01:00:00\n",
        )
        assertEquals(2, CueParser.tracksFor(files, "book.mp3", singleAudio = false).size)
        // Чужий файл: без «єдиного аудіо» збігу немає
        assertTrue(CueParser.tracksFor(files, "other.mp3", singleAudio = false).isEmpty())
        // Єдиний аудіофайл у теці й єдиний FILE у листі — це він, хоч би як його перейменували
        assertEquals(2, CueParser.tracksFor(files, "other.mp3", singleAudio = true).size)
    }

    @Test
    fun cueWithOneTrackIsNotAChapterList() {
        val files = CueParser.parse("FILE \"a.mp3\" MP3\nTRACK 01 AUDIO\nTITLE \"A\"\nINDEX 01 00:00:00\n")
        assertTrue(CueParser.tracksFor(files, "a.mp3", singleAudio = true).isEmpty())
    }

    @Test
    fun cueWithoutTitleGetsFallbackAndBomIsStripped() {
        val files = CueParser.parse(
            "\uFEFFFILE \"a.mp3\" MP3\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 02:00:00\n",
        ) { n -> "Розділ $n" }
        assertEquals(listOf("Розділ 1", "Розділ 2"), files[0].tracks.map { it.title })
    }

    @Test
    fun cueDecodingPrefersUtf8ThenWindows1251() {
        val utf8 = "TITLE \"Прив\u0456т\"".toByteArray(Charsets.UTF_8)
        assertEquals("TITLE \"Прив\u0456т\"", CueParser.decode(utf8))
        val cp1251 = "TITLE \"Привет\"".toByteArray(charset("windows-1251"))
        assertEquals("TITLE \"Привет\"", CueParser.decode(cp1251))
    }
}
