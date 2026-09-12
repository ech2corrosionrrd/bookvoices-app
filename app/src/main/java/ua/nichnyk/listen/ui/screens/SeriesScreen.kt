package ua.nichnyk.listen.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.nichnyk.listen.ui.theme.cardSurface
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.ui.theme.CardShape
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.ShelfQuery
import ua.nichnyk.listen.data.absolutePosition
import ua.nichnyk.listen.ui.components.BookCard
import ua.nichnyk.listen.ui.components.listeningTimeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    seriesName: String,
    libraryVm: LibraryViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val allBooks by libraryVm.books.collectAsStateWithLifecycle()
    val seriesBooks = remember(allBooks, seriesName) {
        ShelfQuery.booksInSeries(allBooks, seriesName)
    }
    val missingFileCounts by libraryVm.missingFileCounts.collectAsStateWithLifecycle()
    val totalDurationMs = seriesBooks.sumOf { it.book.durationMs }
    val totalListenedMs = seriesBooks.sumOf { it.absolutePosition() }
    val completedCount = seriesBooks.count { it.book.completed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        seriesName,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, top = Spacing.s, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                item(span = { GridItemSpan(2) }) {
                    Surface(
                        shape = CardShape,
                        color = cardSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    stringResource(R.string.series_stats_books),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(Spacing.hair))
                                Text(
                                    pluralStringResource(
                                        R.plurals.books_count,
                                        seriesBooks.size,
                                        seriesBooks.size,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            if (completedCount > 0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stringResource(R.string.filter_finished),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(Spacing.hair))
                                    Text(
                                        completedCount.toString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (totalListenedMs > 0L) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        stringResource(R.string.author_stats_listened),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(Spacing.hair))
                                    Text(
                                        listeningTimeText(totalListenedMs),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else if (totalDurationMs > 0L) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        stringResource(R.string.author_stats_duration),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(Spacing.hair))
                                    Text(
                                        listeningTimeText(totalDurationMs),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                items(seriesBooks, key = { it.book.id }) { item ->
                    BookCard(
                        item = item,
                        onClick = { onOpenBook(item.book.id) },
                        missingFiles = missingFileCounts[item.book.id],
                    )
                }
            }
        }
    }
}
