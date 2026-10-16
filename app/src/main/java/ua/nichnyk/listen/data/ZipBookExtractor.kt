package ua.nichnyk.listen.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipInputStream

/** Один аудіофайл, розпакований із архіву: [zipPath] — шлях усередині архіву (для порядку), [file] — копія в сховищі. */
data class ExtractedAudio(val zipPath: String, val file: File)

/** У архіві немає жодного аудіофайла. */
class ZipHasNoAudioException : IOException("zip has no audio")

/** Місця на диску бракує для розпакування. */
class ZipOutOfSpaceException : IOException("not enough space to extract zip")

/** Архів не читається (пошкоджений або не zip). */
class ZipUnreadableException(cause: Throwable?) : IOException("zip is unreadable", cause)

/**
 * Розпаковує з ZIP лише аудіо в [destDir] — одна книга з архіву.
 *
 * Файли лягають **плоско** під новими іменами `<uuid>_<основа>.<розширення>`, а шлях
 * з архіву лишається тільки як ключ порядку. Тому записи на кшталт `../../x` чи
 * `/etc/x` нікуди за межі [destDir] потрапити не можуть: шлях з архіву для файлової
 * системи не використовується взагалі (zip-slip неможливий за конструкцією).
 *
 * Імена в старих архівах із кирилицею часто в cp866 без прапорця UTF-8; спершу
 * пробуємо UTF-8, і якщо з'являються биті імена — розпаковуємо заново в IBM866,
 * далі windows-1251. Часткові результати невдалої спроби видаляються.
 *
 * @param freeSpace скільки байтів зараз вільно; перевіряється під час запису.
 * @param keepFreeBytes запас, який не можна з'їсти.
 * @param isAudio чи це аудіо за іменем (розширення).
 * @param skipPath службовий шлях, який не розпаковуємо (див. `AudioImporter.isJunkPath`).
 * @param checkCancelled кидає виняток скасування, якщо імпорт скасовано.
 */
class ZipBookExtractor(
    private val destDir: File,
    private val freeSpace: () -> Long,
    private val keepFreeBytes: Long,
    private val isAudio: (String) -> Boolean,
    /** Службові шляхи (`__MACOSX/…`, `._файл`, `.trashed-…`), які пропускаємо, хоч би яке в них розширення. */
    private val skipPath: (String) -> Boolean = { false },
    private val checkCancelled: () -> Unit = {},
    private val onEntry: (String) -> Unit = {},
) {

    private class BadNames : Exception()

    fun extract(open: () -> InputStream?): List<ExtractedAudio> {
        val charsets = listOf(Charsets.UTF_8, charset("IBM866"), charset("windows-1251"))
        var last: Throwable? = null
        for (cs in charsets) {
            try {
                return extractWith(open, cs)
            } catch (_: BadNames) {
                continue
            } catch (e: IllegalArgumentException) {
                last = e // «MALFORMED» у назві запису: пробуємо наступне кодування
                continue
            }
        }
        throw ZipUnreadableException(last)
    }

    private fun extractWith(open: () -> InputStream?, charset: Charset): List<ExtractedAudio> {
        val written = ArrayList<ExtractedAudio>()
        try {
            val raw = open() ?: throw ZipUnreadableException(null)
            ZipInputStream(raw.buffered(), charset).use { zip ->
                destDir.mkdirs()
                var total = 0L
                var entries = 0
                while (true) {
                    checkCancelled()
                    val entry = try {
                        zip.nextEntry
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: IOException) {
                        throw ZipUnreadableException(e)
                    } ?: break
                    if (++entries > MAX_ENTRIES) throw ZipUnreadableException(null)
                    val path = entry.name.replace('\\', '/')
                    if (path.contains('�')) throw BadNames()
                    if (entry.isDirectory || skipPath(path) || !isAudio(path.substringAfterLast('/'))) continue

                    onEntry(path)
                    val target = File(destDir, "${UUID.randomUUID()}_${safeName(path)}")
                    var failed = true
                    try {
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var sinceCheck = 0L
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                                sinceCheck += n
                                if (sinceCheck >= SPACE_CHECK_EVERY) {
                                    sinceCheck = 0
                                    checkCancelled()
                                    if (freeSpace() < keepFreeBytes || total > MAX_TOTAL_BYTES) throw ZipOutOfSpaceException()
                                }
                            }
                        }
                        failed = false
                    } catch (e: IOException) {
                        // Диск заповнився саме під час запису: це «замало місця», а не «архів битий».
                        if (looksLikeNoSpace(e)) throw ZipOutOfSpaceException()
                        throw e
                    } finally {
                        if (failed) target.delete()
                    }
                    written += ExtractedAudio(path, target)
                }
            }
            if (written.isEmpty()) throw ZipHasNoAudioException()
            return written
        } catch (e: Throwable) {
            written.forEach { it.file.delete() }
            if (e is IOException && e !is ZipHasNoAudioException && e !is ZipOutOfSpaceException && e !is ZipUnreadableException) {
                throw ZipUnreadableException(e)
            }
            throw e
        }
    }

    private fun looksLikeNoSpace(e: IOException): Boolean {
        val m = e.message.orEmpty()
        return m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
    }

    /** Основа імені без шляху й небезпечних символів, з розширенням; довжину обмежуємо. */
    private fun safeName(path: String): String {
        val base = path.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
        val stem = base.substringBeforeLast('.')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .take(60)
            .ifBlank { "audio" }
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }

    companion object {
        const val MAX_ENTRIES = 5_000
        private const val SPACE_CHECK_EVERY = 8L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024
    }
}
