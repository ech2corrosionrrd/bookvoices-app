package ua.nichnyk.listen.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.ListenApp

class WebDavSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ListenApp ?: return Result.failure()
        val prefs = app.container.prefs
        val settings = prefs.settings.first()
        if (!settings.webDavAutoSync || settings.webDavServer.isBlank()) {
            return Result.success()
        }
        val password = prefs.getWebDavPassword()
        if (password.isBlank() && settings.webDavUser.isBlank()) {
            return Result.success()
        }

        val cloud = WebDavClient.downloadFile(
            serverUrl = settings.webDavServer,
            username = settings.webDavUser,
            pass = password,
        )
        cloud.fold(
            onSuccess = { json ->
                // Результат обовʼязково читаємо. mergeBackupJson сама не кидає — вона
                // ловить усе всередині й повертає false, — тож зовнішній runCatching
                // тут нічого не давав, а невдале злиття було не відрізнити від
                // вдалого. Далі йшло вивантаження, тобто локальний стан лягав поверх
                // хмарного файла, який щойно не вдалося прочитати: копія другого
                // пристрою знищувалася, а синхронізація виглядала успішною.
                //
                // Тепер такий прогін завершується без вивантаження. retry, а не
                // failure: найчастіша причина — обірване завантаження, і наступна
                // спроба зазвичай проходить. Ручний шлях у SettingsViewModel уже
                // поводився саме так — показував помилку й нічого не вивантажував.
                if (!app.container.repo.mergeBackupJson(json)) {
                    AppLog.w("WebDavSync: файл із хмари не злився — вивантаження скасовано")
                    return Result.retry()
                }
            },
            onFailure = { error ->
                // Відсутній файл бекапу — нормальний стан першої синхронізації, не помилка.
                if (error !is WebDavError.BackupAbsent) {
                    return finish(prefs, error)
                }
            },
        )

        val json = app.container.repo.exportBackupJson()
        val uploaded = WebDavClient.uploadFile(
            serverUrl = settings.webDavServer,
            username = settings.webDavUser,
            pass = password,
            content = json,
        )
        return uploaded.fold(
            onSuccess = {
                prefs.setWebDavLastSyncTime(System.currentTimeMillis())
                Result.success()
            },
            onFailure = { error -> finish(prefs, error) },
        )
    }

    /**
     * Постійні помилки (невірний пароль, хибний шлях, http-адреса) не ретраяться:
     * раніше будь-яка невдача давала Result.retry(), і застосунок годинами стукав
     * у чужий сервер із неправильними даними, ніяк про це не повідомляючи.
     */
    private suspend fun finish(prefs: UserPrefs, error: Throwable): Result {
        val permanent = (error as? WebDavError)?.isPermanent == true
        if (permanent) {
            prefs.setWebDavAuthFailed(true)
            return Result.failure()
        }
        return Result.retry()
    }
}

object WebDavSyncScheduler {
    private const val UNIQUE = "bookvoices_webdav_sync"

    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }
        val request = PeriodicWorkRequestBuilder<WebDavSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
