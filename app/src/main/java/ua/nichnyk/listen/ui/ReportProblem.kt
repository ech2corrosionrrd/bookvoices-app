package ua.nichnyk.listen.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.BuildConfig
import ua.nichnyk.listen.DiagnosticLog
import ua.nichnyk.listen.R
import ua.nichnyk.listen.forAppLocale

/**
 * «Повідомити про проблему»: прямий канал від слухача до розробника.
 *
 * Лист — основний шлях: адреса, тема з версією, короткий шаблон і журнал діагностики
 * ([DiagnosticLog], без шляхів і назв книг) уже заповнені, а людина бачить усе в
 * поштовому застосунку й сама натискає «Надіслати». GitHub — для тих, хто не хоче
 * пошти: туди журнал не кладемо (довгий для адреси сторінки), лише версію й пристрій.
 */
object ReportProblem {

    /** Та сама адреса, що в політиці конфіденційності на сторінці Play. */
    const val CONTACT_EMAIL = "ech2corrosionrrd@gmail.com"
    const val ISSUES_URL = "https://github.com/ech2corrosionrrd/bookvoices-app/issues/new"

    /** Сторінка нової Issue з заповненими назвою й текстом. */
    fun issueUri(title: String, body: String): Uri {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "$ISSUES_URL?title=${enc(title)}&body=${enc(body)}".toUri()
    }

    /** Текст листа: шаблон для людини, нижче — технічна шапка, щоб не питати «яка у вас версія». */
    fun emailBody(template: String, header: String): String =
        template.trimEnd() + "\n\n\n—\n" + header.trimEnd() + "\n"

    private fun header(): String = DiagnosticLog.header(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        sdkInt = android.os.Build.VERSION.SDK_INT,
        release = android.os.Build.VERSION.RELEASE ?: "?",
        manufacturer = android.os.Build.MANUFACTURER ?: "?",
        model = android.os.Build.MODEL ?: "?",
        locale = java.util.Locale.getDefault(),
    )

    /**
     * Відкриває поштовий застосунок із готовим листом і журналом у вкладенні.
     * @return false — поштового застосунку немає або щось пішло не так.
     */
    fun hasMailApp(context: Context): Boolean = mailApps(context).isNotEmpty()

    /** Пакети поштових застосунків — ті, що відкривають `mailto:`. */
    fun mailApps(context: Context): List<String> =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_SENDTO, "mailto:".toUri()), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()

    /**
     * Лист, адресований конкретним поштовим застосункам. Не через selector: на MIUI
     * (Redmi, Android 13) ACTION_SEND із selector `mailto:` не знаходить жодного
     * застосунку, а системне вікно вибору показує порожній список. Явний пакет
     * працює скрізь: один застосунок — відкривається одразу, кілька — вікно вибору з ними.
     */
    fun launchIntent(send: Intent, apps: List<String>): Intent? {
        if (apps.isEmpty()) return null
        fun forApp(pkg: String) = Intent(send).apply { selector = null; setPackage(pkg) }
        val first = forApp(apps.first())
        if (apps.size == 1) return first
        return Intent.createChooser(first, null).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, apps.drop(1).map(::forApp).toTypedArray())
        }
    }

    /** Готовий лист без запуску: окремо, щоб склад листа перевірявся тестом. */
    fun composeEmail(context: Context, logUri: Uri?): Intent {
        val res = context.forAppLocale()
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, res.getString(R.string.report_email_subject, BuildConfig.VERSION_NAME))
            putExtra(Intent.EXTRA_TEXT, emailBody(res.getString(R.string.report_email_body), header()))
            if (logUri != null) {
                putExtra(Intent.EXTRA_STREAM, logUri)
                clipData = ClipData.newUri(context.contentResolver, "log", logUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Лише поштові застосунки: ACTION_SEND сам по собі показав би й месенджери,
            // а адреса розробника там нічого не означає.
            selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
        }
    }

    suspend fun byEmail(context: Context): Boolean {
        // Спершу — чи є куди. Журнал збирати й писати на диск без пошти марно.
        val apps = mailApps(context)
        if (apps.isEmpty()) return false
        return runCatching {
            // Журнал не вдався — лист однаково корисний: шапка з версією вже в тексті.
            val logUri = runCatching {
                val log = DiagnosticLog.build()
                withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "backup").apply { mkdirs() }
                    val file = File(dir, "bookvoices-log.txt").apply { writeText(log) }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
            }.onFailure { AppLog.w("ReportProblem: журнал не прикріплено", it) }.getOrNull()
            val intent = launchIntent(composeEmail(context, logUri), apps) ?: return@runCatching false
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure { AppLog.w("ReportProblem: лист не відкрито", it) }.getOrDefault(false)
    }

    /** Відкриває нову Issue в браузері. @return false — браузера немає. */
    fun onGitHub(context: Context): Boolean {
        val res = context.forAppLocale()
        return runCatching {
            val body = emailBody(res.getString(R.string.report_email_body), header())
            val intent = Intent(Intent.ACTION_VIEW, issueUri(res.getString(R.string.report_issue_title), body))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.onFailure { AppLog.w("ReportProblem: GitHub не відкрито", it) }.getOrDefault(false)
    }
}
