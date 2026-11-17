package ua.nichnyk.listen.ui.quick

import ua.nichnyk.listen.forAppLocale
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.MainActivity
import ua.nichnyk.listen.R

/**
 * Ярлик «Продовжити …» на іконці застосунку й плитка у швидких налаштуваннях.
 *
 * Обидва — лише інші двері до того самого `PlayerManager`, що й віджет:
 * своєї логіки відтворення тут немає. Чисті функції винесено окремо, щоб
 * перевіряти їх без лаунчера й системної шторки.
 */
object QuickAccess {

    /** Дія інтента ярлика. Обробляє [MainActivity]; ззовні її не викликають. */
    const val ACTION_RESUME_BOOK = "ua.nichnyk.listen.action.RESUME_BOOK"
    const val EXTRA_BOOK_ID = "ua.nichnyk.listen.extra.BOOK_ID"

    /** Один динамічний ярлик: остання книга. Його id стабільний, тож лаунчер не плодить копій. */
    const val RESUME_SHORTCUT_ID = "resume_last_book"

    /** Стан плитки: активна лише коли справді грає. */
    fun tileState(isPlaying: Boolean): Int =
        if (isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

    /** Що показати під назвою плитки: назва книги або «немає книги». */
    fun tileSubtitle(title: String?, noBook: String): String =
        title?.takeIf { it.isNotBlank() } ?: noBook

    fun resumeIntent(context: Context, bookId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_RESUME_BOOK
            putExtra(EXTRA_BOOK_ID, bookId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    /** id книги з інтента ярлика або null, якщо це не наш інтент. */
    fun bookIdFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == ACTION_RESUME_BOOK }
            ?.getStringExtra(EXTRA_BOOK_ID)
            ?.takeIf { it.isNotBlank() }

    /**
     * Ставить або оновлює ярлик на книгу. Викликати лише при зміні книги чи назви:
     * система обмежує частоту оновлень ярликів, поки застосунок у фоні.
     */
    fun publishResumeShortcut(context: Context, bookId: String, title: String) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, RESUME_SHORTCUT_ID)
                .setShortLabel(title)
                .setLongLabel(context.forAppLocale().getString(R.string.shortcut_resume_long, title))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_resume))
                .setIntent(resumeIntent(context, bookId))
                .build()
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
        }.onFailure { AppLog.w("QuickAccess: не вдалося оновити ярлик", it) }
    }

    /** Книги більше нема (видалили, відновили бекап) — ярлик на неї не лишаємо. */
    fun removeResumeShortcut(context: Context) {
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(RESUME_SHORTCUT_ID))
        }.onFailure { AppLog.w("QuickAccess: не вдалося прибрати ярлик", it) }
    }

    fun reportResumeUsed(context: Context) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, RESUME_SHORTCUT_ID) }
    }

    /** Просить систему перемалювати плитку, якщо вона додана в шторку. */
    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, PlaybackTileService::class.java),
            )
        }.onFailure { AppLog.w("QuickAccess: не вдалося оновити плитку", it) }
    }
}
