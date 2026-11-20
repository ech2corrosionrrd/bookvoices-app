package ua.nichnyk.listen

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.playback.CallAudio

/**
 * «Грати» під час дзвінка відкладається до кінця розмови (PlaybackService.playAfterCall).
 * Тут — яким режимам звуку це відповідає: Media3 вважає відкладений аудіофокус
 * отриманим, тож вирішувати мусимо самі.
 */
class CallAudioTest {

    @Test
    fun normalModeIsNotACall() {
        assertFalse(CallAudio.isInCall(AudioManager.MODE_NORMAL))
        // MODE_INVALID (-2) і MODE_CURRENT (-1) — не режими розмови.
        assertFalse(CallAudio.isInCall(-2))
        assertFalse(CallAudio.isInCall(-1))
    }

    @Test
    fun callRingtoneVoipAndNewerModesAreCalls() {
        assertTrue(CallAudio.isInCall(AudioManager.MODE_RINGTONE))
        assertTrue(CallAudio.isInCall(AudioManager.MODE_IN_CALL))
        assertTrue(CallAudio.isInCall(AudioManager.MODE_IN_COMMUNICATION))
        // CALL_SCREENING (API 30), CALL_REDIRECT і COMMUNICATION_REDIRECT (API 33).
        assertTrue(CallAudio.isInCall(4))
        assertTrue(CallAudio.isInCall(5))
        assertTrue(CallAudio.isInCall(6))
    }
}
