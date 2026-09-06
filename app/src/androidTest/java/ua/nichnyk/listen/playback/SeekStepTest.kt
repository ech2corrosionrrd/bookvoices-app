package ua.nichnyk.listen.playback

import android.content.ComponentName
import androidx.concurrent.futures.await
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ua.nichnyk.listen.ListenApp

/**
 * Крок перемотування, який бачить система.
 *
 * Кнопки в шторці сповіщень і в Android Auto питають крок не в застосунку, а в
 * плеєра сесії. Довгий час там стояли зашиті в конструктор ExoPlayer 15 і 30
 * секунд: користувач ставив у налаштуваннях 60, кнопка в застосунку слухалася,
 * а та сама кнопка в шторці перемотувала на 30. Побачити розбіжність міг лише
 * той, хто змінював крок, — тому вона й прожила так довго.
 */
@RunWith(AndroidJUnit4::class)
class SeekStepTest {

    private val app = ApplicationProvider.getApplicationContext<ListenApp>()
    private val prefs get() = app.container.prefs

    private var controller: MediaController? = null
    private var originalBack = 15_000
    private var originalForward = 30_000

    @Before
    fun setUp() = runBlocking {
        val settings = prefs.settings.first()
        originalBack = settings.skipBackMs
        originalForward = settings.skipForwardMs
        controller = withContext(Dispatchers.Main) {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            withTimeout(20_000) { MediaController.Builder(app, token).buildAsync().await() }
        }
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        prefs.setSkipBack(originalBack)
        prefs.setSkipForward(originalForward)
        withContext(Dispatchers.Main) { controller?.release() }
        controller = null
    }

    /** Дає сервісу побачити нове значення: він читає налаштування підпискою. */
    private suspend fun awaitIncrements(backMs: Long, forwardMs: Long): Pair<Long, Long> {
        var last = 0L to 0L
        withTimeout(10_000) {
            while (true) {
                last = withContext(Dispatchers.Main) {
                    controller!!.seekBackIncrement to controller!!.seekForwardIncrement
                }
                if (last == backMs to forwardMs) break
                delay(100)
            }
        }
        return last
    }

    @Test
    fun sessionReportsStepFromSettings() = runBlocking {
        prefs.setSkipBack(10_000)
        prefs.setSkipForward(60_000)

        val (back, forward) = awaitIncrements(10_000L, 60_000L)

        assertEquals(10_000L, back)
        assertEquals(60_000L, forward)
    }

    @Test
    fun changingStepAgainIsPickedUpWithoutRestart() = runBlocking {
        prefs.setSkipBack(30_000)
        prefs.setSkipForward(15_000)
        val (back, forward) = awaitIncrements(30_000L, 15_000L)

        assertEquals(30_000L, back)
        assertEquals(15_000L, forward)
    }
}
