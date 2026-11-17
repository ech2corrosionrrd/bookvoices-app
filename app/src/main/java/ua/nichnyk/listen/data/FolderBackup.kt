package ua.nichnyk.listen.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.ListenApp

/**
 * Щоденна автокопія в теку, яку вибрав слухач: та сама копія, що й ручний
 * «Резервна копія», лише без натискань і без сервера.
 *
 * Навіщо поряд із WebDAV: більшість людей сервера не має, а прогрес, закладки й
 * мітки живуть лише в базі застосунку. Втратив телефон чи перевстановив — усе
 * пропало. Тека може бути на картці пам'яті або в папці, яку синхронізує
 * Google Drive, Syncthing чи інший застосунок, — це вже вибір людини.
 *
 * Один файл на день (`bookvoices-backup-РРРР-ММ-ДД.json`, повторний запис того
 * ж дня перезаписує його) і [KEEP] останніх днів. Видаляються лише файли з
 * нашим шаблоном імені — чуже в теці не чіпаємо.
 */
object FolderBackup {

    /** Скільки денних копій тримати. Тиждень — досить, щоб відкотитися від невдалого дня. */
    const val KEEP = 7

    private val NAME = Regex("""^bookvoices-backup-(\d{4}-\d{2}-\d{2})\.json$""")

    fun fileName(date: LocalDate): String = "bookvoices-backup-$date.json"

    /** Наші старі копії, які треба прибрати: усе понад [keep] найсвіжіших. Чужі імена — ні. */
    fun filesToPrune(names: List<String>, keep: Int = KEEP): List<String> =
        names.mapNotNull { n -> NAME.matchEntire(n)?.let { n to it.groupValues[1] } }
            .sortedByDescending { it.second }
            .drop(keep)
            .map { it.first }

    /**
     * Постійний дозвіл на читання й запис у теку. Окремо від імпорту книг:
     * там потрібне лише читання, а тут ще й запис.
     */
    fun takePermission(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.onFailure { AppLog.w("FolderBackup: дозвіл на теку не видано", it) }.getOrDefault(false)

    /**
     * Звільняє постійний дозвіл на попередню теку автокопії. Якщо ця сама тека ще
     * потрібна для читання книг ([keepRead] = true), знімаємо лише прапорець запису.
     */
    fun releasePermission(context: Context, treeUri: String, keepRead: Boolean = false) {
        if (treeUri.isBlank()) return
        val flags = if (keepRead) {
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(treeUri.toUri(), flags)
        }
    }

    /** Ім'я теки для екрана налаштувань; null — теки вже немає або доступ втрачено. */
    fun folderName(context: Context, treeUri: String): String? = runCatching {
        DocumentFile.fromTreeUri(context, treeUri.toUri())?.takeIf { it.canWrite() }?.name
    }.getOrNull()

    /**
     * Записи по черзі. Без цього перший запуск щоденної задачі й разова копія після
     * вибору теки йшли одночасно: обидві не знаходили файла дня й обидві його
     * створювали — провайдер називав другий «… (1).json» (бачено на емуляторі).
     */
    private val writeLock = Mutex()

    /** Записує копію за [date] і прибирає старі. Виключень не кидає. */
    suspend fun write(
        context: Context,
        treeUri: String,
        json: String,
        date: LocalDate = LocalDate.now(),
    ): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock { writeLocked(context, treeUri, json, date) }
    }

    private fun writeLocked(context: Context, treeUri: String, json: String, date: LocalDate): Boolean =
        runCatching {
            val dir = DocumentFile.fromTreeUri(context, treeUri.toUri())
                ?.takeIf { it.isDirectory && it.canWrite() }
                ?: error("тека недоступна")
            val existingFiles = dir.listFiles()
            val name = fileName(date)
            val file = existingFiles.firstOrNull { it.name == name }
                ?: dir.createFile("application/json", name)
                ?: error("не вдалося створити файл")
            val bytes = json.toByteArray(Charsets.UTF_8)
            // Перезапис зі скороченням, інакше коротша копія лишила б хвіст попередньої.
            context.contentResolver.openForOverwrite(file.uri).use { out ->
                out.write(bytes)
                out.flush()
            }
            val names = (existingFiles.mapNotNull { it.name } + name).distinct()
            val toPrune = filesToPrune(names).toSet()
            if (toPrune.isNotEmpty()) {
                existingFiles.filter { it.name in toPrune }.forEach { it.delete() }
            }
            true
        }.onFailure { AppLog.w("FolderBackup: копію не записано", it) }.getOrDefault(false)

    /** Повний цикл: експорт, запис, результат у налаштування. Для воркера й кнопки «Зберегти зараз». */
    suspend fun runOnce(app: ListenApp): Boolean {
        val prefs = app.container.prefs
        val tree = prefs.settings.first().autoBackupTreeUri
        if (tree.isBlank()) return true
        val ok = runCatching { app.container.repo.exportBackupJson() }
            .onFailure { AppLog.w("FolderBackup: експорт не вдався", it) }
            .getOrNull()
            ?.let { write(app, tree, it) } ?: false
        prefs.setAutoBackupResult(ok, System.currentTimeMillis())
        return ok
    }
}

class FolderBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? ListenApp ?: return Result.failure()
        if (FolderBackup.runOnce(app)) return Result.success()
        // Теку могли тимчасово відмонтувати (картка пам'яті) — спробуємо ще кілька разів.
        return if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

object FolderBackupScheduler {
    private const val UNIQUE = "bookvoices_folder_backup"
    private const val UNIQUE_NOW = "bookvoices_folder_backup_now"

    fun apply(context: Context, enabled: Boolean) {
        runCatching {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                wm.cancelUniqueWork(UNIQUE)
                return
            }
            val request = PeriodicWorkRequestBuilder<FolderBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            // KEEP: перезапуск застосунку не має відсувати наступну копію ще на добу.
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { AppLog.w("FolderBackupScheduler: не вдалося запланувати", it) }
    }

    /** Одразу після вибору теки: перша копія не має чекати добу. */
    fun runSoon(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<FolderBackupWorker>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { AppLog.w("FolderBackupScheduler: не вдалося запустити копію", it) }
    }
}
