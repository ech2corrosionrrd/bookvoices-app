package ua.nichnyk.listen.playback

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.concurrent.futures.await
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import ua.nichnyk.listen.forAppLocale
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.SmartRewindLogic
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.data.UserSettings
import ua.nichnyk.listen.data.VoicePreset
import ua.nichnyk.listen.data.replayTarget

enum class SleepTimerMode {
    Minutes,
    EndOfChapter,
    EndOfBook,
}

data class JumpBackTarget(
    val chapterIndex: Int,
    val positionMs: Long,
    val timeLabel: String,
)

/**
 * Збій відтворення з уже локалізованим текстом.
 *
 * Потрібен, щоб [PlayerManager.errorMessage] відрізняв власні повідомлення від
 * технічних рядків media3 і системи: доти в снекбар ішло `error.message` як є,
 * тобто зазвичай англійський текст ExoPlayer посеред українського інтерфейсу.
 */
class PlaybackMessageException(override val message: String) : Exception(message)

data class PlayerUiState(
    val connected: Boolean = false,
    val book: BookWithChapters? = null,
    val chapterIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val speed: Float = 1f,
    val sleepRemainingMs: Long? = null,
    val sleepMode: SleepTimerMode? = null,
    /**
     * Скільки хвилин завели останнім таймером — саме вибір користувача, а не
     * залишок. Чіпи в шторці сну підсвічувалися за `sleepRemainingMs`, тож
     * вибраним виглядав той, повз чиє значення зараз проходить зворотний
     * відлік: заведені 15 хвилин через десять показувалися як «5 хв».
     */
    val sleepPresetMinutes: Int? = null,
    val skipSilence: Boolean = false,
    val monoAudio: Boolean = false,
    val volumeBoost: Boolean = false,
    val queue: List<BookWithChapters> = emptyList(),
    /** Крок перемотування з налаштувань — віджету він потрібен для підпису кнопок. */
    val skipBackMs: Int = 15_000,
    val skipForwardMs: Int = 30_000,
    val jumpBackTarget: JumpBackTarget? = null,
    val voicePreset: VoicePreset = VoicePreset.OFF,
    val pitch: Float = 1.0f,
) {
    val isSleepEndOfChapter: Boolean
        get() = sleepMode == SleepTimerMode.EndOfChapter

    val isSleepEndOfBook: Boolean
        get() = sleepMode == SleepTimerMode.EndOfBook

    // book.chapters тут завжди вже відсортовані за index (див. PlayerManager.prepare),
    // тому геттери не сортують на кожній рекомпозиції.
    val chapter: ChapterEntity?
        get() = book?.chapters?.getOrNull(chapterIndex)

    val bookProgress: Float
        get() = ua.nichnyk.listen.data.bookProgressFraction(
            chapters = book?.chapters.orEmpty(),
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            durationMs = book?.book?.durationMs ?: 0L,
        )

    /**
     * Усе, що видно у віджеті «зараз грає», і нічого більше.
     *
     * Прогрес — у цілих відсотках: смужка віджета все одно має 100 поділок, а
     * positionMs змінюється 2,5 рази на секунду.
     */
    fun widgetSnapshot(): WidgetSnapshot = WidgetSnapshot(
        bookId = book?.book?.id,
        title = book?.book?.title,
        subtitle = chapter?.title ?: book?.book?.author,
        coverPath = book?.book?.coverPath,
        progressPercent = (bookProgress * 100).toInt().coerceIn(0, 100),
        isPlaying = isPlaying,
        skipBackMs = skipBackMs,
        skipForwardMs = skipForwardMs,
    )
}

/**
 * Те, що змінюється на кожен тик плеєра — 2,5 рази на секунду.
 *
 * Винесено окремо, бо [PlayerUiState] цілком читався на верхньому рівні
 * PlayerScreen і ListenAppRoot: нова позиція означала нову копію стану, тобто
 * рекомпозицію всього екрана — а в корені навігації ще й усього застосунку.
 * Тепер позицію читають тільки ті кілька елементів, яким вона потрібна.
 */
data class PlaybackTick(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bookProgress: Float = 0f,
)

/** Тікаюча частина стану — для тих, хто справді малює позицію. */
fun PlayerUiState.tick(): PlaybackTick = PlaybackTick(
    positionMs = positionMs,
    durationMs = durationMs,
    bookProgress = bookProgress,
)

/**
 * Той самий стан, але без позиції: рівні між тиками, тож `distinctUntilChanged`
 * гасить 2,5 емісії на секунду.
 *
 * `positionMs` тут навмисно скинутий у нуль, а не лишений «яким був». Якщо
 * позицію звідси все ж прочитають, годинник стане на нулі — це видно з першого
 * запуску, тоді як тихо застаріле значення помітили б місяцями. Те саме
 * стосується [PlayerUiState.bookProgress], який рахується з позиції.
 *
 * `sleepRemainingMs` лишається тут свідомо: він тікає раз на секунду й лише
 * поки заведений таймер сну, тобто в сценарії, який сам собою короткий.
 */
fun PlayerUiState.withoutPosition(): PlayerUiState = copy(positionMs = 0L)

/** Знімок стану для віджета: рівність цих полів означає, що перемальовувати нічого. */
data class WidgetSnapshot(
    val bookId: String?,
    val title: String?,
    val subtitle: String?,
    val coverPath: String?,
    val progressPercent: Int,
    val isPlaying: Boolean,
    val skipBackMs: Int,
    val skipForwardMs: Int,
)

class PlayerManager(
    private val app: Application,
    private val repo: LibraryRepository,
    private val prefs: UserPrefs,
) {
    // Обробник, а не голий SupervisorJob: тут живуть тікер, таймери сну й запис
    // прогресу — усе, що працює, поки грає книга. Виняток у будь-якому з них
    // без обробника йшов би в дефолтний обробник потоку, тобто знімав процес
    // разом із відтворенням. Див. crashSafeHandler.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + ua.nichnyk.listen.crashSafeHandler("PlayerManager"),
    )
    private val connectMutex = Mutex()
    private val prepareMutex = Mutex()
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    private var ticker: Job? = null
    private var sleepJob: Job? = null

    /** Живе, поки триває пільгове вікно після спрацювання таймера сну. */
    private var sleepGraceJob: Job? = null

    /** Відкладений запис швидкості: слайдер шле десятки значень за одне протягування. */
    private var speedPersistJob: Job? = null

    /** Те саме для висоти тону. */
    private var pitchPersistJob: Job? = null

    /** У якому режимі був останній таймер — щоб струшування завело такий самий. */
    private var lastSleepMode: SleepTimerMode? = null
    private var settings = UserSettings()
    private var loadedBookId: String? = null
    private var endOfChapterTargetIndex: Int? = null

    /** true лише після того, як користувач реально запустив відтворення поточної книги. */
    private var playbackStarted = false
    private var endedHandled = false
    private var lastPersistedPositionMs = -1L
    private var lastPersistedChapterIndex = -1
    private var pendingListenMs = 0L
    private var errorSkipCount = 0
    private var releasingController = false

    /** Глави, тривалість яких уже полікували цим запуском; див. [repairChapterDurationIfUnknown]. */
    private val repairedChapterIds = mutableSetOf<String>()

    private val shakeDetector = ShakeDetector(app) {
        onShakeDetected()
    }

    private val sessionListener = object : MediaController.Listener {
        override fun onDisconnected(disconnected: MediaController) {
            if (releasingController) return
            if (controller !== disconnected) return
            val started = playbackStarted
            val snapshot = _state.value
            flushListening()
            if (started) {
                val book = snapshot.book
                if (book != null) {
                    scope.launch {
                        runCatching {
                            repo.saveProgress(
                                bookId = book.book.id,
                                chapterIndex = snapshot.chapterIndex,
                                positionMs = snapshot.positionMs,
                                durationHint = book.book.durationMs,
                                speed = snapshot.speed,
                                completed = null,
                            )
                        }
                    }
                }
            }
            controller = null
            loadedBookId = null
            playbackStarted = false
            _state.update { it.copy(connected = false, isPlaying = false, isBuffering = false) }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playbackState == Player.STATE_READY) errorSkipCount = 0
            publish(player)
            if (player.playbackState == Player.STATE_ENDED && !player.hasNextMediaItem()) {
                // onEvents спрацьовує багато разів поспіль у стані ENDED — пишемо лише раз.
                if (endedHandled) return
                endedHandled = true
                val book = _state.value.book
                if (book != null) {
                    scope.launch {
                        flushListening()
                        runCatching {
                            repo.saveProgress(
                                bookId = book.book.id,
                                chapterIndex = player.currentMediaItemIndex,
                                positionMs = player.duration.coerceAtLeast(0),
                                durationHint = book.book.durationMs,
                                speed = player.playbackParameters.speed,
                                completed = true,
                            )
                        }
                        val isSleepActive = _state.value.sleepMode != null || sleepJob != null || sleepGraceJob != null
                        val q = _state.value.queue
                        if (q.isNotEmpty() && !isSleepActive) {
                            val nextBook = q.first()
                            // Через removeFromQueue, а не _state.update: інакше знята
                            // з черги книга лишалася б у ній на диску й повернулася
                            // після перезапуску процесу.
                            removeFromQueue(nextBook.book.id)
                            play(nextBook)
                        }
                    }
                }
            } else if (player.playbackState != Player.STATE_ENDED) {
                endedHandled = false
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            val currentItem = mediaItem ?: return
            val currentBook = _state.value.book
            val chapterId = currentItem.mediaId
            if (currentBook == null || currentBook.chapters.none { it.id == chapterId }) {
                scope.launch {
                    val bookIdFromExtra = currentItem.mediaMetadata.extras?.getString("book_id")
                    val book = if (bookIdFromExtra != null) repo.getBook(bookIdFromExtra) else repo.getBookForChapter(chapterId)
                    if (book != null) {
                        loadedBookId = book.book.id
                        // Сортування тут — не прикраса: PlayerUiState.chapter бере
                        // главу за позицією в списку, а @Relation порядку не
                        // обіцяє. Це шлях «почали з Auto чи шторки», і без
                        // сортування на телефоні світилася б чужа назва глави.
                        _state.update { s ->
                            s.copy(book = book.copy(chapters = book.chapters.sortedBy { ch -> ch.index }))
                        }
                        prefs.setLastBookId(book.book.id)
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val c = liveController()
            if (c != null) publish(c)
            val io = error.errorCode in IO_ERROR_CODES
            val msg = if (io) {
                app.forAppLocale().getString(R.string.file_unavailable)
            } else {
                app.forAppLocale().getString(R.string.play_failed)
            }
            if (io && c != null && c.isConnected && c.hasNextMediaItem() && errorSkipCount < MAX_ERROR_SKIPS) {
                errorSkipCount++
                _errors.trySend(app.forAppLocale().getString(R.string.skipped_missing_chapter))
                runCatching {
                    c.seekToNextMediaItem()
                    c.prepare()
                    c.play()
                }.onFailure {
                    errorSkipCount = 0
                    _errors.trySend(msg)
                }
            } else {
                errorSkipCount = 0
                _errors.trySend(msg)
            }
        }
    }

    fun initialize() {
        scope.launch {
            prefs.settings.collect { s ->
                settings = s
                if (_state.value.skipBackMs != s.skipBackMs || _state.value.skipForwardMs != s.skipForwardMs) {
                    _state.update { it.copy(skipBackMs = s.skipBackMs, skipForwardMs = s.skipForwardMs) }
                }
                if (_state.value.skipSilence != s.skipSilence) {
                    _state.update { it.copy(skipSilence = s.skipSilence) }
                    sendSkipSilence(s.skipSilence)
                }
                if (_state.value.voicePreset != s.voicePreset) {
                    _state.update { it.copy(voicePreset = s.voicePreset) }
                }
                if (_state.value.pitch != s.pitch) {
                    _state.update { it.copy(pitch = s.pitch) }
                    applyPlaybackParameters(_state.value.speed, s.pitch)
                }
                if (_state.value.monoAudio != s.monoAudio) {
                    _state.update { it.copy(monoAudio = s.monoAudio) }
                }
                if (_state.value.volumeBoost != s.volumeBoost) {
                    _state.update { it.copy(volumeBoost = s.volumeBoost) }
                }
                if (!s.shakeToExtendSleep) {
                    disarmSleepGrace()
                    shakeDetector.stop()
                } else if (_state.value.sleepRemainingMs != null || sleepGraceJob != null) {
                    shakeDetector.start()
                }
            }
        }
        scope.launch {
            runCatching { connectController() }
            startTicker()
            restoreQueue()
            adoptRunningSession()
            restoreLastBookIfNeeded()
            prefs.lastBookId.collect { id ->
                if (id != null && loadedBookId == null && liveController()?.isPlaying != true) {
                    restoreLastBookIfNeeded()
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            // Не на кожну емісію стану: тікер оновлює positionMs кожні 400 мс, а
            // віджет показує лише назву, главу, кнопку й прогрес у цілих відсотках.
            // Раніше кожен тик робив IPC до AppWidgetManager і декодував обкладинку
            // з диска — і все це на головному потоці, ще й коли віджета взагалі немає.
            _state.map { it.widgetSnapshot() }
                .distinctUntilChanged()
                .collect {
                    ua.nichnyk.listen.ui.widget.NowPlayingWidgetProvider
                        .updateAllWidgets(app, _state.value)
                }
        }
    }

    suspend fun play(
        book: BookWithChapters,
        chapterIndex: Int = book.book.currentChapterIndex,
        positionMs: Long = book.book.positionMs,
        applySmartRewind: Boolean = true,
    ) {
        // Черга — це «що грати після цієї книги», тож книга, яку щойно запустили,
        // з неї вибуває. Інакше поставлена в чергу й одразу відкрита книга
        // доходила до кінця й починалася наново — сама себе наступною.
        removeFromQueue(book.book.id)
        val (idx, pos) = replayTarget(
            completed = book.book.completed,
            requestedChapter = chapterIndex,
            requestedPositionMs = positionMs,
            savedChapter = book.book.currentChapterIndex,
            savedPositionMs = book.book.positionMs,
        )
        val targetPos = if (applySmartRewind && settings.smartRewind && !book.book.completed) {
            SmartRewindLogic.applyToPosition(book.book.lastPlayedAt, pos)
        } else {
            pos
        }
        prepare(book, idx, targetPos, autoPlay = true)
        prefs.setLastBookId(book.book.id)
        if (book.book.completed) {
            runCatching {
                repo.saveProgress(
                    bookId = book.book.id,
                    chapterIndex = idx,
                    positionMs = targetPos,
                    durationHint = book.book.durationMs,
                    speed = _state.value.speed,
                    completed = false,
                )
            }
            _state.update { s ->
                val current = s.book ?: return@update s
                s.copy(book = current.copy(book = current.book.copy(completed = false, currentChapterIndex = idx, positionMs = targetPos)))
            }
        }
    }

    suspend fun playBookmark(book: BookWithChapters, chapterId: String, positionMs: Long) {
        val index = book.chapters.sortedBy { it.index }.indexOfFirst { it.id == chapterId }
        if (index < 0) {
            // Раніше тут стояв coerceAtLeast(0), тобто «не знайдено» ставало
            // першою главою: закладка мовчки вмикала книгу з початку на своїй
            // позиції. Глави з таким id справді може не бути — доливання файлів
            // і перепривʼязка дають новий набір, — і про це треба сказати.
            _errors.trySend(app.forAppLocale().getString(R.string.bookmark_chapter_missing))
            return
        }
        play(book, index, positionMs, applySmartRewind = false)
    }

    fun playPause() {
        scope.launch {
            runCatching {
                val c = requireController()
                if (c.mediaItemCount == 0) {
                    val book = _state.value.book ?: prefs.lastBookId.first()?.let { repo.getBook(it) }
                    if (book != null) play(book)
                    return@runCatching
                }
                if (c.isPlaying) {
                    c.pause()
                } else {
                    applySmartRewindOnResume()
                    c.play()
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _errors.trySend(errorMessage(e))
            }
        }
    }

    fun pause() {
        withLiveController { it.pause() }
    }

    /**
     * Відкат при поверненні до книги після паузи.
     *
     * Джерело часу — `lastPlayedAt` у базі, те саме, що й у [play]. Тікер пише
     * його на кожному переході isPlaying → false (persist(force = true)), тож
     * **звідки поставили на паузу — не має значення**: шторка, Android Auto,
     * втрата аудіофокусу й кнопка в застосунку дають однаковий результат.
     * Окреме поле в памʼяті цього не вміло — воно жило лише поки живий процес і
     * заповнювалося тільки паузою через PlayerManager.
     *
     * **Чого цей метод поки не покриває.** Він висить на [playPause], тобто на
     * кнопці в застосунку. Пуск зі шторки, з Auto чи з гарнітури йде прямо до
     * сесійного плеєра й відкату не дістає: пауза на ніч і ранкове «play» з
     * екрана блокування продовжать рівно з того місця, де зупинилися.
     *
     * Зробити це рівномірно означає перенести відкат у сам сесійний плеєр і
     * тримати там мітку зупинки — а це стан, який легко подвоїти з відкатом у
     * [play] на старті книги. Перед релізом такий обмін не вартий того; лишаємо
     * як відому межу (див. ROADMAP, хвиля D).
     */
    private suspend fun applySmartRewindOnResume() {
        if (!settings.smartRewind) return
        val bookId = _state.value.book?.book?.id ?: return
        // Саме з бази, а не зі знімка стану: у памʼяті лежить копія, зроблена в
        // момент завантаження книги, і після годин відтворення вона застаріла.
        val lastPlayedAt = runCatching { repo.getBook(bookId)?.book?.lastPlayedAt }.getOrNull() ?: return
        val rewind = SmartRewindLogic.calculateRewindMs(System.currentTimeMillis() - lastPlayedAt)
        if (rewind > 0L) seekBy(-rewind)
    }

    /**
     * Зупиняє відтворення й ховає міні-плеєр, якщо зараз грає саме ця книга.
     * Suspend: викликач (deleteBook) чекає stop/clear до стирання файлів.
     */
    suspend fun clearIfCurrent(bookId: String) {
        removeFromQueue(bookId)
        prepareMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                if (_state.value.book?.book?.id != bookId && loadedBookId != bookId) return@withContext
                stopAndForgetLocked()
            }
        }
        prefs.setLastBookId(null)
    }

    /** Повна зупинка: відновлення бекапу замінює полицю, старий плеєр лишати небезпечно. */
    suspend fun resetPlayback() {
        clearQueue()
        prepareMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                stopAndForgetLocked()
            }
        }
        prefs.setLastBookId(null)
    }

    private fun stopAndForgetLocked() {
        cancelSleep()
        playbackStarted = false
        endedHandled = true
        loadedBookId = null
        lastPersistedPositionMs = -1L
        lastPersistedChapterIndex = -1
        pendingListenMs = 0L
        val snapshot = _state.value
        // Черга переживає зупинку поточної книги: сюди приходять і видалення однієї
        // книги, і перепривʼязка файлів, а вони до списку «далі» стосунку не мають.
        // Свідоме очищення черги йде окремо — через clearQueue() у resetPlayback().
        _state.value = PlayerUiState(
            connected = snapshot.connected,
            skipSilence = snapshot.skipSilence,
            // Пресет і висота тону — налаштування застосунку, а не властивість
            // книги: зупинка поточної книги їх не скидає.
            voicePreset = snapshot.voicePreset,
            pitch = snapshot.pitch,
            queue = snapshot.queue,
            skipBackMs = snapshot.skipBackMs,
            skipForwardMs = snapshot.skipForwardMs,
        )
        withLiveController { c ->
            c.pause()
            c.stop()
            c.clearMediaItems()
        }
    }

    fun skipBack() {
        seekBy(-settings.skipBackMs.toLong())
    }

    fun skipForward() {
        seekBy(settings.skipForwardMs.toLong())
    }

    private fun recordJumpBack(prevChapterIndex: Int, prevPositionMs: Long) {
        val timeLabel = jumpBackLabel(prevPositionMs)
        _state.update { it.copy(jumpBackTarget = JumpBackTarget(prevChapterIndex, prevPositionMs, timeLabel)) }
    }

    fun jumpBack() {
        val target = _state.value.jumpBackTarget ?: return
        _state.update { it.copy(jumpBackTarget = null) }
        withLiveController { c ->
            if (target.chapterIndex in 0 until c.mediaItemCount) {
                // Без c.play(): повернення до попереднього місця — це перемотка,
                // а не команда «грай». Доти воно знімало з паузи, тоді як та сама
                // перемотка слайдером паузу зберігала.
                c.seekTo(target.chapterIndex, target.positionMs)
            }
        }
    }

    fun dismissJumpBack() {
        _state.update { it.copy(jumpBackTarget = null) }
    }

    fun seekChapter(positionMs: Long) {
        val s = _state.value
        if (kotlin.math.abs(s.positionMs - positionMs) > 30_000L) {
            recordJumpBack(s.chapterIndex, s.positionMs)
        }
        withLiveController { it.seekTo(positionMs.coerceAtLeast(0L)) }
    }

    fun jumpToChapter(index: Int) {
        val s = _state.value
        if (s.chapterIndex != index || s.positionMs > 10_000L) {
            recordJumpBack(s.chapterIndex, s.positionMs)
        }
        withLiveController { c ->
            if (index in 0 until c.mediaItemCount) {
                // Так само без c.play(): гортання списку глав на паузі не має
                // раптово вмикати звук.
                c.seekTo(index, 0L)
            }
        }
    }

    /**
     * Застосовує швидкість негайно, а запис у БД відкладає.
     *
     * Слайдер швидкості викликає це на кожному кадрі перетягування, а раніше кожен
     * виклик робив UPDATE books — десятки записів за одне протягування пальцем.
     * Тепер зберігається лише останнє значення, і лише коли рука зупинилася.
     */
    fun setSpeed(speed: Float) {
        applyPlaybackParameters(speed, _state.value.pitch)
        _state.update { it.copy(speed = speed) }
        speedPersistJob?.cancel()
        speedPersistJob = scope.launch {
            delay(SPEED_PERSIST_DEBOUNCE_MS)
            persistSpeed(speed)
        }
    }

    /**
     * Висота тону застосовується негайно, а запис у налаштування відкладається:
     * слайдер шле десятки значень за одне протягування — те саме, що зі швидкістю.
     */
    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.8f, 1.2f)
        _state.update { it.copy(pitch = clamped) }
        applyPlaybackParameters(_state.value.speed, clamped)
        pitchPersistJob?.cancel()
        pitchPersistJob = scope.launch {
            delay(SPEED_PERSIST_DEBOUNCE_MS)
            prefs.setPitch(clamped)
        }
    }

    /**
     * Пресет лише пишеться в налаштування — далі його читає власним збирачем
     * PlaybackService, як і моно.
     *
     * Дублювати ще й кастомною командою означало б два джерела правди для одного
     * апаратного ефекту; до того ж сервіс має знати пресет і тоді, коли жоден
     * контролер до нього не підключений — саме тому вибір не переживав
     * перезапуску процесу, поки їхав лише командою.
     */
    fun setVoicePreset(preset: VoicePreset) {
        _state.update { it.copy(voicePreset = preset) }
        scope.launch { prefs.setVoicePreset(preset) }
    }

    private fun applyPlaybackParameters(speed: Float, pitch: Float) {
        withLiveController { it.playbackParameters = androidx.media3.common.PlaybackParameters(speed, pitch) }
    }

    private suspend fun persistSpeed(speed: Float) {
        val s = _state.value
        val book = s.book ?: return
        runCatching {
            repo.saveProgress(
                bookId = book.book.id,
                chapterIndex = s.chapterIndex,
                positionMs = s.positionMs,
                durationHint = book.book.durationMs,
                speed = speed,
                completed = null,
                // Зміна швидкості до старту відтворення не має піднімати книгу на полиці.
                touchLastPlayed = playbackStarted,
            )
        }
    }

    fun setSkipSilence(enabled: Boolean) {
        _state.update { it.copy(skipSilence = enabled) }
        sendSkipSilence(enabled)
        scope.launch { prefs.setSkipSilence(enabled) }
    }

    private fun sendSkipSilence(enabled: Boolean) {
        val args = Bundle().apply { putBoolean(PlaybackService.EXTRA_SKIP_SILENCE, enabled) }
        withLiveController {
            it.sendCustomCommand(SessionCommand(PlaybackService.ACTION_SET_SKIP_SILENCE, Bundle.EMPTY), args)
        }
    }

    /**
     * Прапорець тільки пишеться в налаштування: сам конвеєр звуку читає його в
     * PlaybackService власним збирачем. Дублювати ще й кастомною командою через
     * сесію означало б два джерела правди для одного тумблера — а сервіс має
     * знати про моно й тоді, коли жоден контролер до нього не підключений.
     */
    fun setMonoAudio(enabled: Boolean) {
        _state.update { it.copy(monoAudio = enabled) }
        scope.launch { prefs.setMonoAudio(enabled) }
    }

    fun setVolumeBoost(enabled: Boolean) {
        _state.update { it.copy(volumeBoost = enabled) }
        scope.launch { prefs.setVolumeBoost(enabled) }
    }


    /**
     * [android.os.SystemClock.elapsedRealtime], а не системний час: синхронізація з NTP
     * або зміна часового поясу зсувала стінний годинник, і таймер сну спрацьовував
     * не тоді, коли його ставили.
     */
    fun startSleep(minutes: Int) {
        cancelSleep()
        var left = minutes * 60_000L
        lastSleepMode = SleepTimerMode.Minutes
        _state.update {
            it.copy(sleepMode = SleepTimerMode.Minutes, sleepPresetMinutes = minutes, sleepRemainingMs = left)
        }
        if (settings.shakeToExtendSleep) shakeDetector.start()
        sleepJob = scope.launch {
            var lastTick = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                // Крок відліку — див. SleepLogic; ніколи не перестрибує кінець таймера.
                delay(nextSleepTickDelay(left))
                val now = android.os.SystemClock.elapsedRealtime()
                val elapsed = now - lastTick
                lastTick = now
                // Пауза зупиняє відлік. Доти таймер жив за стінним годинником і
                // догоряв у тиші: поставив 15 хвилин, зупинив на 20 — таймера вже
                // немає, хоча слухач вважає його заведеним. Два інші режими
                // рахують по відтворенню, і цей має рахувати так само.
                if (liveController()?.isPlaying != true) continue
                left -= elapsed
                if (left <= 0L) {
                    fadeAndPause()
                    _state.update { it.copy(sleepRemainingMs = null, sleepMode = null, sleepPresetMinutes = null) }
                    armSleepGrace()
                    break
                }
                _state.update { it.copy(sleepRemainingMs = left) }
            }
        }
    }

    fun startSleepUntilChapterEnd() {
        cancelSleep()
        lastSleepMode = SleepTimerMode.EndOfChapter
        _state.update { it.copy(sleepMode = SleepTimerMode.EndOfChapter) }
        if (settings.shakeToExtendSleep) shakeDetector.start()

        sleepJob = scope.launch {
            endOfChapterTargetIndex = (liveController() ?: return@launch).currentMediaItemIndex
            while (isActive) {
                val ctrl = liveController() ?: break
                val targetIdx = endOfChapterTargetIndex ?: break
                val nowIdx = ctrl.currentMediaItemIndex
                val dur = ctrl.duration.takeIf { it > 0 } ?: _state.value.durationMs
                val pos = ctrl.currentPosition
                val remaining = (dur - pos).coerceAtLeast(0L)

                _state.update { it.copy(sleepRemainingMs = wallClockMs(remaining)) }

                // Згасання починається завчасно, щоб завершитися саме на межі глави.
                // Раніше поріг був 1 с, а саме згасання триває майже 3 с — плеєр
                // гарантовано встигав перейти в наступну главу до паузи.
                if (nowIdx != targetIdx || remaining <= sleepLeadContentMs() || ctrl.playbackState == Player.STATE_ENDED) {
                    val wasOnTarget = nowIdx == targetIdx
                    fadeAndPause()
                    if (wasOnTarget) {
                        // Якщо згасання все ж перетнуло межу, стаємо на початок нової глави:
                        // інакше збережена позиція опиняється всередині наступної,
                        // і слухач втрачає місце, на якому заснув.
                        val landed = liveController()?.currentMediaItemIndex
                        if (landed != null && landed != targetIdx) {
                            withLiveController { it.seekTo(landed, 0L) }
                        }
                    }
                    _state.update { it.copy(sleepRemainingMs = null, sleepMode = null, sleepPresetMinutes = null) }
                    endOfChapterTargetIndex = null
                    armSleepGrace()
                    break
                }
                delay(SLEEP_POLL_MS)
            }
        }
    }

    fun startSleepUntilBookEnd() {
        cancelSleep()
        lastSleepMode = SleepTimerMode.EndOfBook
        _state.update { it.copy(sleepMode = SleepTimerMode.EndOfBook) }
        if (settings.shakeToExtendSleep) shakeDetector.start()

        sleepJob = scope.launch {
            while (isActive) {
                val ctrl = liveController() ?: break
                val book = _state.value.book ?: break
                val chapters = book.chapters
                val currentIdx = ctrl.currentMediaItemIndex
                val currentPos = ctrl.currentPosition
                val currentDur = ctrl.duration.takeIf { it > 0 } ?: chapters.getOrNull(currentIdx)?.durationMs ?: 0L

                val remainingInCurrent = (currentDur - currentPos).coerceAtLeast(0L)
                val remainingInUpcoming = if (currentIdx + 1 < chapters.size) {
                    chapters.subList(currentIdx + 1, chapters.size).sumOf { it.durationMs }
                } else 0L
                val totalRemaining = remainingInCurrent + remainingInUpcoming

                _state.update { it.copy(sleepRemainingMs = wallClockMs(totalRemaining)) }

                val isLastChapter = currentIdx >= chapters.size - 1
                if ((isLastChapter && remainingInCurrent <= sleepLeadContentMs()) || ctrl.playbackState == Player.STATE_ENDED) {
                    fadeAndPause()
                    _state.update { it.copy(sleepRemainingMs = null, sleepMode = null, sleepPresetMinutes = null) }
                    armSleepGrace()
                    break
                }
                delay(SLEEP_POLL_MS)
            }
        }
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        disarmSleepGrace()
        shakeDetector.stop()
        endOfChapterTargetIndex = null
        withLiveController { it.volume = 1f }
        _state.update { it.copy(sleepRemainingMs = null, sleepMode = null, sleepPresetMinutes = null) }
    }

    fun addToQueue(book: BookWithChapters) {
        updateQueue { queue ->
            if (queue.any { it.book.id == book.book.id } || _state.value.book?.book?.id == book.book.id) {
                queue
            } else {
                queue + book
            }
        }
    }

    fun removeFromQueue(bookId: String) {
        updateQueue { queue -> queue.filterNot { it.book.id == bookId } }
    }

    fun clearQueue() {
        updateQueue { emptyList() }
    }

    /**
     * Єдиний шлях зміни черги: оновити стан і одразу відкласти новий порядок на диск.
     *
     * Раніше черга жила лише в PlayerUiState, а систему ніщо не тримає від того, щоб
     * прибрати процес застосунку на паузі — і список «далі» зникав, нічого не сказавши.
     */
    private fun updateQueue(transform: (List<BookWithChapters>) -> List<BookWithChapters>) {
        val before = _state.value.queue.map { it.book.id }
        _state.update { s -> s.copy(queue = transform(s.queue)) }
        val after = _state.value.queue.map { it.book.id }
        if (before == after) return
        scope.launch {
            runCatching { repo.saveQueue(after) }
                .onFailure { AppLog.w("PlayerManager: черга не збереглася", it) }
        }
    }

    /** Відновлює чергу з бази при старті процесу. Викликається один раз із initialize(). */
    private suspend fun restoreQueue() {
        if (_state.value.queue.isNotEmpty()) return
        val saved = runCatching { repo.loadQueue() }.getOrNull().orEmpty()
        if (saved.isEmpty()) return
        _state.update { s -> if (s.queue.isEmpty()) s.copy(queue = saved) else s }
    }

    /**
     * Безумовно перечитує чергу з бази.
     *
     * Потрібне відновленню з бекапу: воно міняє queue_items повз плеєр, і копія
     * в памʼяті після цього описує вже неіснуючі книги.
     */
    suspend fun reloadQueue() {
        val saved = runCatching { repo.loadQueue() }.getOrNull().orEmpty()
        _state.update { it.copy(queue = saved) }
    }

    /**
     * Черга, а не полиця: [addToQueue] — це стан самого плеєра, тому пакетний
     * варіант лишається тут. Закріплення, серія, мітки й видалення книг поїхали
     * з цього класу до тих, хто ними й розпоряджається, — ViewModel і репозиторій.
     */
    fun batchAddToQueue(books: List<BookWithChapters>) {
        books.forEach { addToQueue(it) }
    }

    /**
     * Пільгове вікно після спрацювання таймера.
     *
     * Раніше акселерометр вимикався тієї ж миті, коли таймер ставив на паузу, — тобто
     * саме тоді, коли жест найпотрібніший: слухач куняє, за хвилину розплющує очі й
     * хоче продовжити, не шукаючи телефон. Тепер струшування ще [SLEEP_GRACE_MS]
     * повертає відтворення й заново заводить таймер у тому ж режимі.
     */
    private fun armSleepGrace() {
        if (!settings.shakeToExtendSleep) {
            shakeDetector.stop()
            return
        }
        sleepGraceJob?.cancel()
        shakeDetector.start()
        sleepGraceJob = scope.launch {
            delay(SLEEP_GRACE_MS)
            shakeDetector.stop()
            sleepGraceJob = null
        }
    }

    private fun disarmSleepGrace() {
        sleepGraceJob?.cancel()
        sleepGraceJob = null
    }

    private fun onShakeDetected() {
        if (!settings.shakeToExtendSleep) return
        withLiveController { it.volume = 1f }
        if (_state.value.sleepRemainingMs == null) {
            // Таймер уже спрацював: ми в пільговому вікні. Спершу знімаємо з паузи,
            // інакше заведений таймер відлічував би тишу.
            if (sleepGraceJob == null) return
            disarmSleepGrace()
            withLiveController { it.play() }
        }
        when (lastSleepMode) {
            SleepTimerMode.EndOfChapter -> startSleepUntilChapterEnd()
            SleepTimerMode.EndOfBook -> startSleepUntilBookEnd()
            else -> startSleep(SLEEP_EXTEND_MINUTES)
        }
        triggerHaptic()
    }

    private fun triggerHaptic() {
        runCatching {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = app.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            // Гілка для API < 26 прибрана: minSdk і так 26.
            vibrator?.vibrate(
                android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }



    /**
     * @return false, якщо зберігати не було чого: без книги або без поточної глави.
     * Доти екран показував «Закладку збережено» і в цьому випадку теж.
     */
    suspend fun addBookmark(note: String): Boolean {
        val s = _state.value
        val book = s.book ?: return false
        val chapter = s.chapter ?: return false
        repo.addBookmark(book.book.id, chapter, s.positionMs, note)
        return true
    }

    /**
     * Звільняє контролер і зупиняє всі фонові корутини.
     *
     * У продакшені PlayerManager живе стільки ж, скільки процес, тож не викликається;
     * потрібен тестам, щоб не лишати підключених контролерів між прогонами.
     */
    fun release() {
        ticker?.cancel()
        speedPersistJob?.cancel()
        pitchPersistJob?.cancel()
        sleepJob?.cancel()
        sleepGraceJob?.cancel()
        shakeDetector.stop()
        val c = controller
        controller = null
        if (c != null) {
            releasingController = true
            try {
                runCatching { c.removeListener(listener) }
                runCatching { c.release() }
            } finally {
                releasingController = false
            }
        }
        scope.cancel()
    }

    /**
     * Текст для снекбара. Показуємо тільки те, що самі й написали: `error.message`
     * у решті випадків — це технічний рядок media3 або системи, тобто англійський
     * текст посеред локалізованого інтерфейсу.
     */
    fun errorMessage(error: Throwable): String =
        (error as? PlaybackMessageException)?.message?.takeIf { it.isNotBlank() }
            ?: app.forAppLocale().getString(R.string.play_failed)

    private fun liveController(): MediaController? = controller?.takeIf { it.isConnected }

    /**
     * Команди контролеру завжди йдуть через головний потік.
     *
     * scope працює на Dispatchers.Main.immediate, тож виклик із UI виконується
     * синхронно, як і раніше, а виклик із будь-якого іншого потоку — постить задачу
     * замість того, щоб упасти в IllegalStateException і бути мовчки проковтнутим
     * цим самим runCatching.
     */
    private fun withLiveController(block: (MediaController) -> Unit) {
        scope.launch {
            val c = liveController() ?: return@launch
            runCatching { block(c) }.onFailure { AppLog.w("PlayerManager: команда контролеру", it) }
        }
    }

    private suspend fun connectController(): MediaController = connectMutex.withLock {
        withContext(Dispatchers.Main.immediate) { connectControllerLocked() }
    }

    private suspend fun connectControllerLocked(): MediaController {
        liveController()?.let { return it }
        val stale = controller
        if (stale != null) {
            releasingController = true
            try {
                runCatching { stale.removeListener(listener) }
                runCatching { stale.release() }
            } finally {
                releasingController = false
            }
            controller = null
        }
        var last: Throwable? = null
        repeat(5) { attempt ->
            try {
                val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                val ctrl = MediaController.Builder(app, token)
                    .setListener(sessionListener)
                    .buildAsync()
                    .await()
                controller = ctrl
                ctrl.addListener(listener)
                sendSkipSilence(settings.skipSilence)
                applyPlaybackParameters(_state.value.speed, settings.pitch)
                _state.update {
                    it.copy(
                        connected = true,
                        skipSilence = settings.skipSilence,
                        voicePreset = settings.voicePreset,
                        pitch = settings.pitch,
                    )
                }
                return ctrl
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                AppLog.w("PlayerManager: спроба ${attempt + 1} підключення до сервісу", e)
                delay(300L * (attempt + 1))
            }
        }
        error(last?.message ?: app.forAppLocale().getString(R.string.player_not_ready))
    }

    private suspend fun requireController(): MediaController {
        liveController()?.let { return it }
        return connectController()
    }

    /**
     * Підхоплює сесію, яка вже грала до появи цього PlayerManager.
     *
     * Процес може підняти не користувач, а система: відновлення відтворення з
     * Android Auto чи зі шторки створює сервіс, а разом із ним і Application.
     * Контролер тоді підключається до сесії, у якій уже стоїть плейлист, але
     * onMediaItemTransition більше не спрацює — і _state.book лишався б null,
     * а persist() виходив би на порожній книзі, тобто позиція не зберігалася б
     * до першої зміни глави.
     */
    private suspend fun adoptRunningSession() {
        val controller = liveController() ?: return
        if (controller.mediaItemCount == 0) return
        publish(controller)
        if (_state.value.book != null) return
        val item = controller.currentMediaItem ?: return
        val book = item.mediaMetadata.extras?.getString("book_id")?.let { repo.getBook(it) }
            ?: repo.getBookForChapter(item.mediaId)
            ?: return
        loadedBookId = book.book.id
        _state.update { it.copy(book = book.copy(chapters = book.chapters.sortedBy { ch -> ch.index })) }
        prefs.setLastBookId(book.book.id)
    }

    private suspend fun restoreLastBookIfNeeded() {
        if (loadedBookId != null) return
        if (liveController()?.isPlaying == true) return
        val id = prefs.lastBookId.first() ?: return
        val book = repo.getBook(id)
        if (book == null) {
            prefs.setLastBookId(null)
            return
        }
        runCatching { prepare(book, autoPlay = false) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                prefs.setLastBookId(null)
            }
    }

    /**
     * MediaController дозволено чіпати лише з головного потоку, тож перехід туди робить
     * сам PlayerManager, а не кожен викликач. Раніше це трималося на тому, що всі виклики
     * приходять із viewModelScope; один withContext(Dispatchers.IO) у новому місці
     * зламав би плеєр у рантаймі з IllegalStateException.
     */
    private suspend fun prepare(book: BookWithChapters, chapterIndex: Int = book.book.currentChapterIndex, positionMs: Long = book.book.positionMs, autoPlay: Boolean) {
        prepareMutex.withLock {
            withContext(Dispatchers.Main.immediate) { prepareLocked(book, chapterIndex, positionMs, autoPlay) }
        }
    }

    private suspend fun prepareLocked(book: BookWithChapters, chapterIndex: Int, positionMs: Long, autoPlay: Boolean) {
            val c = requireController()
            val chapters = book.chapters.sortedBy { it.index }
            if (chapters.isEmpty()) throw PlaybackMessageException(app.forAppLocale().getString(R.string.play_failed))

            val uriOk = chapters.map { it.uri }.distinct().associateWith { repo.isAudioAccessible(it) }
            val (index, startPos) = resolvePlayableStart(
                requestedIndex = chapterIndex,
                requestedPositionMs = positionMs,
                playable = chapters.map { uriOk[it.uri] == true },
            ) ?: throw PlaybackMessageException(
                app.forAppLocale().getString(R.string.file_unavailable_named, chapters.first().title),
            )

            val items = chapters.map { ch ->
                val builder = MediaItem.Builder()
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
                            .build()
                    )
                if (ch.startMs > 0L || ch.endMs > ch.startMs) {
                    val clip = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(ch.startMs.coerceAtLeast(0L))
                    if (ch.endMs > ch.startMs) {
                        clip.setEndPositionMs(ch.endMs)
                    }
                    builder.setClippingConfiguration(clip.build())
                }
                builder.build()
            }
            errorSkipCount = 0
            c.setMediaItems(items, index, startPos)
            // Книга, яку ще жодного разу не вмикали і в якої швидкість стоїть
            // рівно 1.0, свою швидкість не «обирала» — їй іде значення з
            // налаштувань. У всіх інших випадках виграє швидкість книги.
            val bookChoseSpeed = book.book.playbackSpeed > 0f &&
                (book.book.lastPlayedAt != null || book.book.playbackSpeed != 1f)
            val speed = if (bookChoseSpeed) book.book.playbackSpeed else settings.defaultSpeed
            // Разом зі швидкістю, а не setPlaybackSpeed: інакше збережена висота
            // тону лишалася в стані UI, але до плеєра на новій книзі не доходила.
            c.playbackParameters = androidx.media3.common.PlaybackParameters(speed, settings.pitch)
            sendSkipSilence(settings.skipSilence)
            c.prepare()
            loadedBookId = book.book.id
            playbackStarted = autoPlay
            endedHandled = false
            lastPersistedPositionMs = -1L
            lastPersistedChapterIndex = -1
            _state.update {
                it.copy(
                    book = book.copy(chapters = chapters),
                    chapterIndex = index,
                    positionMs = startPos,
                    speed = speed,
                    pitch = settings.pitch,
                    skipSilence = settings.skipSilence,
                    voicePreset = settings.voicePreset,
                    monoAudio = settings.monoAudio,
                )
            }
            if (autoPlay) c.play() else c.pause()
    }

    private fun seekBy(delta: Long) = withLiveController { c ->
        // Саме рішення — у чистій seekTarget (див. SeekLogic.kt), тут лишається виконання.
        when (
            val action = seekTarget(
                currentPositionMs = c.currentPosition,
                chapterDurationMs = c.duration,
                deltaMs = delta,
                hasPrevious = c.hasPreviousMediaItem(),
                hasNext = c.hasNextMediaItem(),
            )
        ) {
            is SeekAction.To -> c.seekTo(action.positionMs)
            SeekAction.PreviousChapter -> c.seekToPreviousMediaItem()
            SeekAction.NextChapter -> c.seekToNextMediaItem()
        }
    }

    /**
     * Тикер працює лише поки реально йде відтворення. На паузі корутина висить
     * на collectLatest і не будить процес кожні 400 мс — раніше це давало постійні
     * записи в БД і витрату батареї просто від відкритого додатка.
     *
     * У фоні (екран згорнуто) інтервал більший: шторку веде media3 сам, а UI
     * позиції ніхто не читає.
     *
     * Верхня межа розриву — одна константа на обидва режими, а не «інтервал × 5».
     * Прив'язана до режиму, вона розходилася з дійсністю рівно на переході «фон →
     * передній план»: розрив приходив від попереднього `delay(2000)`, а межу вже
     * рахували з переднього плану (400 × 5 = 2000). `delay` гарантує *не менше*
     * заданого, тож ці дві секунди щоразу не проходили за межу й мовчки зникали
     * зі статистики — тобто саме те, чого межа мала не допустити.
     *
     * Час беремо монотонний (`elapsedRealtime`), а не настінний: NTP чи ручна
     * зміна годинника давали від'ємний розрив, і та ж сама тиха втрата.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            _state.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                if (!playing) {
                    flushListening()
                    persist(force = true)
                    return@collectLatest
                }
                var n = 0
                var lastTickMs = SystemClock.elapsedRealtime()
                while (isActive) {
                    val uiVisible = ProcessLifecycleOwner.get().lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)
                    val tickMs = if (uiVisible) TICK_FOREGROUND_MS else TICK_BACKGROUND_MS

                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - lastTickMs
                    lastTickMs = now

                    liveController()?.let { ctrl ->
                        publish(ctrl)
                        if (ctrl.isPlaying && elapsed in MIN_TICK_GAP_MS..MAX_TICK_GAP_MS) {
                            pendingListenMs += elapsed
                        }
                    }
                    val persistEvery = if (uiVisible) 8 else 2
                    if (n % persistEvery == 0) persist()
                    if (pendingListenMs >= LISTEN_FLUSH_MS) flushListening()
                    n++
                    delay(tickMs)
                }
            }
        }
    }

    /** Статистику прослуховування накопичуємо в памʼяті й пишемо пачками, а не 150 разів на хвилину. */
    private fun flushListening() {
        val delta = pendingListenMs
        if (delta <= 0L) return
        pendingListenMs = 0L
        val book = _state.value.book
        val bookId = book?.book?.id
        val bookTitle = book?.book?.title
        val author = book?.book?.author
        scope.launch {
            runCatching {
                repo.recordListening(
                    deltaMs = delta,
                    bookId = bookId,
                    bookTitle = bookTitle,
                    author = author,
                )
            }
        }
    }


    private fun publish(player: Player) {
        if (player.isPlaying) {
            playbackStarted = true
            // Відтворення повернули кнопкою, а не струшуванням — пільгове вікно
            // більше не потрібне, інакше випадковий рух за хвилину завів би таймер сну.
            if (sleepGraceJob != null) {
                disarmSleepGrace()
                shakeDetector.stop()
            }
        }
        val book = _state.value.book
        val chapters = book?.chapters.orEmpty()
        val idx = player.currentMediaItemIndex.coerceAtLeast(0)
        val chapterDur = player.duration.takeIf { it > 0 } ?: chapters.getOrNull(idx)?.durationMs ?: 0L
        repairChapterDurationIfUnknown(book, chapters.getOrNull(idx), player.duration)
        _state.update {
            it.copy(
                chapterIndex = idx,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = chapterDur,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                speed = player.playbackParameters.speed,
                // Разом зі швидкістю: інакше слайдер показував 1.15, коли плеєр
                // після перепідключення насправді грав на 1.0.
                pitch = player.playbackParameters.pitch,
            )
        }
    }


    /**
     * Глава, тривалість якої не прочиталася при імпорті, лікується першим же
     * програшем: плеєр знає справжнє число, база — ні. Див.
     * [LibraryRepository.repairChapterDuration].
     *
     * Набір уже полікованих id — щоб publish, який приходить 2,5 рази на
     * секунду, не ставив у чергу той самий запис знову й знову.
     */
    private fun repairChapterDurationIfUnknown(
        book: BookWithChapters?,
        chapter: ChapterEntity?,
        playerDurationMs: Long,
    ) {
        if (book == null || chapter == null) return
        if (chapter.durationMs > 0L) return
        if (playerDurationMs <= 0L) return
        if (!repairedChapterIds.add(chapter.id)) return
        val bookId = book.book.id
        scope.launch {
            runCatching { repo.repairChapterDuration(chapter.id, playerDurationMs) }
                .onFailure { AppLog.w("PlayerManager: не полікували тривалість глави", it) }
            // Стан тримає власну копію книги, і без перечитування смужка
            // лишалася б кривою до наступного відкриття книги.
            val reloaded = runCatching { repo.getBook(bookId) }.getOrNull() ?: return@launch
            _state.update { s ->
                if (s.book?.book?.id != bookId) s
                else s.copy(book = reloaded.copy(chapters = reloaded.chapters.sortedBy { it.index }))
            }
        }
    }

    private fun persist(force: Boolean = false) {
        // Книгу, яку лише підвантажили при старті додатка, не чіпаємо взагалі:
        // інакше вона вічно висіла б угорі полиці й у фільтрі «слухаю».
        if (!playbackStarted) return
        val s = _state.value
        val book = s.book ?: return
        val c = liveController() ?: return
        val ended = !c.hasNextMediaItem() && c.playbackState == Player.STATE_ENDED
        val changed = s.positionMs != lastPersistedPositionMs || s.chapterIndex != lastPersistedChapterIndex
        if (!force && !changed) return
        lastPersistedPositionMs = s.positionMs
        lastPersistedChapterIndex = s.chapterIndex
        scope.launch {
            runCatching {
                repo.saveProgress(
                    bookId = book.book.id,
                    chapterIndex = s.chapterIndex,
                    positionMs = s.positionMs,
                    durationHint = book.book.durationMs,
                    speed = s.speed,
                    // null = не чіпати позначку «завершено»: знімати її має лише реальний перезапуск книги.
                    completed = if (ended) true else null,
                )
            }
        }
    }

    private companion object {
        /** Як часто скидати накопичену статистику прослуховування в БД. */
        const val LISTEN_FLUSH_MS = 30_000L

        /** Інтервал тикера, поки UI у STARTED (позиція на екрані). */
        const val TICK_FOREGROUND_MS = 400L

        /** Інтервал у фоні: шторку веде media3, UI позиції ніхто не читає. */
        const val TICK_BACKGROUND_MS = 2_000L

        /**
         * Межі розриву між тиками, у які прослуховування ще зараховується.
         *
         * Верхня взята з фонового інтервалу з запасом: усе, що довше, — не
         * слухання, а doze чи заморожений процес. У передньому плані вона
         * свідомо слабша за сам інтервал: розрив 2–10 с там дає лише затримка
         * потоку, а під час неї позиція справді рухалася — зарахувати її
         * правильніше, ніж викинути.
         */
        const val MIN_TICK_GAP_MS = 100L
        const val MAX_TICK_GAP_MS = TICK_BACKGROUND_MS * 5

        /** Крок і період згасання гучності перед паузою за таймером сну. */
        const val FADE_STEP = 0.06f
        const val FADE_STEP_MS = 180L

        /** Повна тривалість згасання: від 1.0 до нуля кроками FADE_STEP. */
        val FADE_TOTAL_MS = (1f / FADE_STEP).toLong() * FADE_STEP_MS

        /** Період опитування таймера «до кінця глави»: позицію в главі інакше не дізнатися. */
        const val SLEEP_POLL_MS = 500L

        /** Скільки після спрацювання таймера струшування ще повертає відтворення. */
        const val SLEEP_GRACE_MS = 5 * 60_000L

        /** На скільки струшування продовжує хвилинний таймер. */
        const val SLEEP_EXTEND_MINUTES = 10

        /** Скільки чекати, поки слайдер швидкості зупиниться, перш ніж писати в БД. */
        const val SPEED_PERSIST_DEBOUNCE_MS = 400L
        const val MAX_ERROR_SKIPS = 8
        val IO_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
    }

    /** За скільки до кінця глави вмикати згасання, щоб воно завершилося на самій межі. */
    private fun sleepLeadMs(): Long =
        if (settings.fadeOnSleep) FADE_TOTAL_MS + SLEEP_POLL_MS else SLEEP_POLL_MS

    /**
     * Скільки часу справді лишилося слухати, якщо попереду [contentMs] книги.
     *
     * «До кінця глави» й «до кінця книги» вимірюють контент, а бейдж таймера
     * показує зворотний відлік — тобто реальні хвилини. На швидкості 1,5× без
     * цього перерахунку значок обіцяв 60 хвилин там, де їх було 40.
     */
    private fun wallClockMs(contentMs: Long): Long {
        val speed = _state.value.speed.takeIf { it > 0f } ?: 1f
        return (contentMs / speed).toLong()
    }

    /**
     * Той самий перерахунок у зворотний бік: скільки контенту встигне пройти за
     * час згасання. Пороги спрацювання порівнюються з контентом, а згасання
     * триває реальні секунди.
     */
    private fun sleepLeadContentMs(): Long {
        val speed = _state.value.speed.takeIf { it > 0f } ?: 1f
        return (sleepLeadMs() * speed).toLong()
    }

    private suspend fun fadeAndPause() {
        val c = liveController() ?: return
        if (settings.fadeOnSleep && c.isPlaying) {
            var volume = 1f
            while (volume > FADE_STEP) {
                volume -= FADE_STEP
                c.volume = volume.coerceAtLeast(0f)
                delay(FADE_STEP_MS)
            }
        }
        c.pause()
        c.volume = 1f
    }
}

