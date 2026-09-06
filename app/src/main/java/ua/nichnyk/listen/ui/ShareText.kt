package ua.nichnyk.listen.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.nichnyk.listen.AppLog
import java.io.File

/**
 * Спільний шлях для «поділитися текстом»: каталог полиці, закладки, бекап.
 *
 * Довгий текст їде файлом, а не в `EXTRA_TEXT`. Транзакція Binder обмежена
 * приблизно мегабайтом на процес, і `startActivity` з великим рядком усередині
 * падає в `TransactionTooLargeException` — на полиці з парою сотень книг
 * експорт каталогу знімав застосунок. Короткий текст лишається текстом:
 * месенджери й нотатники підхоплюють його краще, ніж вкладення.
 */
private const val INLINE_TEXT_LIMIT_BYTES = 64 * 1024

/**
 * @return false, якщо ділитися не було чим (порожній текст) або система не
 * знайшла, кому віддати. Виклик безпечний: власних винятків не кидає.
 */
suspend fun Context.shareText(
    text: String,
    fileName: String,
    mimeType: String,
    subject: String,
    chooserTitle: String? = null,
): Boolean {
    if (text.isBlank()) return false
    return runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        if (text.toByteArray().size <= INLINE_TEXT_LIMIT_BYTES) {
            intent.putExtra(Intent.EXTRA_TEXT, text)
        } else {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "backup").apply { mkdirs() }
                val file = File(dir, fileName).apply { writeText(text) }
                FileProvider.getUriForFile(this@shareText, "$packageName.fileprovider", file)
            }
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.clipData = ClipData.newUri(contentResolver, subject, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, chooserTitle))
        true
    }.onFailure { AppLog.w("shareText: не вдалося поділитися", it) }.getOrDefault(false)
}
