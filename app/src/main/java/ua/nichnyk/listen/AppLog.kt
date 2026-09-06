package ua.nichnyk.listen

import android.util.Log

/**
 * Тонкий журнал для місць, де помилку свідомо проковтнули.
 *
 * У застосунку близько сотні `runCatching`, і майже кожен із них — правильне рішення:
 * недоступний Keystore, зламаний m4b чи відкликаний дозвіл SAF не мають валити плеєр.
 * Але разом із виключенням зникав і будь-який слід того, що взагалі сталося: у релізі
 * не було способу зрозуміти, чому в конкретного слухача не грає книга.
 *
 * Тут немає ні мережі, ні файлів, ні збору статистики — лише logcat, який видно
 * самому застосунку й `adb logcat`. Навмисно не логуємо URI, шляхи та назви книг:
 * це особисті дані, а для розуміння збою вистачає місця й типу помилки.
 */
object AppLog {

    private const val TAG = "BookVoices"

    /** Проковтнута помилка: працюємо далі, але слід лишається. */
    fun w(where: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, where) else Log.w(TAG, where, error)
    }

    /** Подробиці для розробника — тільки в debug-збірці. */
    fun d(where: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$where: $message")
    }

    /** `runCatching { … }.orLog("де")` — той самий проковт, але помітний. */
    fun <T> Result<T>.orLog(where: String): Result<T> = onFailure { w(where, it) }
}
