package ua.nichnyk.listen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import ua.nichnyk.listen.ui.theme.cardSurface
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.data.StatsPeriod
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import ua.nichnyk.listen.ui.theme.Filament
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.ListeningSessionEntity
import ua.nichnyk.listen.playback.PlayerManager
import ua.nichnyk.listen.ui.shareText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import ua.nichnyk.listen.ui.theme.trackSurface

data class HistoryGroup(
    val titleRes: Int,
    val dateLabel: String? = null,
    val sessions: List<ListeningSessionEntity>,
)

class HistoryViewModel(
    private val repo: LibraryRepository,
    private val player: PlayerManager,
    private val prefs: ua.nichnyk.listen.data.UserPrefs,
) : ViewModel() {

    val dailyGoalMinutes: StateFlow<Int> = prefs.settings
        .map { it.dailyGoalMinutes }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setDailyGoalMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.setDailyGoalMinutes(minutes)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _statsPeriod = MutableStateFlow(ua.nichnyk.listen.data.StatsPeriod.Week)
    val statsPeriod: StateFlow<ua.nichnyk.listen.data.StatsPeriod> = _statsPeriod.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val weeklyStats: StateFlow<ua.nichnyk.listen.data.WeeklyStats> = _statsPeriod
        .flatMapLatest { period -> repo.observeListeningStats(period) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ua.nichnyk.listen.data.WeeklyStats())

    fun setStatsPeriod(period: ua.nichnyk.listen.data.StatsPeriod) {
        _statsPeriod.value = period
    }

    val groups: StateFlow<List<HistoryGroup>> = repo.observeRecentSessions(200)
        .map { sessions -> groupSessions(sessions) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearHistory() {
        viewModelScope.launch {
            repo.clearListeningHistory()
        }
    }

    suspend fun exportHistoryCsv(): String = repo.exportHistoryCsv()

    private fun groupSessions(sessions: List<ListeningSessionEntity>): List<HistoryGroup> {
        if (sessions.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val firstOfMonth = today.withDayOfMonth(1)

        val todayList = mutableListOf<ListeningSessionEntity>()
        val yesterdayList = mutableListOf<ListeningSessionEntity>()
        val thisMonthList = mutableListOf<ListeningSessionEntity>()
        val earlierList = mutableListOf<ListeningSessionEntity>()

        for (s in sessions) {
            val date = Instant.ofEpochMilli(s.timestamp).atZone(zone).toLocalDate()
            when {
                date == today -> todayList.add(s)
                date == yesterday -> yesterdayList.add(s)
                !date.isBefore(firstOfMonth) -> thisMonthList.add(s)
                else -> earlierList.add(s)
            }
        }

        val result = mutableListOf<HistoryGroup>()
        if (todayList.isNotEmpty()) {
            result.add(HistoryGroup(titleRes = R.string.history_today, sessions = todayList))
        }
        if (yesterdayList.isNotEmpty()) {
            result.add(HistoryGroup(titleRes = R.string.history_yesterday, sessions = yesterdayList))
        }
        if (thisMonthList.isNotEmpty()) {
            result.add(HistoryGroup(titleRes = R.string.history_this_month, sessions = thisMonthList))
        }
        if (earlierList.isNotEmpty()) {
            result.add(HistoryGroup(titleRes = R.string.history_earlier, sessions = earlierList))
        }
        return result
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    vm: HistoryViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val weeklyStats by vm.weeklyStats.collectAsStateWithLifecycle()
    val statsPeriod by vm.statsPeriod.collectAsStateWithLifecycle()
    val dailyGoalMinutes by vm.dailyGoalMinutes.collectAsStateWithLifecycle()
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var showGoalDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val shareFailedMsg = stringResource(R.string.share_failed)
    val exportCsvTitle = stringResource(R.string.export_history_csv)
    val fileSavedMsg = stringResource(R.string.file_saved)
    val saveFailedMsg = stringResource(R.string.backup_save_failed)
    // Поруч із «Поділитися» — «Зберегти у файл»: MIUI у горизонтальній орієнтації
    // спустошує вікно «Поділитися» (див. ua.nichnyk.listen.ui.SaveToFile.kt).
    val saveCsv = ua.nichnyk.listen.ui.rememberSaveTextToFile(
        mimeType = "text/csv",
        produce = { vm.exportHistoryCsv() },
        onResult = { ok -> snack.showSnackbar(if (ok) fileSavedMsg else saveFailedMsg) },
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showGoalDialog = true }) {
                        Icon(
                            Icons.Outlined.Flag,
                            contentDescription = stringResource(R.string.daily_goal_set),
                            tint = if (dailyGoalMinutes > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (groups.isNotEmpty() || weeklyStats.totalDays > 0) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val ok = context.shareText(
                                        text = vm.exportHistoryCsv(),
                                        fileName = "bookvoices-history.csv",
                                        mimeType = "text/csv",
                                        subject = exportCsvTitle,
                                        chooserTitle = exportCsvTitle,
                                    )
                                    if (!ok) snack.showSnackbar(shareFailedMsg)
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = exportCsvTitle)
                        }
                        IconButton(onClick = { saveCsv("bookvoices-history.csv") }) {
                            Icon(
                                Icons.Outlined.Save,
                                contentDescription = stringResource(R.string.btn_backup_save),
                            )
                        }
                    }
                    if (groups.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.history_clear))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (groups.isEmpty() && weeklyStats.weekMs == 0L && weeklyStats.totalDays == 0 && dailyGoalMinutes == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(Spacing.xxl),
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(Spacing.l))
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                if (weeklyStats.dailyActivity.isNotEmpty()) {
                    item(key = "stats_period_chips") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            StatsPeriod.entries.forEach { period ->
                                val label = when (period) {
                                    StatsPeriod.Week -> stringResource(R.string.stats_period_week)
                                    StatsPeriod.Month -> stringResource(R.string.stats_period_month)
                                    StatsPeriod.Year -> stringResource(R.string.stats_period_year)
                                }
                                FilterChip(
                                    selected = statsPeriod == period,
                                    onClick = { vm.setStatsPeriod(period) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    item(key = "weekly_activity_chart") {
                        WeeklyActivityCard(
                            stats = weeklyStats,
                            period = statsPeriod,
                            dailyGoalMinutes = dailyGoalMinutes,
                            onEditGoal = { showGoalDialog = true },
                        )
                    }
                }

                groups.forEach { group ->
                    item(key = group.titleRes) {
                        Text(
                            text = stringResource(group.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = Spacing.xxs),
                        )
                    }

                    items(group.sessions, key = { it.id }) { session ->
                        HistorySessionCard(
                            session = session,
                            onClick = { onOpenBook(session.bookId) },
                        )
                    }
                }
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(stringResource(R.string.history_clear)) },
                text = { Text(stringResource(R.string.history_clear_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            vm.clearHistory()
                        },
                    ) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        if (showGoalDialog) {
            AlertDialog(
                onDismissRequest = { showGoalDialog = false },
                title = { Text(stringResource(R.string.daily_goal_title)) },
                text = {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        ua.nichnyk.listen.data.ListeningStats.DAILY_GOAL_PRESETS.forEach { preset ->
                            val label = if (preset == 0) {
                                stringResource(R.string.daily_goal_off)
                            } else {
                                ua.nichnyk.listen.ui.components.listeningTimeText(preset * 60_000L)
                            }
                            FilterChip(
                                selected = dailyGoalMinutes == preset,
                                onClick = {
                                    vm.setDailyGoalMinutes(preset)
                                    showGoalDialog = false
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGoalDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun HistorySessionCard(
    session: ListeningSessionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = cardSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(Spacing.m))
            Column(modifier = Modifier.weight(1f)) {
                // Назва книги — без maxLines: див. BookTitleDisplayTest.
                Text(
                    text = session.bookTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (session.author.isNotBlank()) {
                    Text(
                        text = session.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = formatSessionTime(session.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(Spacing.s))
            Text(
                text = ua.nichnyk.listen.ui.components.listeningTimeText(session.durationMs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatSessionTime(timestamp: Long): String {
    val instant = Instant.ofEpochMilli(timestamp)
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

@Composable
fun WeeklyActivityCard(
    stats: ua.nichnyk.listen.data.WeeklyStats,
    modifier: Modifier = Modifier,
    period: StatsPeriod = StatsPeriod.Week,
    dailyGoalMinutes: Int = 0,
    onEditGoal: (() -> Unit)? = null,
) {
    val chartTitle = when (period) {
        StatsPeriod.Week -> stringResource(R.string.weekly_activity)
        StatsPeriod.Month -> stringResource(R.string.monthly_activity)
        StatsPeriod.Year -> stringResource(R.string.yearly_activity)
    }
    val barWidth = when (period) {
        StatsPeriod.Week -> 18.dp
        StatsPeriod.Month -> 6.dp
        StatsPeriod.Year -> 14.dp
    }
    val maxBarHeight = when (period) {
        StatsPeriod.Week -> 64
        StatsPeriod.Month -> 56
        StatsPeriod.Year -> 64
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = cardSurface,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(Spacing.l)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = chartTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = ua.nichnyk.listen.ui.components.listeningTimeText(stats.weekMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (stats.longestStreakDays > 0) {
                Spacer(Modifier.height(Spacing.xs))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.stats_streak_current, stats.currentStreakDays),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (stats.currentStreakDays > 0) Filament else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (stats.currentStreakDays > 0) FontWeight.Medium else FontWeight.Normal,
                    )
                    Text(
                        text = stringResource(R.string.stats_streak_best, stats.longestStreakDays),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (dailyGoalMinutes > 0) {
                val goalProgress = ua.nichnyk.listen.data.ListeningStats.dailyGoalProgress(stats.todayMs, dailyGoalMinutes)
                val todayText = ua.nichnyk.listen.ui.components.listeningTimeText(stats.todayMs)
                val goalText = ua.nichnyk.listen.ui.components.listeningTimeText(dailyGoalMinutes * 60_000L)
                Spacer(Modifier.height(Spacing.s))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { m -> if (onEditGoal != null) m.clickable(onClick = onEditGoal) else m },
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.daily_goal_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.daily_goal_progress, todayText, goalText),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (goalProgress >= 1f) Filament else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(Spacing.xxs))
                    LinearProgressIndicator(
                        progress = { goalProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (goalProgress >= 1f) Filament else MaterialTheme.colorScheme.primary,
                        trackColor = trackSurface,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.l))
            val maxDur = stats.dailyActivity.maxOfOrNull { it.durationMs }?.coerceAtLeast(60_000L) ?: 60_000L
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                stats.dailyActivity.forEach { day ->
                    val fraction = (day.durationMs.toFloat() / maxDur.toFloat()).coerceIn(0.08f, 1f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        val barColor = if (day.isToday) Filament
                            else if (day.durationMs > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                            else trackSurface
                        Box(
                            Modifier
                                .width(barWidth)
                                .height((fraction * maxBarHeight).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(barColor)
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = day.dayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (day.isToday) Filament else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (day.isToday) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
