package ua.nichnyk.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.data.AudioImporter
import ua.nichnyk.listen.data.BackupCodec
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.CoverGenerator
import ua.nichnyk.listen.data.BookTagEntity
import ua.nichnyk.listen.data.BookmarkEntity
import ua.nichnyk.listen.data.DeletedBookEntity
import ua.nichnyk.listen.data.DeletedBookmarkEntity
import ua.nichnyk.listen.data.DeletedTagEntity
import ua.nichnyk.listen.data.DemoFactory
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.ListenDatabase
import ua.nichnyk.listen.data.TOMBSTONE_TTL_DAYS

/**
 * Видалення, що переживає синхронізацію.
 *
 * До надгробків злиття вміло лише додавати: книга, видалена на телефоні, поверталася
 * з хмари наступним же злиттям, бо для іншого пристрою вона просто «є в бекапі,
 * а локально немає». Тут перевіряється обидва напрямки й випадок, коли видалення
 * не має вигравати.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SyncDeletionTest {

    private lateinit var context: Context
    private lateinit var db: ListenDatabase
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
        repo = LibraryRepository(
            context = context,
            db = db,
            covers = CoverGenerator(context),
            importer = AudioImporter(context),
            demo = DemoFactory(context),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        File(context.filesDir, "covers").deleteRecursively()
    }

    /**
     * Мітки часу беремо від «зараз»: надгробки старші за TOMBSTONE_TTL_DAYS
     * прибираються при злитті, тож 1970 рік у тесті означав би зовсім інший сценарій.
     */
    private val now = System.currentTimeMillis()

    private fun bookEntity(id: String, lastPlayedAt: Long?) = BookEntity(
        id = id,
        title = "Книга $id",
        author = "Автор",
        coverPath = null,
        addedAt = 0L,
        lastPlayedAt = lastPlayedAt,
        durationMs = 1000L,
        positionMs = 0L,
        currentChapterIndex = 0,
        playbackSpeed = 1f,
        completed = false,
    )

    private fun withChapters(book: BookEntity) = BookWithChapters(
        book = book,
        chapters = listOf(
            ChapterEntity(
                id = "${book.id}-0", bookId = book.id, index = 0,
                title = "1", uri = "file:///${book.id}", durationMs = 1000L,
            ),
        ),
    )

    private suspend fun insert(book: BookEntity) {
        db.library().upsertBook(book)
        db.library().upsertChapters(withChapters(book).chapters)
    }

    /** Бекап «з іншого пристрою». */
    private fun cloudJson(
        books: List<BookEntity> = emptyList(),
        tombstones: List<DeletedBookEntity> = emptyList(),
        bookmarks: List<BookmarkEntity> = emptyList(),
        tags: List<BookTagEntity> = emptyList(),
        bookmarkTombstones: List<DeletedBookmarkEntity> = emptyList(),
        tagTombstones: List<DeletedTagEntity> = emptyList(),
    ) = BackupCodec.encode(
        books = books.map { withChapters(it) },
        bookmarks = bookmarks,
        listening = emptyList(),
        tombstones = tombstones,
        tags = tags,
        bookmarkTombstones = bookmarkTombstones,
        tagTombstones = tagTombstones,
    )

    private fun bookmark(id: String, bookId: String, createdAt: Long, note: String = "цитата") =
        BookmarkEntity(
            id = id,
            bookId = bookId,
            chapterId = "$bookId-0",
            chapterTitle = "1",
            positionMs = 100L,
            note = note,
            createdAt = createdAt,
        )

    // --- закладки: те саме, що з книгами --------------------------------------

    @Test
    fun bookmarkDeletedHereDoesNotComeBackFromCloud() = runTest {
        insert(bookEntity("b1", lastPlayedAt = now - 10_000L))
        db.library().upsertBookmark(bookmark("bm1", "b1", createdAt = now - 20_000L))
        repo.deleteBookmark("bm1")

        // У хмарі закладка ще є — інший пристрій про видалення не знає.
        assertTrue(
            repo.mergeBackupJson(
                cloudJson(
                    books = listOf(bookEntity("b1", now - 10_000L)),
                    bookmarks = listOf(bookmark("bm1", "b1", createdAt = now - 20_000L)),
                ),
            ),
        )

        assertNull("закладка воскресла", db.library().getBookmark("bm1"))
    }

    @Test
    fun deletingABookmarkLeavesATombstoneThatTravelsInTheBackup() = runTest {
        insert(bookEntity("b1", lastPlayedAt = null))
        db.library().upsertBookmark(bookmark("bm1", "b1", createdAt = now - 20_000L))
        repo.deleteBookmark("bm1")

        val json = repo.exportBackupJson()
        assertTrue("надгробок закладки не поїхав у бекап", json.contains("deletedBookmarks"))
        assertTrue(json.contains("bm1"))
    }

    @Test
    fun bookmarkTombstoneFromCloudRemovesItHere() = runTest {
        insert(bookEntity("b1", lastPlayedAt = now - 10_000L))
        db.library().upsertBookmark(bookmark("bm1", "b1", createdAt = now - 20_000L))

        assertTrue(
            repo.mergeBackupJson(
                cloudJson(
                    books = listOf(bookEntity("b1", now - 10_000L)),
                    bookmarkTombstones = listOf(DeletedBookmarkEntity("bm1", "b1", now - 5_000L)),
                ),
            ),
        )

        assertNull("чуже видалення не доїхало", db.library().getBookmark("bm1"))
    }

    /**
     * Закладка з тим самим id, створена вже ПІСЛЯ видалення, — це нове додавання
     * на іншому пристрої. Надгробок на неї не діє.
     */
    @Test
    fun bookmarkCreatedAfterOurDeletionIsRestored() = runTest {
        insert(bookEntity("b1", lastPlayedAt = now - 10_000L))
        db.library().upsertBookmark(bookmark("bm1", "b1", createdAt = now - 20_000L))
        repo.deleteBookmark("bm1")

        assertTrue(
            repo.mergeBackupJson(
                cloudJson(
                    books = listOf(bookEntity("b1", now - 10_000L)),
                    bookmarks = listOf(bookmark("bm1", "b1", createdAt = now + 60_000L, note = "нова")),
                ),
            ),
        )

        assertEquals("нова", db.library().getBookmark("bm1")?.note)
    }

    // --- мітки ---------------------------------------------------------------

    @Test
    fun tagRemovedHereDoesNotComeBackFromCloud() = runTest {
        insert(bookEntity("b1", lastPlayedAt = now - 10_000L))
        repo.addTag("b1", "класика")
        repo.removeTag("b1", "класика")

        assertTrue(
            repo.mergeBackupJson(
                cloudJson(
                    books = listOf(bookEntity("b1", now - 10_000L)),
                    tags = listOf(BookTagEntity("b1", "класика")),
                ),
            ),
        )

        assertTrue("мітка воскресла", db.library().getAllBookTags().isEmpty())
    }

    @Test
    fun tagTombstoneFromCloudRemovesItHere() = runTest {
        insert(bookEntity("b1", lastPlayedAt = now - 10_000L))
        repo.addTag("b1", "класика")

        assertTrue(
            repo.mergeBackupJson(
                cloudJson(
                    books = listOf(bookEntity("b1", now - 10_000L)),
                    tagTombstones = listOf(DeletedTagEntity("b1", "класика", now - 5_000L)),
                ),
            ),
        )

        assertTrue("чуже видалення мітки не доїхало", db.library().getAllBookTags().isEmpty())
    }

    /**
     * У мітки немає позначки створення, тож «додали заново» видно лише з дії
     * користувача. Повторне ручне додавання знімає надгробок — інакше мітка
     * лишалася б глухою назавжди.
     */
    @Test
    fun addingATagAgainLiftsItsTombstone() = runTest {
        insert(bookEntity("b1", lastPlayedAt = now - 10_000L))
        repo.addTag("b1", "класика")
        repo.removeTag("b1", "класика")
        repo.addTag("b1", "класика")

        assertTrue(db.library().getAllTagTombstones().isEmpty())

        assertTrue(
            repo.mergeBackupJson(
                cloudJson(
                    books = listOf(bookEntity("b1", now - 10_000L)),
                    tags = listOf(BookTagEntity("b1", "класика")),
                ),
            ),
        )

        assertEquals(listOf("класика"), db.library().getAllBookTags().map { it.tag })
    }

    @Test
    fun expiredBookmarkAndTagTombstonesArePrunedToo() = runTest {
        insert(bookEntity("b1", lastPlayedAt = null))
        val ancient = now - (TOMBSTONE_TTL_DAYS + 1) * 24 * 3600 * 1000L
        db.library().upsertBookmarkTombstone(DeletedBookmarkEntity("bm-old", "b1", ancient))
        db.library().upsertTagTombstone(DeletedTagEntity("b1", "старе", ancient))

        repo.pruneHistory()

        assertTrue(db.library().getAllBookmarkTombstones().isEmpty())
        assertTrue(db.library().getAllTagTombstones().isEmpty())
    }

    // --- видалення тут не скасовується хмарою --------------------------------

    @Test
    fun bookDeletedHereDoesNotComeBackFromCloud() = runTest {
        insert(bookEntity("gone", lastPlayedAt = now - 10_000L))
        repo.deleteBook("gone")

        // У хмарі книга ще є — інший пристрій просто не встиг дізнатися.
        assertTrue(repo.mergeBackupJson(cloudJson(books = listOf(bookEntity("gone", now - 10_000L)))))

        assertNull("книга воскресла", db.library().getBook("gone"))
    }

    @Test
    fun deletingLeavesATombstoneThatTravelsInTheBackup() = runTest {
        insert(bookEntity("gone", lastPlayedAt = null))
        repo.deleteBook("gone")

        val exported = repo.exportBackupJson()
        val payload = BackupCodec.decode(exported, BackupCodec.Fallbacks("—") { "Розділ $it" })

        assertEquals(listOf("gone"), payload.tombstones.map { it.id })
        assertEquals(BackupCodec.VERSION, payload.version)
    }

    // --- видалення з хмари застосовується тут --------------------------------

    @Test
    fun tombstoneFromCloudRemovesTheBookHere() = runTest {
        insert(bookEntity("obsolete", lastPlayedAt = now - 10_000L))

        val deletedAt = now - 5_000L
        val json = cloudJson(tombstones = listOf(DeletedBookEntity("obsolete", deletedAt)))
        assertTrue(repo.mergeBackupJson(json))

        assertNull("книга не видалена", db.library().getBook("obsolete"))
        // Надгробок лишається й тут, щоб поїхати далі — з початковим часом.
        assertEquals(deletedAt, db.library().getTombstone("obsolete")?.deletedAt)
    }

    /**
     * Плеєр і файли книги, прибраної чужим надгробком.
     *
     * Злиття робить свої видалення всередині транзакції Room, куди не можна ні
     * стрибати на Dispatchers.Main, ні чіпати файлову систему. Тому побічні ефекти
     * переїхали за коміт — і саме тому їх треба стерегти окремо: якщо колбек
     * загубиться, плеєр лишиться грати книгу, якої в полиці вже немає, а дозволи
     * SAF і скопійовані файли протечуть мовчки.
     */
    @Test
    fun cloudTombstoneStillStopsThePlayerForTheRemovedBook() = runTest {
        val stopped = mutableListOf<String>()
        repo.onBookDeleted = { id -> stopped += id }

        insert(bookEntity("obsolete", lastPlayedAt = now - 10_000L))
        insert(bookEntity("kept", lastPlayedAt = now - 10_000L))

        val json = cloudJson(
            books = listOf(bookEntity("kept", now - 10_000L)),
            tombstones = listOf(DeletedBookEntity("obsolete", now - 5_000L)),
        )
        assertTrue(repo.mergeBackupJson(json))

        assertNull(db.library().getBook("obsolete"))
        assertNotNull("книга без надгробка мала лишитися", db.library().getBook("kept"))
        assertEquals("плеєр мав дізнатися рівно про видалену книгу", listOf("obsolete"), stopped)
    }

    /** Нічого не видалено — колбек плеєра чіпати нема за чим. */
    @Test
    fun mergeWithoutDeletionsDoesNotTouchThePlayer() = runTest {
        val stopped = mutableListOf<String>()
        repo.onBookDeleted = { id -> stopped += id }

        insert(bookEntity("kept", lastPlayedAt = now - 10_000L))
        assertTrue(repo.mergeBackupJson(cloudJson(books = listOf(bookEntity("kept", now - 1_000L)))))

        assertTrue("плеєр смикнули без причини", stopped.isEmpty())
    }

    @Test
    fun tombstoneDoesNotDestroyProgressMadeAfterTheDeletion() = runTest {
        // Видалили на планшеті раніше, але на телефоні слухали вже після того.
        insert(bookEntity("active", lastPlayedAt = now - 1_000L))

        val json = cloudJson(tombstones = listOf(DeletedBookEntity("active", deletedAt = now - 5_000L)))
        assertTrue(repo.mergeBackupJson(json))

        assertNotNull("свіжий прогрес знищено видаленням", db.library().getBook("active"))
    }

    @Test
    fun tombstoneForAnUnknownBookIsStillRemembered() = runTest {
        val deletedAt = now - 5_000L
        val json = cloudJson(tombstones = listOf(DeletedBookEntity("never-had-it", deletedAt)))
        assertTrue(repo.mergeBackupJson(json))

        // Інакше третій пристрій, який ще має цю книгу, ніколи б про видалення не дізнався.
        assertEquals(deletedAt, db.library().getTombstone("never-had-it")?.deletedAt)
    }

    @Test
    fun bookPlayedAfterOurDeletionIsRestored() = runTest {
        insert(bookEntity("second-thoughts", lastPlayedAt = now - 10_000L))
        repo.deleteBook("second-thoughts")

        // Хтось послухав її на іншому пристрої вже після нашого видалення.
        val future = now + 60_000L
        val json = cloudJson(books = listOf(bookEntity("second-thoughts", lastPlayedAt = future)))
        assertTrue(repo.mergeBackupJson(json))

        assertNotNull(db.library().getBook("second-thoughts"))
        assertNull("надгробок мав зникнути", db.library().getTombstone("second-thoughts"))
    }

    // --- сумісність зі старим форматом ---------------------------------------

    @Test
    fun version1BackupWithoutTombstonesStillMerges() = runTest {
        val v1 = """
            {
              "version": 1,
              "exportedAt": 1,
              "books": [{
                "id": "old", "title": "Стара", "author": "Автор",
                "coverPath": null, "addedAt": 1, "lastPlayedAt": 2,
                "durationMs": 10, "positionMs": 5, "currentChapterIndex": 0,
                "playbackSpeed": 1.0, "completed": false,
                "chapters": [{"id":"c","bookId":"old","index":0,"title":"1","uri":"file:///x","durationMs":10}]
              }],
              "bookmarks": [],
              "dailyListening": []
            }
        """.trimIndent()

        assertTrue(repo.mergeBackupJson(v1))
        assertNotNull(db.library().getBook("old"))
        assertTrue(db.library().getAllTombstones().isEmpty())
    }

    // --- правило вирішення ----------------------------------------------------

    @Test
    fun tombstoneWinsOverUntouchedAndEqualTimestamps() {
        assertTrue(BackupCodec.tombstoneWins(bookEntity("a", lastPlayedAt = null), deletedAt = 0L))
        assertTrue(BackupCodec.tombstoneWins(bookEntity("a", lastPlayedAt = 500L), deletedAt = 500L))
        assertTrue(BackupCodec.tombstoneWins(bookEntity("a", lastPlayedAt = 499L), deletedAt = 500L))
        assertFalse(BackupCodec.tombstoneWins(bookEntity("a", lastPlayedAt = 501L), deletedAt = 500L))
    }

    @Test
    fun expiredTombstonesArePrunedSoTheBackupDoesNotGrowForever() = runTest {
        val ancient = now - (TOMBSTONE_TTL_DAYS + 1) * 24 * 3600 * 1000L
        db.library().upsertTombstone(DeletedBookEntity("ancient", ancient))
        db.library().upsertTombstone(DeletedBookEntity("recent", now - 1000L))

        assertTrue(repo.mergeBackupJson(cloudJson()))

        assertNull(db.library().getTombstone("ancient"))
        assertNotNull(db.library().getTombstone("recent"))
    }

    @Test
    fun fullRestoreReplacesTombstonesToo() = runTest {
        insert(bookEntity("local", lastPlayedAt = null))
        repo.deleteBook("local")
        assertEquals(1, db.library().getAllTombstones().size)

        // Відновлення з файла — повна заміна полиці, а не злиття.
        assertTrue(repo.importBackupJson(cloudJson(books = listOf(bookEntity("fresh", null)))))

        assertNotNull(db.library().getBook("fresh"))
        assertTrue("надгробки не скинуто", db.library().getAllTombstones().isEmpty())
    }
}
