package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Журнал іде з телефона до людини, яку обрав слухач, тож головне тут —
 * щоб назви книг і шляхи не просочувалися, а тип і місце збою лишалися.
 */
class DiagnosticLogTest {

    @Test
    fun redactRemovesStoragePathWithSpacesInBookTitle() {
        val line = "W/BookVoices: java.io.FileNotFoundException: " +
            "/storage/emulated/0/Audiobooks/Тіні забутих предків/01 Глава.mp3: open failed: ENOENT"
        val out = DiagnosticLog.redact(line)
        assertFalse(out.contains("Тіні"))
        assertFalse(out.contains("Глава"))
        assertTrue(out.contains("FileNotFoundException: <path>: open failed: ENOENT"))
    }

    @Test
    fun redactRemovesContentUriInsideParentheses() {
        val line = "SecurityException (content://com.android.externalstorage.documents/tree/primary%3AКниги) denied"
        assertEquals("SecurityException (<path>) denied", DiagnosticLog.redact(line))
    }

    @Test
    fun redactRemovesBareSafDocumentId() {
        // ExternalStorageProvider пише id документа без content:// — назва книги лишалась.
        val line = "IllegalArgumentException: Failed to determine if " +
            "primary:Audiobooks/Тіні забутих предків/01.mp3 is child of primary:Audiobooks"
        val out = DiagnosticLog.redact(line)
        assertFalse(out, out.contains("Тіні"))
        assertTrue(out, out.startsWith("IllegalArgumentException: Failed to determine if <path>"))
        // Карта пам'яті має id тому на кшталт 1A2B-3C4D.
        val sd = "FileNotFoundException: 1A2B-3C4D:Книги/Кобзар.m4b: open failed"
        assertEquals("FileNotFoundException: <path>: open failed", DiagnosticLog.redact(sd))
    }

    @Test
    fun redactRemovesWebDavUrlWithLogin() {
        val line = "W/BookVoices: WebDavSync: java.io.IOException: 401 for " +
            "https://cloud.example.com/remote.php/dav/files/olena.k/bookvoices/bookvoices.json"
        val out = DiagnosticLog.redact(line)
        assertFalse(out, out.contains("olena.k"))
        assertTrue(out, out.endsWith("401 for <path>"))
    }

    @Test
    fun redactKeepsStackFramesUntouched() {
        val frame = "\tat ua.nichnyk.listen.playback.PlayerManager.play(PlayerManager.kt:120)"
        assertEquals(frame, DiagnosticLog.redact(frame))
    }

    @Test
    fun redactWorksPerLine() {
        val text = "a /sdcard/Книга один.m4b\nb next line stays"
        assertEquals("a <path>\nb next line stays", DiagnosticLog.redact(text))
    }

    @Test
    fun tailKeepsLastLines() {
        val text = (1..10).joinToString("\n") { "line $it" }
        assertEquals("line 8\nline 9\nline 10", DiagnosticLog.tail(text, 3))
        assertEquals(text, DiagnosticLog.tail(text, 100))
    }

    @Test
    fun composeHasHeaderAndPlaceholderForEmptyLog() {
        val header = DiagnosticLog.header("1.17.0", 11700, 35, "15", "Xiaomi", "2201116TG", Locale.forLanguageTag("uk-UA"))
        val report = DiagnosticLog.compose(header, "")
        assertTrue(report.startsWith("BookVoices 1.17.0 (11700)\nAndroid 15 (API 35)\nXiaomi 2201116TG\nLocale uk-UA\n"))
        assertTrue(report.contains("(log is empty)"))
    }

    @Test
    fun composeRedactsLogBody() {
        val report = DiagnosticLog.compose("H\n", "E/AndroidRuntime: at file:///data/user/0/x/Книга.mp3")
        assertFalse(report.contains("Книга"))
        assertTrue(report.contains("<path>"))
    }
}
