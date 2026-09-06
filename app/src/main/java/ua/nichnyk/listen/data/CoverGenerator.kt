package ua.nichnyk.listen.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.R
import java.io.File
import kotlin.math.abs

class CoverGenerator(private val context: Context) {
    private val palettes = listOf(
        intArrayOf(0xFF3B1F2B.toInt(), 0xFF8C2F39.toInt()),
        intArrayOf(0xFF1B3A2F.toInt(), 0xFF2F6B4F.toInt()),
        intArrayOf(0xFF1E2D4A.toInt(), 0xFF3D5A80.toInt()),
        intArrayOf(0xFF4A2C14.toInt(), 0xFF8A5A2B.toInt()),
        intArrayOf(0xFF2C2140.toInt(), 0xFF5C3D7A.toInt()),
        intArrayOf(0xFF1A3F4A.toInt(), 0xFF2F6F78.toInt()),
        intArrayOf(0xFF3A1F14.toInt(), 0xFF7A3A28.toInt()),
        intArrayOf(0xFF243018.toInt(), 0xFF4E6A32.toInt()),
    )

    fun fromEmbedded(id: String, bytes: ByteArray): String? {
        return runCatching {
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "$id.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        }.onFailure { AppLog.w("CoverGenerator.fromEmbedded", it) }.getOrNull()
    }

    /**
     * Чи намальована ця обкладинка нами (а не витягнута з тегів файла).
     *
     * Потрібно перейменуванню книги: намальована обкладинка несе першу літеру назви
     * й підпис автора, тож після перейменування показувала б стару книгу. Обкладинку
     * з тегів чіпати не можна — вона від видавця й до назви стосунку не має.
     */
    fun isGenerated(path: String?): Boolean {
        path ?: return false
        val file = File(path)
        return file.extension.equals("png", ignoreCase = true) && file.parentFile?.name == "covers"
    }

    /**
     * Кожен виклик пише **новий** файл.
     *
     * Стабільне імʼя `<id>.png` було б зручнішим, але декодовані обкладинки лежать
     * у LruCache із ключем-шляхом: перемальована під тим самим шляхом картинка
     * лишалася б у кеші старою до перезапуску процесу.
     */
    fun generate(id: String, title: String, author: String): String {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "$id-${System.currentTimeMillis()}.png")
        val bmp = createBitmap(SIZE, SIZE)
        val canvas = Canvas(bmp)
        val pair = palettes[(id.hashCode() and 0x7FFFFFFF) % palettes.size]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
            pair[0], pair[1], Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)
        paint.shader = null

        paint.color = 0x22F4B942
        paint.strokeWidth = 3f
        paint.style = Paint.Style.STROKE
        var y = 48f
        while (y < SIZE) {
            canvas.drawLine(0f, y, SIZE.toFloat(), y, paint)
            y += 28f
        }

        paint.style = Paint.Style.STROKE
        paint.color = 0xCCF4B942.toInt()
        paint.strokeWidth = 8f
        val inset = 36f
        canvas.drawRoundRect(RectF(inset, inset, SIZE - inset, SIZE - inset), 18f, 18f, paint)

        val serif = ResourcesCompat.getFont(context, R.font.cormorant_semibold) ?: Typeface.SERIF
        val sans = ResourcesCompat.getFont(context, R.font.plex_medium) ?: Typeface.SANS_SERIF
        val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "B"
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF4B942.toInt()
        paint.typeface = serif
        paint.textSize = 280f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(letter, SIZE / 2f, SIZE / 2f + 90f, paint)

        paint.typeface = serif
        paint.textSize = 52f
        paint.color = 0xFFF3E6C9.toInt()
        val titleLine = title.take(28)
        canvas.drawText(titleLine, SIZE / 2f, SIZE - 140f, paint)

        paint.typeface = sans
        paint.textSize = 32f
        paint.color = 0xCCF3E6C9.toInt()
        paint.letterSpacing = 0.12f
        canvas.drawText(author.take(32).uppercase(), SIZE / 2f, SIZE - 86f, paint)

        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
        bmp.recycle()
        return file.absolutePath
    }

    fun delete(path: String?) {
        path ?: return
        runCatching { File(path).delete() }
    }

    private companion object {
        const val SIZE = 800
    }
}
