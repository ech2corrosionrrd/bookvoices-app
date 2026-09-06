package ua.nichnyk.listen.baseline

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Записує, які класи й методи знадобилися застосунку під час запуску й перших
 * переходів. ART компілює їх наперед, замість інтерпретувати при першому старті.
 *
 * Два окремі збори, а не один:
 *  - [startup] позначений `includeInStartupProfile` і потрапляє ще й у
 *    `startup-prof.txt`, за яким R8 розкладає класи в dex так, щоб потрібні для
 *    старту лежали поруч. Він має лишатися **вузьким** — інакше «профіль запуску»
 *    перестає бути про запуск;
 *  - [journeys] — переходи екранами. Вони теж варті випередної компіляції, але
 *    до розкладки dex стосунку не мають.
 *
 * Профіль лягає в `app/src/release/generated/baselineProfiles/` і його треба комітити:
 * генерація потребує пристрою, і звичайна збірка не має від нього залежати.
 *
 * Запуск: `gradlew.bat :app:generateBaselineProfile`
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Тільки холодний старт до першого кадру полиці. */
    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        grantNotifications()
        pressHome()
        startActivityAndWait()
    }

    /** Полиця, закладки, налаштування — те, куди користувач іде одразу після запуску. */
    @Test
    fun journeys() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = false,
    ) {
        grantNotifications()
        pressHome()
        startActivityAndWait()

        device.waitForIdle()
        device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1f)
        device.waitForIdle()

        openTab("bookmarks")
        openTab("settings")
        openTab("library")
    }

    /**
     * Знімає системний діалог дозволу на сповіщення.
     *
     * На API 33+ MainActivity просить POST_NOTIFICATIONS одразу після старту, і на
     * свіжій установці діалог перекриває весь інтерфейс: нижня навігація стає
     * недосяжною, і в профіль потрапляє лише запуск. На API 30 такого діалогу немає —
     * саме тому помилка не відтворювалася на старішому емуляторі.
     */
    private fun grantNotifications() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant $PACKAGE_NAME android.permission.POST_NOTIFICATIONS")
                .close()
        }
    }

    /**
     * Вкладки шукаються за testTag, а не за підписом.
     *
     * Перша версія цього генератора клікала по локалізованому тексту — і мовчки
     * нічого не знаходила: у профіль потрапив лише запуск, а обидва інші екрани
     * лишилися нескомпільованими. Тому тут assert, а не мʼякий вихід: генератор,
     * який «успішно» записав половину профілю, гірший за той, що впав.
     */
    private fun openTab(route: String) {
        val tag = "tab_$route"
        val tab = device.wait(Until.findObject(By.res(tag)), TIMEOUT_MS)
        assertNotNull("не знайдено вкладку $tag", tab)
        tab.click()
        device.waitForIdle()
        device.wait(Until.findObject(By.res(tag).selected(true)), TIMEOUT_MS)
    }

    private companion object {
        const val PACKAGE_NAME = "app.bookvoices"
        const val TIMEOUT_MS = 5_000L
    }
}
