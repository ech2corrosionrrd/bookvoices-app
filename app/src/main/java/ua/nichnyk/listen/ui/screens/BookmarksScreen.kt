package ua.nichnyk.listen.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.ui.theme.CardShape
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookmarkWithBook
import ua.nichnyk.listen.data.formatClock
import ua.nichnyk.listen.ui.components.BookCover
import ua.nichnyk.listen.ui.shareText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun BookmarksScreen(
    vm: BookmarksViewModel,
    onPlayed: () -> Unit,
) {
    val allItems by vm.items.collectAsStateWithLifecycle()
    val items by vm.filteredItems.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    // remember: SimpleDateFormat перестворювався на кожну рекомпозицію списку.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { SimpleDateFormat("d MMM, HH:mm", locale) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val deletedMsg = stringResource(R.string.bookmark_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    val shareFailedMsg = stringResource(R.string.share_failed)
    var editingBookmark by remember { mutableStateOf<BookmarkWithBook?>(null) }
    var editNoteText by remember { mutableStateOf("") }

    if (allItems.isEmpty() && query.isBlank()) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.xxxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.no_bookmarks), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Spacing.s))
            Text(
                stringResource(R.string.no_bookmarks_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val shareTitle = stringResource(R.string.share_bookmarks)
    val exportSubject = stringResource(R.string.export_bookmarks_title)

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.m, top = Spacing.xl, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(end = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bookmarks_title), style = MaterialTheme.typography.displayMedium)
                }
                IconButton(onClick = {
                    scope.launch {
                        // Через shareText: довгий список закладок раніше їхав у
                        // EXTRA_TEXT без жодного runCatching — тобто впритул до
                        // TransactionTooLargeException і падіння застосунку.
                        val ok = context.shareText(
                            text = vm.exportBookmarksMarkdown(),
                            fileName = "bookvoices-bookmarks.md",
                            mimeType = "text/plain",
                            subject = exportSubject,
                            chooserTitle = shareTitle,
                        )
                        if (!ok) snack.showSnackbar(shareFailedMsg)
                    }
                }) {
                    Icon(Icons.Outlined.Share, contentDescription = shareTitle)
                }
            }
        }

        if (allItems.isNotEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.s))
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.setQuery(it) },
                    placeholder = { Text(stringResource(R.string.search_bookmarks)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(end = Spacing.s, bottom = Spacing.s),
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true,
                )
            }
        }

        if (items.isEmpty() && query.isNotBlank()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.xxxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.no_bookmarks_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(items, key = { it.bookmark.id }) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        vm.play(item.bookmark.bookId, item.bookmark.chapterId, item.bookmark.positionMs, onStarted = onPlayed)
                    }
                    .padding(vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(item.coverPath, Modifier.size(52.dp), corner = 6.dp)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    // Назва книги — без maxLines: див. BookTitleDisplayTest.
                    Text(item.bookTitle, style = MaterialTheme.typography.titleMedium)
                    if (item.bookmark.note.isNotBlank()) {
                        Text(
                            "«${item.bookmark.note}»",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "${item.bookmark.chapterTitle} · ${item.bookmark.positionMs.formatClock()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(fmt.format(Date(item.bookmark.createdAt)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    editingBookmark = item
                    editNoteText = item.bookmark.note
                }) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_note))
                }
                IconButton(onClick = {
                    // Зі скасуванням, а не мовчки: одна кнопка в щільному рядку
                    // списку не має знищувати закладку без вороття, поки видалення
                    // книги поруч питає підтвердження.
                    scope.launch {
                        val restore = vm.delete(item.bookmark.id) ?: return@launch
                        val action = snack.showSnackbar(
                            message = deletedMsg,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Short,
                        )
                        if (action == SnackbarResult.ActionPerformed) vm.restore(restore)
                    }
                }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }

    // Власний хост: снекбар кореня навігації лежить під нижньою панеллю, а дію
    // «Скасувати» треба показати саме тут і саме поки її ще можна натиснути.
    SnackbarHost(
        snack,
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
    )
    }

    if (editingBookmark != null) {
        val target = editingBookmark ?: return
        AlertDialog(
            onDismissRequest = { editingBookmark = null },
            title = { Text(stringResource(R.string.edit_note)) },
            text = {
                Column {
                    Text(
                        "${target.bookTitle} — ${target.bookmark.chapterTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    OutlinedTextField(
                        value = editNoteText,
                        onValueChange = { editNoteText = it },
                        label = { Text(stringResource(R.string.bookmark_note_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateNote(target.bookmark.id, editNoteText)
                    editingBookmark = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingBookmark = null }) { Text(stringResource(R.string.cancel)) }
            },
            shape = CardShape,
        )
    }
}


