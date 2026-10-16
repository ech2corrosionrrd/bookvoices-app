package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.nichnyk.listen.data.BackupCodec

/**
 * Файл резервної копії — не довірений: він міг пошкодитись, прийти з іншої версії чи від
 * сервера, що віддав чужий JSON. Безглузді числа не мають потрапляти в базу й плеєр.
 */
class BackupSanityTest {

    private val fallbacks = BackupCodec.Fallbacks("Unknown") { "Chapter $it" }

    private fun decode(book: String) = BackupCodec.decode(
        """{"books":[$book]}""",
        fallbacks,
    ).books.single()

    private fun bookJson(extra: String) =
        """{"id":"b1","title":"T","author":"A","chapters":[{"id":"c1","index":0,"title":"C","uri":"file:///x","durationMs":1000}]$extra}"""

    @Test
    fun nonsensePlaybackSpeedBecomesOneTimes() {
        assertEquals(1f, decode(bookJson(""","playbackSpeed":0""")).playbackSpeed, 0f)
        assertEquals(1f, decode(bookJson(""","playbackSpeed":-2.5""")).playbackSpeed, 0f)
        assertEquals(1f, decode(bookJson(""","playbackSpeed":"abc"""")).playbackSpeed, 0f)
    }

    @Test
    fun extremeSpeedsAreClampedNotRejected() {
        assertEquals(4f, decode(bookJson(""","playbackSpeed":99""")).playbackSpeed, 0f)
        assertEquals(0.25f, decode(bookJson(""","playbackSpeed":0.01""")).playbackSpeed, 0f)
        assertEquals(1.5f, decode(bookJson(""","playbackSpeed":1.5""")).playbackSpeed, 0f)
    }

    @Test
    fun negativePositionsAndIndexesBecomeZero() {
        val b = decode(bookJson(""","positionMs":-5000,"durationMs":-1,"currentChapterIndex":-3"""))
        assertEquals(0L, b.positionMs)
        assertEquals(0L, b.durationMs)
        assertEquals(0, b.currentChapterIndex)
    }

    @Test
    fun negativeChapterTimesBecomeZero() {
        val payload = BackupCodec.decode(
            """{"books":[{"id":"b1","title":"T","chapters":[{"id":"c1","index":0,"title":"C","uri":"file:///x","durationMs":-9,"startMs":-1,"endMs":-2}]}]}""",
            fallbacks,
        )
        val c = payload.chapters.single()
        assertEquals(0L, c.durationMs)
        assertEquals(0L, c.startMs)
        assertEquals(0L, c.endMs)
    }
}
