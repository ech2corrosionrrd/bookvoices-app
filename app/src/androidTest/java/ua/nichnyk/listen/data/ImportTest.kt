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

    /**
     * Одна книга одним mp3 з главами в ID3: без розбору CHAP це була б одна
     * довга глава без навігації. Файл — справжній mp3, тож тривалість читає
     * той самий MediaMetadataRetriever, що й на пристрої.
     */
    @Test
    fun mp3WithId3ChaptersImportsAsRangesOfOneFile() = runBlocking {
        val file = TestAudio.writeMp3WithChapters(
            File(workDir, "book.mp3"),
            seconds = 20,
            chapters = listOf("Вступ" to 4_000L, "Глава перша" to 9_000L, "Глава друга" to 15_000L),
        )

        val outcome = repo.importUris(listOf(file.toUri()))
        val chapters = db.library().getBook(outcome.single!!.id)!!.chapters.sortedBy { it.index }

        assertEquals(listOf("Вступ", "Глава перша", "Глава друга"), chapters.map { it.title })
        // Перша глава тягнеться до 0: усе до неї інакше було б недосяжним.
        assertEquals(listOf(0L, 9_000L, 15_000L), chapters.map { it.startMs })
        assertEquals(chapters.drop(1).map { it.startMs }, chapters.dropLast(1).map { it.endMs })
        assertTrue("остання глава має ненульову довжину", chapters.last().endMs > chapters.last().startMs)
        assertEquals(1, chapters.map { it.uri }.toSet().size)
    }

    private fun zipOf(name: String, entries: List<Pair<String, ByteArray>>): File {
        val file = File(workDir, name)
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entry, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * Книга в архіві: розпаковуємо в сховище застосунку, глави йдуть за шляхом у
     * архіві (10 після 2), а видалення книги прибирає розпаковані файли.
     */
    @Test
    fun zipArchiveImportsAsOneBookAndDeletingItRemovesExtractedFiles() = runBlocking {
        val zip = zipOf(
            "book.zip",
            listOf(
                "Книга/10.wav" to TestAudio.writeWav(File(workDir, "src10.wav"), 1).readBytes(),
                "Книга/2.wav" to TestAudio.writeWav(File(workDir, "src2.wav"), 1).readBytes(),
                "Книга/1.wav" to TestAudio.writeWav(File(workDir, "src1.wav"), 1).readBytes(),
                "Книга/notes.txt" to "не аудіо".toByteArray(),
            ),
        )
        val importsDir = File(context.filesDir, "imports")
        val before = importsDir.listFiles()?.size ?: 0

        val outcome = repo.importUris(listOf(zip.toUri()))
        val book = db.library().getBook(outcome.single!!.id)!!
        val chapters = book.chapters.sortedBy { it.index }

        assertEquals(listOf("1", "2", "10"), chapters.map { it.title })
        assertTrue("глави мають бути в сховищі застосунку", chapters.all { it.uri.contains("/imports/") })
        assertEquals("текстовий файл не мав розпаковуватись", before + 3, importsDir.listFiles()!!.size)
        assertTrue(book.book.durationMs > 0L)

        repo.deleteBook(book.book.id)
        assertEquals("розпаковані файли мають зникнути разом із книгою", before, importsDir.listFiles()?.size ?: 0)
    }

    @Test
    fun zipWithoutAudioFailsWithAMessageAndLeavesNoFiles() = runBlocking {
        val zip = zipOf("empty.zip", listOf("readme.txt" to "нічого".toByteArray()))
        val importsDir = File(context.filesDir, "imports")
        val before = importsDir.listFiles()?.size ?: 0

        val failure = runCatching { repo.importUris(listOf(zip.toUri())) }.exceptionOrNull()

        assertNotNull("очікувалась помилка", failure)
        assertEquals(context.getString(ua.nichnyk.listen.R.string.import_zip_no_audio), failure!!.message)
        assertEquals(before, importsDir.listFiles()?.size ?: 0)
    }

    /**
     * Файли названі за змістом, а не за номерами: за алфавітом книга пішла б у хибному
     * порядку. Справжній порядок — номер доріжки в тегу, і читається він системою.
     */
    @Test
    fun filesNamedByContentAreOrderedByTrackNumberFromTags() = runBlocking {
        val uris = listOf(
            TestAudio.writeMp3WithTrack(File(workDir, "alpha.mp3"), 2, track = 3).toUri(),
            TestAudio.writeMp3WithTrack(File(workDir, "bravo.mp3"), 2, track = 1).toUri(),
            TestAudio.writeMp3WithTrack(File(workDir, "charlie.mp3"), 2, track = 2).toUri(),
        )

        val outcome = repo.importUris(uris)
        val chapters = db.library().getBook(outcome.single!!.id)!!.chapters.sortedBy { it.index }

        assertEquals(listOf("bravo", "charlie", "alpha"), chapters.map { it.title })
    }

    @Test
    fun numberedFileNamesWinOverConflictingTags() = runBlocking {
        // Імена з номерами — порядок завжди за ними, хоч би що лежало в тегах.
        val uris = listOf(
            TestAudio.writeMp3WithTrack(File(workDir, "01 first.mp3"), 2, track = 9).toUri(),
            TestAudio.writeMp3WithTrack(File(workDir, "02 second.mp3"), 2, track = 1).toUri(),
            TestAudio.writeMp3WithTrack(File(workDir, "03 third.mp3"), 2, track = 5).toUri(),
        )

        val outcome = repo.importUris(uris)
        val chapters = db.library().getBook(outcome.single!!.id)!!.chapters.sortedBy { it.index }

        assertEquals(listOf("01 first", "02 second", "03 third"), chapters.map { it.title })
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
