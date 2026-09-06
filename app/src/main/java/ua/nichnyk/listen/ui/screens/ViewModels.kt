package ua.nichnyk.listen.ui.screens

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookSortOrder
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.BookmarkWithBook
import ua.nichnyk.listen.data.DuplicateBookException
import ua.nichnyk.listen.data.HeadsetAction
import ua.nichnyk.listen.data.ImportOutcome
import ua.nichnyk.listen.data.ImportProgress
import ua.nichnyk.listen.data.LibraryFilter
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.PendingRename
import ua.nichnyk.listen.data.ShelfViewMode
import ua.nichnyk.listen.data.AddLibraryRootResult
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.data.WebDavClient
import ua.nichnyk.listen.playback.tick
import ua.nichnyk.listen.playback.withoutPosition
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.launchSafely
import ua.nichnyk.listen.playback.PlayerManager

class LibraryViewModel(
    private val app: Application,
    private val repo: LibraryRepository,
    private val player: PlayerManager,
    private val prefs: UserPrefs,
    incomingImports: Flow<List<Uri>> = kotlinx.coroutines.flow.emptyFlow(),
) : ViewModel() {
    val books = repo.observeBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playerState = player.state
    val weeklyStats = repo.observeWeeklyStats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ua.nichnyk.listen.data.WeeklyStats())
    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ua.nichnyk.listen.data.UserSettings())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _filter = MutableStateFlow(LibraryFilter.All)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    private val _sortOrder = MutableStateFlow(BookSortOrder.LastPlayed)
    val sortOrder: StateFlow<BookSortOrder> = _sortOrder.asStateFlow()

    val allTags: StateFlow<List<String>> = repo.observeAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookTagsMap: StateFlow<Map<String, Set<String>>> = repo.observeBookTagsMap()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    val allSeries: StateFlow<List<String>> = repo.observeAllSeries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedSeries = MutableStateFlow<String?>(null)
    val selectedSeries: StateFlow<String?> = _selectedSeries.asStateFlow()

    private val _isMultiSelect = MutableStateFlow(false)
    val isMultiSelect: StateFlow<Boolean> = _isMultiSelect.asStateFlow()

    private val _selectedBookIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedBookIds: StateFlow<Set<String>> = _selectedBookIds.asStateFlow()

    /**
     * Результат сканування доступності файлів полиці.
     *
     * `scanned` відрізняє «порахували, недоступних немає» від «ще не рахували».
     * Перше значення `stateIn` — порожній набір, і збирач у `init` знімав через
     * нього фільтр «недоступні» ще до того, як скан устигав дійти хоч до одного
     * файла: достатньо було відкрити полицю з цим фільтром, щоб він мовчки
     * перемкнувся на «усі».
     */
    private data class MissingScan(val ids: Set<String> = emptySet(), val scanned: Boolean = false)

    /**
     * Книги, чиї файли зараз не відкриваються.
     *
     * Сканується не на кожну емісію полиці, а лише коли змінився склад файлів.
     * Раніше тут стояв `books.collectLatest { ... }`: позиція відтворення пишеться
     * кожні кілька секунд, кожен такий запис переспівує потік Room — і перевірка
     * доступності всієї полиці через openAssetFileDescriptor скасовувалася та
     * починалася наново, так і не дійшовши до кінця на великій бібліотеці.
     *
     * WhileSubscribed, а не вічна підписка в init: без екрана полиці цей набір
     * нікому не потрібен, а підписка тримала б запит Room гарячим під час
     * фонового відтворення. Збирач, який лишав його гарячим усупереч цьому
     * коментарю, тепер живе рівно стільки, скільки відкрита полиця, — див. init.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val missingScan: StateFlow<MissingScan> = books
        .map { list -> list.map { it.book.id to it.chapters.map { ch -> ch.uri }.distinct() } }
        .distinctUntilChanged()
        .mapLatest { shelf ->
            MissingScan(
                ids = shelf.mapNotNullTo(mutableSetOf<String>()) { (id, uris) ->
                    id.takeIf { uris.any { uri -> !repo.isAudioAccessible(uri) } }
                },
                scanned = true,
            )
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MissingScan())

    val missingFilesBooks: StateFlow<Set<String>> = missingScan
        .map { it.ids }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _pendingRename = MutableStateFlow<PendingRename?>(null)
    val pendingRename: StateFlow<PendingRename?> = _pendingRename.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var importJob: Job? = null
    private var userCancelledImport = false
    private val libraryActive = MutableStateFlow(false)

    init {
        // Прибирання фільтрів — рівно поки відкрита полиця.
        //
        // Доти обидва збирачі стояли просто в init, тобто підписувалися назавжди:
        // а вище над `missingFilesBooks` є `books`, тобто запит Room по всій полиці.
        // Виходило точно те, чого коментар до `missingFilesBooks` обіцяв уникнути, —
        // скан доступності й запити полиці лишалися гарячими під час фонового
        // відтворення, коли інтерфейсу немає взагалі.
        launchSafely("LibraryViewModel: прибирання фільтрів, поки відкрита полиця") {
            libraryActive.collectLatest { active ->
                if (!active) return@collectLatest
                coroutineScope {
                    launch { dropStaleFilters() }
                    launch {
                        missingScan.collect { scan ->
                            if (scan.scanned && scan.ids.isEmpty() &&
                                _filter.value == LibraryFilter.Unavailable
                            ) {
                                _filter.value = LibraryFilter.All
                            }
                        }
                    }
                    // Нові книги в головних папках — лише поки полиця на екрані.
                    launch {
                        prefs.settings.map { it.libraryRootUris }.distinctUntilChanged().collect { roots ->
                            // Передаємо список з емісії: settings.value тут ще може
                            // бути старим (окремий stateIn), і скан пішов би вхолосту.
                            scanLibraryRoots(roots = roots)
                        }
                    }
                }
            }
        }
        launchSafely("LibraryViewModel: сортування з налаштувань") {
            prefs.settings.map { it.sortOrder }.distinctUntilChanged().collect { _sortOrder.value = it }
        }
        launchSafely("LibraryViewModel: імпорт із зовнішнього наміру") {
            incomingImports.collect { uris ->
                if (uris.isEmpty()) return@collect
                importJob?.join()
                importUris(uris)
            }
        }
    }

    private data class ShelfFilterState(
        val tag: String?,
        val tagsMap: Map<String, Set<String>>,
        val series: String?,
        val missingIds: Set<String>,
    )

    private val shelfFilters: StateFlow<ShelfFilterState> = combine(
        _selectedTag,
        bookTagsMap,
        _selectedSeries,
        missingFilesBooks,
    ) { tag, map, series, missing ->
        ShelfFilterState(tag, map, series, missing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfFilterState(null, emptyMap(), null, emptySet()))

    val visibleBooks: StateFlow<List<BookWithChapters>> = combine(
        books,
        _query,
        _filter,
        _sortOrder,
        shelfFilters,
    ) { bookList, q, f, s, sf ->
        repo.search(bookList, q, f, s, sf.tag, sf.tagsMap, sf.series, sf.missingIds)
    }
        // Натуральне сортування з regex по всій полиці — не робота для головного потоку.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
        if (q.isNotEmpty()) _isSearching.value = true
    }
    fun setSearching(searching: Boolean) {
        _isSearching.value = searching
        if (!searching) _query.value = ""
    }
    fun setFilter(f: LibraryFilter) { _filter.value = f }

    /**
     * Знімає все, що зараз ховає книги.
     *
     * Порожній стан полиці досі був глухим кутом: під фільтром за міткою чи
     * серією він показував те саме речення, що й заголовок над ним, і не давав
     * ані підказки, який саме фільтр діє, ані способу його зняти.
     */
    fun clearFilters() {
        _filter.value = LibraryFilter.All
        _selectedTag.value = null
        _selectedSeries.value = null
        _query.value = ""
        _isSearching.value = false
    }
    fun setSelectedTag(tag: String?) { _selectedTag.value = tag }
    fun setSelectedSeries(series: String?) { _selectedSeries.value = series }
    fun setSortOrder(s: BookSortOrder) {
        _sortOrder.value = s
        launchSafely("LibraryViewModel.setSortOrder") { prefs.setSortOrder(s) }
    }
    fun setShelfViewMode(mode: ShelfViewMode) {
        launchSafely("LibraryViewModel.setShelfViewMode") { prefs.setShelfViewMode(mode) }
    }
    fun dismissRename() { _pendingRename.value = null }
    fun requestRename(id: String, title: String, author: String) {
        _pendingRename.value = PendingRename(id, title, author)
    }

    fun startMultiSelect(initialId: String? = null) {
        _isMultiSelect.value = true
        _selectedBookIds.value = if (initialId != null) setOf(initialId) else emptySet()
    }

    fun toggleSelectBook(id: String) {
        _selectedBookIds.update { if (it.contains(id)) it - id else it + id }
    }

    fun selectAll(bookIds: List<String>) {
        _selectedBookIds.value = bookIds.toSet()
    }

    fun clearSelection() {
        _selectedBookIds.value = emptySet()
        _isMultiSelect.value = false
    }

    fun togglePin(book: BookWithChapters) {
        launchSafely("LibraryViewModel.togglePin") {
            repo.setPinned(book.book.id, !book.book.pinned)
        }
    }

    fun batchTogglePinned() {
        val ids = _selectedBookIds.value.toList()
        if (ids.isEmpty()) return
        val selectedBooks = books.value.filter { it.book.id in ids }
        val allPinned = selectedBooks.isNotEmpty() && selectedBooks.all { it.book.pinned }
        launchSafely("LibraryViewModel.batchTogglePinned") { repo.batchSetPinned(ids, !allPinned) }
        clearSelection()
    }

    fun batchDeleteSelected() {
        val ids = _selectedBookIds.value.toList()
        if (ids.isEmpty()) return
        launchSafely("LibraryViewModel.batchDeleteSelected") { repo.batchDeleteBooks(ids) }
        clearSelection()
    }

    fun batchAssignTag(tag: String) {
        val ids = _selectedBookIds.value.toList()
        if (ids.isEmpty()) return
        launchSafely("LibraryViewModel.batchAssignTag") { repo.batchAssignTag(ids, tag) }
        clearSelection()
    }

    fun batchAddToQueue() {
        val ids = _selectedBookIds.value
        val items = books.value.filter { it.book.id in ids }
        if (items.isNotEmpty()) {
            player.batchAddToQueue(items)
            launchSafely("LibraryViewModel.batchAddToQueue") {
                _messages.send(
                    app.resources.getQuantityString(R.plurals.batch_added_to_queue, items.size, items.size),
                )
            }
        }
        clearSelection()
    }

    /**
     * Знімає фільтр, чий чіп зі шторки вже зник.
     *
     * Видаліть останню книгу серії, поки за цією серією стоїть фільтр, — і
     * полиця лишиться порожньою назавжди: чіпа, яким фільтр знімають, у списку
     * більше немає, а самі по собі ні пошук, ні вкладки «усі / слухаю /
     * завершені» його не скидають. Вихід був один — перезапустити застосунок.
     * Те саме давно було можливе з мітками.
     */
    private suspend fun dropStaleFilters() {
        combine(allSeries, allTags) { series, tags -> series to tags }.collect { (series, tags) ->
            _selectedSeries.value?.let { chosen ->
                if (series.none { it.equals(chosen, ignoreCase = true) }) _selectedSeries.value = null
            }
            _selectedTag.value?.let { chosen ->
                if (tags.none { it == chosen }) _selectedTag.value = null
            }
        }
    }

    fun setLibraryActive(active: Boolean) {
        libraryActive.value = active
        if (!active) {
            libraryScanJob?.cancel()
            libraryScanJob = null
            _libraryRefreshing.value = false
            _pendingRename.value = null
            clearSelection()
            _newBookCandidates.value = emptyList()
        }
    }

    private val _newBookCandidates =
        MutableStateFlow<List<ua.nichnyk.listen.data.LibraryBookCandidate>>(emptyList())
    val newBookCount: StateFlow<Int> = _newBookCandidates
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _libraryRefreshing = MutableStateFlow(false)
    val libraryRefreshing: StateFlow<Boolean> = _libraryRefreshing.asStateFlow()

    private var libraryScanJob: Job? = null

    /**
     * Скан головних папок на **нові книги** (не розділи всередині вже доданої).
     * [notifyIfEmpty] — лише pull-to-refresh: порожній результат тоді у снекбарі.
     * [roots] — знімок зі збирача prefs; якщо null — береться з [settings].
     */
    fun scanLibraryRoots(
        notifyIfEmpty: Boolean = false,
        roots: List<String>? = null,
    ) {
        libraryScanJob?.cancel()
        libraryScanJob = viewModelScope.launch {
            if (notifyIfEmpty) _libraryRefreshing.value = true
            try {
                val rootList = roots ?: settings.value.libraryRootUris
                if (rootList.isEmpty()) {
                    _newBookCandidates.value = emptyList()
                    if (notifyIfEmpty) {
                        _messages.send(app.getString(R.string.library_roots_empty_hint))
                    }
                    return@launch
                }
                if (!repo.anyLibraryRootGranted(rootList)) {
                    _newBookCandidates.value = emptyList()
                    if (notifyIfEmpty) {
                        _messages.send(app.getString(R.string.library_roots_access_lost))
                    }
                    return@launch
                }
                val found = runCatching { repo.scanNewBooksInLibraryRoots(rootList) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLog.w("LibraryViewModel.scanLibraryRoots", e)
                    }
                    .getOrDefault(emptyList())
                _newBookCandidates.value = found
                if (notifyIfEmpty && found.isEmpty()) {
                    _messages.send(app.getString(R.string.scan_no_new_books))
                }
            } finally {
                _libraryRefreshing.value = false
            }
        }
    }

    fun importDiscoveredBooks() {
        val candidates = _newBookCandidates.value
        if (candidates.isEmpty()) return
        import {
            val outcome = repo.importLibraryCandidates(candidates) { _importProgress.value = it }
            _newBookCandidates.value = emptyList()
            outcome
        }
    }

    /**
     * Стрічка «продовжити слухати» на початку полиці.
     *
     * Потік, а не функція з books.value: читання .value у composable не є читанням
     * стану Compose — рядок перемальовувався лише тому, що поруч збиралася сама
     * полиця. Варто було прибрати той збір — і стрічка мовчки завмерла б.
     */
    val continueListening: StateFlow<List<BookWithChapters>> = books
        .map { list -> list.filter { it.book.lastPlayedAt != null && !it.book.completed }.take(8) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun importUris(uris: List<Uri>) = import { repo.importUris(uris, onProgress = { _importProgress.value = it }) }
    fun importTree(uri: Uri) = import { repo.importTree(uri, onProgress = { _importProgress.value = it }) }
    fun importDemo() = import { repo.importDemo() }

    fun play(book: BookWithChapters, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { player.play(book) }
                .onSuccess { onStarted() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _messages.send(player.errorMessage(e))
                }
        }
    }

    fun addToQueue(book: BookWithChapters) {
        player.addToQueue(book)
        launchSafely("LibraryViewModel.addToQueue") {
            _messages.send(app.getString(R.string.added_to_queue, book.book.title))
        }
    }

    fun rename(id: String, title: String, author: String) {
        launchSafely("LibraryViewModel.rename") { repo.renameBook(id, title, author) }
        _pendingRename.value = null
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repo.deleteBook(id) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // Свій рядок, а не e.message: тут ловляться SecurityException
                    // від SAF і подібне, тобто англійський технічний текст, який
                    // раніше йшов просто в снекбар — ще й під заголовком «Не
                    // вдалося відтворити».
                    AppLog.w("LibraryViewModel.delete", e)
                    _messages.send(app.getString(R.string.delete_failed))
                }
        }
    }

    fun cancelImport() {
        userCancelledImport = true
        importJob?.cancel()
    }

    private fun describeOutcome(outcome: ImportOutcome): String {
        val added = outcome.imported.size
        val skipped = outcome.duplicates.size
        return when {
            skipped == 0 -> app.resources.getQuantityString(R.plurals.import_added, added, added)
            added == 0 -> app.getString(R.string.import_duplicate, outcome.duplicates.first())
            else -> app.getString(
                R.string.import_added_with_skipped,
                app.resources.getQuantityString(R.plurals.import_added, added, added),
                skipped,
            )
        }
    }

    private fun import(block: suspend () -> ImportOutcome) {
        if (_busy.value) return
        userCancelledImport = false
        _busy.value = true
        importJob = viewModelScope.launch {
            _importProgress.value = ImportProgress()
            try {
                val outcome = block()
                // Перейменування пропонуємо лише для однієї книги: для теки
                // з десятком книг діалог поспіль був би знущанням.
                val single = outcome.single
                if (single != null && outcome.duplicates.isEmpty() && libraryActive.value) {
                    _pendingRename.value = single
                } else {
                    _messages.send(describeOutcome(outcome))
                }
            } catch (e: CancellationException) {
                if (userCancelledImport) _messages.trySend(app.getString(R.string.import_cancelled))
                throw e
            } catch (e: DuplicateBookException) {
                _messages.send(app.getString(R.string.import_duplicate, e.existingTitle))
            } catch (e: Exception) {
                AppLog.w("LibraryViewModel.import", e)
                _messages.send(app.getString(R.string.import_failed))
            } finally {
                _busy.value = false
                _importProgress.value = ImportProgress()
                importJob = null
            }
        }
    }
}

class PlayerViewModel(
    private val player: PlayerManager,
    private val repo: LibraryRepository,
    prefs: UserPrefs,
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
    val stable: StateFlow<ua.nichnyk.listen.playback.PlayerUiState> = player.state
        .map { it.withoutPosition() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            player.state.value.withoutPosition(),
        )

    /** Позиція й прогрес — для смужки перегляду, годинника й кільця на обкладинці. */
    val tick: StateFlow<ua.nichnyk.listen.playback.PlaybackTick> = player.state
        .map { it.tick() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            player.state.value.tick(),
        )

    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ua.nichnyk.listen.data.UserSettings())

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
    fun jumpChapter(index: Int) = player.jumpToChapter(index)
    fun speed(value: Float) = player.setSpeed(value)
    fun sleep(minutes: Int) = player.startSleep(minutes)
    fun sleepEndOfChapter() = player.startSleepUntilChapterEnd()
    fun sleepEndOfBook() = player.startSleepUntilBookEnd()
    fun cancelSleep() = player.cancelSleep()
    fun removeFromQueue(bookId: String) = player.removeFromQueue(bookId)
    fun clearQueue() = player.clearQueue()
    fun playQueueItem(book: BookWithChapters) {
        viewModelScope.launch {
            runCatching { player.play(book) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _messages.send(player.errorMessage(e))
                }
        }
    }
    fun setSkipSilence(enabled: Boolean) = player.setSkipSilence(enabled)
    fun setMonoAudio(enabled: Boolean) = player.setMonoAudio(enabled)
    fun setVolumeBoost(enabled: Boolean) = player.setVolumeBoost(enabled)
    fun setVoicePreset(preset: ua.nichnyk.listen.data.VoicePreset) = player.setVoicePreset(preset)
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

class SettingsViewModel(
    private val prefs: UserPrefs,
    private val repo: LibraryRepository,
    val billing: ua.nichnyk.listen.billing.ProEntitlementManager,
) : ViewModel() {
    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ua.nichnyk.listen.data.UserSettings())
    val isPro = billing.isPro
    val formattedPrice = billing.formattedPrice

    /**
     * Події біллінгу. Досі їх ніхто не збирав, тож ні успішна покупка, ні відмова
     * Play нікуди не доходили — екран показував лише результат «відновити покупку».
     */
    val billingEvents = billing.events

    init {
        // Другий збирач на ту саму подію, і це навмисно. Той, що в ListenApp,
        // озброюється лише при вже закешованому Pro й живе стільки, скільки
        // процес — він ловить відкликання покупки. Цей потрібен для протилежного
        // випадку: користувач без кешу щойно купив Pro, і функції мають
        // відкритися негайно, а не з наступного запуску. Обидва пишуть те саме
        // значення в той самий StateFlow, тож порядок і повтори нешкідливі.
        viewModelScope.launch {
            // resolvedIsPro, а не isPro: у другого перша емісія — «поки не знаємо»
            // у вигляді false, і вона на мить погасила б платні налаштування.
            billing.resolvedIsPro.collect { prefs.setProEntitled(it) }
        }
    }

    fun buyPro(activity: android.app.Activity) = billing.launchBillingFlow(activity)
    fun restorePurchases(onResult: (Boolean) -> Unit) = billing.restorePurchases(onResult)
    // Кожен перемикач — окремий запис у DataStore. Показати збій тут нема де,
    // тож єдиною реакцією на рідкісний IOException було б падіння на дотику
    // до тумблера. Тепер — рядок у logcat і незмінене значення.
    fun skipBack(ms: Int) = settingWrite("skipBack") { prefs.setSkipBack(ms) }
    fun skipForward(ms: Int) = settingWrite("skipForward") { prefs.setSkipForward(ms) }
    fun paper(value: Boolean) = settingWrite("paper") { prefs.setPaperTheme(value) }
    fun themeMode(mode: ua.nichnyk.listen.data.AppTheme) = settingWrite("themeMode") { prefs.setThemeMode(mode) }
    fun fade(value: Boolean) = settingWrite("fade") { prefs.setFadeOnSleep(value) }
    fun speed(value: Float) = settingWrite("speed") { prefs.setDefaultSpeed(value) }
    fun skipSilence(value: Boolean) = settingWrite("skipSilence") { prefs.setSkipSilence(value) }
    fun voicePreset(preset: ua.nichnyk.listen.data.VoicePreset) =
        settingWrite("voicePreset") { prefs.setVoicePreset(preset) }
    fun monoAudio(value: Boolean) = settingWrite("monoAudio") { prefs.setMonoAudio(value) }
    fun volumeBoost(value: Boolean) = settingWrite("volumeBoost") { prefs.setVolumeBoost(value) }
    fun smartRewind(value: Boolean) = settingWrite("smartRewind") { prefs.setSmartRewind(value) }
    fun debugPro(value: Boolean) = settingWrite("debugPro") { prefs.setDebugPro(value) }
    fun shelfViewMode(mode: ShelfViewMode) = settingWrite("shelfViewMode") { prefs.setShelfViewMode(mode) }
    fun headsetDoubleTap(action: HeadsetAction) = settingWrite("headsetDoubleTap") { prefs.setHeadsetDoubleTapAction(action) }
    fun headsetTripleTap(action: HeadsetAction) = settingWrite("headsetTripleTap") { prefs.setHeadsetTripleTapAction(action) }

    fun doubleTapSeek(value: Boolean) = settingWrite("doubleTapSeek") { prefs.setDoubleTapSeek(value) }
    fun autoBookmarkBluetooth(value: Boolean) = settingWrite("autoBookmarkBluetooth") { prefs.setAutoBookmarkBluetooth(value) }
    fun shakeToExtendSleep(value: Boolean) = settingWrite("shakeSleep") { prefs.setShakeToExtendSleep(value) }
    fun showStatsOnShelf(value: Boolean) = settingWrite("showStats") { prefs.setShowStatsOnShelf(value) }

    fun addLibraryRoot(uri: Uri, onResult: (AddLibraryRootResult) -> Unit) {
        launchSafely("SettingsViewModel.addLibraryRoot") {
            val result = when {
                settings.value.libraryRootUris.size >= UserPrefs.MAX_LIBRARY_ROOTS ->
                    AddLibraryRootResult.LIMIT
                uri.toString() in settings.value.libraryRootUris ->
                    AddLibraryRootResult.DUPLICATE
                !repo.takeLibraryRootPermission(uri) ->
                    AddLibraryRootResult.PERMISSION_FAILED
                !prefs.addLibraryRoot(uri.toString()) ->
                    AddLibraryRootResult.DUPLICATE
                else -> AddLibraryRootResult.ADDED
            }
            onResult(result)
        }
    }

    fun removeLibraryRoot(uri: String) = settingWrite("removeLibraryRoot") {
        prefs.removeLibraryRoot(uri)
    }

    private fun settingWrite(name: String, block: suspend () -> Unit) {
        launchSafely("SettingsViewModel.$name") { block() }
    }
    fun setLanguage(lang: String) {
        launchSafely("SettingsViewModel.setLanguage") {
            prefs.setLanguage(lang)
            if (lang.isBlank()) {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                )
            } else {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(lang)
                )
            }
        }
    }

    suspend fun exportBackupJson(): String = repo.exportBackupJson()
    suspend fun importBackupJson(json: String): Boolean = repo.importBackupJson(json)
    suspend fun exportCatalogMarkdown(): String = repo.exportCatalogMarkdown()

    /**
     * Пароль читається на вимогу, а не живе в UserSettings: інакше кожна емісія
     * налаштувань розшифровувала його в контексті збирача, тобто на головному потоці.
     */
    suspend fun loadWebDavPassword(): String = prefs.getWebDavPassword()

    /**
     * Явне збереження замість запису на кожне натискання клавіші.
     * Раніше поле форми було прив'язане до потоку налаштувань, і асинхронна емісія
     * з попереднім значенням відкочувала введений текст — символи губилися.
     */
    fun saveWebDav(server: String, user: String, password: String, autoSync: Boolean) {
        launchSafely("SettingsViewModel.saveWebDav") {
            prefs.setWebDavConfig(server, user, autoSync)
            prefs.setWebDavPassword(password)
            prefs.setWebDavAuthFailed(false)
        }
    }

    fun setWebDavAutoSync(enabled: Boolean) {
        launchSafely("SettingsViewModel.setWebDavAutoSync") {
            val current = settings.value
            prefs.setWebDavConfig(current.webDavServer, current.webDavUser, enabled)
        }
    }

    suspend fun testWebDav(server: String, user: String, pass: String): Result<Unit> =
        WebDavClient.testConnection(server, user, pass)

    suspend fun syncUploadWebDav(server: String, user: String, pass: String): Result<Unit> {
        val json = repo.exportBackupJson()
        return WebDavClient.uploadFile(server, user, pass, content = json)
            .onSuccess { prefs.setWebDavLastSyncTime(System.currentTimeMillis()) }
    }

    suspend fun syncDownloadWebDav(server: String, user: String, pass: String): Result<Unit> =
        WebDavClient.downloadFile(server, user, pass).mapCatching { json ->
            if (!repo.mergeBackupJson(json)) throw BackupParseException()
            prefs.setWebDavLastSyncTime(System.currentTimeMillis())
        }
}

/** Файл завантажився, але це не бекап BookVoices. */
class BackupParseException : Exception()


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
                    app.resources.getQuantityString(R.plurals.append_added, count, count)
                } else {
                    app.getString(R.string.append_empty)
                }
                _messages.send(msg)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w("BookDetailViewModel.appendUris", e)
                _messages.send(app.getString(R.string.import_failed))
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
                    app.resources.getQuantityString(R.plurals.append_added, count, count)
                } else {
                    app.getString(R.string.append_empty)
                }
                _messages.send(msg)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w("BookDetailViewModel.appendTree", e)
                _messages.send(app.getString(R.string.import_failed))
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
                    _messages.send(app.getString(R.string.scan_no_new_files))
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
                val text = when {
                    result == null || result.isEmpty -> app.getString(R.string.relink_failed)
                    result.isComplete -> app.getString(R.string.relink_done)
                    else -> app.getString(R.string.relink_partial, result.matched, result.total)
                }
                _messages.send(text)
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
            _messages.send(app.getString(R.string.added_to_queue, item.book.title))
        }
    }

    fun rename(title: String, author: String) {
        viewModelScope.launch { repo.renameBook(bookId, title, author) }
    }

    fun updateBookMetadata(title: String, author: String, series: String?, seriesOrder: Float?) {
        viewModelScope.launch {
            repo.renameBook(bookId, title, author)
            repo.updateSeries(bookId, series, seriesOrder)
        }
    }

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
                    _messages.send(app.getString(R.string.delete_failed))
                }
        }
    }
}

class BookmarksViewModel(
    private val app: Application,
    private val repo: LibraryRepository,
    private val player: PlayerManager,
) : ViewModel() {
    val items = repo.observeBookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    // З `items`, а не з другого repo.observeBookmarks(): доти екран тримав два
    // однакові запити Room до тієї самої таблиці.
    val filteredItems: StateFlow<List<BookmarkWithBook>> = combine(
        items,
        _query,
    ) { bookmarks, q ->
        if (q.isBlank()) bookmarks
        else {
            val term = q.trim().lowercase()
            bookmarks.filter {
                it.bookTitle.lowercase().contains(term) ||
                it.bookmark.note.lowercase().contains(term) ||
                it.bookmark.chapterTitle.lowercase().contains(term)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun play(bookId: String, chapterId: String, position: Long, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            val book = repo.getBook(bookId)
            if (book == null) {
                _messages.send(app.getString(R.string.book_missing_title))
                return@launch
            }
            runCatching { player.playBookmark(book, chapterId, position) }
                .onSuccess { onStarted() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _messages.send(player.errorMessage(e))
                }
        }
    }

    /**
     * Видаляє закладку й повертає її саму — щоб екран міг запропонувати
     * «Скасувати». null означає, що видаляти вже не було чого.
     */
    suspend fun delete(id: String): ua.nichnyk.listen.data.BookmarkEntity? {
        val existing = repo.getBookmark(id) ?: return null
        repo.deleteBookmark(id)
        return existing
    }

    fun restore(bookmark: ua.nichnyk.listen.data.BookmarkEntity) {
        launchSafely("BookmarksViewModel.restore") { repo.restoreBookmark(bookmark) }
    }

    fun updateNote(id: String, note: String) {
        viewModelScope.launch { repo.updateBookmarkNote(id, note) }
    }

    suspend fun exportBookmarksMarkdown(): String = repo.exportBookmarksMarkdown()
}

