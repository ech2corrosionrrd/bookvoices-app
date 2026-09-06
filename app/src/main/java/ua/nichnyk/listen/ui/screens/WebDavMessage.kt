package ua.nichnyk.listen.ui.screens

import android.content.Context
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.WebDavError

/**
 * Єдине місце, де помилка WebDAV перетворюється на текст.
 * Сам клієнт більше не носить у собі захардкожених українських повідомлень,
 * і логіка синхронізації не залежить від того, якою мовою вони написані.
 */
fun Context.webDavMessage(error: Throwable?): String = when (error) {
    is WebDavError.Http -> getString(R.string.webdav_err_http, error.code, error.serverMessage)
    is WebDavError -> getString(error.messageRes)
    is BackupParseException -> getString(R.string.backup_restore_failed)
    // Без `error.message`: усе, що сюди дійшло незмапленим, — це IOException або
    // виняток системи, тобто англійський технічний рядок. Він ішов просто в
    // снекбар; тепер іде в logcat, а користувач бачить свою мову.
    else -> {
        if (error != null) AppLog.w("webDavMessage: незмаплена помилка", error)
        getString(R.string.webdav_err_network)
    }
}
