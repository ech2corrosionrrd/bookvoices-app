package ua.nichnyk.listen

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.ui.ReportProblem

/** «Повідомити про проблему»: адреса Issue й текст листа. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ReportProblemTest {

    @Test
    fun issueUriEncodesCyrillicSpacesAndNewlines() {
        val uri = ReportProblem.issueUri("Проблема в BookVoices", "Що сталося:\n\nAndroid 13 & більше")
        assertEquals("github.com", uri.host)
        assertEquals("/ech2corrosionrrd/bookvoices-app/issues/new", uri.path)
        // Uri сам декодує параметри — назад має вийти рівно те, що поклали.
        assertEquals("Проблема в BookVoices", uri.getQueryParameter("title"))
        assertEquals("Що сталося:\n\nAndroid 13 & більше", uri.getQueryParameter("body"))
        assertTrue("пробіли — %20, а не +", "+" !in uri.toString())
    }

    @Test
    fun emailBodyKeepsTemplateFirstAndHeaderBelowSeparator() {
        val body = ReportProblem.emailBody("Що сталося:\n\n", "BookVoices 1.18.0 (11800)\nAndroid 13 (API 33)\n")
        assertTrue(body.startsWith("Що сталося:"))
        assertTrue(body.contains("\n—\nBookVoices 1.18.0 (11800)\nAndroid 13 (API 33)\n"))
    }

    @Test
    fun contactIsThePublishedPrivacyEmail() {
        assertEquals("ech2corrosionrrd@gmail.com", ReportProblem.CONTACT_EMAIL)
    }

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun emailOpensOnlyMailAppsWithAddressSubjectTemplateAndLog() {
        val log = Uri.parse("content://test.authority/bookvoices-log.txt")
        val send = ReportProblem.composeEmail(app, log)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("mailto", send.selector?.data?.scheme)
        assertArrayEquals(arrayOf(ReportProblem.CONTACT_EMAIL), send.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertTrue(send.getStringExtra(Intent.EXTRA_SUBJECT)!!.startsWith("BookVoices "))
        val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.contains(BuildConfig.VERSION_NAME))
        @Suppress("DEPRECATION")
        assertEquals(log, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun emailWithoutLogStillHasTextAndNoAttachment() {
        val send = ReportProblem.composeEmail(app, null)
        @Suppress("DEPRECATION")
        assertEquals(null, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertNotNull(send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun mailAppDetectedOnlyWhenOneHandlesMailto() {
        assertFalse(ReportProblem.hasMailApp(app))
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply { packageName = "mail.app"; name = "mail.app.Compose" }
            },
        )
        assertTrue(ReportProblem.hasMailApp(app))
    }

    @Test
    fun singleMailAppGetsExplicitIntentWithoutSelector() {
        // MIUI: ACTION_SEND із selector `mailto:` не розв'язується — лише явний пакет.
        val send = ReportProblem.composeEmail(app, Uri.parse("content://test.authority/log.txt"))
        val launch = ReportProblem.launchIntent(send, listOf("mail.app"))!!
        assertEquals(Intent.ACTION_SEND, launch.action)
        assertEquals("mail.app", launch.`package`)
        assertEquals(null, launch.selector)
        assertTrue(launch.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(launch.clipData)
    }

    @Test
    fun severalMailAppsGetChooserWithEachAppExplicit() {
        val send = ReportProblem.composeEmail(app, null)
        val launch = ReportProblem.launchIntent(send, listOf("a.mail", "b.mail", "c.mail"))!!
        assertEquals(Intent.ACTION_CHOOSER, launch.action)
        @Suppress("DEPRECATION")
        val first = launch.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("a.mail", first.`package`)
        @Suppress("DEPRECATION")
        val rest = launch.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { (it as Intent).`package` }
        assertEquals(listOf("b.mail", "c.mail"), rest)
    }

    @Test
    fun noMailAppsNoIntent() {
        assertEquals(null, ReportProblem.launchIntent(ReportProblem.composeEmail(app, null), emptyList()))
    }

    @Test
    fun emailOpensTheOnlyMailAppDirectly() = runTest {
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply { packageName = "mail.app"; name = "mail.app.Compose" }
            },
        )
        assertTrue(ReportProblem.byEmail(app))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, started.action)
        assertEquals("mail.app", started.`package`)
    }

    @Test
    fun emailReportsFalseWhenNoMailApp() = runTest {
        assertFalse(ReportProblem.byEmail(app))
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }
}
