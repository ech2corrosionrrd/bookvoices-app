package ua.nichnyk.listen.ui.screens

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.ChapterNaming
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.forAppLocale
import ua.nichnyk.listen.launchSafely
import ua.nichnyk.listen.playback.PlayerManager

class BookDetailViewModel(
    private val app: Application,
    private val repo: LibraryRepository,
    private val player: PlayerManager,
    private val prefs: UserPrefs,
    private val bookId: String,
) : ViewModel() {
    /** Список персонажів — Pro-фіча; межу тримає UserPrefs, тут лише читаємо. */
    val isPro = prefs.settings
        .map { it.isPro }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _bookReady = MutableStateFlow(false)
    val bookReady: StateFlow<Boolean> = _bookReady.asStateFlow()
    val book = repo.observeBook(bookId)
        .onEach { _bookReady.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val bookmarks = repo.observeBookmarksFor(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags = repo.observeTagsForBook(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val characters = repo.observeCharactersForBook(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Скільки глав зараз не відкриваються: > 0 — книзі потрібна перепривʼязка. */
    private val _missingFiles = MutableStateFlow(0)
    val missingFiles: StateFlow<Int> = _missingFiles.asStateFlow()

    private val _relinking = MutableStateFlow(false)
    val relinking: StateFlow<Boolean> = _relinking.asStateFlow()

    private val _appending = MutableStateFlow(false)
    val appending: StateFlow<Boolean> = _appending.asStateFlow()

    /** Скільки нових аудіофайлів знайшов авто-скан теки (I3). */
    private val _newFilesCount = MutableStateFlow(0)
    val newFilesCount: StateFlow<Int> = _newFilesCount.asStateFlow()

    private val _newFileUris = MutableStateFlow<List<Uri>>(emptyList())

    /** Лише pull-to-refresh: фоновий скан при відкритті індикатор не крутить. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var scanJob: Job? = null

    init {
        // Перевіряємо доступність щоразу, коли змінюється склад глав: після
        // перепривʼязки банер має зникнути сам.
        launchSafely("BookDetailViewModel: доступність глав") {
            book.map { it?.chapters?.map { ch -> ch.uri }?.distinct().orEmpty() }
                .distinctUntilChanged()
                .collect { uris ->
                    _missingFiles.value = if (uris.isEmpty()) 0 else uris.count { !repo.isAudioAccessible(it) }
                    // Той самий тригер — після доливання / перепривʼязки склад
                    // змінився, і лічильник нових файлів має перечитатися.
                    scanSourceTree()
                }
        }
    }

    fun addTag(tag: String) {
        launchSafely("BookDetailViewModel.addTag") { repo.addTag(bookId, tag) }
    }

    fun removeTag(tag: String) {
        launchSafely("BookDetailViewModel.removeTag") { repo.removeTag(bookId, tag) }
    }

    fun addCharacter(name: String, role: String?, description: String?) {
        launchSafely("BookDetailViewModel.addCharacter") { repo.addCharacter(bookId, name, role, description) }
    }

    fun deleteCharacter(id: String) {
        launchSafely("BookDetailViewModel.deleteCharacter") { repo.deleteCharacter(id) }
    }

    fun updateNotes(notes: String?) {
        launchSafely("BookDetailViewModel.updateNotes") { repo.updateBookNotes(bookId, notes) }
    }

    fun appendUris(uris: List<Uri>) {
        if (_appending.value || uris.isEmpty()) return
        _appending.value = true
        viewModelScope.launch {
            try {
                val count = repo.appendUrisToBook(bookId, uris)
                val msg = if (count > 0) {
                    app.forAppLocale().resources.getQuantityString(R.plurals.append_added, count, count)
                } else {
                    app.forAppLocale().getString(R.string.append_empty)
                }
                _messages.send(msg)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w("BookDetailViewModel.appendUris", e)
                _messages.send(app.forAppLocale().getString(R.string.import_failed))
            } finally {
                _appending.value = false
            }
        }
    }

    fun appendTree(treeUri: Uri) {
        if (_appending.value) return
        _appending.value = true
        viewModelScope.launch {
            try {
                val count = repo.appendTreeToBook(bookId, treeUri)
                val msg = if (count > 0) {
                    app.forAppLocale().resources.getQuantityString(R.plurals.append_added, count, count)
                } else {
                    app.forAppLocale().getString(R.string.append_empty)
                }
                _messages.send(msg)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w("BookDetailViewModel.appendTree", e)
                _messages.send(app.forAppLocale().getString(R.string.import_failed))
            } finally {
                _appending.value = false
            }
        }
    }

    /**
     * Авто-скан SAF-теки книги (I3).
     *
     * [notifyIfEmpty] — лише для pull-to-refresh: при відкритті екрана порожній
     * результат мовчить, інакше кожна книга без tree grant сипала б снекбари.
     */
    fun scanSourceTree(notifyIfEmpty: Boolean = false) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            if (notifyIfEmpty) _refreshing.value = true
            try {
                val found = try {
                    repo.scanNewAudioInSourceTree(bookId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppLog.w("BookDetailViewModel.scanSourceTree", e)
                    // Не затираємо попередній успішний скан тимчасовою помилкою SAF.
                    return@launch
                }
                _newFileUris.value = found.map { it.uri.toUri() }
                _newFilesCount.value = found.size
                if (notifyIfEmpty && found.isEmpty()) {
                    _messages.send(app.forAppLocale().getString(R.string.scan_no_new_files))
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Долити файли, які вже знайшов [scanSourceTree], без повторного вибору теки. */
    fun appendDiscovered() {
        val uris = _newFileUris.value
        if (uris.isEmpty()) return
        appendUris(uris)
    }

    /**
     * Перепривʼязка книги до нової теки.
     *
     * Найдорожчий сценарій у застосунку, який навмисно не копіює файли до себе:
     * досі переміщена тека означала книгу, яку лишалося тільки видалити й імпортувати
     * заново — разом із прогресом і всіма закладками.
     */
    fun relink(treeUri: Uri) {
        if (_relinking.value) return
        _relinking.value = true
        viewModelScope.launch {
            try {
                val result = runCatching { repo.relinkBook(bookId, treeUri) }.getOrNull()
                _messages.send(relinkMessage(app, result))
                val uris = book.value?.chapters?.map { it.uri }?.distinct().orEmpty()
                _missingFiles.value = uris.count { !repo.isAudioAccessible(it) }
            } finally {
                _relinking.value = false
            }
        }
    }

    fun play(chapterIndex: Int? = null, onStarted: () -> Unit = {}) {
        val item = book.value ?: return
        viewModelScope.launch {
            runCatching {
                if (chapterIndex == null) player.play(item)
                else player.play(item, chapterIndex, 0L, applySmartRewind = false)
            }.onSuccess { onStarted() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _messages.send(player.errorMessage(e))
                }
        }
    }

    fun addToQueue() {
        val item = book.value ?: return
        player.addToQueue(item)
        viewModelScope.launch {
            _messages.send(app.forAppLocale().getString(R.string.added_to_queue, item.book.title))
        }
    }

    fun rename(title: String, author: String) {
        viewModelScope.launch { repo.renameBook(bookId, title, author) }
    }

    /** Перейменувати одну главу. Порожня назва — нічого не змінюємо. */
    fun renameChapter(chapterId: String, title: String) {
        launchSafely("BookDetailViewModel.renameChapter") {
            if (repo.renameChapters(bookId, mapOf(chapterId to title)).isNotEmpty()) {
                player.refreshChapterTitles(bookId)
            }
        }
    }

    /** «Пронумерувати»: усі глави стають «Розділ 1», «Розділ 2»… мовою застосунку. */
    fun numberChapters() {
        launchSafely("BookDetailViewModel.numberChapters") {
            val current = repo.getBook(bookId) ?: return@launchSafely
            val res = app.forAppLocale()
            val wanted = ChapterNaming.numbered(current.chapters) {
                res.getString(R.string.chapter_number, it)
            }
            val changed = repo.renameChapters(bookId, wanted)
            if (changed.isNotEmpty()) player.refreshChapterTitles(bookId)
            _messages.send(
                if (changed.isEmpty()) res.getString(R.string.chapters_numbered_none)
                else res.resources.getQuantityString(R.plurals.chapters_numbered, changed.size, changed.size),
            )
        }
    }

    fun updateBookMetadata(title: String, author: String, series: String?, seriesOrder: Float?) {
        viewModelScope.launch {
            repo.renameBook(bookId, title, author)
            repo.updateSeries(bookId, series, seriesOrder)
        }
    }

    fun updateCover(imageUri: Uri) {
        viewModelScope.launch {
            val ok = runCatching { repo.updateBookCover(bookId, imageUri) }.getOrDefault(false)
            if (!ok) {
                _messages.send(app.forAppLocale().getString(R.string.import_file_failed))
            }
        }
    }

    suspend fun exportBookSummaryMarkdown(): String? = repo.exportBookSummaryMarkdown(bookId)

    fun togglePin() {
        val b = book.value ?: return
        launchSafely("BookDetailViewModel.togglePin") {
            repo.setPinned(b.book.id, !b.book.pinned)
        }
    }

    fun toggleCompleted() {
        val b = book.value ?: return
        launchSafely("BookDetailViewModel.toggleCompleted") {
            repo.setCompleted(b.book.id, !b.book.completed)
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { repo.deleteBookmark(id) }
    }

    fun updateBookmark(id: String, note: String) {
        viewModelScope.launch { repo.updateBookmarkNote(id, note) }
    }

    fun playBookmark(chapterId: String, position: Long, onStarted: () -> Unit = {}) {
        val item = book.value ?: return
        viewModelScope.launch {
            runCatching { player.playBookmark(item, chapterId, position) }
                .onSuccess { onStarted() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _messages.send(player.errorMessage(e))
                }
        }
    }

    fun deleteBook(onGone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.deleteBook(bookId) }
                .onSuccess { onGone() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // e.toString() тут показував користувачу назву класу винятку.
                    AppLog.w("BookDetailViewModel.deleteBook", e)
                    _messages.send(app.forAppLocale().getString(R.string.delete_failed))
                }
        }
    }
}
