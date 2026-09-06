package ua.nichnyk.listen.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.MainActivity
import ua.nichnyk.listen.R
import ua.nichnyk.listen.playback.PlayerUiState

class NowPlayingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext as? ListenApp
        val state = app?.container?.player?.state?.value ?: PlayerUiState()
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, state)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
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

        fun updateAllWidgets(context: Context, state: PlayerUiState) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, NowPlayingWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                for (id in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, id, state)
                }
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: PlayerUiState,
        ) {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, state))
        }

        /**
         * Вміст віджета для заданого стану — окремо від того, кому його віддавати.
         *
         * internal, бо це єдине, що у віджеті можна перевірити тестом: RemoteViews
         * інфлейтиться в тестовому процесі, і видно справжні підписи та прогрес.
         * Через AppWidgetManager нічого з цього не побачити — там лише IPC.
         */
        internal fun buildViews(context: Context, state: PlayerUiState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

            val book = state.book
            if (book != null) {
                views.setTextViewText(R.id.widget_title, book.book.title)
                val subtitle = state.chapter?.title ?: book.book.author
                views.setTextViewText(R.id.widget_subtitle, subtitle)
                val progress = (state.bookProgress * 100).toInt().coerceIn(0, 100)
                views.setProgressBar(R.id.widget_progress, 100, progress, false)
                views.setViewVisibility(R.id.widget_progress, View.VISIBLE)

                val bitmap = book.book.coverPath?.let { cachedCover(it) }
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.widget_cover, bitmap)
                } else {
                    views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
                }
            } else {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_no_book))
                views.setTextViewText(R.id.widget_subtitle, "")
                views.setProgressBar(R.id.widget_progress, 100, 0, false)
                views.setViewVisibility(R.id.widget_progress, View.INVISIBLE)
                views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
            }

            val playIcon = if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            views.setImageViewResource(R.id.widget_btn_play_pause, playIcon)

            // Малюнки стрілок нейтральні, тож єдине місце, де крок узагалі
            // називається, — підпис для TalkBack. Раніше там стояло зашите
            // «15 с» і «30 с», хоча кнопки викликали skipBack/skipForward
            // із налаштувань і могли перемотувати на 10 або 60.
            views.setContentDescription(
                R.id.widget_btn_rewind,
                context.getString(R.string.rewind_sec, state.skipBackMs / 1000),
            )
            views.setContentDescription(
                R.id.widget_btn_forward,
                context.getString(R.string.forward_sec, state.skipForwardMs / 1000),
            )

            // Intent to open App
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

            // Button Intents
            val playPauseIntent = Intent(context, NowPlayingWidgetProvider::class.java).apply {
                action = ACTION_PLAY_PAUSE
            }
            val playPausePending = PendingIntent.getBroadcast(
                context,
                1,
                playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause, playPausePending)

            val rewindIntent = Intent(context, NowPlayingWidgetProvider::class.java).apply {
                action = ACTION_REWIND
            }
            val rewindPending = PendingIntent.getBroadcast(
                context,
                2,
                rewindIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_btn_rewind, rewindPending)

            val forwardIntent = Intent(context, NowPlayingWidgetProvider::class.java).apply {
                action = ACTION_FORWARD
            }
            val forwardPending = PendingIntent.getBroadcast(
                context,
                3,
                forwardIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_btn_forward, forwardPending)

            return views
        }

        /** Шлях останньої декодованої обкладинки; null — ще нічого не декодували. */
        private var coverPath: String? = null
        private var coverBitmap: android.graphics.Bitmap? = null

        /**
         * Обкладинка декодується один раз на книгу, а не на кожне оновлення віджета.
         *
         * Кеш на один запис: віджет показує рівно одну обкладинку. Ключ — шлях, а
         * CoverGenerator.generate() щоразу пише новий файл, тож перемальована
         * обкладинка приходить під новим шляхом і кеш не застрягає на старій.
         * null кешуємо теж: якщо файл не читається, немає сенсу пробувати щотакту.
         */
        @Synchronized
        private fun cachedCover(path: String): android.graphics.Bitmap? {
            if (path == coverPath) return coverBitmap
            val decoded = if (java.io.File(path).exists()) decodeSampledBitmap(path) else null
            coverPath = path
            coverBitmap = decoded
            return decoded
        }

        private fun decodeSampledBitmap(path: String, reqWidth: Int = 180, reqHeight: Int = 180): android.graphics.Bitmap? {
            return runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                var inSampleSize = 1
                if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                    val halfHeight = options.outHeight / 2
                    val halfWidth = options.outWidth / 2
                    while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                        inSampleSize *= 2
                    }
                }
                options.inSampleSize = inSampleSize
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(path, options)
            }.getOrNull()
        }
    }
}
