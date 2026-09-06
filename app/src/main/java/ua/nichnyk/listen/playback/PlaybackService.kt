package ua.nichnyk.listen.playback

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.progress
import ua.nichnyk.listen.data.replayTarget
import ua.nichnyk.listen.data.VoicePreset
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.ListenDatabase
import java.io.File

// ExoPlayer, AnalyticsListener і skipSilenceEnabled — ще нестабільний API media3.
// Саме @OptIn, а не @UnstableApi: інакше вимога opt-in поширилася б на всіх,
// хто лише посилається на цей сервіс.
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    /**
     * Крок перемотування з налаштувань — той самий, яким користуються кнопки в
     * застосунку. Оновлюється підпискою нижче; @Volatile, бо читає його
     * ForwardingPlayer із потоку сесії.
     */
    @Volatile
    private var skipBackMs = 15_000L

    @Volatile
    private var skipForwardMs = 30_000L

    /** Плеєр, який віддається сесії: та сама труба, але з кроком із налаштувань. */
    private var sessionPlayer: SeekAwarePlayer? = null
    // Сюди приходять усі запити медіабраузера (шторка, Android Auto). Виняток
    // на зіпсованому рядку бази не має знімати сервіс разом із відтворенням.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + ua.nichnyk.listen.crashSafeHandler("PlaybackService"),
    )
    private val db by lazy { ListenDatabase.get(this) }
    /**
     * Єдиний власник `Equalizer` у процесі.
     *
     * Раніше поряд жило окреме поле під «чіткість мовлення» з другим
     * `Equalizer(0, sessionId)` на тій самій аудіосесії. AudioEffect віддає
     * контроль останньому створеному екземпляру, тож два тумблери одного
     * ефекту глушили один одного залежно від порядку створення.
     */
    private val voiceEqualizer = VoiceEqualizer()
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    /** Бажаний пресет; застосовується щоразу, коли зʼявляється audio session. */
    private var voicePreset = VoicePreset.OFF
    /** Бажаний стан «підсилення гучності»; застосовується щоразу, коли зʼявляється audio session. */
    private var volumeBoostEnabled = false

    /**
     * Знімок межі Freemium для [onCustomCommand]. UI гейтить ці перемикачі
     * через `enabled = isPro`, але сесійні команди роздаються будь-якому
     * контролеру, що під'єднався, — і той шлях довіряв команді на слово.
     */
    @Volatile
    private var proEntitled = false
    // false до першої емісії налаштувань: автозакладка — Pro-фіча, і вмикати її
    // «за замовчуванням» до того, як межа Freemium прочитана, було б неправильно.
    private var autoBookmarkBluetooth = false
    private val monoAudioProcessor = MonoDownmixAudioProcessor()
    private var doubleTapAction = ua.nichnyk.listen.data.HeadsetAction.NONE
    private var tripleTapAction = ua.nichnyk.listen.data.HeadsetAction.NONE
    private var headsetClickCount = 0
    private var headsetClickJob: kotlinx.coroutines.Job? = null

    /**
     * Коли відтворення останній раз зупинилося (elapsedRealtime).
     *
     * `setHandleAudioBecomingNoisy(true)` уже стоїть у плеєрі, і порядок доставки
     * ACTION_AUDIO_BECOMING_NOISY між внутрішнім обробником media3 та цим
     * приймачем не визначений. Якщо media3 встигав першим, перевірка
     * `isPlaying || playWhenReady` не проходила — і закладка при від'єднанні
     * гарнітури зʼявлялася через раз. Тепер підходить і «щойно грало».
     */
    private var lastStoppedAtMs = 0L

    private val playbackWatcher = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) lastStoppedAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    /**
     * Закладка, коли слухання обірвав від'єднаний вихід звуку.
     *
     * Тільки ACTION_AUDIO_BECOMING_NOISY. У фільтрі був ще
     * `BluetoothDevice.ACTION_ACL_DISCONNECTED`, але цей бродкаст із API 31
     * доставляється лише тим, хто тримає `BLUETOOTH_CONNECT`, — а його в
     * маніфесті нема й не буде: просити небезпечний дозвіл заради підпису
     * закладки не варто того. Гілка була мертвою на всіх Android 12+.
     *
     * NOISY закриває обидва випадки — і від'єднання A2DP, і витягнутий
     * мініджек, — і не потребує жодного дозволу.
     */
    private val disconnectReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (!autoBookmarkBluetooth) return
            if (intent?.action != android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            val justStopped =
                android.os.SystemClock.elapsedRealtime() - lastStoppedAtMs < NOISY_GRACE_MS
            if (!player.isPlaying && !player.playWhenReady && !justStopped) return

            val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val noteStr = if (hasBluetoothOutput()) {
                getString(R.string.bookmark_bluetooth_disconnected, timeStr)
            } else {
                getString(R.string.bookmark_headset_disconnected, timeStr)
            }
            addBookmarkAtCurrentPosition(noteStr)
        }
    }

    /**
     * Чи є серед виходів звуку Bluetooth — щоб підписати закладку саме так.
     *
     * `getDevices` не потребує дозволів. У момент NOISY пристрій, який щойно
     * від'єднався, ще зазвичай у списку; якщо ні — підпис буде загальний, і це
     * не гірше за колишнє «нічого не сталося».
     */
    private fun hasBluetoothOutput(): Boolean = runCatching {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)

    private fun <T> coroutineFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch(Dispatchers.IO) {
            try {
                future.set(block())
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        return future
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val filter = android.content.IntentFilter(
                android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY,
            )
            // Явний RECEIVER_NOT_EXPORTED: дія системна, тож targetSdk 34+ поки
            // не вимагає прапорця, але покладатися на цей виняток означає ловити
            // падіння в наступній версії Android.
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                disconnectReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            // Раніше відмова проглиналася без слідів, і фіча просто не працювала.
            AppLog.w("PlaybackService: не зареєстрували приймач розриву гарнітури", it)
        }
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(monoAudioProcessor))
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setEnableFloatOutput(enableFloatOutput)
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(skipBackMs)
            .setSeekForwardIncrementMs(skipForwardMs)
            .build()

        // audioSessionId зʼявляється лише коли починається відтворення. Без цього слухача
        // «чіткість мовлення» та «підсилення гучності», увімкнені до старту, не застосовувалися ніколи.
        player.addListener(playbackWatcher)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                releaseLoudnessEnhancer()
                voiceEqualizer.setAudioSession(audioSessionId)
                if (volumeBoostEnabled) applyVolumeBoost(true)
            }
        })

        // Крок перемотування читаємо з тих самих налаштувань, що й екран плеєра.
        // Раніше в конструкторі ExoPlayer стояли зашиті 15 і 30 секунд, тож кнопки
        // у шторці й в Auto перемотували не на стільки, скільки просив користувач,
        // — а побачити це міг лише той, хто змінив крок.
        val wrapped = SeekAwarePlayer(player)
        sessionPlayer = wrapped
        val prefs = (application as? ListenApp)?.container?.prefs
        if (prefs != null) {
            scope.launch {
                prefs.settings.collect {
                    skipBackMs = it.skipBackMs.toLong()
                    skipForwardMs = it.skipForwardMs.toLong()
                    wrapped.refreshSeekIncrements()
                    proEntitled = it.isPro
                    player.skipSilenceEnabled = it.skipSilence
                    monoAudioProcessor.isDownmixEnabled = it.monoAudio
                    if (volumeBoostEnabled != it.volumeBoost) {
                        volumeBoostEnabled = it.volumeBoost
                        applyVolumeBoost(it.volumeBoost)
                    }
                    if (voicePreset != it.voicePreset) {
                        voicePreset = it.voicePreset
                        voiceEqualizer.applyPreset(it.voicePreset)
                    }
                    autoBookmarkBluetooth = it.autoBookmarkBluetooth
                    doubleTapAction = it.headsetDoubleTapAction
                    tripleTapAction = it.headsetTripleTapAction
                    // Без цього сесія не дізнається про зміну: вона тримає знімок
                    // стану плеєра й оновлює його лише за подією.
                    sessionPlayer?.refreshSeekIncrements()
                }
            }
        }

        session = MediaLibrarySession.Builder(this, wrapped, object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                if (!isTrustedController(session, controller)) {
                    return MediaSession.ConnectionResult.reject()
                }
                val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_SET_SKIP_SILENCE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SET_VOLUME_BOOST, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(available)
                    .build()
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                // Стиль кореневого списку їде в LibraryParams, а не в metadata
                // кореневого елемента. Android Auto під'єднується як
                // MediaBrowserCompat, а для нього media3 будує BrowserRoot із
                // params.extras — підказка, покладена в MediaMetadata кореня,
                // туди не потрапляє взагалі й лишається мертвою.
                //
                // Прапорці запиту повертаємо як були: за ними браузер розрізняє
                // звичайний корінь і той, що просять для відновлення.
                val rootParams = LibraryParams.Builder()
                    .setExtras(listStyle())
                    .setRecent(params?.isRecent == true)
                    .setOffline(params?.isOffline == true)
                    .setSuggested(params?.isSuggested == true)
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), rootParams))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return coroutineFuture {
                    val children = childrenOf(parentId)
                        ?: return@coroutineFuture LibraryResult.ofError<ImmutableList<MediaItem>>(
                            SessionError.ERROR_BAD_VALUE,
                        )
                    LibraryResult.ofItemList(ImmutableList.copyOf(children.page(page, pageSize)), params)
                }
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                return coroutineFuture {
                    if (mediaId == ROOT_ID) {
                        return@coroutineFuture LibraryResult.ofItem(rootItem(), null)
                    }
                    // Рядок-пояснення теж мають описувати: Auto питає getItem по
                    // тому, що вже показує, і без цієї гілки віддавали б помилку
                    // на елемент, який самі ж і намалювали.
                    if (mediaId.startsWith(EMPTY_PREFIX)) {
                        return@coroutineFuture LibraryResult.ofItem(
                            emptyItemFor(mediaId.removePrefix(EMPTY_PREFIX)),
                            null,
                        )
                    }
                    val category = categoryItem(mediaId)
                    if (category != null) {
                        return@coroutineFuture LibraryResult.ofItem(category, null)
                    }
                    val book = runCatching { db.library().getBook(mediaId) }.getOrNull()
                    if (book != null) {
                        // Саме bookItem, а не власна копія: інакше книга, яку
                        // Auto питає поштучно, приходить без позначки
                        // прослуханого й виглядає незачепленою.
                        LibraryResult.ofItem(bookItem(book), null)
                    } else {
                        val bookForCh = runCatching { db.library().getBookForChapter(mediaId) }.getOrNull()
                        val ch = bookForCh?.chapters?.find { it.id == mediaId }
                        if (bookForCh != null && ch != null) {
                            val item = MediaItem.Builder()
                                .setMediaId(ch.id)
                                .setUri(ch.uri.toUri())
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(ch.title)
                                        .setSubtitle(bookForCh.book.title)
                                        .setArtist(bookForCh.book.author)
                                        .setAlbumTitle(bookForCh.book.title)
                                        .setArtworkUri(bookForCh.book.coverPath?.let { File(it).toUri() })
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                                        .setExtras(Bundle().apply { putString("book_id", bookForCh.book.id) })
                                        .build()
                                )
                                .build()
                            LibraryResult.ofItem(item, null)
                        } else {
                            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        }
                    }
                }
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return coroutineFuture {
                    val books = db.library().getAllBooks()
                    // Дослухану книгу система відновлювати не має: «продовжити»
                    // на ній означає останню секунду. Якщо незавершених немає
                    // зовсім, беремо що є — fromStoredPosition поверне таку
                    // книгу з початку.
                    val started = books.filter { it.book.lastPlayedAt != null }
                    val lastBook = started.filter { !it.book.completed }
                        .maxByOrNull { it.book.lastPlayedAt ?: 0L }
                        ?: started.maxByOrNull { it.book.lastPlayedAt ?: 0L }
                        ?: books.firstOrNull()

                    val request = lastBook?.let { fromStoredPosition(it) }
                        ?: throw IllegalStateException("No books available")

                    MediaSession.MediaItemsWithStartPosition(
                        request.items,
                        request.startIndex,
                        request.startPositionMs,
                    )
                }
            }

            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<Void>> {
                return coroutineFuture {
                    session.notifySearchResultChanged(browser, query, booksMatching(query).size, params)
                    LibraryResult.ofVoid(params)
                }
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return coroutineFuture {
                    val items = booksMatching(query).map { bookItem(it) }.page(page, pageSize)
                    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                }
            }

            /**
             * Розкриває описи в готові до відтворення елементи.
             *
             * Елемент, у якого вже є URI, не чіпаємо. Це не оптимізація, а межа
             * між двома різними джерелами: браузер Auto надсилає **опис** без
             * URI (id книги чи глави), а власний PlayerManager — уже зібрану
             * чергу глав. Без цієї перевірки кожна з N глав від PlayerManager
             * розкривалася б у всю книгу, тобто в чергу лягало б N² елементів.
             */
            private suspend fun expand(
                mediaItems: List<MediaItem>,
            ): Pair<MutableList<MediaItem>, PlaybackRequest?> {
                val expanded = mutableListOf<MediaItem>()
                var start: PlaybackRequest? = null
                for (item in mediaItems) {
                    if (item.localConfiguration != null) {
                        expanded.add(item)
                        continue
                    }
                    val request = resolvePlaybackRequest(item)
                    if (request == null) {
                        expanded.add(item)
                        continue
                    }
                    // Зсув: перша розкрита книга задає старт, а її глави лягають
                    // після вже накопичених елементів.
                    if (start == null) {
                        start = request.copy(startIndex = request.startIndex + expanded.size)
                    }
                    expanded.addAll(request.items)
                }
                return expanded to start
            }

            /**
             * Додавання до черги: книга чи глава розгортається у список глав.
             *
             * Місце старту тут не задається — за «звідки грати» відповідає
             * [onSetMediaItems], а цей колбек лише за «що».
             */
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> {
                return coroutineFuture { expand(mediaItems).first }
            }

            /**
             * «Увімкнути» — і саме звідси береться місце, з якого починати.
             *
             * Доти цього колбека не було зовсім, і media3 брав значення за
             * замовчуванням: перша глава, нульова секунда. Тобто в машині тап по
             * книзі відкидав слухача на початок, хоча картка поруч показувала
             * смужку прослуханого. Відновлення після перезапуску працювало
             * правильно ([onPlaybackResumption]) — розходився саме цей шлях.
             *
             * Явні [startIndex] і [startPositionMs] мають перевагу: якщо
             * контролер сам сказав, звідки грати, підміняти його збереженою
             * позицією не можна. Саме так приходить власний PlayerManager.
             */
            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return coroutineFuture {
                    val (expanded, resolved) = expand(mediaItems)
                    val keepCallerStart = startIndex != C.INDEX_UNSET || resolved == null
                    MediaSession.MediaItemsWithStartPosition(
                        expanded,
                        if (keepCallerStart) startIndex else resolved.startIndex,
                        if (keepCallerStart) startPositionMs else resolved.startPositionMs,
                    )
                }
            }

            /**
             * Подвійний і потрійний тап на кнопці гарнітури.
             *
             * Перехоплення тут коштує дорого: щоб відрізнити один тап від двох,
             * доводиться чекати [HEADSET_MULTI_TAP_WINDOW_MS] перед будь-якою
             * реакцією — тобто пауза й старт, найчастіша дія в застосунку,
             * починають відгукуватися з затримкою. Тому поки обидві дії стоять
             * у «нічого» (а це стан за замовчуванням), подію не чіпаємо взагалі
             * й кнопка працює миттєво, як робить система.
             */
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent,
            ): Boolean {
                if (!headsetGesturesEnabled()) {
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
                val keyEvent = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_KEY_EVENT,
                    android.view.KeyEvent::class.java,
                )
                if (keyEvent != null && keyEvent.action == android.view.KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                    when (keyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_HEADSETHOOK,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        -> {
                            handleHeadsetClick()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    ACTION_SET_SKIP_SILENCE -> {
                        val enabled = args.getBoolean(EXTRA_SKIP_SILENCE, false)
                        if (enabled && !proEntitled) return denyWithoutPro(ACTION_SET_SKIP_SILENCE)
                        player.skipSilenceEnabled = enabled
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    ACTION_SET_VOLUME_BOOST -> {
                        val enabled = args.getBoolean(EXTRA_VOLUME_BOOST, false)
                        if (enabled && !proEntitled) return denyWithoutPro(ACTION_SET_VOLUME_BOOST)
                        volumeBoostEnabled = enabled
                        applyVolumeBoost(enabled)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }).build()

        watchShelfForAuto()
    }

    /**
     * Тримає дерево Auto живим.
     *
     * MediaBrowserCompat — а Android Auto під'єднується саме ним — не
     * перепитує вміст вузла сам: він малює те, що прочитав, і чекає
     * `notifyChildrenChanged`. Без цього смужка на картці застигала на
     * значенні, яке було в мить відкриття списку, щойно почата книга не
     * зʼявлялася в «Слухаю», а рядок «Книг ще немає» лишався на екрані й
     * після того, як книги додали з телефона.
     *
     * Підпис вузла — саме те, що видно на картці, з відсотком, зведеним до
     * цілого. Позиція пишеться в базу постійно, і без цього огрублення
     * браузер перечитував би дерево кілька разів на секунду заради смужки,
     * яка тоншої роздільності все одно не має.
     */
    private fun watchShelfForAuto() {
        scope.launch {
            db.library().observeBooks()
                .map { books -> books to browseSignature(books) }
                .distinctUntilChangedBy { (_, signature) -> signature }
                .flowOn(Dispatchers.Default)
                .collect { (books, _) -> notifyBrowseTreeChanged(books) }
        }
    }

    private fun browseSignature(
        books: List<ua.nichnyk.listen.data.BookWithChapters>,
    ): List<List<Any>> = books.map { b ->
        val (status, progress) = completionOf(b)
        listOf(
            b.book.id,
            b.book.title,
            b.book.author,
            b.book.coverPath.orEmpty(),
            status,
            (progress * 100).toInt(),
        )
    }

    private fun notifyBrowseTreeChanged(books: List<ua.nichnyk.listen.data.BookWithChapters>) {
        val live = session ?: return
        // Автори — теж вузли з картками книг, тож застаріти можуть так само.
        val nodes = listOf(ROOT_ID, CONTINUE_ID, ALL_BOOKS_ID, AUTHORS_ID) +
            books.map { it.book.author }.filter { it.isNotBlank() }.distinct()
                .map { AUTHOR_PREFIX + it }
        nodes.forEach { node ->
            runCatching { live.notifyChildrenChanged(node, Int.MAX_VALUE, null) }
                .onFailure { AppLog.w("PlaybackService: не оновили вузол Auto $node", it) }
        }
    }

    /**
     * Плеєр, на якому виконуються дії гарнітури.
     *
     * Саме sessionPlayer, а не сирий ExoPlayer. Крок перемотування в
     * ExoPlayer зашитий у конструкторі — на той момент skipBackMs і
     * skipForwardMs ще мають значення полів (15 і 30 с), бо збирач
     * налаштувань запускається пізніше. Живий крок віддає лише
     * SeekAwarePlayer.getState(). Виклик player.seekForward() означав би
     * «завжди 30 с», хоч би що стояло в налаштуваннях, — тобто рівно те
     * розходження, яке закрив реліз v1.4.0.
     */
    private val controlledPlayer: androidx.media3.common.Player
        get() = sessionPlayer ?: player

    private fun headsetGesturesEnabled(): Boolean =
        doubleTapAction != ua.nichnyk.listen.data.HeadsetAction.NONE ||
            tripleTapAction != ua.nichnyk.listen.data.HeadsetAction.NONE

    private fun handleHeadsetClick() {
        headsetClickCount++
        headsetClickJob?.cancel()
        headsetClickJob = scope.launch {
            // Скасування наступним тапом має лишити лічильник як є — тому
            // обнуляємо його лише після того, як дія справді виконалася.
            kotlinx.coroutines.delay(HEADSET_MULTI_TAP_WINDOW_MS)
            val taps = headsetClickCount
            headsetClickCount = 0
            when (taps) {
                1 -> togglePlayPause()
                2 -> executeHeadsetAction(doubleTapAction)
                else -> executeHeadsetAction(tripleTapAction)
            }
        }
    }

    /**
     * Те саме, що робить media3, коли ми не перехоплюємо медіакнопку.
     *
     * Своє «isPlaying ? pause : play» виглядає еквівалентним, але ним не є:
     * у STATE_IDLE (після помилки відтворення) воно кличе play() без
     * prepare(), і кнопка мовчки нічого не робить; у STATE_ENDED не
     * перезапускає книгу; а під час буферизації, коли isPlaying ще false, а
     * playWhenReady вже true, — вмикає замість того, щоб поставити на паузу.
     */
    private fun togglePlayPause() {
        androidx.media3.common.util.Util.handlePlayPauseButtonAction(controlledPlayer)
    }

    private fun executeHeadsetAction(action: ua.nichnyk.listen.data.HeadsetAction) {
        when (action) {
            ua.nichnyk.listen.data.HeadsetAction.NONE -> Unit
            ua.nichnyk.listen.data.HeadsetAction.PLAY_PAUSE -> togglePlayPause()
            // Через controlledPlayer: крок береться з налаштувань, а не з
            // конструктора ExoPlayer. Див. коментар до controlledPlayer.
            ua.nichnyk.listen.data.HeadsetAction.FORWARD -> controlledPlayer.seekForward()
            ua.nichnyk.listen.data.HeadsetAction.REWIND -> controlledPlayer.seekBack()
            ua.nichnyk.listen.data.HeadsetAction.NEXT_CHAPTER -> {
                if (controlledPlayer.hasNextMediaItem()) controlledPlayer.seekToNextMediaItem()
            }
            ua.nichnyk.listen.data.HeadsetAction.PREVIOUS_CHAPTER -> {
                if (controlledPlayer.hasPreviousMediaItem()) controlledPlayer.seekToPreviousMediaItem()
            }
            ua.nichnyk.listen.data.HeadsetAction.BOOKMARK -> addBookmarkAtCurrentPosition()
        }
    }

    private fun addBookmarkAtCurrentPosition(note: String = "") {
        val item = controlledPlayer.currentMediaItem ?: return
        val chapterId = item.mediaId
        val positionMs = controlledPlayer.currentPosition
        scope.launch {
            // Назву глави беремо з бази, а не з метаданих: у MediaItem назвою
            // (title) стоїть книга, а глава лежить у subtitle. Записавши title,
            // ми клали б у закладку назву книги — і список закладок показував
            // би її двічі замість того, щоб сказати, де саме те місце.
            val book = db.library().getBookForChapter(chapterId) ?: return@launch
            val chapter = book.chapters.firstOrNull { it.id == chapterId }
            db.library().upsertBookmark(
                ua.nichnyk.listen.data.BookmarkEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    bookId = book.book.id,
                    chapterId = chapterId,
                    positionMs = positionMs,
                    createdAt = System.currentTimeMillis(),
                    chapterTitle = chapter?.title
                        ?: item.mediaMetadata.subtitle?.toString().orEmpty(),
                    note = note,
                ),
            )
        }
    }

    /**
     * Обгортка, яка бере крок перемотування з налаштувань, а не з конструктора плеєра.
     *
     * `ExoPlayer.Builder` приймає крок один раз і назавжди, тож у рантаймі змінити
     * його інакше не можна. Раніше там стояли зашиті 15 і 30 секунд: користувач
     * ставив у налаштуваннях 60, кнопка в застосунку слухалася, а така сама кнопка
     * у шторці й в Auto перемотувала на 30.
     *
     * Саме `ForwardingSimpleBasePlayer`, а не простий `ForwardingPlayer`: другий
     * дозволяє перевизначити геттер, але нікого про зміну не сповіщає — сесія
     * тримає знімок стану плеєра й оновлює його лише за подією. Тут стан
     * перебудовується в [getState], а [invalidateState] каже сесії перечитати його.
     *
     * Позицію рахує сам `SimpleBasePlayer` із того кроку, який ми повідомили,
     * тож перемотування виконує звичайний `seekTo` делегата.
     */
    private inner class SeekAwarePlayer(
        delegate: androidx.media3.common.Player,
    ) : androidx.media3.common.ForwardingSimpleBasePlayer(delegate) {
        override fun getState(): State = super.getState().buildUpon()
            .setSeekBackIncrementMs(skipBackMs)
            .setSeekForwardIncrementMs(skipForwardMs)
            .build()

        /** invalidateState() у SimpleBasePlayer захищений — відкриваємо його для сервісу. */
        fun refreshSeekIncrements() = invalidateState()
    }

    // --- дерево для Android Auto -------------------------------------------

    /**
     * Сторінка списку.
     *
     * Auto запитує вміст вузла порціями, і до цього ми ці параметри ігнорували:
     * полиця на кількасот книг їхала одним Binder-пакетом, тобто прямою дорогою
     * до TransactionTooLargeException у машині, де відлагодити це найважче.
     */
    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0) return this
        val from = (page.coerceAtLeast(0)) * pageSize
        if (from >= size) return emptyList()
        return subList(from, minOf(from + pageSize, size))
    }

    private fun rootItem(): MediaItem =
        browsableItem(ROOT_ID, getString(R.string.app_name), listStyle())

    /**
     * Як Auto малює **вміст** цього вузла: списком чи сіткою.
     *
     * Ключ ставиться на батьківському елементі, а не на дітях, і стосується
     * саме дітей. Книга водночас `isBrowsable` і `isPlayable`, тож у сітку її
     * відправляє BROWSABLE-підказка — PLAYABLE тут керує лише главами.
     */
    private fun contentStyle(browsable: Int, playable: Int): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
    }

    /** Вузол із книгами: обкладинки — це те, за чим книгу впізнають за кермом. */
    private fun bookGridStyle(): Bundle = contentStyle(
        browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
    )

    /** Вузол з назвами (категорії, автори): картинок немає, сітка була б порожньою. */
    private fun listStyle(): Bundle = contentStyle(
        browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
    )

    private fun browsableItem(id: String, title: String, childStyle: Bundle? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .setExtras(childStyle)
                    .build(),
            )
            .build()

    /**
     * Пояснення замість порожнього екрана.
     *
     * Ні `isBrowsable`, ні `isPlayable`: Auto показує такий рядок неактивним і
     * нікуди по ньому не веде. Порожній вузол без цього виглядає як збій
     * застосунку — а полиця буває порожня цілком законно, доки книги не додали
     * з телефона.
     */
    private fun emptyItem(id: String, text: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(text)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    /**
     * Рядок-пояснення для конкретного вузла.
     *
     * Ідентифікатор несе в собі вузол, якому рядок належить, — і не лише щоб
     * три різні пояснення не ділили один id у кеші браузера: за ним [onGetItem]
     * відтворює той самий рядок, коли Auto питає про нього окремо.
     */
    private fun emptyItemFor(parentId: String): MediaItem = emptyItem(
        EMPTY_PREFIX + parentId,
        when (parentId) {
            CONTINUE_ID -> getString(R.string.auto_nothing_started)
            AUTHORS_ID -> getString(R.string.auto_no_authors)
            else -> getString(R.string.auto_empty_library)
        },
    )

    /**
     * Позначка «де я зупинився»: статус і відсоток разом, а не поодинці.
     *
     * Відсоток веде за статусом навмисно. У дослуханої книги позиція зазвичай
     * скинута на нуль, тож збережений дріб дав би «прослухано 0 %» під
     * позначкою «дослухано» — рівно та розбіжність, від якої мала захищати
     * спільна формула.
     */
    private fun completionOf(book: ua.nichnyk.listen.data.BookWithChapters): Pair<Int, Double> =
        when {
            book.book.completed ->
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED to 1.0

            book.book.lastPlayedAt == null ->
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED to 0.0

            else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED to
                book.progress().toDouble()
        }

    /**
     * Картка книги з позначкою прослуханого.
     *
     * `COMPLETION_STATUS` малює в Auto смужку під карткою, а `..._PERCENTAGE`
     * задає її заповнення. Без них усі книги в машині виглядають однаково
     * незачепленими — тобто найпотрібніша за кермом підказка «де я зупинився»
     * зникає саме там, де читати назви ніколи.
     */
    private fun bookItem(book: ua.nichnyk.listen.data.BookWithChapters): MediaItem {
        val (status, progress) = completionOf(book)
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
            // Дробове, 0..1. Auto читає його лише при PARTIALLY_PLAYED, але
            // кладемо завжди — див. [completionOf].
            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, progress)
            // Глави книги — список, а не сітка: у них немає власних обкладинок.
            putAll(listStyle())
        }
        return MediaItem.Builder()
            .setMediaId(book.book.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.book.title)
                    .setArtist(book.book.author)
                    .setIsPlayable(true)
                    .setIsBrowsable(true)
                    .setArtworkUri(book.book.coverPath?.let { File(it).toUri() })
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    /** Опис вузла-категорії; null — це не категорія. */
    private fun categoryItem(mediaId: String): MediaItem? = when {
        mediaId == CONTINUE_ID ->
            browsableItem(mediaId, getString(R.string.auto_continue), bookGridStyle())
        mediaId == ALL_BOOKS_ID ->
            browsableItem(mediaId, getString(R.string.auto_all_books), bookGridStyle())
        mediaId == AUTHORS_ID ->
            browsableItem(mediaId, getString(R.string.auto_authors), listStyle())
        mediaId.startsWith(AUTHOR_PREFIX) ->
            browsableItem(mediaId, mediaId.removePrefix(AUTHOR_PREFIX), bookGridStyle())
        else -> null
    }

    /**
     * Вміст вузла. null означає «такого вузла немає».
     *
     * Корінь більше не є пласким списком усіх книг: у машині гортати кількасот
     * карток однією стрічкою неможливо, а «Слухаю» — це те, заради чого плеєр
     * узагалі відкривають за кермом.
     */
    private suspend fun childrenOf(parentId: String): List<MediaItem>? {
        val library = runCatching { db.library().getAllBooks() }.getOrDefault(emptyList())
        return when {
            // Порожня полиця: показувати три категорії, кожна з яких нікуди не
            // веде, — гірше, ніж один зрозумілий рядок. Книги додаються з
            // телефона, і за кермом із цим усе одно нічого не вдієш.
            library.isEmpty() && parentId == ROOT_ID ->
                listOf(emptyItemFor(ROOT_ID))

            parentId == ROOT_ID -> listOf(
                browsableItem(CONTINUE_ID, getString(R.string.auto_continue), bookGridStyle()),
                browsableItem(ALL_BOOKS_ID, getString(R.string.auto_all_books), bookGridStyle()),
                browsableItem(AUTHORS_ID, getString(R.string.auto_authors), listStyle()),
            )

            parentId == CONTINUE_ID -> library
                .filter { it.book.lastPlayedAt != null && !it.book.completed }
                .map { bookItem(it) }
                .ifEmpty { listOf(emptyItemFor(CONTINUE_ID)) }

            // Порожній рядок потрібен і тут, хоча з кореня в порожній вузол не
            // зайти: Auto відновлює стек перегляду з кешу й питає вміст вузла
            // напряму, тобто повз корінь.
            parentId == ALL_BOOKS_ID -> library
                .map { bookItem(it) }
                .ifEmpty { listOf(emptyItemFor(ALL_BOOKS_ID)) }

            parentId == AUTHORS_ID -> library
                .map { it.book.author }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .map { browsableItem(AUTHOR_PREFIX + it, it, bookGridStyle()) }
                .ifEmpty { listOf(emptyItemFor(AUTHORS_ID)) }

            parentId.startsWith(AUTHOR_PREFIX) -> {
                val author = parentId.removePrefix(AUTHOR_PREFIX)
                library.filter { it.book.author == author }
                    .map { bookItem(it) }
                    .ifEmpty { listOf(emptyItemFor(parentId)) }
            }

            else -> {
                val book = runCatching { db.library().getBook(parentId) }.getOrNull() ?: return null
                chapterItems(book)
            }
        }
    }

    /**
     * Обрізання глави, що живе всередині одного файла (m4b з розділами).
     *
     * Раніше цей блок був виписаний у чотирьох місцях поспіль — у списку глав,
     * у двох гілках додавання до черги й у відновленні після перезапуску.
     * Пропустити його в одному з них означало б книгу, яка на цьому шляху грає
     * файл цілком замість однієї глави, і помітно це лише на m4b.
     */
    private fun MediaItem.Builder.withChapterClipping(ch: ChapterEntity): MediaItem.Builder {
        if (ch.startMs > 0L || ch.endMs > ch.startMs) {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(ch.startMs.coerceAtLeast(0L))
            if (ch.endMs > ch.startMs) {
                clip.setEndPositionMs(ch.endMs)
            }
            setClippingConfiguration(clip.build())
        }
        return this
    }

    private fun chapterExtras(book: ua.nichnyk.listen.data.BookWithChapters): Bundle =
        Bundle().apply { putString("book_id", book.book.id) }

    /**
     * Глави книги для **перегляду**: зверху назва глави.
     *
     * Відрізняється від [playbackQueue] навмисно. У списку глав людина шукає
     * главу, і книга повторювалася б у кожному рядку; у черзі й на шторці
     * зверху має бути те, що слухають, — книга.
     */
    private fun chapterItems(book: ua.nichnyk.listen.data.BookWithChapters): List<MediaItem> {
        val extras = chapterExtras(book)
        return book.chapters.sortedBy { it.index }.map { ch ->
            MediaItem.Builder()
                .setMediaId(ch.id)
                .setUri(ch.uri.toUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ch.title)
                        .setSubtitle(book.book.title)
                        .setArtist(book.book.author)
                        .setAlbumTitle(book.book.title)
                        .setArtworkUri(book.book.coverPath?.let { File(it).toUri() })
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .setExtras(extras)
                        .build(),
                )
                .withChapterClipping(ch)
                .build()
        }
    }

    /** Книга як черга відтворення: зверху назва книги, під нею — глава. */
    private fun playbackQueue(book: ua.nichnyk.listen.data.BookWithChapters): List<MediaItem> {
        val extras = chapterExtras(book)
        return book.chapters.sortedBy { it.index }.map { ch ->
            MediaItem.Builder()
                .setMediaId(ch.id)
                .setUri(ch.uri.toUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.book.title)
                        .setArtist(book.book.author)
                        .setSubtitle(ch.title)
                        .setAlbumTitle(book.book.title)
                        .setArtworkUri(book.book.coverPath?.let { File(it).toUri() })
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .setExtras(extras)
                        .build(),
                )
                .withChapterClipping(ch)
                .build()
        }
    }

    /** Черга плюс місце, з якого її вмикати. */
    private data class PlaybackRequest(
        val items: List<MediaItem>,
        val startIndex: Int,
        val startPositionMs: Long,
    )

    /** Книги, що збігаються із запитом. Спільне для браузера й для голосу. */
    private suspend fun booksMatching(query: String): List<ua.nichnyk.listen.data.BookWithChapters> {
        val books = runCatching { db.library().getAllBooks() }.getOrDefault(emptyList())
        if (query.isBlank()) return books
        // Ті самі три поля, що й у пошуку на полиці (ShelfQuery.filter): без
        // серії голосовий запит назвою циклу не знаходив у машині нічого.
        return books.filter { b ->
            b.book.title.contains(query, ignoreCase = true) ||
                b.book.author.contains(query, ignoreCase = true) ||
                b.book.series?.contains(query, ignoreCase = true) == true
        }
    }

    /**
     * Що саме просить контролер увімкнути — і з якого місця.
     *
     * Три різні шляхи приходять сюди одним: тап по книзі в браузері Auto, тап
     * по конкретній главі та голосове «увімкни таку-то книгу». Останнє media3
     * доставляє як елемент з **порожнім** ідентифікатором і запитом у
     * requestMetadata.searchQuery; доти він не впізнавався ніяк, і в чергу
     * лягав елемент без URI — тобто голосовий пошук мовчки не грав.
     *
     * Місце старту різне й теж навмисно:
     *
     * - книга — зі збереженої позиції, інакше картка обіцяє «прослухано 60 %»,
     *   а натискання відкидає слухача на початок першої глави;
     * - конкретна глава — з її початку, як робить jumpToChapter на телефоні:
     *   людина вибрала главу, а не «продовжити».
     */
    private suspend fun resolvePlaybackRequest(item: MediaItem): PlaybackRequest? {
        val dao = db.library()

        val book = runCatching { dao.getBook(item.mediaId) }.getOrNull()
        if (book != null) return fromStoredPosition(book)

        val byChapter = runCatching { dao.getBookForChapter(item.mediaId) }.getOrNull()
        if (byChapter != null) {
            val items = playbackQueue(byChapter)
            val index = items.indexOfFirst { it.mediaId == item.mediaId }
            if (index >= 0) return PlaybackRequest(items, index, 0L)
        }

        // playFromUri: ідентифікатора немає зовсім, а сам файл лежить у
        // requestMetadata. Без цієї гілки такий запит клав у чергу елемент без
        // URI — тобто мовчав.
        val mediaUri = item.requestMetadata.mediaUri?.toString()
        if (mediaUri != null) {
            val all = runCatching { dao.getAllBooks() }.getOrDefault(emptyList())
            for (b in all) {
                val index = b.chapters.sortedBy { it.index }.indexOfFirst { it.uri == mediaUri }
                if (index >= 0) return PlaybackRequest(playbackQueue(b), index, 0L)
            }
        }

        // Порожній запит — це голе «увімкни» без назви, і Assistant надсилає
        // саме його. booksMatching на порожньому віддає всю полицю в порядку
        // getAllBooks, тобто останню слухану першою: та сама книга, яку віддає
        // відновлення після перезапуску. Тому перевірка на null, а не на blank.
        val query = item.requestMetadata.searchQuery
        if (query != null) {
            val match = booksMatching(query).firstOrNull { it.chapters.isNotEmpty() }
            if (match != null) return fromStoredPosition(match)
        }

        return null
    }

    /**
     * Книга з місця, де слухача перервали минулого разу.
     *
     * Дослухана книга — окремий випадок, і арбітраж тут той самий, що на
     * телефоні ([replayTarget] у PlayerManager.play). Її збережене місце — це
     * остання секунда останньої глави, бо саме так її записує кінець
     * відтворення. Без цього тап по книзі з позначкою «дослухано» давав у
     * машині секунду звуку й одразу кінець.
     */
    private fun fromStoredPosition(
        book: ua.nichnyk.listen.data.BookWithChapters,
    ): PlaybackRequest? {
        val items = playbackQueue(book)
        if (items.isEmpty()) return null
        val (chapter, position) = replayTarget(
            completed = book.book.completed,
            requestedChapter = book.book.currentChapterIndex,
            requestedPositionMs = book.book.positionMs,
            savedChapter = book.book.currentChapterIndex,
            savedPositionMs = book.book.positionMs,
        )
        return PlaybackRequest(
            items = items,
            startIndex = chapter.coerceIn(0, items.size - 1),
            startPositionMs = position.coerceAtLeast(0L),
        )
    }

    private fun isTrustedController(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean {
        if (controller.uid == android.os.Process.myUid()) return true
        if (session.isMediaNotificationController(controller)) return true
        return runCatching { session.isAutomotiveController(controller) }.getOrDefault(false) ||
            runCatching { session.isAutoCompanionController(controller) }.getOrDefault(false)
    }

    /**
     * Відмова на сесійну команду, яка вмикає платну обробку звуку без Pro.
     * Мовчазний `RESULT_SUCCESS` тут був би гіршим: контролер вважав би, що
     * ефект увімкнено.
     */
    private fun denyWithoutPro(action: String): ListenableFuture<SessionResult> {
        AppLog.w("PlaybackService: команду $action відхилено — немає Pro")
        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
    }

    private fun applyVolumeBoost(enabled: Boolean) {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            if (!enabled) {
                releaseLoudnessEnhancer()
                return
            }
            if (loudnessEnhancer == null || loudnessEnhancer?.hasControl() == false) {
                loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(sessionId)
            }
            loudnessEnhancer?.let { le ->
                // Підсилення на 600 мБ (+6 дБ), достатньо для комфортного слухання тихих записів
                le.setTargetGain(600)
                le.enabled = true
            }
        }.onFailure { AppLog.w("PlaybackService: підсилювач гучності недоступний", it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * Користувач змахнув застосунок зі списку задач. Якщо нічого не грає — гасимо сервіс,
     * інакше в шторці залишалося б «мертве» сповіщення на паузі.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun releaseLoudnessEnhancer() {
        runCatching {
            loudnessEnhancer?.release()
        }
        loudnessEnhancer = null
    }

    override fun onDestroy() {
        scope.cancel()
        // voiceEqualizer тримає нативний AudioEffect: без release() він протікав
        // на кожен перезапуск сервісу.
        voiceEqualizer.release()
        releaseLoudnessEnhancer()
        runCatching { player.removeListener(playbackWatcher) }
        session?.release()
        session = null
        sessionPlayer = null
        // Плеєр звільняємо незалежно від того, чи існувала сесія, — інакше він міг протікати.
        runCatching { unregisterReceiver(disconnectReceiver) }
        runCatching { player.release() }
        super.onDestroy()
    }

    companion object {
        const val ROOT_ID = "[bookvoices_root]"

        // Вузли дерева Auto. Квадратні дужки — щоб ідентифікатор категорії
        // не можна було сплутати з UUID книги чи глави.
        const val CONTINUE_ID = "[bookvoices_continue]"
        const val ALL_BOOKS_ID = "[bookvoices_all]"
        const val AUTHORS_ID = "[bookvoices_authors]"
        const val AUTHOR_PREFIX = "[bookvoices_author]"

        /**
         * Початок id рядка-пояснення; далі йде вузол, якому той рядок належить.
         * Нікуди не веде, але має бути впізнаваним: Auto питає getItem і про
         * нього теж.
         */
        const val EMPTY_PREFIX = "[bookvoices_empty]"

        const val ACTION_SET_SKIP_SILENCE = "ua.nichnyk.listen.ACTION_SET_SKIP_SILENCE"
        const val EXTRA_SKIP_SILENCE = "extra_skip_silence"
        const val ACTION_SET_VOLUME_BOOST = "ua.nichnyk.listen.ACTION_SET_VOLUME_BOOST"
        const val EXTRA_VOLUME_BOOST = "extra_volume_boost"

        /**
         * Скільки після зупинки відтворення розрив гарнітури ще вважається
         * «перервали слухання» — див. [lastStoppedAtMs].
         */
        const val NOISY_GRACE_MS = 3_000L

        /** Скільки чекати на другий тап, перш ніж вважати натискання одиночним. */
        const val HEADSET_MULTI_TAP_WINDOW_MS = 350L
    }
}
