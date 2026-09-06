package ua.nichnyk.listen.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import ua.nichnyk.listen.R

/**
 * Помилки WebDAV як типи, а не як текст.
 *
 * Раніше причину визначали пошуком підрядка у повідомленні винятку
 * (`msg.contains("ще немає")`), тож переклад інтерфейсу ламав логіку синхронізації,
 * а самі повідомлення були захардкожені українською.
 */
sealed class WebDavError(val messageRes: Int) : Exception() {

    /** Basic auth без TLS передав би пароль відкритим текстом. */
    data object InsecureUrl : WebDavError(R.string.webdav_err_insecure)

    /** 401/403 — постійна помилка: повторювати запит без втручання користувача марно. */
    data object Auth : WebDavError(R.string.webdav_err_auth)

    /** 404 на адресу теки — шлях у налаштуваннях хибний. */
    data object FolderNotFound : WebDavError(R.string.webdav_err_folder)

    /** 404 на сам файл бекапу — нормальний стан першої синхронізації. */
    data object BackupAbsent : WebDavError(R.string.webdav_err_absent)

    /**
     * 429 — сервер тимчасово відхиляє спроби. Навмисно НЕ permanent: повтор
     * пізніше має сенс, тож автосинхронізація сама вернеться до цього.
     */
    data object TooManyRequests : WebDavError(R.string.webdav_err_rate_limit)

    data class Http(val code: Int, val serverMessage: String) : WebDavError(R.string.webdav_err_http)

    data class Network(override val cause: Throwable) : WebDavError(R.string.webdav_err_network)

    /** true — помилку не виправить повтор запиту (потрібна дія користувача). */
    val isPermanent: Boolean
        get() = this is Auth || this is InsecureUrl || this is FolderNotFound
}

object WebDavClient {

    private const val DEFAULT_FILE = "bookvoices_sync.json"

    suspend fun testConnection(serverUrl: String, username: String, pass: String): Result<Unit> =
        io {
            val normalizedUrl = normalizeUrl(serverUrl)
            // HttpURLConnection не приймає PROPFIND, тому перевіряємо наявність шляху
            // через HEAD — цього достатньо, щоб відрізнити «немає теки» від «немає доступу».
            val connection = createConnection(normalizedUrl, "HEAD", username, pass, timeoutMs = 8_000)
            val code = connection.responseCode
            // responseMessage треба прочитати ДО disconnect(), інакше він порожній.
            val message = runCatching { connection.responseMessage }.getOrNull().orEmpty()
            connection.disconnect()
            describeTestFailure(code, message)?.let { throw it }
        }

    suspend fun uploadFile(
        serverUrl: String,
        username: String,
        pass: String,
        fileName: String = DEFAULT_FILE,
        content: String,
    ): Result<Unit> = io {
        val connection = createConnection(
            targetUrl(serverUrl, fileName), "PUT", username, pass, timeoutMs = 12_000,
        )
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }

        val code = connection.responseCode
        val message = runCatching { connection.responseMessage }.getOrNull().orEmpty()
        connection.disconnect()
        when {
            code in 200..299 -> Unit
            code == 401 || code == 403 -> throw WebDavError.Auth
            code == 404 -> throw WebDavError.FolderNotFound
            else -> throw WebDavError.Http(code, message)
        }
    }

    suspend fun downloadFile(
        serverUrl: String,
        username: String,
        pass: String,
        fileName: String = DEFAULT_FILE,
    ): Result<String> = io {
        val connection = createConnection(
            targetUrl(serverUrl, fileName), "GET", username, pass, timeoutMs = 12_000,
        )
        val code = connection.responseCode
        if (code !in 200..299) {
            val message = runCatching { connection.responseMessage }.getOrNull().orEmpty()
            connection.disconnect()
            when (code) {
                401, 403 -> throw WebDavError.Auth
                404 -> throw WebDavError.BackupAbsent
                else -> throw WebDavError.Http(code, message)
            }
        }
        BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use {
            it.readText()
        }.also { connection.disconnect() }
    }

    fun isAbsentBackup(error: Throwable?): Boolean = error is WebDavError.BackupAbsent

    /**
     * Повертає помилку або null, якщо відповідь означає успіх.
     * Винесено окремо, щоб покрити тестами: раніше 404 помилково вважався успіхом.
     */
    fun describeTestFailure(code: Int, serverMessage: String = ""): WebDavError? = when {
        code in 200..299 || code == 207 -> null
        // Частина серверів не відповідає на HEAD для колекції — це не помилка налаштувань.
        code == 405 || code == 501 -> null
        code == 401 || code == 403 -> WebDavError.Auth
        code == 404 -> WebDavError.FolderNotFound
        // Голе «429» користувач читає як збій і тисне повтор — а кожен повтор
        // продовжує блокування. Пояснюємо, що треба саме зачекати.
        code == 429 -> WebDavError.TooManyRequests
        else -> WebDavError.Http(code, serverMessage)
    }

    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        // Basic auth без TLS = пароль відкритим текстом, тож cleartext заборонений
        // у network_security_config. Пояснюємо це до того, як полетить IOException.
        if (trimmed.startsWith("http://", ignoreCase = true)) throw WebDavError.InsecureUrl
        if (!trimmed.startsWith("https://", ignoreCase = true)) return "https://$trimmed"
        return trimmed
    }

    fun targetUrl(serverUrl: String, fileName: String = DEFAULT_FILE): String {
        val base = normalizeUrl(serverUrl)
        return if (base.endsWith("/")) "$base$fileName" else "$base/$fileName"
    }

    /**
     * Будь-яка мережева проблема повертається як [WebDavError.Network]; типи помилок,
     * кинуті всередині, проходять наскрізь. Викликач ніколи не бачить сирий IOException.
     */
    private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: WebDavError) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(WebDavError.Network(e))
        } catch (e: RuntimeException) {
            Result.failure(WebDavError.Network(e))
        }
    }

    private fun createConnection(
        urlString: String,
        method: String,
        username: String,
        pass: String,
        timeoutMs: Int,
    ): HttpURLConnection {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        if (username.isNotBlank() || pass.isNotBlank()) {
            // Те саме, що робить normalizeUrl з адресою: пробіл на краю — артефакт
            // вставки з буфера, а не вибір користувача. Внутрішні пробіли лишаємо:
            // у власному WebDAV пароль цілком може їх містити.
            val auth = "${username.trim()}:${pass.trim()}"
            val encoded = Base64.encodeToString(auth.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }
        return conn
    }
}
