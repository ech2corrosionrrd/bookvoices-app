package ua.nichnyk.listen.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ua.nichnyk.listen.ListenApp

/**
 * Кнопки віджета (play/pause, назад, вперед).
 *
 * Окремий приймач з exported=false: PendingIntent віджета адресований явно, а
 * сторонні застосунки не можуть надіслати сюди дії й керувати відтворенням.
 * Експортований NowPlayingWidgetProvider приймає лише APPWIDGET_UPDATE.
 */
class NowPlayingWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ListenApp ?: return
        val player = app.container.player
        runCatching {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> player.playPause()
                ACTION_REWIND -> player.skipBack()
                ACTION_FORWARD -> player.skipForward()
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "ua.nichnyk.listen.widget.PLAY_PAUSE"
        const val ACTION_REWIND = "ua.nichnyk.listen.widget.REWIND"
        const val ACTION_FORWARD = "ua.nichnyk.listen.widget.FORWARD"
    }
}
