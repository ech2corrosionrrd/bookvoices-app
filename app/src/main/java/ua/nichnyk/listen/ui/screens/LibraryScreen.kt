package ua.nichnyk.listen.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.nichnyk.listen.ui.theme.cardSurface
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.ui.theme.CardShape
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.LibraryFilter
import ua.nichnyk.listen.data.ShelfViewMode
import ua.nichnyk.listen.data.formatClock
import ua.nichnyk.listen.ui.components.BookCard
import ua.nichnyk.listen.ui.components.BookCover
import ua.nichnyk.listen.ui.components.BookRow
import ua.nichnyk.listen.ui.components.EmptyFilter
import ua.nichnyk.listen.ui.components.EmptyShelf
import ua.nichnyk.listen.ui.components.greetingLine
import ua.nichnyk.listen.ui.components.listeningTimeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenBook: (String) -> Unit,
    onPlayNow: () -> Unit,
    snack: (String) -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val books by vm.books.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val isSearching by vm.isSearching.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val selectedTag by vm.selectedTag.collectAsStateWithLifecycle()
    val allSeries by vm.allSeries.collectAsStateWithLifecycle()
    val selectedSeries by vm.selectedSeries.collectAsStateWithLifecycle()
    val isMultiSelect by vm.isMultiSelect.collectAsStateWithLifecycle()
    val selectedBookIds by vm.selectedBookIds.collectAsStateWithLifecycle()
    val missingFilesBooks by vm.missingFilesBooks.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val pendingRename by vm.pendingRename.collectAsStateWithLifecycle()
    val visible by vm.visibleBooks.collectAsStateWithLifecycle()
    val weeklyStats by vm.weeklyStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var addSheet by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BookWithChapters?>(null) }
    var pendingBatchDelete by remember { mutableStateOf(false) }
    var batchTagDialog by remember { mutableStateOf(false) }
    var bookMenuTarget by remember { mutableStateOf<BookWithChapters?>(null) }
    val resume by vm.continueListening.collectAsStateWithLifecycle()
    val newBookCount by vm.newBookCount.collectAsStateWithLifecycle()
    val libraryRefreshing by vm.libraryRefreshing.collectAsStateWithLifecycle()

    // STARTED/STOPPED, а не лише DisposableEffect(Unit): Home не знімає
    // композицію з NavHost, інакше libraryActive лишався true під час години
    // фонового слухання з відкритою полицею (Room + missingScan + K1).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.setLibraryActive(true)
                Lifecycle.Event.ON_STOP -> vm.setLibraryActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setLibraryActive(false)
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris)
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.importTree(it) }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snack(it) }
    }

    BackHandler(enabled = isMultiSelect) {
        vm.clearSelection()
    }

    @Composable
    fun HeaderContent() {
        Column {
            if (isMultiSelect) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.clearSelection() }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                    }
                    Text(
                        stringResource(R.string.selected_count, selectedBookIds.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.selectAll(visible.map { it.book.id }) }) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.select_all))
                    }
                    // Поки нічого не вибрано, групові дії неактивні. Доти вони
                    // спрацьовували вхолосту: «Видалити» відкривало підтвердження
                    // на нуль книг, а натискання «Так» не робило нічого.
                    val hasSelection = selectedBookIds.isNotEmpty()
                    IconButton(onClick = { vm.batchTogglePinned() }, enabled = hasSelection) {
                        Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.pin_book))
                    }
                    IconButton(onClick = { vm.batchAddToQueue() }, enabled = hasSelection) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = stringResource(R.string.batch_add_to_queue))
                    }
                    IconButton(onClick = { batchTagDialog = true }, enabled = hasSelection) {
                        Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = stringResource(R.string.batch_assign_tag))
                    }
                    IconButton(onClick = { pendingBatchDelete = true }, enabled = hasSelection) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.batch_delete),
                            tint = if (hasSelection) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(greetingLine(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSearching || query.isNotEmpty()) {
                        TextField(
                            value = query,
                            onValueChange = { vm.setQuery(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.search_placeholder)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                        IconButton(onClick = { vm.setSearching(false) }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close_search))
                        }
                    } else {
                        Text("BookVoices", style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            val nextMode = if (settings.shelfViewMode == ShelfViewMode.GRID) ShelfViewMode.LIST else ShelfViewMode.GRID
                            vm.setShelfViewMode(nextMode)
                        }) {
                            Icon(
                                if (settings.shelfViewMode == ShelfViewMode.GRID) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                contentDescription = if (settings.shelfViewMode == ShelfViewMode.GRID) stringResource(R.string.view_mode_list) else stringResource(R.string.view_mode_grid),
                            )
                        }
                        IconButton(onClick = { vm.startMultiSelect() }) {
                            Icon(Icons.Outlined.Checklist, contentDescription = stringResource(R.string.multi_select))
                        }
                        IconButton(onClick = { vm.setSearching(true) }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search_placeholder))
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.m))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryFilter.entries.forEach { item ->
                    val label = when (item) {
                        LibraryFilter.All -> stringResource(R.string.filter_all)
                        LibraryFilter.Listening -> stringResource(R.string.filter_listening)
                        LibraryFilter.Finished -> stringResource(R.string.filter_finished)
                        LibraryFilter.Unavailable -> stringResource(R.string.filter_unavailable)
                    }
                    val enabled = item != LibraryFilter.Unavailable || missingFilesBooks.isNotEmpty()
                    FilterChip(
                        selected = filter == item,
                        onClick = { if (enabled) vm.setFilter(item) },
                        label = { Text(label) },
                        enabled = enabled,
                    )
                }
                Box {
                    val sortTitle = stringResource(sortOrder.titleRes)
                    FilterChip(
                        selected = false,
                        onClick = { sortMenuOpen = true },
                        label = { Text("⇅ $sortTitle") },
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        ua.nichnyk.listen.data.BookSortOrder.entries.forEach { order ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(order.titleRes)) },
                                onClick = {
                                    vm.setSortOrder(order)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }
                allSeries.forEach { seriesName ->
                    FilterChip(
                        selected = selectedSeries == seriesName,
                        onClick = {
                            vm.setSelectedSeries(if (selectedSeries == seriesName) null else seriesName)
                        },
                        label = { Text(seriesName) },
                    )
                }
                selectedSeries?.let { seriesName ->
                    TextButton(onClick = { onOpenSeries(seriesName) }) {
                        Text(stringResource(R.string.open_series))
                    }
                }
                allTags.forEach { tag ->
                    FilterChip(
                        selected = selectedTag == tag,
                        onClick = {
                            vm.setSelectedTag(if (selectedTag == tag) null else tag)
                        },
                        label = { Text("#$tag") },
                    )
                }
            }

            if (newBookCount > 0 && !isMultiSelect) {
                Spacer(Modifier.height(Spacing.m))
                NewBooksBanner(
                    count = newBookCount,
                    busy = busy,
                    onImport = { vm.importDiscoveredBooks() },
                )
            }

            if (settings.showStatsOnShelf && (weeklyStats.weekMs > 0 || weeklyStats.completedBooks > 0) && query.isBlank() && filter == LibraryFilter.All && selectedTag == null && selectedSeries == null && !isMultiSelect) {
                Spacer(Modifier.height(Spacing.m))
                Surface(
                    shape = CardShape,
                    color = cardSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenHistory),
                ) {
                    Row(
                        Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(stringResource(R.string.stats_week_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.hair))
                            Text(listeningTimeText(weeklyStats.weekMs), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (weeklyStats.todayMs > 0) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(stringResource(R.string.stats_today_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(Spacing.hair))
                                Text(listeningTimeText(weeklyStats.todayMs), style = MaterialTheme.typography.titleMedium)
                            }
                        } else if (weeklyStats.completedBooks > 0) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(stringResource(R.string.stats_read_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(Spacing.hair))
                                Text(pluralStringResource(R.plurals.books_count, weeklyStats.completedBooks, weeklyStats.completedBooks), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ResumeSection() {
        // Ті самі умови, що й у картки статистики вище: мітка й серія теж
        // фільтрують полицю, і стрічка не має пропонувати книги повз вибраний
        // фільтр — доти вибір серії лишав нагорі сторонні книги.
        if (resume.isNotEmpty() && query.isBlank() && filter == LibraryFilter.All &&
            selectedTag == null && selectedSeries == null && !isMultiSelect
        ) {
            Column(Modifier.padding(top = Spacing.l)) {
                Text(stringResource(R.string.resume_section), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.s))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    items(resume.size) { i ->
                        val item = resume[i]
                        Row(
                            Modifier
                                .width(260.dp)
                                .clickable {
                                    vm.play(item) { onPlayNow() }
                                }
                                .padding(end = Spacing.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BookCover(item.book.coverPath, Modifier.size(72.dp), corner = 8.dp)
                            Spacer(Modifier.width(Spacing.s))
                            Column(Modifier.weight(1f)) {
                                Text(item.book.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    item.book.durationMs.formatClock(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = libraryRefreshing,
            onRefresh = { vm.scanLibraryRoots(notifyIfEmpty = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
        if (settings.shelfViewMode == ShelfViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, top = Spacing.m, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { HeaderContent() }
                item(span = { GridItemSpan(maxLineSpan) }) { ResumeSection() }

                if (books.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyShelf(onAdd = { addSheet = true }, onDemo = { vm.importDemo() })
                    }
                } else if (visible.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyFilter(
                            query = query,
                            filter = filter,
                            selectedTag = selectedTag,
                            selectedSeries = selectedSeries,
                            onClearFilters = vm::clearFilters,
                        )
                    }
                } else {
                    items(visible, key = { it.book.id }) { item ->
                        BookCard(
                            item,
                            onClick = {
                                if (isMultiSelect) vm.toggleSelectBook(item.book.id)
                                else onOpenBook(item.book.id)
                            },
                            onLongClick = {
                                if (isMultiSelect) vm.toggleSelectBook(item.book.id)
                                else bookMenuTarget = item
                            },
                            hasMissingFiles = missingFilesBooks.contains(item.book.id),
                            isMultiSelect = isMultiSelect,
                            isSelected = selectedBookIds.contains(item.book.id),
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                item { HeaderContent() }
                item { ResumeSection() }

                if (books.isEmpty()) {
                    item {
                        EmptyShelf(onAdd = { addSheet = true }, onDemo = { vm.importDemo() })
                    }
                } else if (visible.isEmpty()) {
                    item {
                        EmptyFilter(
                            query = query,
                            filter = filter,
                            selectedTag = selectedTag,
                            selectedSeries = selectedSeries,
                            onClearFilters = vm::clearFilters,
                        )
                    }
                } else {
                    items(visible, key = { it.book.id }) { item ->
                        BookRow(
                            item,
                            onClick = {
                                if (isMultiSelect) vm.toggleSelectBook(item.book.id)
                                else onOpenBook(item.book.id)
                            },
                            onLongClick = {
                                if (isMultiSelect) vm.toggleSelectBook(item.book.id)
                                else bookMenuTarget = item
                            },
                            hasMissingFiles = missingFilesBooks.contains(item.book.id),
                            isMultiSelect = isMultiSelect,
                            isSelected = selectedBookIds.contains(item.book.id),
                        )
                    }
                }
            }
        }
        }

        if (!isMultiSelect) {
            FloatingActionButton(
                onClick = { if (!busy) addSheet = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.xl, bottom = 96.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Outlined.LibraryMusic, contentDescription = stringResource(R.string.add_book))
            }
        }

        if (busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = CardShape, tonalElevation = 6.dp) {
                    Column(
                        Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(Spacing.m))
                        Text(
                            when {
                                importProgress.scanning -> stringResource(R.string.import_scanning)
                                importProgress.total > 0 -> stringResource(
                                    R.string.import_progress,
                                    importProgress.done.coerceAtMost(importProgress.total),
                                    importProgress.total,
                                )
                                else -> stringResource(R.string.import_working)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val current = importProgress.currentName
                        if (!current.isNullOrBlank()) {
                            Spacer(Modifier.height(Spacing.xxs))
                            Text(
                                current,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(Spacing.s))
                        TextButton(onClick = { vm.cancelImport() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }

    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = Spacing.xxl).padding(bottom = Spacing.xxxl)) {
                Text(stringResource(R.string.add_book), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.empty_shelf_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.l))
                TextButton(onClick = {
                    addSheet = false
                    runCatching {
                        pickFiles.launch(
                            arrayOf(
                                "audio/*",
                                "audio/mpeg",
                                "audio/mp4",
                                "audio/x-m4a",
                                "audio/x-m4b",
                                "application/mp4",
                                "application/ogg",
                                "audio/ogg",
                                "audio/flac",
                                "audio/wav",
                                "application/octet-stream",
                            ),
                        )
                    }.onFailure {
                        snack(context.getString(R.string.file_picker_unavailable))
                    }
                }) {
                    Icon(Icons.Outlined.LibraryMusic, contentDescription = null)
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.select_files))
                }
                TextButton(onClick = {
                    addSheet = false
                    runCatching {
                        pickTree.launch(null)
                    }.onFailure {
                        snack(context.getString(R.string.file_picker_unavailable))
                    }
                }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.folder_chapters))
                }
                TextButton(onClick = {
                    addSheet = false
                    vm.importDemo()
                }) { Text(stringResource(R.string.demo_chapter)) }
            }
        }
    }

    pendingRename?.let { pending ->
        var title by remember(pending.id) { mutableStateOf(pending.title) }
        var author by remember(pending.id) { mutableStateOf(pending.author) }
        AlertDialog(
            onDismissRequest = { vm.dismissRename() },
            title = { Text(stringResource(R.string.rename_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title_label)) }, singleLine = true)
                    Spacer(Modifier.height(Spacing.s))
                    OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.author_label)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.rename(pending.id, title.trim(), author.trim()) },
                    // Порожня назва перетворювала книгу на безіменний рядок на
                    // полиці, звідки її вже не знайти пошуком.
                    enabled = title.isNotBlank(),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissRename() }) { Text(stringResource(R.string.leave_as_is)) }
            },
            shape = CardShape,
        )
    }

    if (batchTagDialog) {
        var tagText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { batchTagDialog = false },
            title = { Text(stringResource(R.string.batch_assign_tag)) },
            text = {
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text(stringResource(R.string.tag_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tagText.isNotBlank()) {
                            vm.batchAssignTag(tagText.trim())
                            batchTagDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { batchTagDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
            shape = CardShape,
        )
    }

    bookMenuTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { bookMenuTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .padding(horizontal = Spacing.xxl)
                    .padding(bottom = Spacing.xxxl),
            ) {
                Text(target.book.title, style = MaterialTheme.typography.titleLarge)
                if (target.book.author.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.hair))
                    Text(target.book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(Spacing.l))

                TextButton(
                    onClick = {
                        vm.addToQueue(target)
                        bookMenuTarget = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = null)
                    Spacer(Modifier.width(Spacing.m))
                    Text(stringResource(R.string.add_to_queue), modifier = Modifier.weight(1f))
                }

                TextButton(
                    onClick = {
                        vm.togglePin(target)
                        bookMenuTarget = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.PushPin, contentDescription = null)
                    Spacer(Modifier.width(Spacing.m))
                    Text(
                        if (target.book.pinned) stringResource(R.string.unpin_book) else stringResource(R.string.pin_book),
                        modifier = Modifier.weight(1f),
                    )
                }

                TextButton(
                    onClick = {
                        val t = target
                        bookMenuTarget = null
                        vm.requestRename(t.book.id, t.book.title, t.book.author)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                    Spacer(Modifier.width(Spacing.m))
                    Text(stringResource(R.string.rename_book), modifier = Modifier.weight(1f))
                }

                TextButton(
                    onClick = {
                        val t = target
                        bookMenuTarget = null
                        pendingDelete = t
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(Spacing.m))
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_book_confirm)) },
            text = { Text(item.book.title) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(item.book.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (pendingBatchDelete) {
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.batch_delete_confirm,
                        selectedBookIds.size,
                        selectedBookIds.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.batchDeleteSelected()
                    pendingBatchDelete = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun NewBooksBanner(
    count: Int,
    busy: Boolean,
    onImport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(Spacing.l),
    ) {
        Text(
            stringResource(R.string.scan_new_books_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            pluralStringResource(R.plurals.scan_new_books_body, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(Spacing.m))
        FilledTonalButton(onClick = onImport, enabled = !busy) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.scan_import_books_action))
        }
    }
}
