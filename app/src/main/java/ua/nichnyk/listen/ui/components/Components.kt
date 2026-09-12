package ua.nichnyk.listen.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.LibraryFilter
import ua.nichnyk.listen.data.MissingFiles
import ua.nichnyk.listen.data.formatClock
import ua.nichnyk.listen.data.greetingRes
import ua.nichnyk.listen.data.hoursMinutesSeconds
import ua.nichnyk.listen.data.progress
import ua.nichnyk.listen.playback.PlayerUiState
import ua.nichnyk.listen.ui.theme.Filament
import kotlin.math.min
import ua.nichnyk.listen.ui.theme.trackSurface

@Composable
fun greetingLine(): String {
    return stringResource(greetingRes())
}

/**
 * «3 год 25 хв» / «3 h 25 min» — одиниці з ресурсів, а не з коду.
 * Менше хвилини показується в секундах: «0 хв» після перших хвилин
 * прослуховування виглядало так, ніби статистика не працює.
 */
@Composable
fun listeningTimeText(ms: Long): String {
    val (hours, minutes, seconds) = ms.hoursMinutesSeconds()
    val h = stringResource(R.string.unit_hours_short)
    val m = stringResource(R.string.unit_minutes_short)
    val s = stringResource(R.string.unit_seconds_short)
    return when {
        hours > 0L && minutes > 0L -> "$hours $h $minutes $m"
        hours > 0L -> "$hours $h"
        minutes > 0L -> "$minutes $m"
        else -> "$seconds $s"
    }
}

/**
 * Кеш декодованих обкладинок. Розмір — 1/8 доступної купи, облік у кілобайтах.
 * Без нього кожен скрол полиці перечитував і перемальовував усі файли заново.
 *
 * Живе стільки ж, скільки процес, тому вміє звільнятися на вимогу системи:
 * інакше десятки мегабайтів картинок утримували б застосунок у черзі на вбивство
 * саме тоді, коли він грає у фоні й має вижити.
 */
object CoverCache {

    internal val lru = object : LruCache<String, ImageBitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceIn(4 * 1024, 48 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    /**
     * Реакція на onTrimMemory. Помірний тиск урізає кеш удвічі, серйозний —
     * скидає повністю: полиця перемалюється, книга далі гратиме.
     */
    fun trim(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> lru.evictAll()
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> lru.trimToSize(lru.size() / 2)
        }
    }
}

private fun decodeCover(path: String): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val sample = (min(bounds.outWidth, bounds.outHeight) / 400).coerceAtLeast(1)
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}.getOrNull()

@Composable
fun BookCover(path: String?, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    // Синхронний BitmapFactory під час композиції давав просадки кадрів при скролі полиці,
    // тому декодуємо на IO і віддаємо результат через стан.
    var bitmap by remember(path) { mutableStateOf(path?.let { CoverCache.lru.get(it) }) }
    LaunchedEffect(path) {
        if (path != null && bitmap == null) {
            val decoded = withContext(Dispatchers.IO) { decodeCover(path) }
            if (decoded != null) CoverCache.lru.put(path, decoded)
            bitmap = decoded
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("B", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
fun LampCover(
    path: String?,
    playing: Boolean,
    /**
     * Лямбда, а не значення: прогрес рахується з позиції, тобто змінюється
     * 2,5 рази на секунду. Викликається всередині `Canvas`, тож читання
     * потрапляє у фазу малювання — кільце оновлюється без жодної рекомпозиції.
     */
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val glow by animateFloatAsState(if (playing) 0.55f else 0.18f, label = "glow")
    val pulse = rememberInfiniteTransition(label = "lamp")
    val breathe by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (playing) 1.045f else 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f * breathe
            drawCircle(Filament.copy(alpha = glow * 0.35f), radius = radius)
            drawCircle(Filament.copy(alpha = glow), radius = radius * 0.92f, style = Stroke(width = 10f))
            drawArc(
                color = Filament,
                startAngle = -90f,
                sweepAngle = 360f * progress().coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(size.width / 2 - radius * 0.92f, size.height / 2 - radius * 0.92f),
                size = Size(radius * 1.84f, radius * 1.84f),
                style = Stroke(width = 8f, cap = StrokeCap.Round),
            )
        }
        BookCover(
            path = path,
            modifier = Modifier
                .fillMaxSize()
                .padding(34.dp),
            corner = 16.dp,
        )
    }
}

@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    /** Див. [LampCover]: значення читає сам `LinearProgressIndicator` при малюванні. */
    progress: () -> Float,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    visible: Boolean,
) {
    AnimatedVisibility(
        visible = visible && state.book != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        val book = state.book ?: return@AnimatedVisibility
        Surface(
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand),
        ) {
            Column {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(book.book.coverPath, Modifier.size(48.dp), corner = 6.dp)
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        // Без maxLines навмисно: «повне відображення назв» —
                        // задокументована поведінка (README, розділ «Що вміє»).
                        // Довга назва розсуває смужку міні-плеєра, і це вибір,
                        // а не недогляд. Тримає BookTitleDisplayTest.
                        Text(book.book.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.chapter?.title ?: book.book.author,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val playStr = stringResource(R.string.play)
                    val pauseStr = stringResource(R.string.pause)
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) pauseStr else playStr,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * «Немає N з M файлів» на обкладинці.
 *
 * Раніше тут стояв самий трикутник, і книга, де зник один файл із сорока,
 * виглядала так само, як книга, від якої не лишилося нічого. Випадки різні:
 * перший лікується долиттям файлів, другий — перепривʼязкою теки.
 *
 * Число показуємо завжди, зокрема «20/20»: одне правило читається легше, ніж
 * трикутник, який іноді з числом, а іноді без.
 */
@Composable
fun MissingFilesBadge(
    missing: MissingFiles,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    // Читачам екрана — ціле речення, а не «3 навскіс 20».
    // Форму диктує загальна кількість файлів — саме її іменник і стоїть у фразі.
    val spoken = pluralStringResource(
        R.plurals.book_files_missing_count,
        missing.total,
        missing.missing,
        missing.total,
    )
    Surface(
        shape = RoundedCornerShape(if (compact) 6.dp else 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.semantics { contentDescription = spoken },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 2.dp),
            modifier = Modifier.padding(
                horizontal = if (compact) 3.dp else 5.dp,
                vertical = if (compact) 1.dp else 2.dp,
            ),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 10.dp else 13.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "${missing.missing}/${missing.total}",
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
fun BookCard(
    item: BookWithChapters,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    missingFiles: MissingFiles? = null,
    isMultiSelect: Boolean = false,
    isSelected: Boolean = false,
) {
    Column(
        modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            BookCover(
                item.book.coverPath,
                Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .then(
                        if (isSelected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                        else Modifier
                    ),
                corner = 12.dp,
            )
            val p = item.progress()
            if (p > 0f) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                    // Коли зникли всі файли, позиція втрачає сенс до перепривʼязки:
                    // смужка лишається (число правдиве), але гасне, щоб картка не
                    // читалася як готова до відтворення. При частковій втраті колір
                    // не чіпаємо — решту розділів слухати можна.
                    color = if (missingFiles?.isWhole == true) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Filament
                    },
                    trackColor = trackSurface,
                )
            }
            if (item.book.pinned) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.s)
                        .size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = stringResource(R.string.pinned_badge),
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            if (isMultiSelect) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.s)
                        .size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            } else if (missingFiles != null) {
                MissingFilesBadge(
                    missingFiles,
                    compact = false,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.s),
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))
        // Назва книги — без maxLines: див. BookTitleDisplayTest.
        Text(item.book.title, style = MaterialTheme.typography.titleMedium)
        if (!item.book.series.isNullOrBlank()) {
            val seriesText = buildString {
                append(item.book.series)
                item.book.seriesOrder?.let { order ->
                    val numStr = if (order % 1f == 0f) order.toInt().toString() else order.toString()
                    append(" #$numStr")
                }
            }
            Text(
                seriesText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            item.book.author,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookRow(
    item: BookWithChapters,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    missingFiles: MissingFiles? = null,
    isMultiSelect: Boolean = false,
    isSelected: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Spacing.s, horizontal = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isMultiSelect) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(Spacing.m))
        }

        Box {
            BookCover(item.book.coverPath, Modifier.size(56.dp), corner = 8.dp)
            if (item.book.pinned) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.hair)
                        .size(18.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = stringResource(R.string.pinned_badge),
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            if (missingFiles != null && !isMultiSelect) {
                MissingFilesBadge(
                    missingFiles,
                    compact = true,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.hair),
                )
            }
        }

        Spacer(Modifier.width(Spacing.m))

        Column(Modifier.weight(1f)) {
            // Назва книги — без maxLines: див. BookTitleDisplayTest.
            Text(
                item.book.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!item.book.series.isNullOrBlank()) {
                    val seriesText = buildString {
                        append(item.book.series)
                        item.book.seriesOrder?.let { order ->
                            val numStr = if (order % 1f == 0f) order.toInt().toString() else order.toString()
                            append(" #$numStr")
                        }
                    }
                    Text(
                        seriesText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(" • ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Text(
                    item.book.author,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val p = item.progress()
            if (p > 0f) {
                Spacer(Modifier.height(Spacing.xxs))
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    // Коли зникли всі файли, позиція втрачає сенс до перепривʼязки:
                    // смужка лишається (число правдиве), але гасне, щоб картка не
                    // читалася як готова до відтворення. При частковій втраті колір
                    // не чіпаємо — решту розділів слухати можна.
                    color = if (missingFiles?.isWhole == true) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Filament
                    },
                    trackColor = trackSurface,
                )
            }
        }

        Spacer(Modifier.width(Spacing.s))
        Text(
            item.book.durationMs.formatClock(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyFilter(
    query: String,
    filter: LibraryFilter,
    selectedTag: String? = null,
    selectedSeries: String? = null,
    onClearFilters: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xxl))
        Text(stringResource(R.string.nothing_found_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Spacing.s))
        // Мітка, серія й «недоступні» теж ховають книги — доти всі три випадки
        // провалювалися в `else` і показували те саме речення, що вже стоїть
        // заголовком вище.
        val message = when {
            query.isNotBlank() -> stringResource(R.string.nothing_found_query, query)
            filter == LibraryFilter.Listening -> stringResource(R.string.nothing_found_listening)
            filter == LibraryFilter.Finished -> stringResource(R.string.nothing_found_finished)
            filter == LibraryFilter.Unavailable -> stringResource(R.string.nothing_found_unavailable)
            // Мітка, серія або їх поєднання — назвати конкретний чіп тут нема як,
            // зате нижче є кнопка, яка знімає їх усі.
            else -> stringResource(R.string.nothing_found_filtered)
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val filtersActive = query.isNotBlank() ||
            filter != LibraryFilter.All ||
            selectedTag != null ||
            selectedSeries != null
        if (filtersActive && onClearFilters != null) {
            Spacer(Modifier.height(Spacing.s))
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.clear_filters))
            }
        }
    }
}

@Composable
fun EmptyShelf(onAdd: () -> Unit, onDemo: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("✦", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(Spacing.l))
        Text(stringResource(R.string.empty_shelf_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(R.string.empty_shelf_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        androidx.compose.material3.Button(onClick = onAdd) { Text(stringResource(R.string.add_book)) }
        androidx.compose.material3.TextButton(onClick = onDemo) { Text(stringResource(R.string.demo_chapter)) }
    }
}

