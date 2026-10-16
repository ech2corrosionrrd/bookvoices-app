package ua.nichnyk.listen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ua.nichnyk.listen.data.AudioImporter
import ua.nichnyk.listen.data.ZipBookExtractor
import ua.nichnyk.listen.data.ZipHasNoAudioException
import ua.nichnyk.listen.data.ZipOutOfSpaceException
import ua.nichnyk.listen.data.ZipUnreadableException

/**
 * Книга в архіві: розпаковуємо лише аудіо, плоско, без виходу за межі теки.
 *
 * Помилки тут дорогі: архів на гігабайти може заповнити сховище, а кривий шлях
 * у записі — записати файл туди, куди не слід.
 */
class ZipBookExtractorTest {

    private lateinit var dest: File

    @Before
    fun setUp() {
        dest = File.createTempFile("zipbook", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dest.deleteRecursively()
    }

    private fun zip(charset: Charset = Charsets.UTF_8, build: (ZipOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes, charset).use(build)
        return bytes.toByteArray()
    }

    private fun ZipOutputStream.add(name: String, content: ByteArray = byteArrayOf(1, 2, 3)) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    private fun extractor(
        free: () -> Long = { Long.MAX_VALUE },
        keep: Long = 0L,
        cancelled: () -> Unit = {},
    ) = ZipBookExtractor(
        destDir = dest,
        freeSpace = free,
        keepFreeBytes = keep,
        isAudio = AudioImporter::isAudioName,
        skipPath = AudioImporter::isJunkPath,
        checkCancelled = cancelled,
    )

    @Test
    fun extractsOnlyAudioAndKeepsArchiveOrderKeys() {
        val bytes = zip {
            it.add("Book/CD2/01.mp3")
            it.add("Book/CD1/02.mp3")
            it.add("Book/cover.jpg")
            it.add("Book/readme.txt")
            it.putNextEntry(ZipEntry("Book/CD1/"))
            it.closeEntry()
        }
        val out = extractor().extract { ByteArrayInputStream(bytes) }

        assertEquals(listOf("Book/CD2/01.mp3", "Book/CD1/02.mp3"), out.map { it.zipPath })
        assertTrue(out.all { it.file.exists() && it.file.parentFile == dest })
        assertEquals(2, dest.listFiles()!!.size)
    }

    @Test
    fun macOsResourceForksAndHiddenFilesAreNotExtractedAsAudio() {
        // Архів із Mac: поряд із кожним файлом лежить «._файл» і тека __MACOSX.
        val bytes = zip {
            it.add("Книга/01.mp3")
            it.add("Книга/02.mp3")
            it.add("__MACOSX/Книга/._01.mp3")
            it.add("__MACOSX/Книга/._02.mp3")
            it.add("Книга/._03.mp3")
            it.add("Книга/.trashed-1700000000-04.mp3")
        }
        val out = extractor().extract { ByteArrayInputStream(bytes) }

        assertEquals(listOf("Книга/01.mp3", "Книга/02.mp3"), out.map { it.zipPath })
        assertEquals(2, dest.listFiles()!!.size)
    }

    @Test
    fun archiveWithOnlyJunkAudioIsEmpty() {
        val bytes = zip { it.add("__MACOSX/._01.mp3") }
        try {
            extractor().extract { ByteArrayInputStream(bytes) }
            fail("очікувався ZipHasNoAudioException")
        } catch (_: ZipHasNoAudioException) {
            // очікувано
        }
        assertEquals(0, dest.listFiles()!!.size)
    }

    @Test
    fun hostileEntryNamesCannotEscapeTheDestinationFolder() {
        val bytes = zip {
            it.add("../../evil.mp3")
            it.add("/abs/path/root.mp3")
            it.add("C:\\Windows\\win.mp3")
            it.add("a/../../b.mp3")
        }
        val out = extractor().extract { ByteArrayInputStream(bytes) }

        // Записи з «..» у шляху не розпаковуються взагалі (це службові сегменти), решта —
        // абсолютні шляхи й диск Windows — розкладаються плоско, без виходу за destDir.
        assertEquals(listOf("/abs/path/root.mp3", "C:/Windows/win.mp3"), out.map { it.zipPath })
        assertEquals(2, out.size)
        // Усе лежить прямо в destDir, під новими іменами; шлях з архіву лише ключ порядку.
        assertTrue(out.all { it.file.canonicalFile.parentFile == dest.canonicalFile })
        assertEquals(2, dest.listFiles()!!.size)
        assertTrue(File(dest.parentFile, "evil.mp3").exists().not())
    }

    @Test
    fun archiveWithoutAudioFailsAndLeavesNothingBehind() {
        val bytes = zip { it.add("notes.txt"); it.add("cover.png") }
        try {
            extractor().extract { ByteArrayInputStream(bytes) }
            fail("очікувався ZipHasNoAudioException")
        } catch (_: ZipHasNoAudioException) {
            // очікувано
        }
        assertEquals(0, dest.listFiles()!!.size)
    }

    @Test
    fun cyrillicNamesInOldDosArchivesAreRecovered() {
        val cp866 = Charset.forName("IBM866")
        val bytes = zip(cp866) {
            it.add("Книга/Глава 2.mp3")
            it.add("Книга/Глава 1.mp3")
        }
        val out = extractor().extract { ByteArrayInputStream(bytes) }
        assertEquals(listOf("Книга/Глава 2.mp3", "Книга/Глава 1.mp3"), out.map { it.zipPath })
    }

    @Test
    fun utf8NamesAreReadAsIs() {
        val bytes = zip { it.add("Книга/Глава 1.mp3") }
        val out = extractor().extract { ByteArrayInputStream(bytes) }
        assertEquals("Книга/Глава 1.mp3", out.single().zipPath)
    }

    @Test
    fun runningOutOfSpaceAbortsAndDeletesPartialFiles() {
        // Понад 8 МБ нулів: стискається до кількох КБ, а розпакований обсяг тригерить перевірку місця.
        val big = ByteArray(9 * 1024 * 1024)
        val bytes = zip { it.add("a.mp3", byteArrayOf(1)); it.add("b.mp3", big) }
        try {
            extractor(free = { 0L }, keep = 1L).extract { ByteArrayInputStream(bytes) }
            fail("очікувався ZipOutOfSpaceException")
        } catch (_: ZipOutOfSpaceException) {
            // очікувано
        }
        assertEquals("часткові файли мають бути видалені", 0, dest.listFiles()!!.size)
    }

    @Test
    fun truncatedArchiveIsUnreadableAndCleansUp() {
        val bytes = zip { it.add("a.mp3", ByteArray(50_000) { i -> (i * 31).toByte() }) }
        val cut = bytes.copyOf(bytes.size / 2)
        try {
            extractor().extract { ByteArrayInputStream(cut) }
            fail("очікувався ZipUnreadableException")
        } catch (_: ZipUnreadableException) {
            // очікувано
        }
        assertEquals(0, dest.listFiles()!!.size)
    }

    @Test
    fun cancellationStopsExtractionAndRemovesFiles() {
        val bytes = zip { it.add("a.mp3"); it.add("b.mp3") }
        var calls = 0
        try {
            extractor(cancelled = { if (++calls > 2) throw kotlinx.coroutines.CancellationException("stop") })
                .extract { ByteArrayInputStream(bytes) }
            fail("очікувалось скасування")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // очікувано
        }
        assertEquals(0, dest.listFiles()!!.size)
    }

    @Test
    fun overlongAndOddNamesAreMadeSafe() {
        val long = "x".repeat(300)
        val bytes = zip { it.add("dir/$long.mp3"); it.add("dir/a:b*c?.MP3") }
        val out = extractor().extract { ByteArrayInputStream(bytes) }
        assertEquals(2, out.size)
        assertTrue(out.all { it.file.name.length < 120 })
        assertTrue(out.all { !it.file.name.contains(':') && !it.file.name.contains('*') && !it.file.name.contains('?') })
        assertTrue(out.all { it.file.name.endsWith(".mp3") })
    }
}
