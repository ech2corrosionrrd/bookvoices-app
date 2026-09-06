package ua.nichnyk.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.data.AudioImporter
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BackupCodec
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.DailyListeningEntity
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
}
