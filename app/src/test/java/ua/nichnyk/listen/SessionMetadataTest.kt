package ua.nichnyk.listen

import android.os.Bundle
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.playback.browseChapterMetadata
import ua.nichnyk.listen.playback.queueChapterMetadata

/**
 * Без `displayTitle` MIUI на екрані блокування склеює автора й книгу в один рядок
 * (опис сесії платформа тоді складає з «назва, виконавець, альбом»). Тут — що всі
 * поля на місці: `displayTitle` для екрана блокування, `artist`/`albumTitle` для
 * Bluetooth-магнітол.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SessionMetadataTest {

    @Test
    fun queueItem_hasBookOnTopAndChapterBelow() {
        val m = queueChapterMetadata("Тіні забутих предків", "Коцюбинський", "Розділ 3", null)
        assertEquals("Тіні забутих предків", m.title.toString())
        assertEquals("Тіні забутих предків", m.displayTitle.toString())
        assertEquals("Розділ 3", m.subtitle.toString())
        assertEquals("Коцюбинський", m.artist.toString())
        assertEquals("Тіні забутих предків", m.albumTitle.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER, m.mediaType)
        assertEquals(true, m.isPlayable)
    }

    @Test
    fun queueItem_renamingChapterKeepsDisplayTitle() {
        // Так робить PlayerManager.refreshChapterTitles після перейменування глави.
        val m = queueChapterMetadata("Книга", "Автор", "Стара назва", null)
            .buildUpon().setSubtitle("Нова назва").build()
        assertEquals("Книга", m.displayTitle.toString())
        assertEquals("Нова назва", m.subtitle.toString())
    }

    @Test
    fun browseItem_hasChapterOnTopAndBookBelow() {
        val extras = Bundle().apply { putString("book_id", "b1") }
        val m = browseChapterMetadata("Книга", "Автор", "Розділ 1", null, extras)
        assertEquals("Розділ 1", m.title.toString())
        assertEquals("Розділ 1", m.displayTitle.toString())
        assertEquals("Книга", m.subtitle.toString())
        assertEquals("Автор", m.artist.toString())
        assertEquals("Книга", m.albumTitle.toString())
        assertEquals(false, m.isBrowsable)
        assertEquals("b1", m.extras?.getString("book_id"))
    }
}
