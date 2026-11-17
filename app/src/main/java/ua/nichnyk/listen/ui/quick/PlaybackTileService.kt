package ua.nichnyk.listen.ui.quick

import ua.nichnyk.listen.forAppLocale
import android.os.Build
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.R
import ua.nichnyk.listen.crashSafeHandler

/**
 * Плитка «BookVoices» у швидких налаштуваннях: тап — грати/пауза.
 *
 * Те саме, що кнопка віджета: `playPause()` і сам відновлює останню книгу,
 * якщо плеєр порожній (після перезапуску процесу). Поки шторка відкрита,
 * плитка стежить за станом плеєра; коли закрита — система її не тримає, і
 * PlayerManager просить перемалювати через [QuickAccess.refreshTile].
 */
class PlaybackTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashSafeHandler("PlaybackTile"))
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = application as? ListenApp ?: return
        watch?.cancel()
        watch = scope.launch {
            app.container.player.state
                .map { it.isPlaying to it.book?.book?.title }
                .distinctUntilChanged()
                .collect { (playing, title) -> render(playing, title) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = application as? ListenApp ?: return
        ua.nichnyk.listen.AppLog.d("PlaybackTile", "onClick, isPlaying=${app.container.player.state.value.isPlaying}")
        app.container.player.playPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(isPlaying: Boolean, title: String?) {
        val tile = qsTile ?: return
        tile.state = QuickAccess.tileState(isPlaying)
        tile.label = forAppLocale().getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = QuickAccess.tileSubtitle(title, forAppLocale().getString(R.string.widget_no_book))
        }
        tile.contentDescription = buildString {
            append(forAppLocale().getString(R.string.app_name))
            if (title != null) append(", ").append(title)
        }
        tile.updateTile()
    }
}
