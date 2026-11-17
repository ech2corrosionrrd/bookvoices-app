package ua.nichnyk.listen.data

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Відкриває документ SAF на **перезапис**.
 *
 * Спершу `"wt"` (запис зі скороченням): без скорочення коротший новий вміст лишив би
 * хвіст старого файла. Але `"wt"` підтримують не всі провайдери документів — частина
 * хмарних і сторонніх файлових менеджерів кидає `IllegalArgumentException("Bad mode")`
 * або `FileNotFoundException`. Тоді відкриваємо `"rw"` / `"w"` і після запису
 * обрізаємо файл до фактичної довжини: інакше повторна автокопія того самого дня
 * або «Зберегти у файл» поверх довшого документа лишали би зіпсований JSON.
 */
fun ContentResolver.openForOverwrite(uri: Uri): OutputStream {
    val truncating = runCatching { openOutputStream(uri, "wt") }
    truncating.getOrNull()?.let { return it }
    val pfd = runCatching { openFileDescriptor(uri, "rw") }.getOrNull()
        ?: runCatching { openFileDescriptor(uri, "w") }.getOrNull()
    if (pfd != null) {
        return TruncatingOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pfd))
    }
    throw truncating.exceptionOrNull() ?: IllegalStateException("openOutputStream повернув null")
}

/**
 * Дописує з початку й на [close] відрізає хвіст, довший за новий вміст.
 * Якщо провайдер не вміє `truncate`, файл усе одно записано з початку.
 */
internal class TruncatingOutputStream(
    private val file: FileOutputStream,
) : FilterOutputStream(file) {
    // FilterOutputStream за замовчуванням пише масив по байту через write(int):
    // копія на кілька МБ — мільйони системних викликів на запасному шляху.
    override fun write(b: ByteArray, off: Int, len: Int) {
        file.write(b, off, len)
    }

    override fun close() {
        try {
            flush()
            val channel = file.channel
            channel.truncate(channel.position())
        } catch (_: IOException) {
            // Хвіст лишиться лише там, де провайдер не віддає звичайний файл.
        } finally {
            super.close()
        }
    }
}
