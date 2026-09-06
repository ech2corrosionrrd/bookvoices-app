package ua.nichnyk.listen.data

import android.content.Context
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * Мінімальний WebDAV-сервер для тестів: HEAD/GET/PUT, Basic auth, задавані коди помилок.
 *
 * Обов'язково HTTPS: застосунок навмисно відмовляється працювати поверх http,
 * бо пароль ішов би відкритим текстом, і тест має ходити тим самим шляхом,
 * що й реальна синхронізація. Сертифікат самопідписаний і довіра до нього
 * встановлюється лише всередині тестового процесу.
 */
class FakeWebDavServer {

    private val files = ConcurrentHashMap<String, ByteArray>()
    private lateinit var serverSocket: SSLServerSocket
    private var running = false

    var expectedUser: String = "user"
    var expectedPassword: String = "secret"

    /** Код, який сервер поверне замість нормальної обробки (для перевірки помилок). */
    var forcedStatus: Int? = null

    val port: Int get() = serverSocket.localPort
    fun url(path: String = "/dav/"): String = "https://localhost:$port$path"

    /**
     * [assetsContext] — контекст ІНСТРУМЕНТАЦІЇ, а не застосунку: сертифікат
     * лежить в ассетах тестового APK, і через контекст застосунку його не видно.
     */
    fun start(assetsContext: Context) {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            assetsContext.assets.open("webdav-test.p12").use { load(it, PASSWORD) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD) }
            .keyManagers
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers

        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, null) }
        serverSocket = serverContext.serverSocketFactory
            .createServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) as SSLServerSocket

        // Клієнт у цьому ж процесі має довіряти самопідписаному сертифікату.
        // Поза тестом ця довіра нікуди не потрапляє: застосунок працює зі
        // системними центрами сертифікації.
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }
        HttpsURLConnection.setDefaultSSLSocketFactory(clientContext.socketFactory)

        running = true
        thread(isDaemon = true, name = "fake-webdav") {
            while (running) {
                val socket = runCatching { serverSocket.accept() as SSLSocket }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
    }

    fun put(path: String, body: String) {
        files[path] = body.toByteArray(Charsets.UTF_8)
    }

    fun get(path: String): String? = files[path]?.toString(Charsets.UTF_8)

    private fun handle(socket: SSLSocket) {
        socket.use {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            var authorization: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':').trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "authorization" -> authorization = value
                }
            }

            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }

            val out = socket.getOutputStream()
            forcedStatus?.let {
                respond(out, it, "Forced")
                return
            }
            if (!authorized(authorization)) {
                respond(out, 401, "Unauthorized")
                return
            }
            when (method) {
                "HEAD" -> if (path.endsWith("/")) respond(out, 200, "OK") else respond(out, 404, "Not Found")
                "PUT" -> {
                    files[path] = body
                    respond(out, 201, "Created")
                }
                "GET" -> {
                    val stored = files[path]
                    if (stored == null) respond(out, 404, "Not Found") else respond(out, 200, "OK", stored)
                }
                else -> respond(out, 405, "Method Not Allowed")
            }
        }
    }

    private fun authorized(header: String?): Boolean {
        val encoded = header?.removePrefix("Basic ")?.trim() ?: return false
        val decoded = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return false
        return decoded == "$expectedUser:$expectedPassword"
    }

    private fun respond(out: OutputStream, code: Int, message: String, body: ByteArray = ByteArray(0)) {
        val header = buildString {
            append("HTTP/1.1 $code $message\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Content-Type: application/json\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) return buffer.toString("UTF-8").trimEnd('\r')
            buffer.write(b)
        }
    }

    private companion object {
        val PASSWORD = "testpass".toCharArray()
    }
}
