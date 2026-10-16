package ua.nichnyk.listen.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException

/**
 * Віддає обкладинки іншим процесам — Android Auto, шторці, екрану блокування.
 *
 * `MediaMetadata.artworkUri` з `file:///data/user/0/…/files/covers/…` читає **інший**
 * процес, а приватне сховище застосунку йому недоступне: у логу це
 * `FileNotFoundException … open failed: EACCES`, на екрані Auto — рядки списку без
 * обкладинок (знайдено прогоном на Desktop Head Unit 24 вересня 2026). Екран
 * відтворення обкладинку мав, бо приходить іншим шляхом.
 *
 * Провайдер експортований, тож віддає **рівно одне**: файл із `filesDir/covers` за
 * іменем, тільки на читання. Ні обходу каталогів, ні переліку, ні запису. Імена —
 * `<id книги>.jpg` і `<id>-<мітка>.png`, вгадати їх не можна, а сама обкладинка — не
 * секрет: її бачить будь-який користувач Auto.
 */
class CoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Обкладинки лише для читання")
        val name = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException(uri.toString())
        val dir = File(context!!.filesDir, COVERS_DIR)
        val file = CoverUris.resolve(dir, name) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    internal companion object {
        const val COVERS_DIR = "covers"
    }
}

/** Адреси обкладинок для `MediaMetadata.artworkUri` і чиста, перевірювана логіка провайдера. */
object CoverUris {

    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun authority(context: Context): String = "${context.packageName}.covers"

    /**
     * `content://…covers/<ім'я>` для обкладинки з `filesDir/covers`; для будь-якого
     * іншого шляху — як і раніше `file://` (нічого гіршого, ніж було, не виходить).
     */
    fun artwork(context: Context, coverPath: String?): Uri? {
        coverPath ?: return null
        val file = File(coverPath)
        val dir = File(context.filesDir, CoverProvider.COVERS_DIR)
        val name = file.name
        if (isSafeName(name) && file.parentFile?.canonicalPath == dir.canonicalPath) {
            return Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(name)
                .build()
        }
        return file.toUri()
    }

    fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name) && name != "." && name != ".."

    /**
     * Файл обкладинки за іменем або `null`. Канонічний шлях має лежати прямо в `dir`:
     * символьне посилання чи `..` не вивели б за межі теки, навіть якщо ім'я пройшло
     * перевірку символів.
     */
    fun resolve(dir: File, name: String): File? {
        if (!isSafeName(name)) return null
        val file = File(dir, name).canonicalFile
        if (file.parentFile != dir.canonicalFile) return null
        return file.takeIf { it.isFile }
    }
}
