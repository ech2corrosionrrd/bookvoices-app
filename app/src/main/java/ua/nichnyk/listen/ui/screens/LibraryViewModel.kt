package ua.nichnyk.listen.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookSortOrder
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.DuplicateBookException
import ua.nichnyk.listen.data.ImportOutcome
import ua.nichnyk.listen.data.ImportProgress
import ua.nichnyk.listen.data.LibraryFilter
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.MissingFiles
import ua.nichnyk.listen.data.PendingRename
import ua.nichnyk.listen.data.ShelfViewMode
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.forAppLocale
import ua.nichnyk.listen.launchSafely
import ua.nichnyk.listen.playback.PlayerManager

/** Скільки скан доступності файлів лишається чинним без повторного обходу. */
private const val MISSING_SCAN_TTL_MS = 60_000L

/** Підсумок перепривʼязки словами — спільний для екрана книги й полиці. */
internal fun relinkMessage(app: android.app.Application, result: ua.nichnyk.listen.data.RelinkResult?): String =
    when {
        result == null || result.isEmpty -> app.forAppLocale().getString(R.string.relink_failed)
        result.isComplete -> app.forAppLocale().getString(R.string.relink_done)
        else -> app.forAppLocale().getString(R.string.relink_partial, result.matched, result.total)
    }

/** Імпорт живе стільки, скільки процес, а не екран; див. [LibraryViewModel.import]. */
private val importScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate,
)

class LibraryViewModel(
    private val app: Application,
    private val repo: LibraryRepository,
    private val player: PlayerManager,
    private val prefs: UserPrefs,
    incomingImports: Flow<List<Uri>> = kotlinx.coroutines.flow.emptyFlow(),
    // Стан імпорту живе в AppContainer: переживає перестворення ViewModel.
    private val imports: ImportState = ImportState(),
) : ViewModel() {
    /**
     * null — Room ще не відповів (перший запуск процесу, зокрема одразу після оновлення
     * застосунку). До першої відповіді полицю не можна називати порожньою: інакше на
     * ~секунду блимав екран «Полиця порожня» і здавалося, що бібліотека зникла
     * (помічено на Redmi Note 11 Pro, 13 листопада 2026). Один запит до бази на обидва потоки нижче.
     */
    private val booksOrNull: StateFlow<List<ua.nichnyk.listen.data.BookWithChapters>?> = repo.observeBooks()
        .map<List<ua.nichnyk.listen.data.BookWithChapters>, List<ua.nichnyk.listen.data.BookWithChapters>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val books = booksOrNull.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** false, доки база не віддала полицю вперше; див. [booksOrNull]. */
    val booksLoaded = booksOrNull.map { it != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
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
    private data class MissingScan(
        val counts: Map<String, MissingFiles> = emptyMap(),
        val scanned: Boolean = false,
    )

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
    /**
     * Останній скан і склад полиці, на якому його зроблено.
     *
     * `stateIn(WhileSubscribed)` піднімає весь ланцюг наново щоразу, коли на
     * полицю повертаються після пʼяти секунд відсутності, — а це звичайний шлях
     * «відкрив книгу, послухав, повернувся». На полиці зі 100 книг кожне таке
     * повернення коштувало повного обходу 1398 файлів (близько секунди), і весь
     * цей час позначки зниклих файлів були відсутні, хоча щойно показувалися.
     *
     * Памʼять коротка навмисно: скан і існує, щоб помічати файли, зниклі поза
     * застосунком. За хвилину така зміна ще встигне проявитися, а десяток
     * переходів між книгою й полицею — уже ні.
     */
    private class CachedScan(
        val signature: List<Pair<String, List<String>>>,
        val result: MissingScan,
        val atMs: Long,
    )

    @Volatile
    private var lastScan: CachedScan? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val missingScan: StateFlow<MissingScan> = books
        .map { list -> list.map { it.book.id to it.chapters.map { ch -> ch.uri }.distinct() } }
        .distinctUntilChanged()
        .mapLatest { shelf ->
            // Рахуємо, скільки саме файлів зникло, а не «чи зник хоч один». Різниця
            // видима: «немає 3 з 20» лікується долиттям файлів, «немає 20 з 20» —
            // перепривʼязкою теки, і на полиці ці випадки досі виглядали однаково.
            val cached = lastScan
            if (cached != null &&
                cached.signature == shelf &&
                System.currentTimeMillis() - cached.atMs < MISSING_SCAN_TTL_MS
            ) {
                return@mapLatest cached.result
            }
            val started = System.currentTimeMillis()
            // Один паралельний прохід по всій полиці, а не книга за книгою: так
            // перевірки різних книг ідуть одночасно, а URI, спільний для кількох,
            // перевіряється один раз.
            val gone = repo.inaccessibleUris(shelf.flatMap { it.second })
            val counts = mutableMapOf<String, MissingFiles>()
            for ((id, uris) in shelf) {
                val missing = uris.count { it in gone }
                if (missing > 0) counts[id] = MissingFiles(missing = missing, total = uris.size)
            }
            AppLog.d(
                "missingScan",
                "${shelf.size} книг, ${shelf.sumOf { it.second.size }} файлів за " +
                    "${System.currentTimeMillis() - started} мс",
            )
            MissingScan(counts = counts, scanned = true)
                .also { lastScan = CachedScan(shelf, it, System.currentTimeMillis()) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MissingScan())

    /** Скільки файлів книги не відкривається — і зі скількох. */
    val missingFileCounts: StateFlow<Map<String, MissingFiles>> = missingScan
        .map { it.counts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val missingFilesBooks: StateFlow<Set<String>> = missingScan
        .map { it.counts.keys }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val busy: StateFlow<Boolean> = imports.busy

    val importProgress: StateFlow<ImportProgress> = imports.progress

    private val _pendingRename = MutableStateFlow<PendingRename?>(null)
    val pendingRename: StateFlow<PendingRename?> = _pendingRename.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    // Свої повідомлення + підсумки імпорту з процесного ImportState (імпорт міг початися в минулому VM).
    val messages: Flow<String> = kotlinx.coroutines.flow.merge(_messages.receiveAsFlow(), imports.messages)

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
                            if (scan.scanned && scan.counts.isEmpty() &&
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
                imports.join()
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
                    app.forAppLocale().resources.getQuantityString(R.plurals.batch_added_to_queue, items.size, items.size),
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
                        _messages.send(app.forAppLocale().getString(R.string.library_roots_empty_hint))
                    }
                    return@launch
                }
                if (!repo.anyLibraryRootGranted(rootList)) {
                    _newBookCandidates.value = emptyList()
                    if (notifyIfEmpty) {
                        _messages.send(app.forAppLocale().getString(R.string.library_roots_access_lost))
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
                    _messages.send(app.forAppLocale().getString(R.string.scan_no_new_books))
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
            val outcome = repo.importLibraryCandidates(candidates) { imports.reportProgress(it) }
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

    fun importUris(uris: List<Uri>) = import { repo.importUris(uris, onProgress = { imports.reportProgress(it) }) }
    fun importTree(uri: Uri) = import { repo.importTree(uri, onProgress = { imports.reportProgress(it) }) }
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
            _messages.send(app.forAppLocale().getString(R.string.added_to_queue, book.book.title))
        }
    }

    /**
     * Додає групу книг (цикл або автора) у чергу в заданому порядку.
     * Якщо серед них є недослухані — бере лише недослухані, інакше всі.
     */
    fun addBooksToQueue(items: List<BookWithChapters>) {
        if (items.isEmpty()) return
        val unfinished = items.filterNot { it.book.completed }
        val toAdd = unfinished.ifEmpty { items }
        player.batchAddToQueue(toAdd)
        launchSafely("LibraryViewModel.addBooksToQueue") {
            _messages.send(
                app.forAppLocale().resources.getQuantityString(R.plurals.batch_added_to_queue, toAdd.size, toAdd.size),
            )
        }
    }

    fun rename(id: String, title: String, author: String) {
        launchSafely("LibraryViewModel.rename") { repo.renameBook(id, title, author) }
        _pendingRename.value = null
    }

    /**
     * Перепривʼязка книги з полиці: той самий результат і те саме повідомлення, що й на
     * екрані книги, але без переходу туди — тап по втраченій книзі вже спитав, що робити.
     */
    fun relinkBook(bookId: String, treeUri: Uri) {
        viewModelScope.launch {
            val result = runCatching { repo.relinkBook(bookId, treeUri) }.getOrNull()
            _messages.send(relinkMessage(app, result))
        }
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
                    _messages.send(app.forAppLocale().getString(R.string.delete_failed))
                }
        }
    }

    fun cancelImport() {
        imports.cancel()
    }

    private fun describeOutcome(outcome: ImportOutcome): String {
        val added = outcome.imported.size
        val skipped = outcome.duplicates.size
        return when {
            skipped == 0 -> app.forAppLocale().resources.getQuantityString(R.plurals.import_added, added, added)
            added == 0 -> app.forAppLocale().getString(R.string.import_duplicate, outcome.duplicates.first())
            else -> app.forAppLocale().getString(
                R.string.import_added_with_skipped,
                app.forAppLocale().resources.getQuantityString(R.plurals.import_added, added, added),
                skipped,
            )
        }
    }

    private fun import(block: suspend () -> ImportOutcome) {
        // Скоуп процесу, а не viewModelScope: вихід з екрана чи закриття активності
        // очищали ViewModel і мовчки обривали довге копіювання / розпакування.
        // busy/Job/скасування тримає ImportState (спільний для всіх екземплярів VM),
        // тож паралельний другий імпорт неможливий, а cancelImport працює з нового VM.
        imports.tryStart(importScope) {
            try {
                val outcome = block()
                // Перейменування пропонуємо лише для однієї книги: для теки
                // з десятком книг діалог поспіль був би знущанням.
                val single = outcome.single
                if (single != null && outcome.duplicates.isEmpty() && libraryActive.value) {
                    _pendingRename.value = single
                } else {
                    imports.post(describeOutcome(outcome))
                }
            } catch (e: CancellationException) {
                if (imports.userCancelled) imports.post(app.forAppLocale().getString(R.string.import_cancelled))
                throw e
            } catch (e: DuplicateBookException) {
                imports.post(app.forAppLocale().getString(R.string.import_duplicate, e.existingTitle))
            } catch (e: Exception) {
                AppLog.w("LibraryViewModel.import", e)
                imports.post(app.forAppLocale().getString(R.string.import_failed))
            }
        }
    }
}
