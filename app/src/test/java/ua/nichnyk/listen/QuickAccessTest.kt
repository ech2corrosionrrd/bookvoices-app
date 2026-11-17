package ua.nichnyk.listen

import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.ui.quick.QuickAccess

/** Ярлик «Продовжити …» і плитка: стан, інтент і життя динамічного ярлика. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QuickAccessTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    @Test
    fun tileIsActiveOnlyWhilePlaying() {
        assertEquals(Tile.STATE_ACTIVE, QuickAccess.tileState(isPlaying = true))
        assertEquals(Tile.STATE_INACTIVE, QuickAccess.tileState(isPlaying = false))
    }

    @Test
    fun tileSubtitleFallsBackWhenNoBook() {
        assertEquals("Кобзар", QuickAccess.tileSubtitle("Кобзар", "—"))
        assertEquals("—", QuickAccess.tileSubtitle(null, "—"))
        assertEquals("—", QuickAccess.tileSubtitle("  ", "—"))
    }

    @Test
    fun resumeIntentRoundTripsBookId() {
        val intent = QuickAccess.resumeIntent(context, "book-42")
        assertEquals("book-42", QuickAccess.bookIdFrom(intent))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun foreignIntentsAreIgnored() {
        assertNull(QuickAccess.bookIdFrom(null))
        assertNull(QuickAccess.bookIdFrom(Intent(Intent.ACTION_MAIN).putExtra(QuickAccess.EXTRA_BOOK_ID, "x")))
        assertNull(QuickAccess.bookIdFrom(Intent(QuickAccess.ACTION_RESUME_BOOK)))
    }

    @Test
    fun publishKeepsSingleShortcutAndRemoveDropsIt() {
        QuickAccess.publishResumeShortcut(context, "a", "Перша книга")
        QuickAccess.publishResumeShortcut(context, "b", "Друга книга з дуже довгою назвою, яку не обрізаємо")

        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(1, shortcuts.size)
        val s = shortcuts.single()
        assertEquals(QuickAccess.RESUME_SHORTCUT_ID, s.id)
        assertEquals("Друга книга з дуже довгою назвою, яку не обрізаємо", s.shortLabel.toString())
        assertTrue(s.longLabel.toString().contains("Друга книга"))
        assertEquals("b", QuickAccess.bookIdFrom(s.intent))

        QuickAccess.removeResumeShortcut(context)
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty())
    }
}
