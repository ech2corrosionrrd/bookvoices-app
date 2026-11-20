package ua.nichnyk.listen

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * «Поділитися журналом» — локальна діагностика без мережі.
 *
 * [AppLog] пише лише в logcat, а logcat без `adb` бачить тільки сам застосунок.
 * Тут ми читаємо **власні** записи (тег `BookVoices` і падіння `AndroidRuntime`
 * нашого ж UID — чужих застосунків система не віддає) і складаємо текст, яким
 * слухач сам вирішує, з ким поділитися. Нічого не відправляється автоматично.
 *
 * Системний буфер logcat переживає смерть процесу, тож стек останнього падіння
 * теж потрапляє у звіт — окремого файла для крешів не треба.
 *
 * [AppLog] обіцяє не писати шляхи й URI, але стек виключення від бібліотеки
 * (`FileNotFoundException: /storage/…/Назва книги.mp3`) цю обіцянку не знає.
 * Тому текст проходить [redact] перед тим, як піти з телефона.
 */
object DiagnosticLog {

    /** Скільки останніх рядків брати: досить для сесії, мало для месенджера. */
    const val MAX_LINES = 1500

    // Назви книг містять пробіли, тож `\S+` лишив би хвіст назви. Шлях триває до
    // роздільника повідомлення (`: `), закритої дужки або кінця рядка. Дужка кінчає
    // шлях, лише якщо далі до `:` чи кінця рядка вже немає `/` або `%2F`: інакше це
    // дужка в назві теки («Дюна (2021)/01.mp3»), і назва файла лишалася б у звіті.
    private const val PATH_TAIL = """[^\n]*?(?=: |\)(?![^:\n]*(?:/|%2[Ff]))|[ \t]*$)"""
    private val pathPatterns = listOf(
        Regex("""(content|file)://$PATH_TAIL""", RegexOption.MULTILINE),
        Regex("""(/storage|/sdcard|/mnt|/data/user|/data/data)/$PATH_TAIL""", RegexOption.MULTILINE),
        // Адреса WebDAV: у шляху Nextcloud — логін (`/dav/files/<логін>/`).
        Regex("""https?://$PATH_TAIL""", RegexOption.MULTILINE),
        // Id документа SAF без `content://`: системні повідомлення на кшталт «Failed to
        // determine if primary:Книги/Назва/01.mp3 is child of …». Том — `primary`, `home`
        // або id картки пам'яті `1A2B-3C4D`.
        Regex("""(?<![\w/])(primary|home|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}):(?=\S)$PATH_TAIL""", RegexOption.MULTILINE),
    )

    /** Прибирає URI й шляхи до файлів, лишаючи тип помилки й місце. */
    fun redact(text: String): String =
        pathPatterns.fold(text) { acc, re -> re.replace(acc, "<path>") }

    /** Останні [max] рядків — початок буфера найменш корисний. */
    fun tail(text: String, max: Int = MAX_LINES): String {
        val lines = text.lines().dropLastWhile { it.isEmpty() }
        return if (lines.size <= max) lines.joinToString("\n")
        else lines.takeLast(max).joinToString("\n")
    }

    /** Шапка звіту: без ідентифікаторів пристрою, лише те, що пояснює збій. */
    fun header(
        versionName: String,
        versionCode: Int,
        sdkInt: Int,
        release: String,
        manufacturer: String,
        model: String,
        locale: Locale,
    ): String = buildString {
        appendLine("BookVoices $versionName ($versionCode)")
        appendLine("Android $release (API $sdkInt)")
        appendLine("$manufacturer $model")
        appendLine("Locale ${locale.toLanguageTag()}")
    }

    /** Повний текст звіту: шапка + очищений хвіст журналу. */
    fun compose(header: String, rawLog: String): String = buildString {
        append(header)
        appendLine()
        val body = redact(tail(rawLog)).trim()
        append(body.ifEmpty { "(log is empty)" })
        appendLine()
    }

    /** Читає власний logcat. Без винятків: при збої повертає порожній рядок. */
    suspend fun readOwnLogcat(): String = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "time",
                "BookVoices:V", "AndroidRuntime:E", "*:S",
            ).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            text
        }.onFailure { AppLog.w("DiagnosticLog: logcat недоступний", it) }.getOrDefault("")
    }

    suspend fun build(): String = compose(
        header(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE ?: "?",
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            locale = Locale.getDefault(),
        ),
        readOwnLogcat(),
    )
}
