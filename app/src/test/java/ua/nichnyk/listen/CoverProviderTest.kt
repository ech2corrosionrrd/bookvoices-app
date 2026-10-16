package ua.nichnyk.listen

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.playback.CoverProvider
import ua.nichnyk.listen.playback.CoverUris

/**
 * Обкладинки для Android Auto: `artworkUri` має бути `content://`, який читає інший
 * процес, а не `file://` у приватне сховище (там Auto отримував EACCES і показував
 * рядки без обкладинок). Провайдер експортований, тому окремо перевіряємо, що він
 * не віддає нічого, крім файлів із `filesDir/covers`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CoverProviderTest {

    private lateinit var context: Context
    private lateinit var coversDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        Robolectric.setupContentProvider(CoverProvider::class.java, CoverUris.authority(context))
    }

    @After
    fun tearDown() {
        coversDir.deleteRecursively()
    }

    private fun cover(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File(coversDir, name).apply { writeBytes(bytes) }

    @Test
    fun coverFromCoversDirBecomesContentUri() {
        val file = cover("7732596d-b925-4909-88c4-87c8d6411c65.jpg")

        val uri = CoverUris.artwork(context, file.absolutePath)

        assertNotNull(uri)
        assertEquals("content", uri!!.scheme)
        assertEquals(CoverUris.authority(context), uri.authority)
        assertEquals("7732596d-b925-4909-88c4-87c8d6411c65.jpg", uri.lastPathSegment)
    }

    @Test
    fun contentUriReadsTheSameBytes() {
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val file = cover("abc-1700000000000.png", bytes)
        val uri = CoverUris.artwork(context, file.absolutePath)!!

        val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

        assertTrue(bytes.contentEquals(read))
        assertEquals("image/png", context.contentResolver.getType(uri))
    }

    @Test
    fun nullOrForeignPathIsHandledLikeBefore() {
        assertNull(CoverUris.artwork(context, null))

        // Не з filesDir/covers — лишається file://, як було до провайдера.
        val outside = File(context.cacheDir, "x.jpg").apply { writeBytes(byteArrayOf(1)) }
        val uri = CoverUris.artwork(context, outside.absolutePath)
        assertEquals("file", uri!!.scheme)
    }

    @Test
    fun providerDoesNotServeAnythingOutsideCoversDir() {
        // Секрет поруч, але поза covers: його не має бути видно через провайдер.
        File(context.filesDir, "secret.txt").writeText("не для Auto")
        val base = "content://${CoverUris.authority(context)}"

        for (evil in listOf("..%2Fsecret.txt", "..", ".", "%2E%2E%2Fsecret.txt", "secret.txt", "a%2Fb.jpg")) {
            assertThrows("«$evil» не має відкриватися", FileNotFoundException::class.java) {
                context.contentResolver.openInputStream(Uri.parse("$base/$evil"))
            }
        }
        // Довгий шлях із кількох сегментів — теж ні.
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openInputStream(Uri.parse("$base/a/b.jpg"))
        }
    }

    @Test
    fun providerIsReadOnly() {
        val file = cover("w.jpg")
        val uri = CoverUris.artwork(context, file.absolutePath)!!

        assertThrows(SecurityException::class.java) {
            context.contentResolver.openOutputStream(uri, "w")
        }
        assertEquals(0, context.contentResolver.delete(uri, null, null))
        assertNull(context.contentResolver.insert(uri, android.content.ContentValues()))
        assertTrue("файл не має зникнути", file.exists())
    }

    @Test
    fun nameValidationAndResolveGuardAgainstTraversal() {
        assertTrue(CoverUris.isSafeName("7732596d-b925.jpg"))
        assertTrue(CoverUris.isSafeName("id-1700000000000.png"))
        assertFalse(CoverUris.isSafeName(".."))
        assertFalse(CoverUris.isSafeName("."))
        assertFalse(CoverUris.isSafeName("a/b.jpg"))
        assertFalse(CoverUris.isSafeName("a\\b.jpg"))
        assertFalse(CoverUris.isSafeName(""))
        assertFalse(CoverUris.isSafeName("x".repeat(129)))

        assertNull("відсутній файл", CoverUris.resolve(coversDir, "none.jpg"))
        assertNull("теку не віддаємо", CoverUris.resolve(coversDir.parentFile!!, "covers"))
        cover("ok.jpg")
        assertNotNull(CoverUris.resolve(coversDir, "ok.jpg"))
    }
}
