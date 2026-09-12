plugins {
    alias(libs.plugins.android.application) apply false
    // com.android.test приходить із того самого артефакту AGP, що й application.
    // Оголошуємо версію тут один раз, інакше Gradle відмовляється перевіряти
    // сумісність: «плагін уже на classpath з невідомою версією».
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// =============================================================================
//  Версія обох застосунків — з тега релізу
//
//  Раніше versionCode і versionName були зашиті в модулях, а APK у релізі
//  іменувалися за тегом. Тег v1.2.0 давав файл bookvoices-cloud-1.2.0.apk, усередині
//  якого стояло versionName 0.1.0 і versionCode 1: зовнішня версія розходилася з
//  внутрішньою, а оновлення поверх попередньої збірки не працювало — для Android
//  це той самий versionCode, тобто не оновлення.
//
//  Тепер джерело одне — тег. Обидва застосунки нумеруються ним разом: тег у
//  репозиторії один, реліз спільний, і різні числа всередині означали б лише
//  ще один спосіб розійтися.
// =============================================================================

/**
 * Версія для збірок без тега: локальних і будь-яких з гілки.
 *
 * Суфікс «-dev» навмисний: у списку застосунків має бути видно, що це не реліз.
 * На versionCode він не впливає — той рахується з числової частини.
 *
 * Число тут — **наступна** версія, а не остання випущена. Після v1.9.0 воно
 * лишилося «1.9.0-dev», тобто versionCode 10900 проти 11000 у вже випущеній
 * v1.10.0: збірка з гілки переставала ставитися поверх релізу, бо для Android
 * це відкат версії. Тримає це в порядку крок «devVersion випереджає тег» у CI —
 * PR із простроченим числом туди не проходить.
 */
val devVersion = "1.13.0-dev"

/** «v1.2.0» → «1.2.0». Усе, що не схоже на тег версії, — null. */
fun versionFromTag(tag: String?): String? = tag
    ?.trim()
    ?.takeIf { it.matches(Regex("""^v\d+\.\d+\.\d+.*$""")) }
    ?.removePrefix("v")

// Тег можна передати явно (-PreleaseTag=v1.2.0). Якщо його немає — беремо з
// оточення GitHub Actions, але тільки коли прогін справді на тегу: інакше гілка
// з іменем на «v» удавала б реліз.
val requestedTag = (findProperty("releaseTag") as String?)?.trim().orEmpty()
val actionsTag = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }

val appVersionName: String = if (requestedTag.isNotEmpty()) {
    // Явно попросили зібрати реліз — мовчки підсунути dev-версію було б гірше,
    // ніж не зібратися взагалі.
    versionFromTag(requestedTag)
        ?: throw GradleException(
            "releaseTag=«$requestedTag» не схожий на vX.Y.Z — версію APK з нього не зібрати.",
        )
} else {
    versionFromTag(actionsTag) ?: devVersion
}

/**
 * X.Y.Z → XXYYZZ. Монотонно зростає разом із версією, тож Android бачить
 * оновлення там, де воно є. Мінор і патч мають лишатися меншими за 100 —
 * інакше 1.2.100 і 1.3.0 дали б однакове число.
 */
val appVersionCode: Int = run {
    val parts = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersionName)
        ?: throw GradleException("Версія «$appVersionName» не схожа на X.Y.Z.")
    val (major, minor, patch) = parts.destructured
    if (minor.toInt() >= 100 || patch.toInt() >= 100) {
        throw GradleException(
            "Версія «$appVersionName»: мінор і патч мають бути меншими за 100, " +
                "інакше versionCode перестане зростати монотонно.",
        )
    }
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

extra["appVersionName"] = appVersionName
extra["appVersionCode"] = appVersionCode
