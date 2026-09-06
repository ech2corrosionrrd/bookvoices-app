package ua.nichnyk.listen.data

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Синхронізація WebDAV проти справжнього HTTPS-сервера.
 *
 * Юніт-тести перевіряли лише класифікацію кодів відповіді. Тут ходить реальний
 * HttpsURLConnection з Basic auth: саме там жили помилки, які не видно з логіки —
 * порожній responseMessage після disconnect(), тіло запиту, коди 401/404.
 */
@RunWith(AndroidJUnit4::class)
class WebDavTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: FakeWebDavServer
    private lateinit var db: ListenDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var workDir: File

    private val user get() = server.expectedUser
    private val password get() = server.expectedPassword

    @Before
    fun setUp() {
        // Спершу присвоєння, потім старт: інакше падіння в start() лишає server
        // неініціалізованим, і tearDown маскує справжню помилку своєю.
        server = FakeWebDavServer()
        server.start(InstrumentationRegistry.getInstrumentation().context)
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
        workDir = File(context.cacheDir, "webdav-test").apply { deleteRecursively(); mkdirs() }
    }

    // Прибирання не має падати само: інакше воно затуляє справжню помилку setUp.
    @After
    fun tearDown() {
        if (::server.isInitialized) runCatching { server.stop() }
        if (::db.isInitialized) runCatching { db.close() }
        if (::workDir.isInitialized) runCatching { workDir.deleteRecursively() }
        runCatching { File(context.filesDir, "covers").deleteRecursively() }
    }

    // --- з'єднання --------------------------------------------------------

    @Test
    fun testConnectionSucceedsForExistingFolder() = runBlocking {
        val result = WebDavClient.testConnection(server.url(), user, password)
        assertTrue("перевірка зʼєднання не вдалася: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun wrongPasswordIsReportedAsAuthError() = runBlocking {
        val result = WebDavClient.testConnection(server.url(), user, "не той пароль")
        assertTrue(result.isFailure)
        assertTrue(
            "очікувалася помилка автентифікації, отримано ${result.exceptionOrNull()}",
            result.exceptionOrNull() is WebDavError.Auth,
        )
        // Саме ця помилка зупиняє фонову синхронізацію замість нескінченних повторів.
        assertTrue((result.exceptionOrNull() as WebDavError).isPermanent)
    }

    @Test
    fun missingFolderIsReportedSeparatelyFromAuth() = runBlocking {
        // Шлях без завершального слеша сервер вважає файлом, а не текою.
        val result = WebDavClient.testConnection(server.url("/dav/no-such-folder"), user, password)
        assertTrue(result.exceptionOrNull() is WebDavError.FolderNotFound)
    }

    @Test
    fun serverErrorKeepsHttpCode() = runBlocking {
        server.forcedStatus = 503
        val error = WebDavClient.testConnection(server.url(), user, password).exceptionOrNull()
        assertTrue(error is WebDavError.Http)
        assertEquals(503, (error as WebDavError.Http).code)
        // Тимчасова помилка сервера має ретраїтися, на відміну від невірного пароля.
        assertTrue(!error.isPermanent)
    }

    @Test
    fun plainHttpIsRejectedBeforeAnyRequest() = runBlocking {
        val result = WebDavClient.testConnection("http://localhost:${server.port}/dav/", user, password)
        assertTrue(result.exceptionOrNull() is WebDavError.InsecureUrl)
    }

    // --- вивантаження й завантаження --------------------------------------

    @Test
    fun uploadThenDownloadReturnsSameContent() = runBlocking {
        val payload = """{"version":1,"books":[]}"""

        val upload = WebDavClient.uploadFile(server.url(), user, password, content = payload)
        assertTrue("вивантаження не вдалося: ${upload.exceptionOrNull()}", upload.isSuccess)
        assertEquals(payload, server.get("/dav/bookvoices_sync.json"))

        val download = WebDavClient.downloadFile(server.url(), user, password)
        assertEquals(payload, download.getOrNull())
    }

    @Test
    fun uploadPreservesNonAsciiContent() = runBlocking {
        // Назви книг українською: помилка кодування тут зіпсувала б усю копію.
        val payload = """{"title":"Кобзар","author":"Шевченко"}"""
        WebDavClient.uploadFile(server.url(), user, password, content = payload)
        assertEquals(payload, WebDavClient.downloadFile(server.url(), user, password).getOrNull())
    }

    @Test
    fun missingBackupIsNotAnError() = runBlocking {
        val result = WebDavClient.downloadFile(server.url(), user, password)
        val error = result.exceptionOrNull()

        // Перша синхронізація завжди починається з відсутнього файла — воркер
        // має піти далі й вивантажити свою копію, а не здатися.
        assertTrue(error is WebDavError.BackupAbsent)
        assertTrue(WebDavClient.isAbsentBackup(error))
        assertTrue(!(error as WebDavError).isPermanent)
    }

    @Test
    fun urlWithoutTrailingSlashStillResolvesFile() = runBlocking {
        WebDavClient.uploadFile(server.url("/dav"), user, password, content = "{}")
        assertNotNull(server.get("/dav/bookvoices_sync.json"))
    }

    // --- повний цикл синхронізації ----------------------------------------

    @Test
    fun librarySurvivesUploadAndMergeBack() = runBlocking {
        val audio = TestAudio.writeWav(File(workDir, "01.wav"), 1)
        val outcome = repo.importUris(listOf(audio.toUri()), title = "Тигролови", author = "Багряний")
        val bookId = outcome.single!!.id
        repo.saveProgress(bookId, chapterIndex = 0, positionMs = 3_000L, durationHint = 1_000L, speed = 1.5f)

        // Вивантажуємо стан полиці.
        val json = repo.exportBackupJson()
        assertTrue(WebDavClient.uploadFile(server.url(), user, password, content = json).isSuccess)

        // Локально «відкочуємо» прогрес, ніби книгу відновили з нуля на цьому пристрої.
        // touchLastPlayed = false принципово: інакше локальна копія стає свіжішою
        // за хмарну, і злиття справедливо лишає її — це перевіряє окремий тест нижче.
        repo.saveProgress(
            bookId, chapterIndex = 0, positionMs = 0L, durationHint = 1_000L,
            speed = 1f, touchLastPlayed = false,
        )
        db.library().getBook(bookId)!!.book.let { assertEquals(0L, it.positionMs) }

        // Забираємо з сервера й зливаємо назад.
        val cloud = WebDavClient.downloadFile(server.url(), user, password).getOrThrow()
        assertTrue(repo.mergeBackupJson(cloud))

        val merged = db.library().getBook(bookId)!!.book
        assertEquals("Тигролови", merged.title)
        assertEquals(1.5f, merged.playbackSpeed, 0.001f)
        assertNotNull(merged.lastPlayedAt)
    }

    @Test
    fun mergeKeepsFresherLocalProgress() = runBlocking {
        val audio = TestAudio.writeWav(File(workDir, "02.wav"), 1)
        val bookId = repo.importUris(listOf(audio.toUri())).single!!.id
        repo.saveProgress(bookId, 0, 1_000L, 1_000L, 1f)
        val json = repo.exportBackupJson()

        // Слухали далі вже після вивантаження — хмара не має відкотити прогрес назад.
        Thread.sleep(20)
        repo.saveProgress(bookId, 0, 9_000L, 1_000L, 1f)

        assertTrue(repo.mergeBackupJson(json))
        assertEquals(9_000L, db.library().getBook(bookId)!!.book.positionMs)
    }

    @Test
    fun downloadedGarbageDoesNotDestroyLibrary() = runBlocking {
        val audio = TestAudio.writeWav(File(workDir, "03.wav"), 1)
        repo.importUris(listOf(audio.toUri()))
        server.put("/dav/bookvoices_sync.json", "це взагалі не json")

        val cloud = WebDavClient.downloadFile(server.url(), user, password).getOrThrow()
        assertTrue(!repo.mergeBackupJson(cloud))

        // Полиця має лишитися недоторканою.
        assertEquals(1, db.library().getAllBooks().size)
    }

    @Test
    fun emptyPasswordStillSendsCredentials() = runBlocking {
        // Частина серверів приймає порожній пароль із логіном — заголовок має піти.
        server.expectedPassword = ""
        val result = WebDavClient.testConnection(server.url(), user, "")
        assertTrue("логін без пароля не пройшов: ${result.exceptionOrNull()}", result.isSuccess)
        assertNull(result.exceptionOrNull())
    }
}
