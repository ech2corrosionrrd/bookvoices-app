package ua.nichnyk.listen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.data.SecretStore
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.data.VoicePreset

/**
 * Межа Freemium — там, де вона справді проходить.
 *
 * `UserPrefs.settings` — єдине місце, де вирішується, платна фіча ввімкнена чи ні:
 * значення приходять звідти вже замаскованими, тож PlaybackService і PlayerManager
 * про біллінг не знають нічого. Досі це місце не було покрите жодним тестом —
 * найдорожча логіка в застосунку трималася на уважності при рев'ю.
 *
 * Перевіряємо обидва напрямки. Не лише «без ліцензії платне вимкнене», а й
 * «безкоштовне лишається безкоштовним»: список замкненого має збігатися з тим,
 * що обіцяє paywall, і зайвий замок тут — така сама помилка, як забутий.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class FreemiumBoundaryTest {

    private lateinit var prefs: UserPrefs

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = UserPrefs(context, SecretStore(context))
    }

    /** Усі платні тумблери у ввімкненому стані — далі дивимося, що з них дійде. */
    private suspend fun storeEveryPaidToggleOn() {
        prefs.setSkipSilence(true)
        prefs.setVolumeBoost(true)
        prefs.setVoicePreset(VoicePreset.SPEECH_CLARITY)
        prefs.setPitch(1.15f)
        prefs.setAutoBookmarkBluetooth(true)
    }

    @Test
    fun withoutPro_paidValuesComeBackMasked() = runTest {
        storeEveryPaidToggleOn()
        prefs.setProEntitled(false)

        val s = prefs.settings.first()
        assertFalse("пропуск тиші має бути замкнений", s.skipSilence)
        assertFalse("Volume Boost має бути замкнений", s.volumeBoost)
        assertEquals("пресет еквалайзера має бути замкнений", VoicePreset.OFF, s.voicePreset)
        assertEquals("pitch має повернутися до 1.0", 1f, s.pitch, 0.0001f)
        assertFalse("автозакладка при розриві BT має бути замкнена", s.autoBookmarkBluetooth)
        assertFalse(s.isPro)
    }

    @Test
    fun withPro_paidValuesComeThrough() = runTest {
        storeEveryPaidToggleOn()
        prefs.setProEntitled(true)

        val s = prefs.settings.first()
        assertTrue(s.skipSilence)
        assertTrue(s.volumeBoost)
        assertEquals(VoicePreset.SPEECH_CLARITY, s.voicePreset)
        assertEquals(1.15f, s.pitch, 0.0001f)
        assertTrue(s.autoBookmarkBluetooth)
        assertTrue(s.isPro)
    }

    /**
     * Сценарій відкликаної покупки: ліцензія була, Play її зняв.
     *
     * Саме він колись і не працював — Pro не знімався ніде, і збережені тумблери
     * лишалися ввімкненими назавжди. Тут перевіряємо, що маскування вмикається
     * назад без жодного перезапису самих значень у DataStore.
     */
    @Test
    fun proRevoked_paidValuesGoBackToDefaults() = runTest {
        storeEveryPaidToggleOn()
        prefs.setProEntitled(true)
        assertTrue(prefs.settings.first().skipSilence)

        prefs.setProEntitled(false)

        val s = prefs.settings.first()
        assertFalse(s.skipSilence)
        assertFalse(s.volumeBoost)
        assertEquals(VoicePreset.OFF, s.voicePreset)
        assertEquals(1f, s.pitch, 0.0001f)
        assertFalse(s.autoBookmarkBluetooth)
    }

    /**
     * Зворотний бік межі. Синхронізація, статистика, теми, розумне перемотування
     * і моно — безкоштовні, і замкнути їх ненароком так само погано, як роздати платне.
     */
    @Test
    fun withoutPro_freeFeaturesStayUntouched() = runTest {
        prefs.setSmartRewind(true)
        prefs.setMonoAudio(true)
        prefs.setShowStatsOnShelf(true)
        prefs.setShakeToExtendSleep(true)
        prefs.setDoubleTapSeek(true)
        prefs.setDefaultSpeed(1.5f)
        prefs.setSkipForward(45_000)
        prefs.setWebDavConfig(server = "https://example.com", user = "reader", autoSync = true)
        prefs.setProEntitled(false)

        val s = prefs.settings.first()
        assertTrue("розумне перемотування безкоштовне", s.smartRewind)
        assertTrue("моно безкоштовне", s.monoAudio)
        assertTrue("статистика на полиці безкоштовна", s.showStatsOnShelf)
        assertTrue("продовження сну струшуванням безкоштовне", s.shakeToExtendSleep)
        assertTrue("подвійний тап безкоштовний", s.doubleTapSeek)
        assertEquals("швидкість безкоштовна", 1.5f, s.defaultSpeed, 0.0001f)
        assertEquals("крок перемотування безкоштовний", 45_000, s.skipForwardMs)
        assertTrue("автосинхронізація WebDAV безкоштовна", s.webDavAutoSync)
    }

    /**
     * Значення в DataStore лишаються недоторканими: маскування — це вигляд, а не
     * перезапис. Інакше покупка після пробного вмикання тумблерів мовчки віддавала б
     * користувачеві вимкнені фічі, які він уже налаштував.
     */
    @Test
    fun maskingDoesNotOverwriteStoredChoice() = runTest {
        storeEveryPaidToggleOn()

        prefs.setProEntitled(false)
        assertFalse(prefs.settings.first().skipSilence)

        prefs.setProEntitled(true)
        val restored = prefs.settings.first()
        assertTrue("вибір мав пережити період без ліцензії", restored.skipSilence)
        assertEquals(VoicePreset.SPEECH_CLARITY, restored.voicePreset)
        assertEquals(1.15f, restored.pitch, 0.0001f)
    }
}
