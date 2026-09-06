package ua.nichnyk.listen.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Timer
import ua.nichnyk.listen.ui.theme.cardSurface
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.ui.theme.CardShape
import ua.nichnyk.listen.data.UserSettings
import ua.nichnyk.listen.data.VoicePreset
import ua.nichnyk.listen.ui.components.CharactersBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.formatClock
import ua.nichnyk.listen.data.formatSpeed
import ua.nichnyk.listen.playback.JumpBackTarget
import ua.nichnyk.listen.playback.PlaybackTick
import ua.nichnyk.listen.playback.PlayerUiState
import ua.nichnyk.listen.playback.sleepBadgeMinutes
import ua.nichnyk.listen.ui.components.LampCover
import ua.nichnyk.listen.ui.theme.dividerLine
import kotlin.math.abs
import kotlin.math.hypot
/**
 * Розміри й стилі, якими різняться дві розкладки плеєра.
 *
 * Раніше розкладки були двома копіями по ~250 рядків кожна — з тими самими
 * жестами, панеллю дій, кнопками й значком сну. Копії встигли розійтися:
 * у горизонтальній, наприклад, не було підпису «Прочитано NN%». Тепер спільне
 * лежить у спільних функціях, а різне — тут.
 */
/**
 * Підказка про виконаний жест: текст плюс номер жесту.
 *
 * Номер потрібен саме для того, щоб два однакові жести підряд були двома
 * різними значеннями стану — інакше таймер приховування не перезапускається.
 */
private data class GestureHint(val text: String, val seq: Long)

private data class PlayerMetrics(
    val seekButton: Dp,
    val seekIcon: Dp,
    val playButton: Dp,
    val playIcon: Dp,
    val titleStyle: TextStyle,
    val subtitleStyle: TextStyle,
    val timeStyle: TextStyle,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    /**
     * Шлях до paywall. Доти замкнені елементи плеєра просто гасли: кнопка
     * «Персонажі» була сірою, тумблери в шторці швидкості — теж, і дізнатися
     * ні що це Pro, ні де його взяти, з цього екрана було ніяк.
     */
    onRequestPro: () -> Unit = {},
) {
    // stable, а не state: у повному стані лежить positionMs, і кожен його тик
    // (2,5 рази на секунду) інвалідував це тіло цілком — усі 600 рядків обох
    // гілок розкладки. Позиція тепер приходить окремо, у tick.
    val state by vm.stable.collectAsStateWithLifecycle()
    // Без `by`: collectAsStateWithLifecycle() повертає State і саме по собі значення не читає.
    // Читають його лише ті кілька елементів нижче, кожен у власній області
    // рекомпозиції, а кільце прогресу — узагалі лише при малюванні.
    val tick = vm.tick.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val book = state.book
    // remember за id книги: observeCharacters віддає холодний Room-Flow, тобто
    // новий екземпляр на кожен виклик, а collectAsStateWithLifecycle тримає flow за ключ і
    // перезапускає збирання, коли ключ змінився. Тіло екрана рекомпозиться на
    // кожен тик позиції (2,5 раза на секунду), тож без remember це давало нову
    // підписку на запит бази кожні 400 мс, поки грає книга.
    val bookId = book?.book?.id
    val charactersFlow = remember(bookId) {
        if (bookId != null) vm.observeCharacters(bookId) else kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val characters by charactersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Межу Freemium тримає UserPrefs; тут лишається показати, що саме замкнене,
    // і дати шлях до paywall.
    val isPro = settings.isPro
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val bmSavedMsg = stringResource(R.string.bookmark_saved)
    val bmFailedMsg = stringResource(R.string.bookmark_failed)
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var bookmarkDialogOpen by remember { mutableStateOf(false) }
    var bookmarkNote by remember { mutableStateOf("") }
    var gestureHint by remember { mutableStateOf<GestureHint?>(null) }
    var gestureSeq by remember { mutableLongStateOf(0L) }

    // Один таймер на поточну підказку, а не власна корутина на кожен жест:
    // два свайпи поспіль — і таймер першого гасив підказку від другого.
    //
    // Ключ включає лічильник: два однакові жести підряд дають однаковий текст,
    // і без нього LaunchedEffect не перезапустився б — тобто друга підказка
    // гасла б за таймером першої, тією самою помилкою, від якої тут і йдеться.
    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(700)
            gestureHint = null
        }
    }

    // Замкнене в шторці швидкості веде до paywall — але спершу закриваємо саму
    // шторку: дві модальні шторки одна над одною закриваються по черзі й
    // виглядають як зависання.
    fun requestPro() {
        sheet = null
        onRequestPro()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val isLandscape = maxWidth > maxHeight

        if (book == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.nothing_playing), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(Spacing.s))
                Text(stringResource(R.string.choose_book_prompt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onBack) { Text(stringResource(R.string.to_shelf)) }
            }
            return@BoxWithConstraints
        }

        val metrics = if (isLandscape) {
            PlayerMetrics(
                seekButton = 48.dp,
                seekIcon = 26.dp,
                playButton = 64.dp,
                playIcon = 36.dp,
                titleStyle = MaterialTheme.typography.headlineMedium,
                subtitleStyle = MaterialTheme.typography.bodyMedium,
                timeStyle = MaterialTheme.typography.labelMedium,
            )
        } else {
            PlayerMetrics(
                seekButton = 52.dp,
                seekIcon = 28.dp,
                playButton = 76.dp,
                playIcon = 40.dp,
                titleStyle = MaterialTheme.typography.headlineLarge,
                subtitleStyle = MaterialTheme.typography.bodyLarge,
                timeStyle = MaterialTheme.typography.labelLarge,
            )
        }

        val topActions: @Composable () -> Unit = {
            PlayerTopActions(
                queueSize = state.queue.size,
                isPro = isPro,
                onBack = onBack,
                onQueue = { sheet = Sheet.Queue },
                onCharacters = { if (isPro) sheet = Sheet.Characters else onRequestPro() },
                onChapters = { sheet = Sheet.Chapters },
                onBookmark = {
                    bookmarkNote = ""
                    bookmarkDialogOpen = true
                },
            )
        }

        val transport: @Composable () -> Unit = {
            PlayerTransport(
                state = state,
                settings = settings,
                metrics = metrics,
                onSpeed = { sheet = Sheet.Speed },
                onSleep = { sheet = Sheet.Sleep },
                onSkipBack = vm::skipBack,
                onSkipForward = vm::skipForward,
                onPlayPause = vm::playPause,
            )
        }

        val cover: @Composable (Modifier) -> Unit = { coverModifier ->
            PlayerCover(
                coverPath = book.book.coverPath,
                playing = state.isPlaying,
                progress = { tick.value.bookProgress },
                settings = settings,
                feedback = gestureHint?.text,
                onFeedback = { text ->
                    gestureSeq += 1
                    gestureHint = GestureHint(text, gestureSeq)
                },
                onSkipBack = vm::skipBack,
                onSkipForward = vm::skipForward,
                onInstantBookmark = {
                    vm.bookmark("") { saved ->
                        scope.launch {
                            snack.showSnackbar(if (saved) bmSavedMsg else bmFailedMsg)
                        }
                    }
                },
                modifier = coverModifier,
            )
        }

        if (isLandscape) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.xxl, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxl),
            ) {
                cover(
                    Modifier
                        .weight(0.42f)
                        .fillMaxHeight(0.92f)
                        .aspectRatio(1f),
                )

                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    topActions()

                    Column {
                        // Без maxLines: «повне відображення назв» — документована
                        // поведінка, яку тримає BookTitleDisplayTest. Доти
                        // горизонтальна розкладка обрізала назву на другому рядку,
                        // а вертикальна показувала повністю — ще один слід від
                        // двох копій цього екрана.
                        Text(book.book.title, style = metrics.titleStyle)
                        Text(
                            state.chapter?.title ?: book.book.author,
                            style = metrics.subtitleStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    JumpBackChip(
                        target = state.jumpBackTarget,
                        onJump = vm::jumpBack,
                        onDismiss = vm::dismissJumpBack,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )

                    ChapterProgress(
                        tick = tick,
                        onSeek = vm::seek,
                        timeStyle = metrics.timeStyle,
                    )

                    transport()

                    // Було лише у вертикальній розкладці: горизонтальна лишалася
                    // без підпису «Прочитано NN%» рівно тому, що дві копії коду
                    // розійшлися.
                    BookProgressLabel(
                        tick = tick,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl),
            ) {
                topActions()

                Spacer(Modifier.height(Spacing.s))
                cover(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .widthIn(max = 280.dp)
                        .aspectRatio(1f)
                        .align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(Spacing.xl))

                // Назва книги — без maxLines: див. BookTitleDisplayTest.
                Text(
                    book.book.title,
                    style = metrics.titleStyle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    state.chapter?.title ?: book.book.author,
                    style = metrics.subtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                JumpBackChip(
                    target = state.jumpBackTarget,
                    onJump = vm::jumpBack,
                    onDismiss = vm::dismissJumpBack,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Spacing.s),
                )

                Spacer(Modifier.height(Spacing.xl))
                ChapterProgress(
                    tick = tick,
                    onSeek = vm::seek,
                    timeStyle = metrics.timeStyle,
                )

                Spacer(Modifier.height(Spacing.s))
                transport()

                Spacer(Modifier.height(Spacing.m))
                BookProgressLabel(
                    tick = tick,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }

        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(Spacing.l))
    }

    if (bookmarkDialogOpen) {
        val defaultChapterTitle = stringResource(R.string.chapters)
        AlertDialog(
            onDismissRequest = { bookmarkDialogOpen = false },
            title = { Text(stringResource(R.string.add_bookmark)) },
            text = {
                Column {
                    // tick, а не state: у stable позиція скинута в нуль, і підпис
                    // показував би «00:00» замість місця, яке зараз зберігають.
                    Text(
                        "${state.chapter?.title ?: defaultChapterTitle} · ${tick.value.positionMs.formatClock()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.m))
                    OutlinedTextField(
                        value = bookmarkNote,
                        onValueChange = { bookmarkNote = it },
                        label = { Text(stringResource(R.string.bookmark_note_label)) },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // За результатом, а не безумовно: без книги чи без поточної
                    // глави закладка не створюється, а екран усе одно рапортував
                    // «Закладку збережено».
                    vm.bookmark(bookmarkNote.trim()) { saved ->
                        scope.launch { snack.showSnackbar(if (saved) bmSavedMsg else bmFailedMsg) }
                    }
                    bookmarkDialogOpen = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkDialogOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
            shape = CardShape,
        )
    }

    // Sheet.Characters має власний ModalBottomSheet (див. нижче), тож зовнішній
    // для нього не відкриваємо: інакше позаду наповненої шторки ставала друга,
    // порожня, і закриття однієї лишало другу.
    if (sheet != null && sheet != Sheet.Characters) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            when (sheet) {
                Sheet.Speed -> Column(
                    Modifier
                        .padding(horizontal = Spacing.xl)
                        .padding(top = Spacing.s, bottom = Spacing.xxxl)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.speed_and_audio), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Spacing.l))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                val newSpeed = (Math.round((state.speed - 0.05f) * 20) / 20f).coerceIn(0.5f, 3.0f)
                                vm.speed(newSpeed)
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.Remove, contentDescription = "-0.05")
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                state.speed.formatSpeed(),
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(stringResource(R.string.current_speed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        IconButton(
                            onClick = {
                                val newSpeed = (Math.round((state.speed + 0.05f) * 20) / 20f).coerceIn(0.5f, 3.0f)
                                vm.speed(newSpeed)
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "+0.05")
                        }
                    }

                    Spacer(Modifier.height(Spacing.m))
                    Slider(
                        value = state.speed.coerceIn(0.5f, 3.0f),
                        onValueChange = {
                            val stepped = (Math.round(it * 20) / 20f).coerceIn(0.5f, 3.0f)
                            vm.speed(stepped)
                        },
                        valueRange = 0.5f..3.0f,
                        steps = 49,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0.5×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("1.0×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("2.0×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("3.0×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(Modifier.height(Spacing.l))
                    Text(stringResource(R.string.quick_presets), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.s))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).forEach { preset ->
                            FilterChip(
                                selected = kotlin.math.abs(state.speed - preset) < 0.02f,
                                onClick = { vm.speed(preset) },
                                label = { Text(preset.formatSpeed()) },
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.xl))
                    HorizontalDivider(color = dividerLine)
                    Spacer(Modifier.height(Spacing.m))
                    // Замкнений рядок веде до paywall — той самий візерунок, що
                    // й на екрані налаштувань. Доти тут був лише сірий тумблер.
                    Row(
                        Modifier.fillMaxWidth().let { if (isPro) it else it.clickable { requestPro() } },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.skip_silence), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (isPro) stringResource(R.string.skip_silence_desc) else stringResource(R.string.pro_locked),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.skipSilence,
                            onCheckedChange = { vm.setSkipSilence(it) },
                            enabled = isPro,
                        )
                    }
                    Spacer(Modifier.height(Spacing.m))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.mono_audio), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.mono_audio_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.monoAudio,
                            onCheckedChange = { vm.setMonoAudio(it) },
                        )
                    }
                    Spacer(Modifier.height(Spacing.m))
                    Row(
                        Modifier.fillMaxWidth().let { if (isPro) it else it.clickable { requestPro() } },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.volume_boost), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (isPro) stringResource(R.string.volume_boost_desc) else stringResource(R.string.pro_locked),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.volumeBoost,
                            onCheckedChange = { vm.setVolumeBoost(it) },
                            enabled = isPro,
                        )
                    }
                    Spacer(Modifier.height(Spacing.l))
                    HorizontalDivider()
                    Spacer(Modifier.height(Spacing.l))

                    Text(stringResource(R.string.equalizer_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isPro) stringResource(R.string.speech_clarity_desc) else stringResource(R.string.pro_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    val eqPresets = listOf(
                        VoicePreset.OFF to stringResource(R.string.eq_preset_off),
                        VoicePreset.SPEECH_CLARITY to stringResource(R.string.eq_preset_clarity),
                        VoicePreset.WARM to stringResource(R.string.eq_preset_warm),
                        VoicePreset.TREBLE_CUT to stringResource(R.string.eq_preset_treble_cut),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        eqPresets.forEach { (preset, label) ->
                            FilterChip(
                                selected = state.voicePreset == preset,
                                onClick = { if (isPro) vm.setVoicePreset(preset) else requestPro() },
                                label = { Text(label) },
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.l))
                    Row(
                        modifier = Modifier.fillMaxWidth().let { if (isPro) it else it.clickable { requestPro() } },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // formatSpeed(), а не власний "%.2fx": множник висоти
                            // тону писався латинською «x», поки швидкість поруч —
                            // знаком множення.
                            "${stringResource(R.string.pitch_title)}: ${state.pitch.formatSpeed()}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (kotlin.math.abs(state.pitch - 1.0f) > 0.01f) {
                            TextButton(onClick = { vm.setPitch(1.0f) }) {
                                Text(stringResource(R.string.pitch_reset))
                            }
                        }
                    }
                    Slider(
                        value = state.pitch,
                        onValueChange = { vm.setPitch(it) },
                        valueRange = 0.8f..1.2f,
                        steps = 7,
                        enabled = isPro,
                    )
                }

                Sheet.Sleep -> Column(Modifier.padding(Spacing.xl).padding(bottom = Spacing.xxl)) {
                    Text(stringResource(R.string.sleep_timer), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Spacing.s))
                    Text(stringResource(R.string.sleep_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.l))

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        FilterChip(
                            selected = state.isSleepEndOfChapter,
                            onClick = {
                                vm.sleepEndOfChapter()
                                sheet = null
                            },
                            label = { Text(stringResource(R.string.sleep_end_of_chapter)) },
                        )
                        FilterChip(
                            selected = state.isSleepEndOfBook,
                            onClick = {
                                vm.sleepEndOfBook()
                                sheet = null
                            },
                            label = { Text(stringResource(R.string.sleep_until_book_end)) },
                        )
                    }
                    Spacer(Modifier.height(Spacing.s))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        listOf(5, 15, 30, 45, 60).forEach { min ->
                            FilterChip(
                                // sleepPresetMinutes — саме те, що завели, а не
                                // залишок: доти підсвіченим ставав той чіп, повз
                                // чиє значення зараз проходив зворотний відлік.
                                selected = state.sleepPresetMinutes == min,
                                onClick = {
                                    vm.sleep(min)
                                    sheet = null
                                },
                                label = { Text(stringResource(R.string.minutes_format, min)) },
                            )
                        }
                    }
                    if (state.sleepRemainingMs != null) {
                        Spacer(Modifier.height(Spacing.s))
                        TextButton(onClick = {
                            vm.cancelSleep()
                            sheet = null
                        }) { Text(stringResource(R.string.sleep_off)) }
                    }
                }

                Sheet.Chapters -> Column(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl)) {
                    Text(stringResource(R.string.chapters), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Spacing.s))
                    // Відкриваємо на поточній главі: у книзі з сотнею файлів
                    // список починався з першої, і місце, де слухач зараз,
                    // доводилося шукати гортанням.
                    val chaptersState = rememberLazyListState()
                    LaunchedEffect(state.chapterIndex) {
                        chaptersState.scrollToItem(state.chapterIndex.coerceAtLeast(0))
                    }
                    LazyColumn(state = chaptersState) {
                        val chapters = book?.chapters.orEmpty().sortedBy { it.index }
                        itemsIndexed(chapters) { index, ch ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.jumpChapter(index)
                                        sheet = null
                                    }
                                    .padding(vertical = Spacing.m),
                            ) {
                                Text(
                                    ch.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (index == state.chapterIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(ch.durationMs.formatClock(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Sheet.Queue -> Column(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.headlineMedium)
                        if (state.queue.isNotEmpty()) {
                            TextButton(onClick = { vm.clearQueue() }) {
                                Text(stringResource(R.string.queue_clear), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.s))
                    if (state.queue.isEmpty()) {
                        Text(
                            stringResource(R.string.queue_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.xxl),
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            itemsIndexed(state.queue, key = { _, b -> b.book.id }) { _, item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            cardSurface,
                                            MaterialTheme.shapes.medium,
                                        )
                                        .padding(Spacing.m),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        // Назва книги — без maxLines: див. BookTitleDisplayTest.
                                        Text(
                                            item.book.title,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        if (item.book.author.isNotBlank()) {
                                            Text(
                                                item.book.author,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            item.book.durationMs.formatClock(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    IconButton(onClick = {
                                        vm.playQueueItem(item)
                                        sheet = null
                                    }) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.queue_play_now))
                                    }
                                    IconButton(onClick = { vm.removeFromQueue(item.book.id) }) {
                                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.delete))
                                    }
                                }
                            }
                        }
                    }
                }
                // Обидва — нічого: персонажів малює власна шторка після цього блоку.
                Sheet.Characters, null -> Unit
            }
        }
    }

    if (sheet == Sheet.Characters) {
        CharactersBottomSheet(
            characters = characters,
            onAddCharacter = { name, role, desc ->
                book?.book?.id?.let { vm.addCharacter(it, name, role, desc) }
            },
            onDeleteCharacter = { id -> vm.deleteCharacter(id) },
            onDismiss = { sheet = null },
        )
    }
}

/**
 * Обкладинка з жестами перемотування — спільна для обох розкладок.
 *
 * Свайп і однопальцевий double-tap — те саме, що кнопки транспорту (H6).
 * Два пальці подвійним тапом — миттєва закладка без діалогу (J3).
 */
@Composable
private fun PlayerCover(
    coverPath: String?,
    playing: Boolean,
    progress: () -> Float,
    settings: UserSettings,
    feedback: String?,
    onFeedback: (String) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onInstantBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondsUnit = stringResource(R.string.unit_seconds_short)
    val forwardLabel = "\u23E9 +${settings.skipForwardMs / 1000} $secondsUnit"
    val backLabel = "\u23EA -${settings.skipBackMs / 1000} $secondsUnit"

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .pointerInput(forwardLabel, backLabel) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                    onDragEnd = {
                        if (abs(totalDrag) > 60f) {
                            if (totalDrag > 0) {
                                onSkipForward()
                                onFeedback(forwardLabel)
                            } else {
                                onSkipBack()
                                onFeedback(backLabel)
                            }
                        }
                    },
                )
            }
            .pointerInput(settings.doubleTapSeek, forwardLabel, backLabel) {
                if (settings.doubleTapSeek) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2f) {
                                onSkipBack()
                                onFeedback(backLabel)
                            } else {
                                onSkipForward()
                                onFeedback(forwardLabel)
                            }
                        },
                    )
                }
            }
            .pointerInput(Unit) {
                // detectTapGestures бачить лише один палець — для J3 свій цикл.
                // Події з ≥2 пальцями споживаємо, інакше однопальцевий double-tap
                // seek також спрацює на тому ж жесті.
                val slop = viewConfiguration.touchSlop
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                var lastTwoFingerTapUptime = 0L
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var maxPointers = 1
                    var dragged = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > maxPointers) maxPointers = pressed
                        if (maxPointers >= 2) {
                            event.changes.forEach { it.consume() }
                        }
                        for (change in event.changes) {
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            if (hypot(dx.toDouble(), dy.toDouble()) > slop) {
                                dragged = true
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!dragged && maxPointers >= 2) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTwoFingerTapUptime in 1..doubleTapTimeout) {
                            lastTwoFingerTapUptime = 0L
                            onInstantBookmark()
                        } else {
                            lastTwoFingerTapUptime = now
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LampCover(
            path = coverPath,
            playing = playing,
            progress = progress,
            modifier = Modifier.fillMaxSize(),
        )

        if (feedback != null) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = Spacing.xl, vertical = Spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Верхня панель дій: назад, черга, персонажі, глави, закладка. */
@Composable
private fun PlayerTopActions(
    queueSize: Int,
    isPro: Boolean,
    onBack: () -> Unit,
    onQueue: () -> Unit,
    onCharacters: () -> Unit,
    onChapters: () -> Unit,
    onBookmark: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onQueue) {
            Box {
                Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = stringResource(R.string.queue_title))
                if (queueSize > 0) {
                    Text(
                        text = queueSize.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
        // Не `enabled = isPro`: вимкнена кнопка мовчить, і користувач не дізнається
        // ні що це Pro, ні де його взяти. Замок показуємо значком, а дотик веде
        // до paywall — так само, як на екрані книги.
        IconButton(onClick = onCharacters) {
            Box {
                Icon(Icons.Outlined.Person, contentDescription = stringResource(R.string.characters_title))
                if (!isPro) {
                    Text(
                        text = "\uD83D\uDC8E",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
        IconButton(onClick = onChapters) {
            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = stringResource(R.string.chapters))
        }
        IconButton(onClick = onBookmark) {
            Icon(Icons.Outlined.BookmarkAdd, contentDescription = stringResource(R.string.bookmark))
        }
    }
}

/** Пропозиція повернутися туди, звідки щойно перемотали. */
@Composable
private fun JumpBackChip(
    target: JumpBackTarget?,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = target != null, modifier = modifier) {
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            onClick = onJump,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = Spacing.m, end = Spacing.xxs, top = Spacing.hair, bottom = Spacing.hair),
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    stringResource(R.string.jump_back_to, target?.timeLabel.orEmpty()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.jump_back_dismiss),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** Швидкість, перемотування, пуск/пауза й таймер сну. */
@Composable
private fun PlayerTransport(
    state: PlayerUiState,
    settings: UserSettings,
    metrics: PlayerMetrics,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            state.speed.formatSpeed(),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onSpeed)
                .padding(Spacing.s),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        IconButton(onClick = onSkipBack, modifier = Modifier.size(metrics.seekButton)) {
            Icon(
                painterResource(R.drawable.ic_seek_replay),
                contentDescription = stringResource(R.string.rewind_sec, settings.skipBackMs / 1000),
                modifier = Modifier.size(metrics.seekIcon),
            )
        }
        Box(
            Modifier
                .size(metrics.playButton)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (state.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(metrics.playIcon),
            )
        }
        IconButton(onClick = onSkipForward, modifier = Modifier.size(metrics.seekButton)) {
            Icon(
                painterResource(R.drawable.ic_seek_forward),
                contentDescription = stringResource(R.string.forward_sec, settings.skipForwardMs / 1000),
                modifier = Modifier.size(metrics.seekIcon),
            )
        }
        Box {
            IconButton(onClick = onSleep) {
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = stringResource(R.string.sleep_timer),
                    tint = if (state.sleepRemainingMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            val sleepLeft = state.sleepRemainingMs
            if (sleepLeft != null) {
                val label = when {
                    state.isSleepEndOfChapter -> stringResource(R.string.sleep_badge_chapter)
                    state.isSleepEndOfBook -> "\uD83D\uDCD6"
                    else -> sleepBadgeMinutes(sleepLeft).toString()
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = Spacing.xxs, end = Spacing.xxs),
                )
            }
        }
    }
}

/**
 * Смужка перегляду з годинником «пройдено / лишилося».
 *
 * Окрема функція не заради краси: позиція оновлюється 2,5 рази на секунду, і
 * поки ці рядки стояли просто в тілі PlayerScreen, кожен тик перемальовував
 * увесь екран разом із обома гілками розкладки. Тепер область рекомпозиції —
 * рівно цей блок, якому позиція справді потрібна.
 *
 * Стан перетягування живе тут же, а не на верхньому рівні екрана: за межами
 * слайдера його ніхто не читає, а підняте нагору воно рекомпозило все тіло на
 * кожен рух пальця.
 *
 * Раніше цей блок стояв двома копіями — в горизонтальній і вертикальній
 * гілках. Різнилися вони лише стилем підписів, тому [timeStyle] і лишився
 * параметром.
 */
@Composable
private fun ChapterProgress(
    tick: State<PlaybackTick>,
    onSeek: (Long) -> Unit,
    timeStyle: TextStyle,
    // Явний fillMaxWidth: доти обидві копії були голим `Column {}` і тримали
    // повну ширину лише тому, що так їх міряв батько. Виносити блок у функцію
    // з такою неявною домовленістю означало б чекати, поки вона колись зламається.
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val now = tick.value
    val sliderMax = now.durationMs.coerceAtLeast(1L).toFloat()
    val shownMs = if (dragging) dragValue.toLong() else now.positionMs
    Column(modifier) {
        Slider(
            value = if (dragging) dragValue else now.positionMs.toFloat().coerceIn(0f, sliderMax),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..sliderMax,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                shownMs.formatClock(),
                style = timeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "-${(now.durationMs - shownMs).coerceAtLeast(0).formatClock()}",
                style = timeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** «Прочитано NN%». Теж окремо — рахується з позиції, тобто змінюється на кожен тик. */
@Composable
private fun BookProgressLabel(
    tick: State<PlaybackTick>,
    modifier: Modifier = Modifier,
) {
    Text(
        stringResource(R.string.book_progress, (tick.value.bookProgress * 100).toInt()),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private enum class Sheet { Speed, Sleep, Chapters, Queue, Characters }
