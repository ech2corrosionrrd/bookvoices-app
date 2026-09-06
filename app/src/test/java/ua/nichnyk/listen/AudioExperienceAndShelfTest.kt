package ua.nichnyk.listen

import ua.nichnyk.listen.data.VoicePreset
import ua.nichnyk.listen.playback.voiceBandGains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.BackupCodec
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookSortOrder
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.LibraryFilter
import ua.nichnyk.listen.data.ShelfQuery
import ua.nichnyk.listen.data.SmartRewindLogic

class AudioExperienceAndShelfTest {

    @Test
    fun smartRewind_calculatesCorrectIntervals() {
        // Less than 10 mins: 0s
        assertEquals(0L, SmartRewindLogic.calculateRewindMs(0L))
        assertEquals(0L, SmartRewindLogic.calculateRewindMs(5 * 60 * 1000L))
        assertEquals(0L, SmartRewindLogic.calculateRewindMs(9 * 60 * 1000L))

        // 10 mins to 1 hour: 5s
        assertEquals(5_000L, SmartRewindLogic.calculateRewindMs(10 * 60 * 1000L))
        assertEquals(5_000L, SmartRewindLogic.calculateRewindMs(30 * 60 * 1000L))
        assertEquals(5_000L, SmartRewindLogic.calculateRewindMs(59 * 60 * 1000L))

        // 1 hour to 24 hours: 15s
        assertEquals(15_000L, SmartRewindLogic.calculateRewindMs(60 * 60 * 1000L))
        assertEquals(15_000L, SmartRewindLogic.calculateRewindMs(5 * 60 * 60 * 1000L))
        assertEquals(15_000L, SmartRewindLogic.calculateRewindMs(23 * 60 * 60 * 1000L))

        // Over 24 hours: 30s
        assertEquals(30_000L, SmartRewindLogic.calculateRewindMs(24 * 60 * 60 * 1000L))
        assertEquals(30_000L, SmartRewindLogic.calculateRewindMs(48 * 60 * 60 * 1000L))
    }

    @Test
    fun smartRewind_applyToPosition_matchesTableAndClamps() {
        val now = 1_000_000_000L
        assertEquals(60_000L, SmartRewindLogic.applyToPosition(null, 60_000L, now))
        assertEquals(0L, SmartRewindLogic.applyToPosition(now - 48 * 60 * 60 * 1000L, 0L, now))
        assertEquals(55_000L, SmartRewindLogic.applyToPosition(now - 10 * 60 * 1000L, 60_000L, now))
    }

    @Test
    fun shelfQuery_pinnedBooksAppearFirst() {
        val book1 = makeBook("1", "Alpha", "Author A", pinned = false, lastPlayedAt = 1000L)
        val book2 = makeBook("2", "Beta", "Author B", pinned = true, lastPlayedAt = 500L)
        val book3 = makeBook("3", "Gamma", "Author C", pinned = false, lastPlayedAt = 2000L)
        val book4 = makeBook("4", "Delta", "Author D", pinned = true, lastPlayedAt = 1500L)

        val list = listOf(book1, book2, book3, book4)
        val sorted = ShelfQuery.apply(list, "", LibraryFilter.All, BookSortOrder.LastPlayed)

        // Pinned books (Delta, Beta) must come first, followed by unpinned (Gamma, Alpha)
        assertEquals(listOf("4", "2", "3", "1"), sorted.map { it.book.id })
    }

    @Test
    fun shelfQuery_filtersBySeriesAndSearch() {
        val b1 = makeBook("1", "Dune", "Frank Herbert", series = "Dune", seriesOrder = 1f)
        val b2 = makeBook("2", "Dune Messiah", "Frank Herbert", series = "Dune", seriesOrder = 2f)
        val b3 = makeBook("3", "Foundation", "Isaac Asimov", series = "Foundation", seriesOrder = 1f)

        val list = listOf(b1, b2, b3)

        // Filter by series
        val duneOnly = ShelfQuery.apply(list, "", LibraryFilter.All, selectedSeries = "Dune")
        assertEquals(listOf("1", "2"), duneOnly.map { it.book.id })

        // Search within series
        val searchSeries = ShelfQuery.apply(list, "Foundation", LibraryFilter.All)
        assertEquals(listOf("3"), searchSeries.map { it.book.id })
    }

    @Test
    fun backupCodec_encodesAndDecodesPinnedAndSeries() {
        val book = makeBook(
            id = "b123",
            title = "The Hobbit",
            author = "J.R.R. Tolkien",
            pinned = true,
            series = "Middle Earth",
            seriesOrder = 1.0f,
        )

        val json = BackupCodec.encode(
            books = listOf(book),
            bookmarks = emptyList(),
            listening = emptyList(),
            tombstones = emptyList(),
            tags = emptyList(),
            sessions = emptyList(),
            exportedAt = 123456789L,
        )

        val decoded = BackupCodec.decode(
            json,
            BackupCodec.Fallbacks("Unknown", { "Chapter $it" }),
        )

        assertEquals(1, decoded.books.size)
        val decodedBook = decoded.books[0]
        assertEquals("b123", decodedBook.id)
        assertEquals("The Hobbit", decodedBook.title)
        assertTrue(decodedBook.pinned)
        assertEquals("Middle Earth", decodedBook.series)
        assertEquals(1.0f, decodedBook.seriesOrder)
    }

    @Test
    fun backupCodec_mergePreservesPinnedAndSeries() {
        val local = makeBookEntity("b1", "Book 1", "Author", pinned = false, series = "Series A", seriesOrder = 1f, lastPlayedAt = 100L)
        val incoming = makeBookEntity("b1", "Book 1", "Author", pinned = true, series = "Series A", seriesOrder = 1f, lastPlayedAt = 200L)

        val merged = BackupCodec.mergeBook(local, incoming)
        assertEquals(true, merged?.pinned)
        assertEquals("Series A", merged?.series)
        assertEquals(1f, merged?.seriesOrder)
    }

    /**
     * Файл, зроблений до появи `pinned`, поля не має — decode дає false. Якби
     * злиття брало це значення як істину, відновлення з такого бекапу мовчки
     * поскидало б усі закріплення на пристрої.
     */
    @Test
    fun backupCodec_mergeDoesNotUnpinFromOlderBackup() {
        val local = makeBookEntity("b1", "Book 1", "Author", pinned = true, lastPlayedAt = 100L)
        val incoming = makeBookEntity("b1", "Book 1", "Author", pinned = false, lastPlayedAt = 200L)

        assertTrue(BackupCodec.mergeBook(local, incoming)?.pinned == true)
    }

    /** Так само для серії: старий файл не має поля, локальне значення має вціліти. */
    @Test
    fun backupCodec_mergeKeepsLocalSeriesWhenBackupHasNone() {
        val local = makeBookEntity("b1", "Book 1", "Author", series = "Дюна", seriesOrder = 2f, lastPlayedAt = 100L)
        val incoming = makeBookEntity("b1", "Book 1", "Author", series = null, seriesOrder = null, lastPlayedAt = 200L)

        val merged = BackupCodec.mergeBook(local, incoming)
        assertEquals("Дюна", merged?.series)
        assertEquals(2f, merged?.seriesOrder)
    }

    @Test
    fun monoAudioProcessor_downmixesStereo() {
        val processor = stereoProcessor()
        processor.isDownmixEnabled = true

        // L = 1000, R = 3000 -> обидва канали 2000
        val output = processor.process(shorts(1000, 3000))
        assertEquals(2000.toShort(), output.short)
        assertEquals(2000.toShort(), output.short)
    }

    /**
     * Перемикач має діяти на вже налаштованому конвеєрі, без переконфігурації:
     * onConfigure викликається лише на початку файла, і якби «увімкнено»
     * читалося там, тумблер не робив би нічого до наступної глави.
     */
    @Test
    fun monoAudioProcessor_togglesWithoutReconfiguring() {
        val processor = stereoProcessor()

        processor.isDownmixEnabled = false
        val asIs = processor.process(shorts(1000, 3000))
        assertEquals(1000.toShort(), asIs.short)
        assertEquals(3000.toShort(), asIs.short)

        // Той самий, уже налаштований процесор — configure більше не викликаємо.
        processor.isDownmixEnabled = true
        val mixed = processor.process(shorts(1000, 3000))
        assertEquals(2000.toShort(), mixed.short)
        assertEquals(2000.toShort(), mixed.short)

        processor.isDownmixEnabled = false
        val backAsIs = processor.process(shorts(1000, 3000))
        assertEquals(1000.toShort(), backAsIs.short)
        assertEquals(3000.toShort(), backAsIs.short)
    }

    /** Не стерео — крізь себе не пускаємо взагалі. */
    @Test
    fun monoAudioProcessor_ignoresNonStereoFormats() {
        val processor = ua.nichnyk.listen.playback.MonoDownmixAudioProcessor()
        processor.isDownmixEnabled = true

        val surround = androidx.media3.common.audio.AudioProcessor.AudioFormat(
            44100,
            6,
            androidx.media3.common.C.ENCODING_PCM_16BIT,
        )
        assertEquals(androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET, processor.configure(surround))

        val float = androidx.media3.common.audio.AudioProcessor.AudioFormat(
            44100,
            2,
            androidx.media3.common.C.ENCODING_PCM_FLOAT,
        )
        assertEquals(androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET, processor.configure(float))
    }

    /**
     * Неповний кадр на вході має бути спожитий: конвеєр media3 крутить
     * queueInput, доки вхід не спорожніє, і хвіст зациклив би його.
     */
    @Test
    fun monoAudioProcessor_drainsPartialFrame() {
        val processor = stereoProcessor()
        processor.isDownmixEnabled = true

        val input = java.nio.ByteBuffer.allocateDirect(6).order(java.nio.ByteOrder.nativeOrder())
        input.putShort(1000.toShort())
        input.putShort(3000.toShort())
        input.putShort(1234.toShort()) // половина другого кадру
        input.flip()

        processor.queueInput(input)
        assertFalse(input.hasRemaining())
    }

    private fun stereoProcessor(): ua.nichnyk.listen.playback.MonoDownmixAudioProcessor {
        val processor = ua.nichnyk.listen.playback.MonoDownmixAudioProcessor()
        val stereo = androidx.media3.common.audio.AudioProcessor.AudioFormat(
            44100,
            2,
            androidx.media3.common.C.ENCODING_PCM_16BIT,
        )
        assertEquals(2, processor.configure(stereo).channelCount)
        processor.flush()
        return processor
    }

    private fun shorts(vararg values: Int): java.nio.ByteBuffer {
        val buffer = java.nio.ByteBuffer.allocateDirect(values.size * 2)
            .order(java.nio.ByteOrder.nativeOrder())
        values.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun ua.nichnyk.listen.playback.MonoDownmixAudioProcessor.process(
        input: java.nio.ByteBuffer,
    ): java.nio.ByteBuffer {
        queueInput(input)
        return output
    }

    private fun makeBook(
        id: String,
        title: String,
        author: String,
        pinned: Boolean = false,
        series: String? = null,
        seriesOrder: Float? = null,
        lastPlayedAt: Long? = null,
    ): BookWithChapters = BookWithChapters(
        book = makeBookEntity(id, title, author, pinned, series, seriesOrder, lastPlayedAt),
        chapters = emptyList(),
    )

    private fun makeBookEntity(
        id: String,
        title: String,
        author: String,
        pinned: Boolean = false,
        series: String? = null,
        seriesOrder: Float? = null,
        lastPlayedAt: Long? = null,
    ): BookEntity = BookEntity(
        id = id,
        title = title,
        author = author,
        coverPath = null,
        addedAt = 1000L,
        lastPlayedAt = lastPlayedAt,
        durationMs = 100_000L,
        positionMs = 0L,
        currentChapterIndex = 0,
        playbackSpeed = 1f,
        completed = false,
        pinned = pinned,
        series = series,
        seriesOrder = seriesOrder,
    )

    @Test
    fun userSettings_fastInnovationsDefaults() {
        val settings = ua.nichnyk.listen.data.UserSettings()
        // Skip silence is false by default
        assertEquals(false, settings.skipSilence)
        // Auto-bookmark on Bluetooth is true by default
        assertEquals(true, settings.autoBookmarkBluetooth)
    }

    @Test
    fun playerUiState_jumpBackTargetHolding() {
        val emptyState = ua.nichnyk.listen.playback.PlayerUiState()
        org.junit.Assert.assertNull(emptyState.jumpBackTarget)

        val target = ua.nichnyk.listen.playback.JumpBackTarget(chapterIndex = 2, positionMs = 125_000L, timeLabel = "02:05")
        val updatedState = emptyState.copy(jumpBackTarget = target)

        org.junit.Assert.assertNotNull(updatedState.jumpBackTarget)
        assertEquals(2, updatedState.jumpBackTarget?.chapterIndex)
        assertEquals(125_000L, updatedState.jumpBackTarget?.positionMs)
        assertEquals("02:05", updatedState.jumpBackTarget?.timeLabel)
    }

    /**
     * Раніше цей тест переписував форматування часу всередину себе й порівнював
     * власну копію з власними ж очікуваннями — про застосунок він не стверджував
     * нічого. Тепер підпис рахує та сама функція, що й на кнопці.
     */
    @Test
    fun jumpBackLabel_padsMinutesAndAddsHoursOnlyWhenNeeded() {
        val locale = java.util.Locale.US
        assertEquals("14:25", ua.nichnyk.listen.playback.jumpBackLabel((14 * 60 + 25) * 1000L, locale))
        assertEquals("00:07", ua.nichnyk.listen.playback.jumpBackLabel(7_000L, locale))
        assertEquals("05:03", ua.nichnyk.listen.playback.jumpBackLabel((5 * 60 + 3) * 1000L, locale))
        assertEquals(
            "02:45:10",
            ua.nichnyk.listen.playback.jumpBackLabel((2 * 3600 + 45 * 60 + 10) * 1000L, locale),
        )
        assertEquals("година рівно", "01:00:00", ua.nichnyk.listen.playback.jumpBackLabel(3_600_000L, locale))
        assertEquals("нуль і від'ємне — той самий початок", "00:00", ua.nichnyk.listen.playback.jumpBackLabel(0L, locale))
        assertEquals("00:00", ua.nichnyk.listen.playback.jumpBackLabel(-5_000L, locale))
    }

    @Test
    fun proBilling_constantsAndEvents() {
        assertEquals("bookvoices_pro_lifetime", ua.nichnyk.listen.billing.ProEntitlementManager.SKU_PRO_LIFETIME)
        val successEvent: ua.nichnyk.listen.billing.BillingEvent = ua.nichnyk.listen.billing.BillingEvent.PurchaseSuccess
        val cancelEvent: ua.nichnyk.listen.billing.BillingEvent = ua.nichnyk.listen.billing.BillingEvent.UserCanceled
        val errorEvent: ua.nichnyk.listen.billing.BillingEvent = ua.nichnyk.listen.billing.BillingEvent.Error("Network error")

        assertTrue(successEvent is ua.nichnyk.listen.billing.BillingEvent.PurchaseSuccess)
        assertTrue(cancelEvent is ua.nichnyk.listen.billing.BillingEvent.UserCanceled)
        assertEquals("Network error", (errorEvent as ua.nichnyk.listen.billing.BillingEvent.Error).message)
    }

    @Test
    fun backupCodec_encodesAndDecodesNotesAndCharacters() {
        val book = makeBook("book-100", "Sherlock Holmes", "Arthur Conan Doyle")
            .copy(book = makeBook("book-100", "Sherlock Holmes", "Arthur Conan Doyle").book.copy(notes = "Classic detective masterpiece"))

        val character = ua.nichnyk.listen.data.BookCharacterEntity(
            id = "char-1",
            bookId = "book-100",
            name = "John Watson",
            role = "Companion",
            description = "Doctor and biographer",
            createdAt = 123456789L,
        )

        val encoded = BackupCodec.encode(
            books = listOf(book),
            bookmarks = emptyList(),
            listening = emptyList(),
            characters = listOf(character),
        )

        val decoded = BackupCodec.decode(
            json = encoded,
            fallbacks = BackupCodec.Fallbacks(unknownAuthor = "Unknown", chapterTitle = { "Chapter $it" }),
        )

        assertEquals(1, decoded.books.size)
        assertEquals("Classic detective masterpiece", decoded.books.first().notes)
        assertEquals(1, decoded.characters.size)
        val decodedChar = decoded.characters.first()
        assertEquals("char-1", decodedChar.id)
        assertEquals("book-100", decodedChar.bookId)
        assertEquals("John Watson", decodedChar.name)
        assertEquals("Companion", decodedChar.role)
        assertEquals("Doctor and biographer", decodedChar.description)
    }

    // --- голосовий еквалайзер --------------------------------------------
    //
    // Перевіряється саме мапінг пресета у смуги. Раніше тут стояв тест, який
    // рахував `VoicePreset.values().size` і `coerceIn` зі стандартної бібліотеки:
    // він проходив, поки пресет узагалі не застосовувався на старті відтворення.

    @Test
    fun voiceBandGains_offIsFlat() {
        assertTrue(voiceBandGains(VoicePreset.OFF, 5).all { it == 0f })
    }

    @Test
    fun voiceBandGains_speechClarityCutsLowAndBoostsMid() {
        val gains = voiceBandGains(VoicePreset.SPEECH_CLARITY, 5)
        assertEquals(5, gains.size)
        assertTrue("найнижча смуга має різатися", gains[0] < 0f)
        assertTrue("середина має підніматися", gains[2] > 0f)
        assertTrue("підйом середини сильніший за нижню середину", gains[2] > gains[1])
    }

    @Test
    fun voiceBandGains_trebleCutOnlyTouchesTop() {
        val gains = voiceBandGains(VoicePreset.TREBLE_CUT, 5)
        assertEquals(0f, gains[0], 0.0001f)
        assertEquals(0f, gains[1], 0.0001f)
        assertTrue("верхня смуга різиться найсильніше", gains[4] < gains[3])
        assertTrue(gains[3] < 0f)
    }

    @Test
    fun voiceBandGains_warmBoostsLowMidAndTamesTop() {
        val gains = voiceBandGains(VoicePreset.WARM, 5)
        assertTrue("нижня середина тепліє", gains[1] > 0f)
        assertTrue("верх приглушується", gains.last() < 0f)
    }

    /**
     * Кількість смуг задає чипсет, не ми. На трисмуговому еквалайзері зашиті
     * індекси `numBands - 2` били в середину, а `if (numBands >= 4)` робив
     * WARM і TREBLE_CUT повним no-op — пресет просто нічого не змінював.
     */
    @Test
    fun voiceBandGains_worksOnNarrowEqualizers() {
        for (bands in 2..10) {
            for (preset in VoicePreset.entries) {
                val gains = voiceBandGains(preset, bands)
                assertEquals(bands, gains.size)
                assertTrue(
                    "пресет $preset на $bands смугах вийшов за діапазон",
                    gains.all { it >= -1f && it <= 1f },
                )
            }
        }
        for (preset in listOf(VoicePreset.WARM, VoicePreset.TREBLE_CUT)) {
            assertTrue(
                "пресет $preset на 3 смугах не робить нічого",
                voiceBandGains(preset, 3).any { it != 0f },
            )
        }
    }

    @Test
    fun voiceBandGains_degenerateBandCountsStayFlat() {
        assertEquals(0, voiceBandGains(VoicePreset.WARM, 0).size)
        assertEquals(1, voiceBandGains(VoicePreset.WARM, 1).size)
        assertTrue(voiceBandGains(VoicePreset.WARM, 1).all { it == 0f })
        // Кількість смуг приходить з `eq.numberOfBands`, тобто ззовні. Від'ємне
        // число там означало б зламаний драйвер, а не привід кинути виняток.
        assertEquals(0, voiceBandGains(VoicePreset.SPEECH_CLARITY, -3).size)
    }

    /**
     * Розширення `voiceBandGains_worksOnNarrowEqualizers`: там «не робить нічого»
     * перевірялося лише на трьох смугах. Пресет, тихий на пʼятисмуговому чипсеті,
     * той тест пропустив би.
     */
    @Test
    fun voiceBandGains_everyPresetActsOnEveryBandCount() {
        for (bands in 2..10) {
            for (preset in VoicePreset.entries - VoicePreset.OFF) {
                assertTrue(
                    "пресет $preset на $bands смугах — тихий no-op",
                    voiceBandGains(preset, bands).any { it != 0f },
                )
            }
        }
    }

    /**
     * Нотатки додали в книгу разом із персонажами, але `mergeBook` перелічує
     * поля вручну — і поле там забули, тож між пристроями воно не переносилося.
     */
    @Test
    fun mergeBook_carriesNotesFromCloud() {
        val local = makeBook("book-200", "Місто", "Підмогильний").book
            .copy(lastPlayedAt = 1_000L, notes = null)
        val incoming = local.copy(lastPlayedAt = 2_000L, notes = "перечитати розділ 4")

        val merged = BackupCodec.mergeBook(local, incoming)
        assertEquals("перечитати розділ 4", merged?.notes)
    }

    /**
     * Пін, серія й нотатки — не прогрес, і не мають залежати від lastPlayedAt.
     *
     * Раніше mergeBook виходив по `if (cloudLast < localLast) return null` і
     * відкидав увесь вхідний запис. Книга, послухана тут пізніше, назавжди
     * лишалася без нотатки, серії й піна, доданих на іншому пристрої: обмін
     * ішов, а полиця розходилася.
     */
    @Test
    fun mergeBook_shelfFieldsArriveEvenWhenLocalProgressIsFresher() {
        val local = makeBookEntity("b1", "Book", "Author", lastPlayedAt = 5_000L)
        val incoming = local.copy(
            lastPlayedAt = 1_000L,
            positionMs = 999L,
            pinned = true,
            series = "Хроніки",
            seriesOrder = 2f,
            notes = "враження",
        )

        val merged = BackupCodec.mergeBook(local, incoming)

        assertEquals("пін мав доїхати", true, merged?.pinned)
        assertEquals("серія мала доїхати", "Хроніки", merged?.series)
        assertEquals(2f, merged?.seriesOrder)
        assertEquals("нотатка мала доїхати", "враження", merged?.notes)
        // А прогрес — ні: локальний свіжіший.
        assertEquals(5_000L, merged?.lastPlayedAt)
        assertEquals(local.positionMs, merged?.positionMs)
    }

    /** Контракт лишається: null означає «нічого не змінилося, рядок не чіпати». */
    @Test
    fun mergeBook_returnsNullWhenNothingChanged() {
        val local = makeBookEntity("b1", "Book", "Author", lastPlayedAt = 5_000L)
        val staleCloud = local.copy(lastPlayedAt = 1_000L, positionMs = 999L)

        assertNull(BackupCodec.mergeBook(local, staleCloud))
    }

    /**
     * Перейменування має власний арбітраж — `renamedAt`, а не `lastPlayedAt`.
     * Інакше книга, яку просто слухали пізніше, перезаписувала б назву, виправлену
     * на іншому пристрої; а без поля взагалі перейменування не доїжджало нікуди.
     */
    @Test
    fun mergeBook_carriesRenameFromTheDeviceThatRenamedLater() {
        val local = makeBookEntity("b1", "Стара назва", "Старий автор", lastPlayedAt = 9_000L)
        val incoming = local.copy(
            title = "Виправлена назва",
            author = "Виправлений автор",
            lastPlayedAt = 1_000L,
            renamedAt = 50_000L,
        )

        val merged = BackupCodec.mergeBook(local, incoming)

        assertEquals("Виправлена назва", merged?.title)
        assertEquals("Виправлений автор", merged?.author)
        assertEquals(50_000L, merged?.renamedAt)
        // Прогрес при цьому лишається локальним — він свіжіший.
        assertEquals(9_000L, merged?.lastPlayedAt)
    }

    @Test
    fun mergeBook_localRenameWinsOverAnOlderOne() {
        val local = makeBookEntity("b1", "Моя назва", "Автор", lastPlayedAt = 1_000L)
            .copy(renamedAt = 90_000L)
        val incoming = local.copy(title = "Чужа назва", lastPlayedAt = 9_000L, renamedAt = 20_000L)

        val merged = BackupCodec.mergeBook(local, incoming)

        assertEquals("Моя назва", merged?.title)
        assertEquals("новіше перейменування має пережити злиття", 90_000L, merged?.renamedAt)
    }

    @Test
    fun mergeBook_keepsLocalNotesWhenCloudHasNone() {
        val local = makeBook("book-201", "Місто", "Підмогильний").book
            .copy(lastPlayedAt = 1_000L, notes = "мій висновок")
        val incoming = local.copy(lastPlayedAt = 2_000L, notes = null)

        val merged = BackupCodec.mergeBook(local, incoming)
        assertEquals("мій висновок", merged?.notes)
    }
}
