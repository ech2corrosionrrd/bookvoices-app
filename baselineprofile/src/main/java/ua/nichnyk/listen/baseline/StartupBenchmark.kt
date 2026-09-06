package ua.nichnyk.listen.baseline

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Скільки коштує холодний старт із профілем і без нього.
 *
 * Два прогони того самого сценарію, що відрізняються лише режимом компіляції:
 *  - [startupNoCompilation] — ART інтерпретує байткод, доки не прогріється JIT.
 *    Це найгірший випадок, а не вигаданий: саме так виглядає перший запуск після
 *    встановлення, якщо профілю немає;
 *  - [startupBaselineProfile] — класи з baseline-профілю скомпільовані наперед.
 *    `BaselineProfileMode.Require` навмисно: якщо профіль не потрапив у збірку,
 *    тест має впасти, а не тихо зміряти те саме вдруге.
 *
 * Запуск: `gradlew.bat :baselineprofile:profileGeneratorBenchmarkReleaseAndroidTest`
 *
 * **Цифри з емулятора не є цифрами з телефона.** Абсолютні мілісекунди тут
 * завищені й шумні; сенс має лише різниця між двома режимами, та й та — як
 * порядок величини, а не як обіцянка користувачеві.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = measureStartup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() =
        measureStartup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measureStartup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            // Діалог дозволу на API 33+ перекрив би перший кадр і потрапив
            // у вимір як частина запуску.
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("pm grant $PACKAGE_NAME android.permission.POST_NOTIFICATIONS")
                    .close()
            }
            pressHome()
        },
    ) {
        startActivityAndWait()
    }

    private companion object {
        const val PACKAGE_NAME = "app.bookvoices"

        /** Достатньо, щоб медіана перестала стрибати, і не настільки, щоб прогін тривав годину. */
        const val ITERATIONS = 10
    }
}
