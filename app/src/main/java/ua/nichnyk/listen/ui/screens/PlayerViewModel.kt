package ua.nichnyk.listen.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.BookmarkEntity
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.data.UserSettings
import ua.nichnyk.listen.data.VoicePreset
import ua.nichnyk.listen.launchSafely
import ua.nichnyk.listen.playback.PlaybackTick
import ua.nichnyk.listen.playback.PlayerManager
import ua.nichnyk.listen.playback.PlayerUiState
import ua.nichnyk.listen.playback.tick
import ua.nichnyk.listen.playback.withoutPosition

class PlayerViewModel(
    private val player: PlayerManager,
    private val repo: LibraryRepository,
    private val prefs: UserPrefs,
) : ViewModel() {
    /**
     * Повний стан, разом із позицією. Тікає 2,5 рази на секунду, тож на верхньому
     * рівні екрана його читати не можна — для цього є [stable] і [tick].
     */
    val state = player.state

    /**
     * Стан без позиції: емісія лише тоді, коли справді щось змінилося (глава,
     * пауза, швидкість, черга). Саме це читає тіло PlayerScreen і корінь навігації.
     */
    val stable: StateFlow<PlayerUiState> = player.state
        .map { it.withoutPosition() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            player.state.value.withoutPosition(),
        )

    /**
     * Закладки поточної книги — для крапок на смужці перегляду. Підписка живе, поки
     * плеєр відкритий: без екрана плеєра нікому ці крапки не потрібні.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookmarkMarks: StateFlow<List<BookmarkEntity>> = player.state
        .map { it.book?.book?.id }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeBookmarksFor(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Позиція й прогрес — для смужки перегляду, годинника й кільця на обкладинці. */
    val tick: StateFlow<PlaybackTick> = player.state
        .map { it.tick() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            player.state.value.tick(),
        )

    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        launchSafely("PlayerViewModel: помилки плеєра") {
            player.errors.collect { _messages.send(it) }
        }
    }

    fun playPause() = player.playPause()
    fun skipBack() = player.skipBack()
    fun skipForward() = player.skipForward()
    fun seek(ms: Long) = player.seekChapter(ms)
    fun seekBook(ms: Long) = player.seekBook(ms)
    fun setSeekWholeBook(enabled: Boolean) {
        viewModelScope.launch { prefs.setSeekWholeBook(enabled) }
    }
    fun dismissNextInSeries() = player.dismissNextInSeries()
    /** «Далі в циклі» — та сама дорога, що й книга з черги: свіжий запис із бази. */
    fun playNextInSeries() {
        val next = player.state.value.nextInSeries ?: return
        player.dismissNextInSeries()
        playQueueItem(next)
    }
    fun jumpChapter(index: Int) = player.jumpToChapter(index)
    fun speed(value: Float) = player.setSpeed(value)
    fun sleep(minutes: Int) = player.startSleep(minutes)
    fun sleepEndOfChapter() = player.startSleepUntilChapterEnd()
    fun sleepEndOfBook() = player.startSleepUntilBookEnd()
    fun cancelSleep() = player.cancelSleep()
    fun removeFromQueue(bookId: String) = player.removeFromQueue(bookId)
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = player.moveQueueItem(fromIndex, toIndex)
    fun clearQueue() = player.clearQueue()
    fun playQueueItem(book: BookWithChapters) {
        viewModelScope.launch {
            runCatching {
                val fresh = repo.getBook(book.book.id) ?: book
                player.play(fresh)
            }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _messages.send(player.errorMessage(e))
                }
        }
    }
    fun setSkipSilence(enabled: Boolean) = player.setSkipSilence(enabled)
    fun setMonoAudio(enabled: Boolean) = player.setMonoAudio(enabled)
    fun setVolumeBoost(enabled: Boolean) = player.setVolumeBoost(enabled)
    fun setVoicePreset(preset: VoicePreset) = player.setVoicePreset(preset)
    fun setPitch(pitch: Float) = player.setPitch(pitch)
    fun observeCharacters(bookId: String) = repo.observeCharactersForBook(bookId)
    fun addCharacter(bookId: String, name: String, role: String?, description: String?) {
        launchSafely("PlayerViewModel.addCharacter") { repo.addCharacter(bookId, name, role, description) }
    }
    fun deleteCharacter(id: String) {
        launchSafely("PlayerViewModel.deleteCharacter") { repo.deleteCharacter(id) }
    }
    fun updateBookNotes(bookId: String, notes: String?) {
        launchSafely("PlayerViewModel.updateBookNotes") { repo.updateBookNotes(bookId, notes) }
    }
    fun jumpBack() = player.jumpBack()
    fun dismissJumpBack() = player.dismissJumpBack()
    fun toggleAbLoop() = player.toggleAbLoop()
    fun clearAbLoop() = player.clearAbLoop()

    /**
     * @param onResult false, якщо зберігати не було чого. Доти екран показував
     * «Закладку збережено» і тоді, коли книги чи глави не було й закладка не
     * створювалася.
     */
    fun bookmark(note: String, onResult: (Boolean) -> Unit = {}) {
        launchSafely("PlayerViewModel.bookmark") { onResult(player.addBookmark(note)) }
    }

    fun playBook(book: BookWithChapters, chapterIndex: Int, position: Long = 0L) {
        viewModelScope.launch {
            runCatching { player.play(book, chapterIndex, position, applySmartRewind = false) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _messages.send(player.errorMessage(e))
                }
        }
    }
}
