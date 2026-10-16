package ua.nichnyk.listen

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.playback.ReconnectResume

/**
 * Автопродовження після короткого розриву навушників. Ціна помилки несиметрична:
 * зайве «не продовжив» — це одне натискання, а зайве «продовжив» — звук у тиші,
 * на роботі чи вночі. Тому кожен випадок «не вмикати» має власний тест.
 */
class ReconnectResumeTest {

    private val a2dp = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    private fun resume(
        enabled: Boolean = true,
        pausedAt: Long? = 1_000L,
        now: Long = 1_000L + 60_000L,
        playing: Boolean = false,
        types: Collection<Int> = a2dp,
    ) = ReconnectResume.shouldResume(enabled, pausedAt, now, playing, types)

    @Test
    fun resumesWhenHeadphonesComeBackSoonAfterTheyDroppedTheStream() {
        assertTrue(resume())
    }

    @Test
    fun neverResumesWhenTheSettingIsOff() {
        assertFalse(resume(enabled = false))
    }

    @Test
    fun neverResumesIfNothingWasPausedByADisconnect() {
        // Користувач сам поставив на паузу — повернення навушників не привід вмикати звук.
        assertFalse(resume(pausedAt = null))
    }

    @Test
    fun doesNotResumeAfterTheWindowHasPassed() {
        assertTrue(resume(now = 1_000L + ReconnectResume.WINDOW_MS))
        assertFalse(resume(now = 1_000L + ReconnectResume.WINDOW_MS + 1))
    }

    @Test
    fun doesNothingWhenPlaybackAlreadyRunning() {
        assertFalse(resume(playing = true))
    }

    @Test
    fun clockGoingBackwardsIsNotTrusted() {
        assertFalse(resume(now = 500L))
    }

    @Test
    fun onlyListeningOutputsCount() {
        assertTrue(resume(types = listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertTrue(resume(types = listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
        assertTrue(resume(types = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)))
        assertTrue(resume(types = listOf(AudioDeviceInfo.TYPE_HEARING_AID)))
        assertFalse(resume(types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertFalse(resume(types = listOf(AudioDeviceInfo.TYPE_HDMI)))
        assertFalse(resume(types = listOf(AudioDeviceInfo.TYPE_TELEPHONY)))
        // SCO зʼявляється під час дзвінка в гарнітурі: продовжувати книгу в розмові не можна
        assertFalse(resume(types = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        assertFalse(resume(types = emptyList()))
    }

    @Test
    fun oneListeningOutputAmongOthersIsEnough() {
        assertTrue(resume(types = listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun windowIsShortEnoughToMeanTheSameSession() {
        assertEquals(15 * 60_000L, ReconnectResume.WINDOW_MS)
    }
    // --- чи обірвав розрив саме відтворення ---------------------------------

    private fun interrupted(
        isPlaying: Boolean = false,
        playWhenReady: Boolean = false,
        msSinceStop: Long = 1_000L,
        byUser: Boolean = false,
    ) = ReconnectResume.interruptedPlayback(isPlaying, playWhenReady, msSinceStop, byUser, graceMs = 3_000L)

    @Test
    fun playingAtDisconnectIsAnInterruption() {
        assertTrue(interrupted(isPlaying = true, msSinceStop = 60_000L, byUser = true))
        assertTrue(interrupted(playWhenReady = true, msSinceStop = 60_000L))
    }

    @Test
    fun mediaPausedItJustBeforeOurReceiverIsAnInterruption() {
        assertTrue(interrupted(msSinceStop = 500L, byUser = false))
    }

    @Test
    fun pauseByUserSecondsBeforeUnplugIsNotAnInterruption() {
        assertFalse(interrupted(msSinceStop = 1_500L, byUser = true))
    }

    @Test
    fun oldPauseIsNotAnInterruption() {
        assertFalse(interrupted(msSinceStop = 10_000L, byUser = false))
    }
}
