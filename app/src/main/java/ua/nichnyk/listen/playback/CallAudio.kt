package ua.nichnyk.listen.playback

import android.media.AudioManager

/**
 * Чи зайнятий звук телефонною розмовою.
 *
 * Навіщо окремо від аудіофокусу: поки триває дзвінок, Android не відмовляє в
 * аудіофокусі, а **відкладає** запит (`AUDIOFOCUS_REQUEST_DELAYED`) до кінця розмови.
 * Media3 1.11 вважає відкладений фокус отриманим і запускає відтворення. Звук
 * система під час дзвінка глушить, тож книга мовчки «програвалась», і слухач
 * втрачав місце (Redmi власника й емулятор, 20 листопада 2026).
 *
 * Будь-який режим, крім `MODE_NORMAL`, означає розмову або її підготовку: дзвінок
 * (`IN_CALL`), VoIP (`IN_COMMUNICATION`), виклик (`RINGTONE`), а також
 * `CALL_SCREENING` і переадресації з новіших API. Числа порівнюємо напряму, щоб
 * не залежати від того, які константи є на minSdk.
 */
object CallAudio {
    fun isInCall(mode: Int): Boolean = mode > AudioManager.MODE_NORMAL
}
