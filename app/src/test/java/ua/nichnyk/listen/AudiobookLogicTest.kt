package ua.nichnyk.listen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import ua.nichnyk.listen.data.AudioImporter
import ua.nichnyk.listen.data.WebDavClient
import ua.nichnyk.listen.data.WebDavError
import ua.nichnyk.listen.data.BackupCodec
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.BookmarkEntity
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.DailyListeningEntity
import ua.nichnyk.listen.data.absolutePosition
import ua.nichnyk.listen.data.SmartRewindLogic
import ua.nichnyk.listen.data.formatClock
import ua.nichnyk.listen.data.hoursMinutesSeconds
import ua.nichnyk.listen.data.formatSpeed
import ua.nichnyk.listen.data.progress
import ua.nichnyk.listen.data.replayTarget
import ua.nichnyk.listen.data.ShelfQuery
import ua.nichnyk.listen.data.BookSortOrder

class AudiobookLogicTest {

    @Test
    fun testNaturalCompareSortsNumerically() {
        val list = listOf(
            "Chapter 10.mp3",
            "Chapter 2.mp3",
            "Chapter 1.mp3",
            "Chapter 20.mp3",
            "Chapter 3.mp3"
        )
        val sorted = list.sortedWith { a, b -> AudioImporter.naturalCompare(a, b) }
        val expected = listOf(
            "Chapter 1.mp3",
            "Chapter 2.mp3",
            "Chapter 3.mp3",
            "Chapter 10.mp3",
            "Chapter 20.mp3"
        )
        assertEquals(expected, sorted)
    }

    @Test
    fun testNaturalCompareUkrainianText() {
        val list = listOf(
            "Розділ 10 - Фінал",
            "Розділ 1 - Початок",
            "Розділ 2 - Продовження"
        )
        val sorted = list.sortedWith { a, b -> AudioImporter.naturalCompare(a, b) }
        val expected = listOf(
            "Розділ 1 - Початок",
            "Розділ 2 - Продовження",
            "Розділ 10 - Фінал"
        )
        assertEquals(expected, sorted)
    }

    @Test
    fun testFormatClock() {
        assertEquals("0:00", 0L.formatClock())
        assertEquals("0:00", (-100L).formatClock())
        assertEquals("0:45", 45_000L.formatClock())
        assertEquals("1:05", 65_000L.formatClock())
        assertEquals("10:00", 600_000L.formatClock())
        assertEquals("1:01:05", 3_665_000L.formatClock())
        val customMs = (2L * 3600L + 30L * 60L + 15L) * 1000L
        assertEquals("2:30:15", customMs.formatClock())
    }

    @Test
    fun testFormatSpeed() {
        assertEquals("1×", 1.0f.formatSpeed())
        assertEquals("1.2×", 1.2f.formatSpeed())
        assertEquals("1.25×", 1.25f.formatSpeed())
        assertEquals("0.8×", 0.8f.formatSpeed())
        assertEquals("2×", 2.0f.formatSpeed())
        assertEquals("2.5×", 2.5f.formatSpeed())
        assertEquals("1.15×", 1.15f.formatSpeed())
    }

    @Test
    fun testHoursMinutesSeconds() {
        // Одиниці («год»/«хв»/«с») підставляє UI з ресурсів, тому тут перевіряється
        // лише розкладання тривалості.
        assertEquals(Triple(0L, 0L, 0L), 0L.hoursMinutesSeconds())
        assertEquals(Triple(0L, 0L, 0L), (-5_000L).hoursMinutesSeconds())
        assertEquals(Triple(0L, 0L, 45L), 45_000L.hoursMinutesSeconds())
        assertEquals(Triple(0L, 45L, 0L), (45 * 60 * 1000L).hoursMinutesSeconds())
        assertEquals(Triple(2L, 0L, 0L), (2 * 3600 * 1000L).hoursMinutesSeconds())
        assertEquals(Triple(3L, 25L, 0L), ((3 * 3600 + 25 * 60) * 1000L).hoursMinutesSeconds())
        // Раніше це давало «0 хв» — ніби прослуховування не зарахувалося взагалі.
        assertEquals(Triple(0L, 0L, 14L), 14_000L.hoursMinutesSeconds())
        assertEquals(Triple(0L, 0L, 59L), 59_999L.hoursMinutesSeconds())
    }

    @Test
    fun testCalculateSmartRewind() {
        val now = 1_000_000_000L
        val currentPosition = 60_000L

        // Менше 10 хв — без відкату (коротка пауза)
        val recentPause = now - (2 * 60 * 1000L)
        assertEquals(60_000L, SmartRewindLogic.applyToPosition(recentPause, currentPosition, now))

        // 10 хв — 5 с
        val pause10Min = now - (10 * 60 * 1000L)
        assertEquals(55_000L, SmartRewindLogic.applyToPosition(pause10Min, currentPosition, now))

        // 2 години — 15 с
        val pause2Hours = now - (2 * 3600 * 1000L)
        assertEquals(45_000L, SmartRewindLogic.applyToPosition(pause2Hours, currentPosition, now))

        // Понад добу — 30 с
        val pause2Days = now - (48 * 3600 * 1000L)
        assertEquals(30_000L, SmartRewindLogic.applyToPosition(pause2Days, currentPosition, now))

        // Відкат не переходить за 0
        val nearStartPos = 3_000L
        assertEquals(0L, SmartRewindLogic.applyToPosition(pause2Days, nearStartPos, now))
    }

    @Test
    fun testBookProgressCalculation() {
        val book = BookEntity(
            id = "1",
            title = "Тестова книга",
            author = "Автор",
            coverPath = null,
            addedAt = 1000L,
            lastPlayedAt = 2000L,
            durationMs = 100_000L,
            positionMs = 25_000L,
            currentChapterIndex = 1,
            playbackSpeed = 1f,
            completed = false,
        )
        val chapters = listOf(
            ChapterEntity(id = "c1", bookId = "1", index = 0, title = "Глава 1", uri = "uri1", durationMs = 30_000L),
            ChapterEntity(id = "c2", bookId = "1", index = 1, title = "Глава 2", uri = "uri2", durationMs = 70_000L)
        )
        val bookWithChapters = BookWithChapters(book, chapters)

        assertEquals(55_000L, bookWithChapters.absolutePosition())
        assertEquals(0.55f, bookWithChapters.progress(), 0.001f)
    }

    @Test
    fun testReplayTargetRestartsCompletedBook() {
        assertEquals(0 to 0L, replayTarget(true, 3, 90_000L, 3, 90_000L))
        assertEquals(2 to 0L, replayTarget(true, 2, 0L, 3, 90_000L))
        assertEquals(1 to 5_000L, replayTarget(false, 1, 5_000L, 1, 5_000L))
    }

    @Test
    fun testBackupCodecRoundTrip() {
        val book = BookEntity(
            id = "book_1",
            title = "Кобзар",
            author = "Тарас Шевченко",
            coverPath = null,
            addedAt = 1_000L,
            lastPlayedAt = 2_000L,
            durationMs = 180_000L,
            positionMs = 45_000L,
            currentChapterIndex = 0,
            playbackSpeed = 1.25f,
            completed = false,
        )
        val chapter = ChapterEntity(
            id = "chap_1",
            bookId = "book_1",
            index = 0,
            title = "Розділ 1",
            uri = "file:///storage/emulated/0/audio/1.mp3",
            durationMs = 180_000L,
            startMs = 0L,
            endMs = 0L,
        )
        val mark = BookmarkEntity(
            id = "bm_1",
            bookId = "book_1",
            chapterId = "chap_1",
            chapterTitle = "Розділ 1",
            positionMs = 45_000L,
            note = "Улюблений вірш",
            createdAt = 3_000L,
        )
        val tag = ua.nichnyk.listen.data.BookTagEntity("book_1", "улюблені")
        val day = DailyListeningEntity("2026-01-01", 600_000L)
        val session = ua.nichnyk.listen.data.ListeningSessionEntity(
            id = "s1",
            bookId = "book_1",
            bookTitle = "Кобзар",
            author = "Тарас Шевченко",
            timestamp = 4_000L,
            durationMs = 120_000L,
        )

        val json = BackupCodec.encode(
            books = listOf(BookWithChapters(book, listOf(chapter))),
            bookmarks = listOf(mark),
            listening = listOf(day),
            tags = listOf(tag),
            sessions = listOf(session),
        )
        val decoded = BackupCodec.decode(json, fallbacks)

        assertEquals(listOf(book), decoded.books)
        assertEquals(listOf(chapter), decoded.chapters)
        assertEquals(listOf(mark), decoded.bookmarks)
        assertEquals(listOf(day), decoded.listening)
        assertEquals(listOf(tag), decoded.tags)
        assertEquals(listOf(session), decoded.sessions)
        // Число літералом, а не BackupCodec.VERSION: інакше тест погодиться з
        // будь-якою зміною формату мовчки. Піднімати його треба свідомо — і разом
        // із записом у README про те, що саме додала нова версія.
        assertEquals(4, decoded.version)
    }

    @Test
    fun testBackupCodecSkipsBrokenEntries() {
        // Половина відновленої полиці краща за нуль: книга без id/title і розділ без uri
        // пропускаються, решта імпортується.
        val json = """
            {
              "version": 1,
              "books": [
                { "title": "Без id" },
                { "id": "b2", "title": "Ціла", "chapters": [
                    { "id": "c1", "index": 0, "title": "A", "uri": "file:///a.mp3", "durationMs": 10 },
                    { "id": "c2", "index": 1, "title": "Без uri" }
                ] }
              ]
            }
        """.trimIndent()

        val decoded = BackupCodec.decode(json, fallbacks)
        assertEquals(1, decoded.books.size)
        assertEquals("b2", decoded.books[0].id)
        assertEquals("Невідомий", decoded.books[0].author)
        assertEquals(1, decoded.chapters.size)
        assertEquals("c1", decoded.chapters[0].id)
    }

    @Test
    fun testBackupCodecDropsOrphanBookmarks() {
        // Закладка без своєї книги порушила б зовнішній ключ і зірвала б усю транзакцію
        // відновлення — а не лише сам запис.
        val json = """
            {
              "books": [ { "id": "b1", "title": "Є", "chapters": [] } ],
              "bookmarks": [
                { "id": "m1", "bookId": "b1", "positionMs": 1 },
                { "id": "m2", "bookId": "немає", "positionMs": 2 }
              ]
            }
        """.trimIndent()

        val decoded = BackupCodec.decode(json, fallbacks)
        assertEquals(1, decoded.bookmarks.size)
        assertEquals("m1", decoded.bookmarks[0].id)
        assertTrue(decoded.tags.isEmpty())
        assertTrue(decoded.sessions.isEmpty())
    }

    @Test
    fun testBackupCodecV2WithoutSessionsReadsEmpty() {
        val json = """
            {
              "version": 2,
              "books": [ { "id": "b1", "title": "Є", "chapters": [] } ],
              "tags": [ { "bookId": "b1", "tag": "класика" } ]
            }
        """.trimIndent()
        val decoded = BackupCodec.decode(json, fallbacks)
        assertEquals(2, decoded.version)
        assertEquals(1, decoded.tags.size)
        assertTrue(decoded.sessions.isEmpty())
    }

    @Test
    fun testBackupCodecRejectsForeignFile() {
        try {
            BackupCodec.decode("""{"hello":"world"}""", fallbacks)
            throw AssertionError("очікувався JSONException")
        } catch (_: org.json.JSONException) {
            // саме те, що треба: чужий файл не має мовчки стирати полицю
        }
    }

    @Test
    fun testMergeBookPrefersFresherProgress() {
        val local = sampleBook(lastPlayedAt = 5_000L, positionMs = 10_000L)
        val olderCloud = sampleBook(lastPlayedAt = 4_000L, positionMs = 99_000L)
        val newerCloud = sampleBook(lastPlayedAt = 6_000L, positionMs = 99_000L)

        assertNull(BackupCodec.mergeBook(local, olderCloud))
        assertEquals(99_000L, BackupCodec.mergeBook(local, newerCloud)?.positionMs)
        // За рівності перемагає хмара — щоб повторне злиття було ідемпотентним.
        val sameTime = sampleBook(lastPlayedAt = 5_000L, positionMs = 77_000L)
        assertEquals(77_000L, BackupCodec.mergeBook(local, sameTime)?.positionMs)
    }

    @Test
    fun testMergeBookKeepsLocalIdentity() {
        // Назва й обкладинка лишаються локальними: хмара переносить прогрес, а не полицю.
        val local = sampleBook(lastPlayedAt = 1_000L, positionMs = 0L).copy(title = "Локальна назва")
        val cloud = sampleBook(lastPlayedAt = 2_000L, positionMs = 5L).copy(title = "Хмарна назва")
        val merged = BackupCodec.mergeBook(local, cloud)
        assertEquals("Локальна назва", merged?.title)
        assertEquals(5L, merged?.positionMs)
    }

    @Test
    fun testImageAndAudioFileRecognition() {
        assertTrue(ua.nichnyk.listen.data.AudioImporter.isImageName("cover.jpg"))
        assertTrue(ua.nichnyk.listen.data.AudioImporter.isImageName("folder.PNG"))
        assertTrue(ua.nichnyk.listen.data.AudioImporter.isImageName("front.webp"))
        assertFalse(ua.nichnyk.listen.data.AudioImporter.isImageName("chapter1.mp3"))

        assertTrue(ua.nichnyk.listen.data.AudioImporter.isAudioName("01_intro.mp3"))
        assertTrue(ua.nichnyk.listen.data.AudioImporter.isAudioName("book.m4b"))
        assertTrue(ua.nichnyk.listen.data.AudioImporter.isAudioName("track.flac"))
        assertFalse(ua.nichnyk.listen.data.AudioImporter.isAudioName("cover.jpeg"))

        assertTrue(ua.nichnyk.listen.data.AudioImporter.isImageMime("image/jpeg"))
        assertTrue(ua.nichnyk.listen.data.AudioImporter.isImageMime("image/png"))
        assertFalse(ua.nichnyk.listen.data.AudioImporter.isImageMime("audio/mpeg"))
    }

    @Test
    fun testChplPayloadParsesChapters() {
        val intro = "Intro".toByteArray()
        val next = "Next".toByteArray()
        val payload = java.nio.ByteBuffer.allocate(80).order(java.nio.ByteOrder.BIG_ENDIAN)
        payload.put(0) // version
        payload.put(0).put(0).put(0) // flags
        payload.put(0) // reserved
        payload.put(0).put(0).put(2) // count = 2
        payload.putLong(0)
        payload.put(intro.size.toByte())
        payload.put(intro)
        payload.putLong(60_000L * 10_000L)
        payload.put(next.size.toByte())
        payload.put(next)
        val marks = ua.nichnyk.listen.data.Mp4ChapterParser.parseChplPayload(payload.array())
        assertEquals(2, marks.size)
        assertEquals("Intro", marks[0].title)
        assertEquals(0L, marks[0].startMs)
        assertEquals("Next", marks[1].title)
        assertEquals(60_000L, marks[1].startMs)
        val ranges = ua.nichnyk.listen.data.Mp4ChapterParser.toRanges(marks, 120_000L)
        assertEquals(60_000L, ranges[0].second)
        assertEquals(60_000L, ranges[1].second)
    }

    @Test
    fun testMp4ChapterParserWindows1251Fallback() {
        val win1251Bytes = "Розділ 1".toByteArray(java.nio.charset.Charset.forName("windows-1251"))
        val win1251Next = "Розділ 2".toByteArray(java.nio.charset.Charset.forName("windows-1251"))
        val payload = java.nio.ByteBuffer.allocate(100).order(java.nio.ByteOrder.BIG_ENDIAN)
        payload.put(0) // version
        payload.put(0).put(0).put(0) // flags
        payload.put(0) // reserved
        payload.put(0).put(0).put(2) // count = 2
        payload.putLong(0)
        payload.put(win1251Bytes.size.toByte())
        payload.put(win1251Bytes)
        payload.putLong(60_000L * 10_000L)
        payload.put(win1251Next.size.toByte())
        payload.put(win1251Next)
        val marks = ua.nichnyk.listen.data.Mp4ChapterParser.parseChplPayload(payload.array())
        assertEquals(2, marks.size)
        assertEquals("Розділ 1", marks[0].title)
        assertEquals("Розділ 2", marks[1].title)
    }

    @Test
    fun testWebDavConfigValidation() {
        val server = "cloud.example.com/remote.php/webdav"
        val normalized = if (!server.startsWith("http://") && !server.startsWith("https://")) "https://$server" else server
        assertEquals("https://cloud.example.com/remote.php/webdav", normalized)

        val target = if (normalized.endsWith("/")) "${normalized}bookvoices_sync.json" else "$normalized/bookvoices_sync.json"
        assertEquals("https://cloud.example.com/remote.php/webdav/bookvoices_sync.json", target)
    }

    /**
     * Персонаж не має позначки останньої зміни — лише createdAt. Тому арбітраж
     * між надгробком і записом із бекапу спирається саме на неї.
     */
    @Test
    fun testCharacterTombstoneArbitration() {
        fun character(createdAt: Long) = ua.nichnyk.listen.data.BookCharacterEntity(
            id = "ch1",
            bookId = "b1",
            name = "Степан Радченко",
            createdAt = createdAt,
        )

        // Той самий запис, який ми й видалили: створений до видалення — лишається видаленим.
        assertTrue(BackupCodec.characterTombstoneWins(character(1_000L), deletedAt = 5_000L))
        // Межа: створення рівно в мить видалення теж не воскрешає.
        assertTrue(BackupCodec.characterTombstoneWins(character(5_000L), deletedAt = 5_000L))
        // Додали заново на іншому пристрої вже після видалення — надгробок поступається.
        assertFalse(BackupCodec.characterTombstoneWins(character(9_000L), deletedAt = 5_000L))
    }

    @Test
    fun testWebDavTestResponseClassification() {
        // Успішні відповіді
        assertNull(WebDavClient.describeTestFailure(200))
        assertNull(WebDavClient.describeTestFailure(204))
        assertNull(WebDavClient.describeTestFailure(207))
        // Сервер не приймає HEAD для колекції — це не помилка налаштувань
        assertNull(WebDavClient.describeTestFailure(405))
        assertNull(WebDavClient.describeTestFailure(501))

        // 404 раніше помилково вважався успіхом: користувач бачив «підключено»,
        // а вивантаження потім падало, бо теки не існує.
        assertTrue(WebDavClient.describeTestFailure(404) is WebDavError.FolderNotFound)

        assertTrue(WebDavClient.describeTestFailure(401) is WebDavError.Auth)
        assertTrue(WebDavClient.describeTestFailure(403) is WebDavError.Auth)

        // 429 показувався голим числом у діалозі «Http error», і природна реакція
        // натиснути повтор лише продовжувала блокування на боці сервера.
        val rate = WebDavClient.describeTestFailure(429, "Too Many Requests")
        assertTrue(rate is WebDavError.TooManyRequests)
        // Не permanent: на відміну від Auth, повтор пізніше має сенс, тож
        // автосинхронізація мусить сама повернутися до цього.
        assertFalse(rate!!.isPermanent)

        val http = WebDavClient.describeTestFailure(500, "Server Error")
        assertTrue(http is WebDavError.Http)
        assertEquals(500, (http as WebDavError.Http).code)
    }

    @Test
    fun testAbsentWebDavBackupDetection() {
        // Раніше причину визначали пошуком підрядка в тексті помилки, тож переклад
        // інтерфейсу ламав логіку синхронізації.
        assertTrue(WebDavClient.isAbsentBackup(WebDavError.BackupAbsent))
        assertFalse(WebDavClient.isAbsentBackup(WebDavError.Auth))
        assertFalse(WebDavClient.isAbsentBackup(WebDavError.FolderNotFound))
        assertFalse(WebDavClient.isAbsentBackup(IllegalStateException("Резервної копії на сервері ще немає")))
        assertFalse(WebDavClient.isAbsentBackup(null))
    }

    @Test
    fun testWebDavPermanentErrorsAreNotRetried() {
        // Постійні помилки не мають ретраїтися воркером: раніше будь-яка невдача
        // давала Result.retry(), і застосунок годинами стукав у сервер із хибним паролем.
        assertTrue(WebDavError.Auth.isPermanent)
        assertTrue(WebDavError.InsecureUrl.isPermanent)
        assertTrue(WebDavError.FolderNotFound.isPermanent)
        assertFalse(WebDavError.BackupAbsent.isPermanent)
        assertFalse(WebDavError.Http(503, "").isPermanent)
        assertFalse(WebDavError.Network(java.io.IOException()).isPermanent)
    }

    @Test
    fun testWebDavUrlNormalisation() {
        assertEquals(
            "https://cloud.example.com/remote.php/webdav/bookvoices_sync.json",
            WebDavClient.targetUrl("cloud.example.com/remote.php/webdav"),
        )
        assertEquals(
            "https://cloud.example.com/dav/bookvoices_sync.json",
            WebDavClient.targetUrl("https://cloud.example.com/dav/"),
        )
        assertEquals("https://host/dav", WebDavClient.normalizeUrl("  host/dav  "))
        // http:// відхиляється до звернення до мережі: Basic auth без TLS —
        // це пароль відкритим текстом.
        try {
            WebDavClient.normalizeUrl("http://host/dav")
            throw AssertionError("очікувалася WebDavError.InsecureUrl")
        } catch (e: WebDavError) {
            assertTrue(e is WebDavError.InsecureUrl)
        }
    }

    @Test
    fun testMultiDiscRelativePathSort() {
        val files = listOf(
            "Book/CD1/01.mp3",
            "Book/CD1/02.mp3",
            "Book/CD2/01.mp3",
            "Book/CD2/02.mp3",
        )
        val byFileName = files.sortedWith { a, b ->
            AudioImporter.naturalCompare(a.substringAfterLast('/'), b.substringAfterLast('/'))
        }
        assertEquals(
            listOf("Book/CD1/01.mp3", "Book/CD2/01.mp3", "Book/CD1/02.mp3", "Book/CD2/02.mp3"),
            byFileName,
        )
        val byRelativePath = files.sortedWith { a, b -> AudioImporter.naturalCompare(a, b) }
        assertEquals(
            listOf("Book/CD1/01.mp3", "Book/CD1/02.mp3", "Book/CD2/01.mp3", "Book/CD2/02.mp3"),
            byRelativePath,
        )
    }

    @Test
    fun testShareImportSortsByOriginalNameNotCopiedName() {
        val original = listOf("10.mp3" to "uuid-c.mp3", "2.mp3" to "uuid-a.mp3", "1.mp3" to "uuid-b.mp3")
        val byCopied = original.sortedWith { a, b -> AudioImporter.naturalCompare(a.second, b.second) }.map { it.first }
        val byOriginal = original.sortedWith { a, b -> AudioImporter.naturalCompare(a.first, b.first) }.map { it.first }
        assertEquals(listOf("2.mp3", "1.mp3", "10.mp3"), byCopied)
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), byOriginal)
    }

    @Test
    fun testWavDurationFromHeader() {
        val file = kotlin.io.path.createTempFile("demo", ".wav").toFile()
        try {
            val sampleRate = 22050
            val seconds = 3
            val dataSize = sampleRate * 2 * seconds
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
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
            header.putInt(dataSize)
            file.outputStream().use {
                it.write(header.array())
                it.write(ByteArray(dataSize))
            }
            assertEquals(3_000L, ua.nichnyk.listen.data.DemoFactory.wavDurationMs(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun testAuthorSortSecondaryByTitle() {
        val b1 = BookWithChapters(
            book = sampleBook(0L, 0L).copy(id = "1", title = "Кобзар", author = "Шевченко"),
            chapters = emptyList(),
        )
        val b2 = BookWithChapters(
            book = sampleBook(0L, 0L).copy(id = "2", title = "Гайдамаки", author = "Шевченко"),
            chapters = emptyList(),
        )
        val b3 = BookWithChapters(
            book = sampleBook(0L, 0L).copy(id = "3", title = "Кавказ", author = "Шевченко"),
            chapters = emptyList(),
        )
        val sorted = ShelfQuery.sort(listOf(b1, b2, b3), BookSortOrder.Author)
        assertEquals(listOf("Гайдамаки", "Кавказ", "Кобзар"), sorted.map { it.book.title })
    }

    @Test
    fun testPaletteIndexNonNegative() {
        val hash = Int.MIN_VALUE
        val index = (hash and 0x7FFFFFFF) % 8
        assertTrue(index in 0 until 8)
    }

    @Test
    fun testAppThemeEnumValues() {
        assertEquals(3, ua.nichnyk.listen.data.AppTheme.entries.size)
        assertEquals("NIGHT", ua.nichnyk.listen.data.AppTheme.NIGHT.name)
        assertEquals("PAPER", ua.nichnyk.listen.data.AppTheme.PAPER.name)
        assertEquals("OLED", ua.nichnyk.listen.data.AppTheme.OLED.name)
    }

    @Test
    fun testBookmarkSearchFilter() {
        val bm1 = ua.nichnyk.listen.data.BookmarkWithBook(
            bookmark = BookmarkEntity(
                id = "bm1",
                bookId = "b1",
                chapterId = "ch1",
                chapterTitle = "Вступ",
                positionMs = 10_000L,
                note = "Цікава цитата",
                createdAt = 0L,
            ),
            bookTitle = "Кобзар",
            coverPath = null,
        )
        val bm2 = ua.nichnyk.listen.data.BookmarkWithBook(
            bookmark = BookmarkEntity(
                id = "bm2",
                bookId = "b2",
                chapterId = "ch2",
                chapterTitle = "Частина 2",
                positionMs = 20_000L,
                note = "Важлива думка",
                createdAt = 0L,
            ),
            bookTitle = "Тіні забутих предків",
            coverPath = null,
        )
        val all = listOf(bm1, bm2)

        fun filter(query: String) = all.filter {
            val q = query.lowercase().trim()
            it.bookTitle.lowercase().contains(q) ||
            it.bookmark.note.lowercase().contains(q) ||
            it.bookmark.chapterTitle.lowercase().contains(q)
        }

        assertEquals(listOf(bm1), filter("кобзар"))
        assertEquals(listOf(bm1), filter("цитата"))
        assertEquals(listOf(bm2), filter("тіні"))
        assertEquals(listOf(bm2), filter("думка"))
        assertEquals(listOf(bm2), filter("частина"))
        assertEquals(emptyList<ua.nichnyk.listen.data.BookmarkWithBook>(), filter("невідомо"))
    }

    private val fallbacks = BackupCodec.Fallbacks(
        unknownAuthor = "Невідомий",
        chapterTitle = { n -> "Розділ $n" },
    )

    private fun sampleBook(lastPlayedAt: Long, positionMs: Long) = BookEntity(
        id = "b1",
        title = "Книга",
        author = "Автор",
        coverPath = null,
        addedAt = 0L,
        lastPlayedAt = lastPlayedAt,
        durationMs = 100_000L,
        positionMs = positionMs,
        currentChapterIndex = 0,
        playbackSpeed = 1f,
        completed = false,
    )
}
