package ua.nichnyk.listen.data

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Імпорт на справжніх файлах і справжній БД.
 *
 * Юніт-тести покривали лише сортування імен; усе, що відбувається далі —
 * читання метаданих, збирання книги, виявлення дублікатів, прибирання за собою —
 * перевіряється тільки тут.
 */
@RunWith(AndroidJUnit4::class)
class ImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ListenDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var workDir: File

    @Before
    fun setUp() {
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
        workDir = File(context.cacheDir, "import-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        db.close()
        workDir.deleteRecursively()
        File(context.filesDir, "covers").deleteRecursively()
    }

    private fun wav(name: String, seconds: Int = 1) =
        TestAudio.writeWav(File(workDir, name), seconds).toUri()

    @Test
    fun importsFilesAsChaptersInNaturalOrder() = runBlocking {
        // Імена навмисне в «неправильному» лексикографічному порядку: 10 має йти після 2.
        val uris = listOf(wav("10.wav"), wav("2.wav"), wav("1.wav"))

        val outcome = repo.importUris(uris)
        val book = db.library().getBook(outcome.single!!.id)

        assertNotNull(book)
        assertEquals(3, book!!.chapters.size)
        assertEquals(
            listOf("1", "2", "10"),
            book.chapters.sortedBy { it.index }.map { it.title },
        )
        // Тривалість книги — сума розділів, і вона реально прочитана з файлів.
        assertTrue("тривалість не прочитана: ${book.book.durationMs}", book.book.durationMs > 0L)
        assertEquals(book.chapters.sumOf { it.durationMs }, book.book.durationMs)
    }

    @Test
    fun importGeneratesCoverAndStartsUnplayed() = runBlocking {
        val outcome = repo.importUris(listOf(wav("chapter.wav")))
        val book = db.library().getBook(outcome.single!!.id)!!.book

        val cover = book.coverPath
        assertNotNull("обкладинка не згенерована", cover)
        assertTrue("файл обкладинки відсутній", File(cover!!).exists())

        // Свіжа книга не має вважатися розпочатою — інакше вона одразу
        // потрапляє у фільтр «слухаю» і на верх полиці.
        assertNull(book.lastPlayedAt)
        assertEquals(0L, book.positionMs)
        assertFalse(book.completed)
    }

    @Test
    fun importUsesGivenTitleAndAuthor() = runBlocking {
        val outcome = repo.importUris(listOf(wav("a.wav")), title = "Кобзар", author = "Шевченко")
        val book = db.library().getBook(outcome.single!!.id)!!.book
        assertEquals("Кобзар", book.title)
        assertEquals("Шевченко", book.author)
    }

    @Test
    fun secondImportOfSameFilesIsRejectedAsDuplicate() = runBlocking {
        val uris = listOf(wav("one.wav"), wav("two.wav"))
        repo.importUris(uris)

        val failure = runCatching { repo.importUris(uris) }.exceptionOrNull()

        assertTrue("очікувався DuplicateBookException, отримано $failure", failure is DuplicateBookException)
        assertEquals(1, db.library().getAllBooks().size)
    }

    @Test
    fun differentFilesAreNotDuplicates() = runBlocking {
        repo.importUris(listOf(wav("first.wav", seconds = 1)))
        repo.importUris(listOf(wav("second.wav", seconds = 2)))
        assertEquals(2, db.library().getAllBooks().size)
    }

    @Test
    fun deleteBookRemovesCoverButKeepsUserFiles() = runBlocking {
        val source = File(workDir, "keepme.wav")
        TestAudio.writeWav(source, 1)
        val outcome = repo.importUris(listOf(source.toUri()))
        val cover = db.library().getBook(outcome.single!!.id)!!.book.coverPath!!

        repo.deleteBook(outcome.single!!.id)

        assertNull(db.library().getBook(outcome.single!!.id))
        assertFalse("обкладинка не прибрана", File(cover).exists())
        // Головна обіцянка застосунку: чужі аудіофайли він не чіпає.
        assertTrue("видалено файл користувача", source.exists())
    }

    @Test
    fun importedBookSurvivesBackupRoundTrip() = runBlocking {
        val outcome = repo.importUris(listOf(wav("x.wav"), wav("y.wav")), title = "Джерело")
        val original = db.library().getBook(outcome.single!!.id)!!

        val json = repo.exportBackupJson()
        db.library().deleteAllBooks()
        assertEquals(0, db.library().getAllBooks().size)

        assertTrue(repo.importBackupJson(json))

        val restored = db.library().getBook(outcome.single!!.id)
        assertNotNull(restored)
        assertEquals(original.book, restored!!.book)
        assertEquals(
            original.chapters.sortedBy { it.index },
            restored.chapters.sortedBy { it.index },
        )
    }

    @Test
    fun restoreFromForeignFileLeavesLibraryIntact() = runBlocking {
        repo.importUris(listOf(wav("mine.wav")))

        // Чужий JSON не має стирати полицю: розбір падає до будь-яких змін у БД.
        assertFalse(repo.importBackupJson("""{"totally":"unrelated"}"""))
        assertEquals(1, db.library().getAllBooks().size)
    }
}
