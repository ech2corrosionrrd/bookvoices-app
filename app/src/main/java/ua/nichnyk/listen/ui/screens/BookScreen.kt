package ua.nichnyk.listen.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveDone
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.ui.components.CharactersBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.formatClock
import ua.nichnyk.listen.data.progress
import ua.nichnyk.listen.ui.components.BookCover
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    vm: BookDetailViewModel,
    onRequestPro: () -> Unit = {},
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenAuthor: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
) {
    val item by vm.book.collectAsStateWithLifecycle()
    val ready by vm.bookReady.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val characters by vm.characters.collectAsStateWithLifecycle()
    // Список персонажів — Pro-фіча (див. UserPrefs.isPro). Нотатки поряд лишаються
    // безкоштовними, тож замикається саме одна кнопка, а не весь рядок.
    val isPro by vm.isPro.collectAsStateWithLifecycle()
    val missingFiles by vm.missingFiles.collectAsStateWithLifecycle()
    val relinking by vm.relinking.collectAsStateWithLifecycle()
    val appending by vm.appending.collectAsStateWithLifecycle()
    val newFilesCount by vm.newFilesCount.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    val pickRelinkFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> if (uri != null) vm.relink(uri) }

    val pickAppendFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.appendUris(uris) }

    val pickAppendTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { vm.appendTree(it) } }

    var confirmDelete by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var appendSheetOpen by remember { mutableStateOf(false) }
    var addTagDialogOpen by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }
    var charactersSheetOpen by remember { mutableStateOf(false) }
    var notesDialogOpen by remember { mutableStateOf(false) }
    var notesDraft by remember { mutableStateOf("") }
    val loaded = item

    if (!ready) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    if (loaded == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.book_missing_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(Spacing.s))
                Text(
                    stringResource(R.string.book_missing_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xl))
                TextButton(onClick = onBack) { Text(stringResource(R.string.to_shelf)) }
            }
        }
        return
    }
    val book = loaded

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { vm.togglePin() }) {
                        Icon(
                            if (book.book.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (book.book.pinned) stringResource(R.string.unpin_book) else stringResource(R.string.pin_book),
                            tint = if (book.book.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { appendSheetOpen = true }, enabled = !appending) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = stringResource(R.string.append_files))
                    }
                    IconButton(onClick = { renameOpen = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rename_dialog_title))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.scanSourceTree(notifyIfEmpty = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    BookCover(book.book.coverPath, Modifier.size(132.dp), corner = 12.dp)
                    Spacer(Modifier.width(Spacing.l))
                    Column(Modifier.weight(1f)) {
                        // Назва книги — без maxLines: див. BookTitleDisplayTest.
                        Text(book.book.title, style = MaterialTheme.typography.headlineMedium)
                        // Локальна змінна, а не book.book.series у трьох місцях:
                        // всередині clickable розумне приведення не працює, і там
                        // стояв єдиний `!!` у кодовій базі. Лямбда переживає
                        // рекомпозицію, тож зникла серія кинула б NPE вже після
                        // того, як напис намалювався.
                        val series = book.book.series
                        if (!series.isNullOrBlank()) {
                            val seriesText = buildString {
                                append(series)
                                book.book.seriesOrder?.let { order ->
                                    val numStr = if (order % 1f == 0f) order.toInt().toString() else order.toString()
                                    append(" #$numStr")
                                }
                            }
                            Text(
                                seriesText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .clickable { onOpenSeries(series) }
                                    .padding(vertical = Spacing.hair),
                            )
                        }
                        Text(
                            book.book.author,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable { onOpenAuthor(book.book.author) }
                                .padding(vertical = Spacing.hair),
                        )
                        Spacer(Modifier.height(Spacing.s))
                        val pct = (book.progress() * 100).roundToInt()
                        val chCount = pluralStringResource(R.plurals.chapters_count, book.chapters.size, book.chapters.size)
                        Text(
                            "$chCount · ${book.book.durationMs.formatClock()} · $pct%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { vm.removeTag(tag) },
                            label = { Text("#$tag") },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.remove_tag),
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                    AssistChip(
                        onClick = {
                            newTagText = ""
                            addTagDialogOpen = true
                        },
                        label = { Text(stringResource(R.string.add_tag)) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
                if (missingFiles > 0) {
                    Spacer(Modifier.height(Spacing.l))
                    RelinkBanner(
                        busy = relinking,
                        onChooseFolder = { runCatching { pickRelinkFolder.launch(null) } },
                    )
                }
                if (newFilesCount > 0) {
                    Spacer(Modifier.height(Spacing.l))
                    NewFilesBanner(
                        count = newFilesCount,
                        busy = appending,
                        onAppend = { vm.appendDiscovered() },
                    )
                }
                Spacer(Modifier.height(Spacing.l))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    Button(
                        onClick = { vm.play(onStarted = onOpenPlayer) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(when {
                            book.book.completed -> R.string.btn_listen_again
                            book.book.lastPlayedAt == null -> R.string.btn_start
                            else -> R.string.btn_continue
                        }))
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { vm.addToQueue() },
                    ) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = null)
                        Spacer(Modifier.width(Spacing.xxs))
                        Text(stringResource(R.string.add_to_queue))
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    androidx.compose.material3.OutlinedButton(
                        // Не `enabled = isPro`: вимкнена кнопка мовчить, і користувач
                        // не дізнається ні що це Pro, ні де його взяти. У налаштуваннях
                        // такі елементи ведуть у paywall — тут має бути так само.
                        onClick = { if (isPro) charactersSheetOpen = true else onRequestPro() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            when {
                                !isPro -> "${stringResource(R.string.characters_title)} 💎"
                                characters.isNotEmpty() ->
                                    "${stringResource(R.string.characters_title)} (${characters.size})"
                                else -> stringResource(R.string.characters_title)
                            },
                        )
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            notesDraft = book.book.notes.orEmpty()
                            notesDialogOpen = true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.NoteAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            if (!book.book.notes.isNullOrBlank()) {
                                stringResource(R.string.book_notes_title) + " ✍️"
                            } else {
                                stringResource(R.string.book_notes_title)
                            },
                        )
                    }
                }
                // Позначка «дослухано» досі ставилася лише сама, коли плеєр
                // дійшов до кінця. На ній тримаються фільтр «Дослухані»,
                // лічильник на полиці й статус картки в машині — тож книгу,
                // дослухану деінде, не було як закрити.
                androidx.compose.material3.TextButton(
                    onClick = { vm.toggleCompleted() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (book.book.completed) Icons.Outlined.RemoveDone else Icons.Outlined.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        stringResource(
                            if (book.book.completed) R.string.mark_unfinished else R.string.mark_finished,
                        ),
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
                Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleLarge)
            }
            val chapters = book.chapters.sortedBy { it.index }
            itemsIndexed(chapters, key = { _, ch -> ch.id }) { index, ch ->
                val current = index == book.book.currentChapterIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.play(index, onStarted = onOpenPlayer)
                        }
                        .padding(vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "%02d".format(index + 1),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            ch.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        )
                        Text(ch.durationMs.formatClock(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (bookmarks.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(Spacing.m))
                    Text(stringResource(R.string.bookmarks_title), style = MaterialTheme.typography.titleLarge)
                }
                itemsIndexed(bookmarks, key = { _, b -> b.id }) { _, mark ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.playBookmark(mark.chapterId, mark.positionMs, onStarted = onOpenPlayer)
                            }
                            .padding(vertical = Spacing.s),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mark.note.ifBlank { mark.chapterTitle }, style = MaterialTheme.typography.titleMedium)
                            Text("${mark.chapterTitle} · ${mark.positionMs.formatClock()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // Літерал, а не крок зі Spacing: це не ритм між блоками, а просвіт
            // під останнім розділом, щоб міні-плеєр не перекривав його собою.
            item { Spacer(Modifier.height(96.dp)) }
        }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_book_confirm)) },
            text = { Text(stringResource(R.string.delete_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteBook(onBack)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (renameOpen) {
        var title by remember(book.book.id) { mutableStateOf(book.book.title) }
        var author by remember(book.book.id) { mutableStateOf(book.book.author) }
        var series by remember(book.book.id) { mutableStateOf(book.book.series.orEmpty()) }
        var seriesOrder by remember(book.book.id) {
            mutableStateOf(
                book.book.seriesOrder?.let {
                    if (it % 1f == 0f) it.toInt().toString() else it.toString()
                }.orEmpty()
            )
        }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringResource(R.string.rename_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title_label)) }, singleLine = true)
                    Spacer(Modifier.height(Spacing.s))
                    OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.author_label)) }, singleLine = true)
                    Spacer(Modifier.height(Spacing.s))
                    OutlinedTextField(series, { series = it }, label = { Text(stringResource(R.string.series)) }, singleLine = true)
                    Spacer(Modifier.height(Spacing.s))
                    OutlinedTextField(
                        seriesOrder,
                        { seriesOrder = it },
                        label = { Text(stringResource(R.string.series_order)) },
                        singleLine = true,
                        // Поле числове, і те, що сюди не є числом, мовчки стає null
                        // при збереженні. Показувати при цьому буквену клавіатуру
                        // означало б запрошувати саме до такого введення.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateBookMetadata(
                        title = title,
                        author = author,
                        series = series.trim().ifBlank { null },
                        // Кома, а не крапка: українська розкладка дає саме її, а
                        // "1,5".toFloatOrNull() — це null, тобто номер зникав мовчки.
                        seriesOrder = seriesOrder.trim().replace(',', '.').toFloatOrNull(),
                    )
                    renameOpen = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (appendSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { appendSheetOpen = false },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.l)) {
                Text(stringResource(R.string.append_files), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    stringResource(R.string.append_files_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.l))
                FilledTonalButton(
                    onClick = {
                        appendSheetOpen = false
                        runCatching {
                            pickAppendFiles.launch(arrayOf("audio/*", "application/ogg"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.select_files))
                }
                Spacer(Modifier.height(Spacing.s))
                FilledTonalButton(
                    onClick = {
                        appendSheetOpen = false
                        runCatching {
                            pickAppendTree.launch(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.folder_chapters))
                }
                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }

    if (addTagDialogOpen) {
        AlertDialog(
            onDismissRequest = { addTagDialogOpen = false },
            title = { Text(stringResource(R.string.add_tag)) },
            text = {
                OutlinedTextField(
                    value = newTagText,
                    onValueChange = { newTagText = it },
                    placeholder = { Text(stringResource(R.string.tag_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newTagText.isNotBlank()) {
                            vm.addTag(newTagText)
                        }
                        addTagDialogOpen = false
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { addTagDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (notesDialogOpen) {
        AlertDialog(
            onDismissRequest = { notesDialogOpen = false },
            title = { Text(stringResource(R.string.book_notes_title)) },
            text = {
                OutlinedTextField(
                    value = notesDraft,
                    onValueChange = { notesDraft = it },
                    placeholder = { Text(stringResource(R.string.book_notes_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateNotes(notesDraft)
                        notesDialogOpen = false
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { notesDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (charactersSheetOpen) {
        CharactersBottomSheet(
            characters = characters,
            onAddCharacter = { name, role, desc -> vm.addCharacter(name, role, desc) },
            onDeleteCharacter = { id -> vm.deleteCharacter(id) },
            onDismiss = { charactersSheetOpen = false },
        )
    }
}


/**
 * Книга є, файлів немає.
 *
 * Свідомо не блокує решту екрана: закладки й список глав лишаються доступними —
 * саме вони і є те цінне, що перепривʼязка рятує.
 */
@Composable
private fun RelinkBanner(
    busy: Boolean,
    onChooseFolder: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(Spacing.l),
    ) {
        Text(
            stringResource(R.string.relink_banner_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.relink_banner_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(Spacing.m))
        FilledTonalButton(onClick = onChooseFolder, enabled = !busy) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.relink_action))
        }
    }
}

@Composable
private fun NewFilesBanner(
    count: Int,
    busy: Boolean,
    onAppend: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(Spacing.l),
    ) {
        Text(
            stringResource(R.string.scan_new_files_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            pluralStringResource(R.plurals.scan_new_files_body, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(Spacing.m))
        FilledTonalButton(onClick = onAppend, enabled = !busy) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.scan_append_action))
        }
    }
}
