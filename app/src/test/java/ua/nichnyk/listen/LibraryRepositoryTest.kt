package ua.nichnyk.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.data.AudioImporter
import ua.nichnyk.listen.data.BookCharacterEntity
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookTagEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.BookmarkEntity
import ua.nichnyk.listen.data.BackupCodec
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.DailyListeningEntity
import ua.nichnyk.listen.data.DeletedBookmarkEntity
import ua.nichnyk.listen.data.DeletedCharacterEntity
import ua.nichnyk.listen.data.DeletedTagEntity
import ua.nichnyk.listen.data.CoverGenerator
import ua.nichnyk.listen.data.DemoFactory
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.ListenDatabase
import ua.nichnyk.listen.data.ListeningSessionEntity
import ua.nichnyk.listen.data.ListeningStats
import ua.nichnyk.listen.data.SESSION_MERGE_GAP_MS

/**
 * Репозиторій проти справжньої бази Room — але без емулятора.
 *
 * Досі все, що торкалося Android, перевірялося лише в androidTest, тобто в
 * найповільнішій задачі CI. Room, Context і файлова система під Robolectric
 * поводяться достатньо реалістично для саме цих сценаріїв.
 */
@RunWith(RobolectricTestRunner::class)
// Звичайний Application, а не ListenApp: справжній піднімає AppContainer і планує
// WebDAV-синхронізацію через WorkManager, якого в JVM-тесті ніхто не ініціалізував.
@Config(sdk = [33], application = android.app.Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: ListenDatabase
    private lateinit var covers: CoverGenerator
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        // Robolectric виконує тест на головному потоці з паузованим looper, а
        // deleteBook/relinkBook перемикаються на Dispatchers.Main і чекають — це
        // гарантований дедлок. Тестовий диспетчер виконує такий блок на місці.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        covers = CoverGenerator(context)
        repo = LibraryRepository(
            context = context,
            db = db,
            covers = covers,
            importer = AudioImporter(context),
            demo = DemoFactory(context),
        )
        File(context.filesDir, "covers").deleteRecursively()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        File(context.filesDir, "covers").deleteRecursively()
    }

    private suspend fun insertBook(
        id: String,
        title: String = "Кобзар",
        author: String = "Шевченко",
        coverPath: String? = null,
    ) {
        db.library().upsertBook(
            BookEntity(
                id = id,
                title = title,
                author = author,
                coverPath = coverPath,
                addedAt = 0L,
                lastPlayedAt = null,
                durationMs = 1000L,
                positionMs = 0L,
                currentChapterIndex = 0,
                playbackSpeed = 1f,
                completed = false,
            ),
        )
        db.library().upsertChapters(
            listOf(
                ChapterEntity(
                    id = "$id-0", bookId = id, index = 0,
                    title = "1", uri = "file:///$id", durationMs = 1000L,
                ),
            ),
        )
    }

    // --- назви глав -----------------------------------------------------------

    @Test
    fun renameChapterUpdatesTitleAndBookmarkCopyAndReturnsOldName() = runTest {
        insertBook("r")
        db.library().upsertBookmark(BookmarkEntity("bm", "r", "r-0", "1", 500L, "", 1L))

        val previous = repo.renameChapters("r", mapOf("r-0" to "  Пролог   перший "))

        assertEquals(mapOf("r-0" to "1"), previous)
        val after = db.library().getBook("r")!!
        assertEquals("Пролог перший", after.chapters.single().title)
        assertTrue("перейменування глави має ставити мітку часу книги", after.book.renamedAt > 0L)
        assertEquals("Пролог перший", db.library().getBookmark("bm")!!.chapterTitle)
    }

    @Test
    fun mergeBackupPropagatesNewerChapterTitlesWithoutTouchingLocalUris() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        db.library().upsertBookmark(BookmarkEntity("bm-local", "local", "local-c0", "Розділ 1", 500L, "нотатка", 1L))

        val remote = book("remote", "Кобзар", "content://other").let {
            it.copy(
                book = it.book.copy(renamedAt = 5_000L),
                chapters = listOf(
                    ChapterEntity("remote-c0", "remote", 0, "Причинна", "content://other/01.mp3", 20_000L),
                    ChapterEntity("remote-c1", "remote", 1, "Перебендя", "content://other/02.mp3", 30_000L),
                ),
            )
        }
        val json = BackupCodec.encode(books = listOf(remote), bookmarks = emptyList(), listening = emptyList())

        assertTrue(repo.mergeBackupJson(json))

        val merged = db.library().getBook("local")!!
        assertEquals(5_000L, merged.book.renamedAt)
        val chapters = merged.chapters.sortedBy { it.index }
        assertEquals(listOf("Причинна", "Перебендя"), chapters.map { it.title })
        assertEquals(listOf("local-c0", "local-c1"), chapters.map { it.id })
        assertEquals(listOf("file:///local/01.mp3", "file:///local/02.mp3"), chapters.map { it.uri })
        assertEquals("Причинна", db.library().getBookmark("bm-local")!!.chapterTitle)
    }

    @Test
    fun mergeDoesNotConsumeChapterStampWhenCountsDiffer() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        val mismatched = book("local", "Кобзар", "content://other").let { source ->
            source.copy(
                book = source.book.copy(renamedAt = 5_000L),
                chapters = source.chapters.map { it.copy(title = "Нова ${it.index}") } +
                    ChapterEntity("local-c2", "local", 2, "Нова 2", "content://other/03.mp3", 10_000L),
            )
        }
        assertTrue(repo.mergeBackupJson(BackupCodec.encode(listOf(mismatched), emptyList(), emptyList())))
        val held = db.library().getBook("local")!!
        assertEquals(0L, held.book.renamedAt)
        assertEquals(listOf("Розділ 1", "Розділ 2"), held.chapters.sortedBy { it.index }.map { it.title })

        val aligned = book("local", "Кобзар", "content://other").let { source ->
            source.copy(
                book = source.book.copy(renamedAt = 5_000L),
                chapters = listOf(
                    source.chapters[0].copy(title = "Причинна"),
                    source.chapters[1].copy(title = "Перебендя"),
                ),
            )
        }
        assertTrue(repo.mergeBackupJson(BackupCodec.encode(listOf(aligned), emptyList(), emptyList())))
        val after = db.library().getBook("local")!!
        assertEquals(5_000L, after.book.renamedAt)
        assertEquals(listOf("Причинна", "Перебендя"), after.chapters.sortedBy { it.index }.map { it.title })
    }

    @Test
    fun mergeRedrawsGeneratedCoverWhenTitleChanges() = runTest {
        val cover = File(context.filesDir, "covers/old.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        insertFull(
            book("local", "Кобзар", "file:///local").let {
                it.copy(book = it.book.copy(coverPath = cover.absolutePath))
            },
        )
        val remote = book("local", "Гайдамаки", "file:///local").let {
            it.copy(book = it.book.copy(renamedAt = 9_000L, author = "Шевченко"))
        }
        assertTrue(repo.mergeBackupJson(BackupCodec.encode(listOf(remote), emptyList(), emptyList())))
        val after = db.library().getBook("local")!!
        assertEquals("Гайдамаки", after.book.title)
        assertEquals("Шевченко", after.book.author)
        assertNotEquals(cover.absolutePath, after.book.coverPath)
        assertTrue(covers.isGenerated(after.book.coverPath))
        assertTrue(File(after.book.coverPath!!).exists())
    }

    @Test
    fun renameChapterIgnoresBlankUnchangedAndForeignChapters() = runTest {
        insertBook("a")
        insertBook("b")
        assertTrue(repo.renameChapters("a", mapOf("a-0" to "   ")).isEmpty())
        assertTrue(repo.renameChapters("a", mapOf("a-0" to "1")).isEmpty())
        // Глава іншої книги через цю книгу не перейменовується.
        assertTrue(repo.renameChapters("a", mapOf("b-0" to "Чуже")).isEmpty())
        assertEquals("1", db.library().getBook("b")!!.chapters.single().title)
    }

    // --- точкові оновлення книги -------------------------------------------

    @Test
    fun saveProgressDoesNotTouchOtherFields() = runTest {
        insertBook("p", title = "Назва")
        repo.setPinned("p", true)
        repo.updateSeries("p", "Цикл", 2f)
        repo.saveProgress("p", chapterIndex = 3, positionMs = 42_000L, durationHint = 9_999L, speed = 1.5f)
        val b = db.library().getBook("p")!!.book
        assertEquals(3, b.currentChapterIndex)
        assertEquals(42_000L, b.positionMs)
        assertEquals(1.5f, b.playbackSpeed, 0.001f)
        assertTrue(b.pinned)
        assertEquals("Цикл", b.series)
        assertEquals("Назва", b.title)
        // Своя тривалість (1000) важить більше за підказку.
        assertEquals(1000L, b.durationMs)
        assertNotNull(b.lastPlayedAt)
    }

    @Test
    fun saveProgressWithoutTouchKeepsLastPlayedAndCompleted() = runTest {
        insertBook("q")
        repo.setCompleted("q", true)
        val before = db.library().getBook("q")!!.book.lastPlayedAt
        repo.saveProgress("q", 0, 5_000L, 1000L, 1f, completed = null, touchLastPlayed = false)
        val b = db.library().getBook("q")!!.book
        assertEquals(before, b.lastPlayedAt)
        assertTrue(b.completed)
        repo.saveProgress("q", 0, 0L, 1000L, 1f, completed = false)
        assertFalse(db.library().getBook("q")!!.book.completed)
    }

    @Test
    fun saveProgressUsesDurationHintOnlyWhenBookHasNone() = runTest {
        insertBook("d")
        db.library().updateBook(db.library().getBook("d")!!.book.copy(durationMs = 0L))
        repo.saveProgress("d", 0, 0L, durationHint = 7_000L, speed = 1f)
        assertEquals(7_000L, db.library().getBook("d")!!.book.durationMs)
    }

    @Test
    fun saveProgressForMissingBookIsNotAnError() = runTest {
        repo.saveProgress("нема", 0, 0L, 0L, 1f)
        assertNull(db.library().getBook("нема"))
    }

    @Test
    fun renameAndProgressWritesDoNotOverwriteEachOther() = runTest {
        insertBook("r", title = "Стара")
        withContext(Dispatchers.IO) {
            val saves = (1..40).map { i ->
                async { repo.saveProgress("r", 0, i * 1_000L, 1000L, 1f) }
            }
            val rename = async { repo.renameBook("r", "Нова", "Автор") }
            (saves + rename).awaitAll()
        }
        val b = db.library().getBook("r")!!.book
        assertEquals("Нова", b.title)
        assertEquals("Автор", b.author)
        assertTrue(b.positionMs in 1_000L..40_000L)
    }

    @Test
    fun renameKeepsProgress() = runTest {
        insertBook("k")
        repo.saveProgress("k", 2, 77_000L, 1000L, 1.25f)
        repo.renameBook("k", "Інша", "Хтось")
        val b = db.library().getBook("k")!!.book
        assertEquals(2, b.currentChapterIndex)
        assertEquals(77_000L, b.positionMs)
        assertEquals(1.25f, b.playbackSpeed, 0.001f)
    }

    @Test
    fun setCompletedKeepsPositionAndStampsLastPlayed() = runTest {
        insertBook("c")
        repo.saveProgress("c", 1, 12_000L, 1000L, 1f, touchLastPlayed = false)
        assertNull(db.library().getBook("c")!!.book.lastPlayedAt)
        repo.setCompleted("c", true)
        val b = db.library().getBook("c")!!.book
        assertTrue(b.completed)
        assertEquals(12_000L, b.positionMs)
        assertNotNull(b.lastPlayedAt)
    }

    @Test
    fun repairChapterDurationFillsOnlyZeroAndRecomputesBook() = runTest {
        insertBook("z")
        db.library().upsertChapters(
            listOf(
                ChapterEntity(id = "z-0", bookId = "z", index = 0, title = "1", uri = "file:///z0", durationMs = 1000L),
                ChapterEntity(id = "z-1", bookId = "z", index = 1, title = "2", uri = "file:///z1", durationMs = 0L),
            ),
        )
        repo.repairChapterDuration("z-0", 99_999L)
        assertEquals(1000L, db.library().getBook("z")!!.chapters.first { it.id == "z-0" }.durationMs)
        repo.repairChapterDuration("z-1", 4_000L)
        val book = db.library().getBook("z")!!
        assertEquals(4_000L, book.chapters.first { it.id == "z-1" }.durationMs)
        assertEquals(5_000L, book.book.durationMs)
    }

    // --- статистика --------------------------------------------------------

    /**
     * Головна причина, чому цей запис зроблено атомарним: flushListening() зривається
     * з тікера, з паузи і з onDisconnected, і паралельні read-modify-write раніше
     * перезаписували одне одного.
     */
    @Test
    fun concurrentListeningWritesDoNotLoseTime() = runTest {
        val writes = 50
        val delta = 1_000L
        withContext(Dispatchers.IO) {
            (1..writes).map { async { repo.recordListening(delta) } }.awaitAll()
        }
        val today = ListeningStats.dateKey(System.currentTimeMillis())
        assertEquals(writes * delta, db.library().getDailyListening(today)?.durationMs)
    }

    @Test
    fun nonPositiveListeningIsIgnored() = runTest {
        repo.recordListening(0L)
        repo.recordListening(-5L)
        val today = ListeningStats.dateKey(System.currentTimeMillis())
        assertEquals(null, db.library().getDailyListening(today))
    }

    @Test
    fun weeklyStatsSeeWhatWasJustRecorded() = runTest {
        insertBook("done")
        repo.recordListening(90_000L)
        val stats = repo.observeWeeklyStats().first()
        assertEquals(90_000L, stats.todayMs)
        assertEquals(90_000L, stats.weekMs)
    }

    // --- перейменування й обкладинка ---------------------------------------

    @Test
    fun renameRedrawsGeneratedCover() = runTest {
        val id = "book-generated"
        val original = covers.generate(id, "Кобзар", "Шевченко")
        insertBook(id, coverPath = original)

        repo.renameBook(id, "Гайдамаки", "Т. Шевченко")

        val after = db.library().getBook(id)!!.book
        assertEquals("Гайдамаки", after.title)
        assertEquals("Т. Шевченко", after.author)
        assertNotEquals("обкладинку не перемальовано", original, after.coverPath)
        assertTrue("нової обкладинки немає на диску", File(after.coverPath!!).exists())
        assertFalse("стара обкладинка лишилася сміттям", File(original).exists())
    }

    @Test
    fun renameKeepsCoverTakenFromFileTags() = runTest {
        val id = "book-embedded"
        val embedded = covers.fromEmbedded(id, ByteArray(8) { 1 })!!
        insertBook(id, coverPath = embedded)

        repo.renameBook(id, "Інша назва", "Інший автор")

        val after = db.library().getBook(id)!!.book
        // Обкладинка від видавця до назви стосунку не має — чіпати її не можна.
        assertEquals(embedded, after.coverPath)
        assertTrue(File(embedded).exists())
    }

    @Test
    fun renameWithoutChangesLeavesCoverAlone() = runTest {
        val id = "book-same"
        val original = covers.generate(id, "Кобзар", "Шевченко")
        insertBook(id, coverPath = original)

        repo.renameBook(id, "  Кобзар  ", " Шевченко ")

        val after = db.library().getBook(id)!!.book
        assertEquals("зайве перемальовування", original, after.coverPath)
    }

    @Test
    fun renameTrimsWhitespace() = runTest {
        insertBook("trim")
        repo.renameBook("trim", "  Місто  ", "  Підмогильний  ")
        val after = db.library().getBook("trim")!!.book
        assertEquals("Місто", after.title)
        assertEquals("Підмогильний", after.author)
    }

    @Test
    fun renamingMissingBookIsNotAnError() = runTest {
        repo.renameBook("нема такої", "Назва", "Автор")
        assertEquals(0, db.library().getAllBooks().size)
    }

    // --- обкладинки --------------------------------------------------------

    @Test
    fun generatedCoversGetDistinctPathsSoTheImageCacheCannotGoStale() {
        val first = covers.generate("same-id", "Один", "Автор")
        val second = covers.generate("same-id", "Два", "Автор")
        assertNotEquals(first, second)
        assertTrue(covers.isGenerated(first))
        assertTrue(covers.isGenerated(second))
    }

    @Test
    fun embeddedCoverIsNotConsideredGenerated() {
        val embedded = covers.fromEmbedded("id", ByteArray(4))!!
        assertFalse(covers.isGenerated(embedded))
        assertFalse(covers.isGenerated(null))
    }

    // --- пакетне видалення ---------------------------------------------------

    /**
     * Пакетне видалення має прибирати за собою рівно те саме, що й одиночне.
     *
     * Раніше в нього був свій код — надгробок і DELETE ... WHERE id IN (...), — і
     * він не чіпав ні обкладинок, ні копій аудіо в filesDir/imports. Тобто
     * «видалити 20 книг» лишало 20 файлів обкладинок і 20 копій аудіо, і
     * побачити це можна було тільки за розміром сховища.
     */
    @Test
    fun batchDeleteRemovesCoversAndImportedCopies() = runTest {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val ids = listOf("batch-1", "batch-2", "batch-3")
        val covers = mutableListOf<File>()
        val audio = mutableListOf<File>()

        ids.forEach { id ->
            val cover = File(context.filesDir, "covers/$id.png").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(8))
            }
            val track = File(imports, "$id.mp3").apply { writeBytes(ByteArray(8)) }
            covers += cover
            audio += track

            db.library().upsertBook(
                BookEntity(
                    id = id, title = id, author = "Автор", coverPath = cover.absolutePath,
                    addedAt = 0L, lastPlayedAt = null, durationMs = 1000L, positionMs = 0L,
                    currentChapterIndex = 0, playbackSpeed = 1f, completed = false,
                ),
            )
            db.library().upsertChapters(
                listOf(
                    ChapterEntity(
                        id = "$id-0", bookId = id, index = 0,
                        title = "1", uri = track.toURI().toString(), durationMs = 1000L,
                    ),
                ),
            )
        }

        repo.batchDeleteBooks(ids)

        ids.forEach { assertNull("книга лишилася в базі: $it", db.library().getBook(it)) }
        covers.forEach { assertFalse("обкладинка-сирота: $it", it.exists()) }
        audio.forEach { assertFalse("копія аудіо лишилася: $it", it.exists()) }
        // Надгробки потрібні, щоб книга не повернулася наступним злиттям із хмари.
        assertEquals(ids.size, db.library().getAllTombstones().count { it.id in ids })
    }

    @Test
    fun tagsCanBeAddedAndRemoved() = runTest {
        insertBook("book-tag", "Кобзар", "Шевченко")
        repo.addTag("book-tag", "поезія")
        repo.addTag("book-tag", "класика")

        val tags = repo.getTagsForBook("book-tag")
        assertEquals(listOf("класика", "поезія"), tags.sorted())

        repo.removeTag("book-tag", "поезія")
        val after = repo.getTagsForBook("book-tag")
        assertEquals(listOf("класика"), after)
    }

    @Test
    fun restoreBackupKeepsTagsAndSessions() = runTest {
        insertBook("book-1", "Кобзар", "Шевченко")
        repo.addTag("book-1", "поезія")
        db.library().insertSession(
            ListeningSessionEntity(
                id = "s1",
                bookId = "book-1",
                bookTitle = "Кобзар",
                author = "Шевченко",
                timestamp = System.currentTimeMillis(),
                durationMs = 60_000L,
            ),
        )

        val json = repo.exportBackupJson()
        repo.deleteBook("book-1")
        db.library().deleteAllSessions()
        assertTrue(db.library().getAllBooks().isEmpty())
        assertTrue(db.library().getAllSessions().isEmpty())

        assertTrue(repo.importBackupJson(json))

        val restored = db.library().getBook("book-1")
        assertEquals("Кобзар", restored?.book?.title)
        assertEquals(listOf("поезія"), repo.getTagsForBook("book-1"))
        val sessions = db.library().getAllSessions()
        assertEquals(1, sessions.size)
        assertEquals("s1", sessions[0].id)
        assertEquals(60_000L, sessions[0].durationMs)
    }

    @Test
    fun batchDeleteContinuesPastABookThatFailsToDelete() = runTest {
        insertBook("a", "Перша", "А")
        insertBook("b", "Друга", "Б")
        insertBook("c", "Третя", "В")
        // Зупинка плеєра на другій книзі падає: решта все одно має бути видалена.
        repo.onBookDeleted = { id -> if (id == "b") error("плеєр не зупинився") }

        val failure = runCatching { repo.batchDeleteBooks(listOf("a", "b", "c")) }.exceptionOrNull()

        assertTrue("про збій мали повідомити", failure != null)
        assertEquals("перша й третя видалені, друга не зачеплена збоєм", listOf("b"), db.library().getAllBooks().map { it.book.id })
    }

    @Test
    fun restoreFromAFileWithOnlyUnusableBookRecordsLeavesTheShelfIntact() = runTest {
        insertBook("book-1", "Кобзар", "Шевченко")

        // Є масив «books», але записи без id чи назви: розбір дає нуль книг. Це не «порожня копія».
        assertFalse(repo.importBackupJson("""{"books":[{"foo":1},{"title":"без id"},{"id":"x"}]}"""))

        assertEquals("Кобзар", db.library().getBook("book-1")?.book?.title)
    }

    @Test
    fun restoreFromAnExplicitlyEmptyBackupStillReplacesTheShelf() = runTest {
        insertBook("book-1", "Кобзар", "Шевченко")

        assertTrue(repo.importBackupJson("""{"books":[]}"""))

        assertTrue(db.library().getAllBooks().isEmpty())
    }

    // --- історія сесій -----------------------------------------------------

    /**
     * Найважливіше в історії — що вона читабельна.
     *
     * recordListening приходить кожні 30 секунд відтворення. Доки це був простий
     * insert, година прослуховування давала сто двадцять однакових карток, а екран
     * історії з лімітом у 200 рядків показував півтори години життя застосунку.
     */
    @Test
    fun consecutiveListeningFlushesBecomeOneSession() = runTest {
        insertBook("b1", "Тіні", "Коцюбинський")
        repeat(6) {
            repo.recordListening(30_000L, bookId = "b1", bookTitle = "Тіні", author = "Коцюбинський")
        }

        val sessions = db.library().getAllSessions()
        assertEquals(1, sessions.size)
        assertEquals(180_000L, sessions[0].durationMs)
        assertEquals("b1", sessions[0].bookId)
    }

    /** Перехід на іншу книгу обриває сесію, навіть якщо пауза нульова. */
    @Test
    fun switchingBookStartsNewSession() = runTest {
        insertBook("b1", "Тіні", "Коцюбинський")
        insertBook("b2", "Земля", "Кобилянська")
        repo.recordListening(30_000L, bookId = "b1", bookTitle = "Тіні", author = "Коцюбинський")
        repo.recordListening(30_000L, bookId = "b2", bookTitle = "Земля", author = "Кобилянська")
        repo.recordListening(30_000L, bookId = "b1", bookTitle = "Тіні", author = "Коцюбинський")

        val sessions = db.library().getAllSessions()
        assertEquals(3, sessions.size)
    }

    /** Пауза коротша за вікно злиття, але вже інша доба — окрема сесія. */
    @Test
    fun pauseAcrossMidnightStartsNewSession() = runTest {
        insertBook("b1", "Тіні", "Коцюбинський")
        val zone = ZoneId.systemDefault()
        val firstEnd = LocalDate.of(2026, 3, 15).atTime(23, 55).atZone(zone).toInstant().toEpochMilli()
        db.library().appendSession(
            newId = "before",
            bookId = "b1",
            bookTitle = "Тіні",
            author = "Коцюбинський",
            now = firstEnd,
            deltaMs = 5 * 60_000L,
        )
        val secondEnd = LocalDate.of(2026, 3, 16).atTime(0, 20).atZone(zone).toInstant().toEpochMilli()
        db.library().appendSession(
            newId = "after",
            bookId = "b1",
            bookTitle = "Тіні",
            author = "Коцюбинський",
            now = secondEnd,
            deltaMs = 15 * 60_000L,
        )

        assertEquals(2, db.library().getAllSessions().size)
    }

    /** Довга перерва — нова сесія, навіть якщо книга та сама. */
    @Test
    fun longPauseStartsNewSession() = runTest {
        insertBook("b1", "Тіні", "Коцюбинський")
        val now = System.currentTimeMillis()
        db.library().appendSession(
            newId = "old",
            bookId = "b1",
            bookTitle = "Тіні",
            author = "Коцюбинський",
            now = now - SESSION_MERGE_GAP_MS - 60_000L,
            deltaMs = 30_000L,
        )
        repo.recordListening(30_000L, bookId = "b1", bookTitle = "Тіні", author = "Коцюбинський")

        assertEquals(2, db.library().getAllSessions().size)
    }

    /** Час початку сесії — коли почали слухати, а не коли спрацював лічильник. */
    @Test
    fun sessionTimestampIsStartOfListening() = runTest {
        insertBook("b1", "Тіні", "Коцюбинський")
        val before = System.currentTimeMillis()
        repo.recordListening(30_000L, bookId = "b1", bookTitle = "Тіні", author = "Коцюбинський")

        val session = db.library().getAllSessions().single()
        assertTrue(session.timestamp <= before)
        assertTrue(session.timestamp >= before - 30_000L - 5_000L)
    }

    // --- черга «далі» ------------------------------------------------------

    /** Черга має пережити перезапуск процесу — тобто лежати в базі, а не в памʼяті. */
    @Test
    fun queueKeepsOrderAndSurvivesReload() = runTest {
        insertBook("b1")
        insertBook("b2")
        insertBook("b3")

        repo.saveQueue(listOf("b3", "b1", "b2"))

        assertEquals(listOf("b3", "b1", "b2"), repo.loadQueue().map { it.book.id })
    }

    /** Видалена книга йде з черги сама — інакше в списку «далі» лишався б привид. */
    @Test
    fun deletedBookLeavesQueue() = runTest {
        insertBook("b1")
        insertBook("b2")
        repo.saveQueue(listOf("b1", "b2"))

        repo.deleteBook("b1")

        assertEquals(listOf("b2"), repo.loadQueue().map { it.book.id })
    }

    /**
     * Черга не їде в бекап — вона про цей телефон. Але й зникати від відновлення
     * вона не має: replaceAll стирає книги, і queue_items порожнів за ним через
     * CASCADE, тож список «далі» після кожного відновлення виявлявся порожнім,
     * навіть коли всі його книги були у файлі.
     */
    @Test
    fun restoreKeepsQueuedBooksThatSurvived() = runTest {
        insertBook("b1", "Тіні", "Коцюбинський")
        insertBook("b2", "Земля", "Кобилянська")
        repo.saveQueue(listOf("b2", "b1"))

        val json = repo.exportBackupJson()
        assertTrue(repo.importBackupJson(json))

        assertEquals(listOf("b2", "b1"), repo.loadQueue().map { it.book.id })
    }

    /** Книги, якої в файлі немає, після відновлення в черзі бути не може. */
    @Test
    fun restoreDropsQueuedBooksMissingFromFile() = runTest {
        insertBook("b1", "Тіні", "Коцюбинський")
        val json = repo.exportBackupJson()

        insertBook("b2", "Земля", "Кобилянська")
        repo.saveQueue(listOf("b1", "b2"))

        assertTrue(repo.importBackupJson(json))

        assertEquals(listOf("b1"), repo.loadQueue().map { it.book.id })
    }

    /**
     * Денна статистика при злитті: за дату лишається більше з двох чисел.
     *
     * Раніше злиття її не чіпало взагалі, і «за тиждень» на полиці розходився
     * з іншим пристроєм після кожного завантаження з хмари. Сума не годиться:
     * повторне злиття того самого файла подвоювало б тиждень.
     */
    @Test
    fun mergeTakesLargerDailyListening() = runTest {
        val today = ListeningStats.dateKey(System.currentTimeMillis())
        db.library().upsertDailyListening(DailyListeningEntity(today, 60_000L))
        val remote = BackupCodec.encode(
            books = emptyList(),
            bookmarks = emptyList(),
            listening = listOf(DailyListeningEntity(today, 100_000L)),
            tombstones = emptyList(),
            tags = emptyList(),
            sessions = emptyList(),
        )

        assertTrue(repo.mergeBackupJson(remote))
        assertEquals(100_000L, db.library().getDailyListening(today)?.durationMs)

        // Ідемпотентність: те саме злиття вдруге нічого не додає.
        assertTrue(repo.mergeBackupJson(remote))
        assertEquals(100_000L, db.library().getDailyListening(today)?.durationMs)
    }

    /** Менше число з хмари локальний день не зменшує. */
    @Test
    fun mergeDoesNotShrinkLocalDailyListening() = runTest {
        val today = ListeningStats.dateKey(System.currentTimeMillis())
        db.library().upsertDailyListening(DailyListeningEntity(today, 120_000L))
        val remote = BackupCodec.encode(
            books = emptyList(),
            bookmarks = emptyList(),
            listening = listOf(DailyListeningEntity(today, 30_000L)),
            tombstones = emptyList(),
            tags = emptyList(),
            sessions = emptyList(),
        )

        assertTrue(repo.mergeBackupJson(remote))
        assertEquals(120_000L, db.library().getDailyListening(today)?.durationMs)
    }

    @Test
    fun observeBooksByAuthorReturnsMatchingBooks() = runTest {
        insertBook("b1", "Кобзар", "Шевченко")
        insertBook("b2", "Гайдамаки", "Шевченко")
        insertBook("b3", "Місто", "Підмогильний")

        val shevchenkoBooks = repo.observeBooksByAuthor("Шевченко").first()
        assertEquals(2, shevchenkoBooks.size)
        assertEquals(setOf("b1", "b2"), shevchenkoBooks.map { it.book.id }.toSet())
    }
    /**
     * Гуртова перевірка доступності має відповідати поштучній.
     *
     * Її переписали на паралельний обхід із дедуплікацією, щоб полиця зі 100 книг
     * не стояла секунду без позначок; така правка легко з'їдає окремі результати —
     * тому звіряємо з `isAudioAccessible`, який лишився поштучним.
     */
    @Test
    fun bulkAccessibilityMatchesOneByOne() = runTest {
        val present = File(context.filesDir, "present.mp3").apply { writeBytes(ByteArray(8)) }
        val alsoPresent = File(context.filesDir, "also.mp3").apply { writeBytes(ByteArray(8)) }
        val gone = File(context.filesDir, "gone.mp3")

        val uris = listOf(present, alsoPresent, gone).map { "file://${it.absolutePath}" }
        val bulk = repo.inaccessibleUris(uris)
        val oneByOne = uris.filterNot { repo.isAudioAccessible(it) }.toSet()

        assertEquals(oneByOne, bulk)
        assertEquals(setOf("file://${gone.absolutePath}"), bulk)
    }

    /** Той самий файл у кількох книгах перевіряється один раз і не двоїться у відповіді. */
    @Test
    fun bulkAccessibilityDeduplicatesRepeatedUris() = runTest {
        val gone = "file://${File(context.filesDir, "missing.mp3").absolutePath}"
        assertEquals(setOf(gone), repo.inaccessibleUris(listOf(gone, gone, gone)))
    }

    /** Порожній список не має ходити нікуди й повертає порожній набір. */
    @Test
    fun bulkAccessibilityOnEmptyInputReturnsEmpty() = runTest {
        assertEquals(emptySet<String>(), repo.inaccessibleUris(emptyList()))
    }

    /**
     * Відновлення з резервної копії замінює полицю, тож обкладинки й копії аудіо від
     * книг, яких у файлі немає, мають прибиратися з диска, а ті, що лишилися, — ні.
     */
    @Test
    fun restoreBackupCleansUpOrphanedCoversAndAudioCopiesWhileKeepingSurvivedOnes() = runTest {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val keptCover = File(context.filesDir, "covers/kept.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        val keptAudio = File(imports, "kept.mp3").apply { writeBytes(ByteArray(8)) }
        db.library().upsertBook(
            BookEntity(
                id = "kept", title = "Залишена", author = "А", coverPath = keptCover.absolutePath,
                addedAt = 0L, lastPlayedAt = null, durationMs = 1000L, positionMs = 0L,
                currentChapterIndex = 0, playbackSpeed = 1f, completed = false,
            ),
        )
        db.library().upsertChapters(
            listOf(
                ChapterEntity(
                    id = "kept-0", bookId = "kept", index = 0,
                    title = "1", uri = keptAudio.toURI().toString(), durationMs = 1000L,
                ),
            ),
        )
        val backupJson = repo.exportBackupJson()

        val goneCover = File(context.filesDir, "covers/gone.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        val goneAudio = File(imports, "gone.mp3").apply { writeBytes(ByteArray(8)) }
        db.library().upsertBook(
            BookEntity(
                id = "gone", title = "Зникла", author = "Б", coverPath = goneCover.absolutePath,
                addedAt = 0L, lastPlayedAt = null, durationMs = 1000L, positionMs = 0L,
                currentChapterIndex = 0, playbackSpeed = 1f, completed = false,
            ),
        )
        db.library().upsertChapters(
            listOf(
                ChapterEntity(
                    id = "gone-0", bookId = "gone", index = 0,
                    title = "1", uri = goneAudio.toURI().toString(), durationMs = 1000L,
                ),
            ),
        )

        assertTrue(repo.importBackupJson(backupJson))

        assertTrue("обкладинку вцілілої книги стерто", keptCover.exists())
        assertTrue("аудіо вцілілої книги стерто", keptAudio.exists())
        assertFalse("обкладинка заміненої книги лишилася сиротою", goneCover.exists())
        assertFalse("копія аудіо заміненої книги лишилася сиротою", goneAudio.exists())
    }

    /** Якщо дві книги ділять один файл або обкладинку, видалення першої не ламає другу. */
    @Test
    fun deleteBookPreservesSharedCoverAndAudioCopyStillReferencedByAnotherBook() = runTest {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val sharedCover = File(context.filesDir, "covers/shared.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        val sharedAudio = File(imports, "shared.mp3").apply { writeBytes(ByteArray(8)) }

        for (id in listOf("b1", "b2")) {
            db.library().upsertBook(
                BookEntity(
                    id = id, title = id, author = "А", coverPath = sharedCover.absolutePath,
                    addedAt = 0L, lastPlayedAt = null, durationMs = 1000L, positionMs = 0L,
                    currentChapterIndex = 0, playbackSpeed = 1f, completed = false,
                ),
            )
            db.library().upsertChapters(
                listOf(
                    ChapterEntity(
                        id = "$id-0", bookId = id, index = 0,
                        title = "1", uri = sharedAudio.toURI().toString(), durationMs = 1000L,
                    ),
                ),
            )
        }

        repo.deleteBook("b1")
        assertTrue("обкладинка ще потрібна другій книзі", sharedCover.exists())
        assertTrue("файл ще потрібен другій книзі", sharedAudio.exists())

        repo.deleteBook("b2")
        assertFalse("після останньої книги обкладинка має зникнути", sharedCover.exists())
        assertFalse("після останньої книги копія має зникнути", sharedAudio.exists())
    }

    /**
     * Та сама книга, імпортована на двох пристроях окремо, має різні UUID і різні
     * локальні URI. Злиття з хмари переносить прогрес, закладки, мітки й персонажів
     * у локальну книгу, не плодячи дублікат із чужими недоступними URI.
     */
    @Test
    fun mergeBackupMatchesIndependentlyImportedBookByFingerprintInsteadOfDuplicating() = runTest {
        db.library().upsertBook(
            BookEntity(
                id = "local-id",
                title = "Тіні забутих предків",
                author = "Коцюбинський",
                coverPath = "/local/cover.png",
                addedAt = 1_000L,
                lastPlayedAt = 2_000L,
                durationMs = 50_000L,
                positionMs = 1_000L,
                currentChapterIndex = 0,
                playbackSpeed = 1f,
                completed = false,
            ),
        )
        db.library().upsertChapters(
            listOf(
                ChapterEntity("local-c0", "local-id", 0, "Розділ 1", "file:///local/01.mp3", 20_000L),
                ChapterEntity("local-c1", "local-id", 1, "Розділ 2", "file:///local/02.mp3", 30_000L),
            ),
        )

        val remoteBook = BookWithChapters(
            book = BookEntity(
                id = "remote-id",
                title = "Тіні забутих предків",
                author = "М. Коцюбинський",
                coverPath = "/remote/cover.png",
                addedAt = 1_500L,
                lastPlayedAt = 50_000L,
                durationMs = 50_000L,
                positionMs = 14_000L,
                currentChapterIndex = 1,
                playbackSpeed = 1.25f,
                completed = false,
                notes = "Нотатка з планшета",
            ),
            chapters = listOf(
                ChapterEntity("remote-c0", "remote-id", 0, "Розділ 1", "content://other/01.mp3", 20_000L),
                ChapterEntity("remote-c1", "remote-id", 1, "Розділ 2", "content://other/02.mp3", 30_000L),
            ),
        )
        val remoteJson = BackupCodec.encode(
            books = listOf(remoteBook),
            bookmarks = listOf(
                BookmarkEntity(
                    id = "bm-1",
                    bookId = "remote-id",
                    chapterId = "remote-c1",
                    chapterTitle = "Розділ 2",
                    positionMs = 12_000L,
                    note = "Важливе місце",
                    createdAt = 40_000L,
                ),
            ),
            listening = emptyList(),
            tombstones = emptyList(),
            tags = listOf(BookTagEntity("remote-id", "класика")),
            sessions = emptyList(),
            characters = listOf(
                BookCharacterEntity(
                    id = "char-1",
                    bookId = "remote-id",
                    name = "Іван Палійчук",
                    role = "головний герой",
                    description = null,
                    createdAt = 30_000L,
                ),
            ),
        )

        assertTrue(repo.mergeBackupJson(remoteJson))

        val allBooks = db.library().getAllBooks()
        assertEquals("дублікат із чужими URI не мав зʼявитися", 1, allBooks.size)
        val merged = allBooks.single()
        assertEquals("local-id", merged.book.id)
        assertEquals(1, merged.book.currentChapterIndex)
        assertEquals(14_000L, merged.book.positionMs)
        assertEquals(1.25f, merged.book.playbackSpeed, 0.001f)
        assertEquals("Нотатка з планшета", merged.book.notes)
        assertEquals(
            listOf("file:///local/01.mp3", "file:///local/02.mp3"),
            merged.chapters.sortedBy { it.index }.map { it.uri },
        )

        val bookmark = db.library().getAllBookmarks().single()
        assertEquals("local-id", bookmark.bookId)
        assertEquals("local-c1", bookmark.chapterId)
        assertEquals("Важливе місце", bookmark.note)

        assertEquals(listOf("класика"), repo.getTagsForBook("local-id"))
        val character = db.library().getAllCharacters().single()
        assertEquals("local-id", character.bookId)
        assertEquals("Іван Палійчук", character.name)
    }

    @Test
    fun updateBookCoverBytesReplacesCoverAndDeletesOldFile() = runTest {
        val oldCover = File(context.filesDir, "covers/old.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        db.library().upsertBook(
            BookEntity(
                id = "b-cover",
                title = "Місто",
                author = "Підмогильний",
                coverPath = oldCover.absolutePath,
                addedAt = 1_000L,
                lastPlayedAt = null,
                durationMs = 10_000L,
                positionMs = 0L,
                currentChapterIndex = 0,
                playbackSpeed = 1f,
                completed = false,
            ),
        )

        val customBytes = byteArrayOf(9, 8, 7, 6)
        assertTrue(repo.updateBookCoverBytes("b-cover", customBytes))

        val updated = repo.getBook("b-cover")!!.book
        assertFalse("старий файл обкладинки має бути прибраний", oldCover.exists())
        val newCoverFile = File(updated.coverPath!!)
        assertTrue("новий файл обкладинки має існувати", newCoverFile.exists())
        assertTrue("новий файл обкладинки не має бути порожнім", newCoverFile.length() > 0L)
        assertTrue(newCoverFile.name.endsWith(".jpg"))
    }

    @Test
    fun exportBookSummaryMarkdownIncludesNotesCharactersAndBookmarksInChapterOrder() = runTest {
        db.library().upsertBook(
            BookEntity(
                id = "b-md",
                title = "Тигролови",
                author = "Іван Багряний",
                coverPath = null,
                addedAt = 1_000L,
                lastPlayedAt = 2_000L,
                durationMs = 100_000L,
                positionMs = 20_000L,
                currentChapterIndex = 0,
                playbackSpeed = 1f,
                completed = false,
                series = "Пригоди",
                seriesOrder = 1f,
                notes = "Улюблений роман",
            ),
        )
        val ch1 = ChapterEntity("ch-1", "b-md", 0, "Частина перша", "file:///1.mp3", 50_000L)
        val ch2 = ChapterEntity("ch-2", "b-md", 1, "Частина друга", "file:///2.mp3", 50_000L)
        db.library().upsertChapters(listOf(ch1, ch2))
        repo.addTag("b-md", "класика")
        repo.addCharacter("b-md", "Григорій Многогрішний", "інженер", "Головний герой")
        repo.addBookmark("b-md", ch2, 15_000L, "Друга закладка")
        repo.addBookmark("b-md", ch1, 5_000L, "Перша закладка")

        val md = repo.exportBookSummaryMarkdown("b-md")!!
        assertTrue(md.contains("# Тигролови"))
        assertTrue(md.contains("*Іван Багряний*"))
        assertTrue(md.contains("Пригоди #1"))
        assertTrue(md.contains("#класика"))
        assertTrue(md.contains("Улюблений роман"))
        assertTrue(md.contains("**Григорій Многогрішний** (інженер) — Головний герой"))
        val idx1 = md.indexOf("Частина перша")
        val idx2 = md.indexOf("Частина друга")
        assertTrue("закладки у конспекті книги мають іти в порядку розділів", idx1 in 0 until idx2)
    }

    // --- злиття копії: крайні випадки --------------------------------------

    private fun book(id: String, title: String, uriPrefix: String, lastPlayedAt: Long? = null) =
        BookWithChapters(
            book = BookEntity(
                id = id, title = title, author = "Автор", coverPath = null,
                addedAt = 1L, lastPlayedAt = lastPlayedAt, durationMs = 50_000L,
                positionMs = 0L, currentChapterIndex = 0, playbackSpeed = 1f, completed = false,
            ),
            chapters = listOf(
                ChapterEntity("$id-c0", id, 0, "Розділ 1", "$uriPrefix/01.mp3", 20_000L),
                ChapterEntity("$id-c1", id, 1, "Розділ 2", "$uriPrefix/02.mp3", 30_000L),
            ),
        )

    private suspend fun insertFull(b: BookWithChapters) {
        db.library().upsertBook(b.book)
        db.library().upsertChapters(b.chapters)
    }

    @Test
    fun mergeBackupDoesNotMergeBooksWithSameTitleButDifferentChapters() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        val other = book("remote", "Кобзар", "content://other").let {
            it.copy(chapters = it.chapters.map { c -> c.copy(title = "Інша глава ${c.index}", durationMs = c.durationMs + 5_000L) })
        }

        assertTrue(repo.mergeBackupJson(BackupCodec.encode(listOf(other), emptyList(), emptyList())))

        assertEquals(
            "різні книги з однаковою назвою не можна зливати",
            setOf("local", "remote"),
            db.library().getAllBooks().map { it.book.id }.toSet(),
        )
    }

    @Test
    fun mergeBackupMatchesEachLocalBookOnlyOnce() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        val a = book("remote-a", "Кобзар", "content://a", lastPlayedAt = 10L)
        val b = book("remote-b", "Кобзар", "content://b", lastPlayedAt = 20L)

        assertTrue(repo.mergeBackupJson(BackupCodec.encode(listOf(a, b), emptyList(), emptyList())))

        val ids = db.library().getAllBooks().map { it.book.id }.toSet()
        assertTrue("локальна книга лишилась", "local" in ids)
        assertEquals("одна локальна книга поглинає лише одну віддалену", 2, ids.size)
    }

    @Test
    fun mergeBackupAppliesRemoteTagTombstoneToFingerprintMatchedBook() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        repo.addTag("local", "класика")
        val remote = book("remote", "Кобзар", "content://other")
        val json = BackupCodec.encode(
            books = listOf(remote),
            bookmarks = emptyList(),
            listening = emptyList(),
            tagTombstones = listOf(DeletedTagEntity("remote", "класика", System.currentTimeMillis())),
        )

        assertTrue(repo.mergeBackupJson(json))

        assertEquals(1, db.library().getAllBooks().size)
        assertEquals(
            "чужий надгробок мітки має діяти на локальну книгу через перенесений id",
            emptyList<String>(),
            repo.getTagsForBook("local"),
        )
    }

    @Test
    fun mergeBackupRemapsListeningSessionsToLocalBook() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        val remote = book("remote", "Кобзар", "content://other")
        val session = ListeningSessionEntity("s-1", "remote", "Кобзар", "Автор", System.currentTimeMillis(), 60_000L)
        val json = BackupCodec.encode(
            books = listOf(remote),
            bookmarks = emptyList(),
            listening = emptyList(),
            sessions = listOf(session),
        )

        assertTrue(repo.mergeBackupJson(json))

        assertEquals("local", db.library().getAllSessions().single().bookId)
    }

    private fun mark(id: String, bookId: String, createdAt: Long) =
        BookmarkEntity(id, bookId, "$bookId-c0", "Розділ 1", 1_000L, "нотатка", createdAt)

    @Test
    fun mergeBackupAppliesRemoteBookmarkTombstoneToFingerprintMatchedBook() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        db.library().upsertBookmark(mark("bm-1", "local", createdAt = 1_000L))
        val json = BackupCodec.encode(
            books = listOf(book("remote", "Кобзар", "content://other")),
            bookmarks = emptyList(),
            listening = emptyList(),
            bookmarkTombstones = listOf(DeletedBookmarkEntity("bm-1", "remote", deletedAt = 2_000L)),
        )

        assertTrue(repo.mergeBackupJson(json))

        assertEquals(1, db.library().getAllBooks().size)
        assertTrue("закладку, стерту на іншому пристрої, прибрано", db.library().getAllBookmarks().isEmpty())
    }

    @Test
    fun mergeBackupKeepsBookmarkCreatedAfterRemoteTombstone() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        db.library().upsertBookmark(mark("bm-1", "local", createdAt = 3_000L))
        val json = BackupCodec.encode(
            books = listOf(book("remote", "Кобзар", "content://other")),
            bookmarks = emptyList(),
            listening = emptyList(),
            bookmarkTombstones = listOf(DeletedBookmarkEntity("bm-1", "remote", deletedAt = 2_000L)),
        )

        assertTrue(repo.mergeBackupJson(json))

        assertEquals(
            "створена після видалення — це нове додавання, воно виживає",
            listOf("bm-1"),
            db.library().getAllBookmarks().map { it.id },
        )
    }

    @Test
    fun mergeBackupAppliesRemoteCharacterTombstoneToFingerprintMatchedBook() = runTest {
        insertFull(book("local", "Кобзар", "file:///local"))
        db.library().upsertCharacter(BookCharacterEntity("ch-1", "local", "Тарас", null, null, createdAt = 1_000L))
        val json = BackupCodec.encode(
            books = listOf(book("remote", "Кобзар", "content://other")),
            bookmarks = emptyList(),
            listening = emptyList(),
            characterTombstones = listOf(DeletedCharacterEntity("ch-1", "remote", deletedAt = 2_000L)),
        )

        assertTrue(repo.mergeBackupJson(json))

        assertTrue("персонажа, прибраного на іншому пристрої, прибрано й тут", db.library().getAllCharacters().isEmpty())
    }
}
