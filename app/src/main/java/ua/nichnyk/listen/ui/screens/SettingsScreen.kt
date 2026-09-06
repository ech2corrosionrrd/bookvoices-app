package ua.nichnyk.listen.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.nichnyk.listen.ui.theme.Spacing
import ua.nichnyk.listen.ui.theme.CardShape
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.AddLibraryRootResult
import ua.nichnyk.listen.data.HeadsetAction
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.data.formatSpeed
import ua.nichnyk.listen.ui.shareText
import ua.nichnyk.listen.ui.theme.dividerLine

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOpenHistory: () -> Unit = {},
    // Шторка Pro живе в корені навігації: до неї ведуть ще екрани книги й плеєра,
    // а друга копія тут дублювала і саму шторку, і обробник «відновити покупку».
    onRequestPro: () -> Unit = {},
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val isPro by vm.isPro.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    val successMsg = stringResource(R.string.backup_restored_success)
    val failMsg = stringResource(R.string.backup_restore_failed)
    val backupFailedMsg = stringResource(R.string.share_failed)
    var confirmRestore by remember { mutableStateOf(false) }

    // Позначка платної фічі. Сам запобіжник живе в UserPrefs (значення приходять
    // уже замаскованими), тут лише видимий стан і шлях до paywall.
    val lockedLabel = stringResource(R.string.pro_locked)

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    // Читання файла — на IO: rememberCoroutineScope працює на головному
                    // потоці, і великий бекап помітно підвішував інтерфейс.
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        } ?: ""
                    }
                    if (vm.importBackupJson(json)) {
                        snack.showSnackbar(successMsg)
                    } else {
                        snack.showSnackbar(failMsg)
                    }
                }.onFailure {
                    ua.nichnyk.listen.AppLog.w("SettingsScreen: відновлення з бекапу", it)
                    snack.showSnackbar(failMsg)
                }
            }
        }
    }

    val libraryRootAdded = stringResource(R.string.library_roots_added)
    val libraryRootDuplicate = stringResource(R.string.library_roots_duplicate)
    val libraryRootPermissionFailed = stringResource(R.string.library_roots_permission_failed)
    val pickLibraryRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.addLibraryRoot(uri) { result ->
            scope.launch {
                snack.showSnackbar(
                    when (result) {
                        AddLibraryRootResult.ADDED -> libraryRootAdded
                        AddLibraryRootResult.DUPLICATE -> libraryRootDuplicate
                        AddLibraryRootResult.LIMIT ->
                            context.getString(R.string.library_roots_limit, UserPrefs.MAX_LIBRARY_ROOTS)
                        AddLibraryRootResult.PERMISSION_FAILED -> libraryRootPermissionFailed
                    },
                )
            }
        }
    }

    // Снекбар живе у Scaffold, а не всередині прокручуваного Column:
    // раніше повідомлення про результат синхронізації малювалося в кінці вмісту
    // сторінки й було видно лише тому, хто догорнув донизу.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
    ) { inner ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(inner)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl)
            .padding(bottom = 100.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.displayMedium)
        Text(stringResource(R.string.settings_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.l))

        // --- Pro Banner ---
        androidx.compose.material3.Surface(
            shape = CardShape,
            color = if (isPro) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            onClick = onRequestPro,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isPro) "💎" else "✨",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (isPro) stringResource(R.string.pro_already_active) else stringResource(R.string.pro_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isPro) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = if (isPro) stringResource(R.string.pro_lifetime_desc) else stringResource(R.string.pro_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = (if (isPro) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.8f),
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.xxl))

        // --- 1. Вигляд та Тема ---
        Text(stringResource(R.string.section_appearance), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FilterChip(
                selected = s.themeMode == ua.nichnyk.listen.data.AppTheme.NIGHT,
                onClick = { vm.themeMode(ua.nichnyk.listen.data.AppTheme.NIGHT) },
                label = { Text(stringResource(R.string.theme_night)) },
            )
            FilterChip(
                selected = s.themeMode == ua.nichnyk.listen.data.AppTheme.PAPER,
                onClick = { vm.themeMode(ua.nichnyk.listen.data.AppTheme.PAPER) },
                label = { Text(stringResource(R.string.theme_paper)) },
            )
            FilterChip(
                selected = s.themeMode == ua.nichnyk.listen.data.AppTheme.OLED,
                onClick = { vm.themeMode(ua.nichnyk.listen.data.AppTheme.OLED) },
                label = { Text(stringResource(R.string.theme_oled)) },
            )
        }

        Spacer(Modifier.height(Spacing.l))
        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FilterChip(
                selected = s.language.isBlank(),
                onClick = { vm.setLanguage("") },
                label = { Text(stringResource(R.string.language_system)) },
            )
            FilterChip(
                selected = s.language == "uk",
                onClick = { vm.setLanguage("uk") },
                label = { Text(stringResource(R.string.language_uk)) },
            )
            FilterChip(
                selected = s.language == "en",
                onClick = { vm.setLanguage("en") },
                label = { Text(stringResource(R.string.language_en)) },
            )
            FilterChip(
                selected = s.language == "pl",
                onClick = { vm.setLanguage("pl") },
                label = { Text(stringResource(R.string.language_pl)) },
            )
            FilterChip(
                selected = s.language == "de",
                onClick = { vm.setLanguage("de") },
                label = { Text(stringResource(R.string.language_de)) },
            )
            FilterChip(
                selected = s.language == "es",
                onClick = { vm.setLanguage("es") },
                label = { Text(stringResource(R.string.language_es)) },
            )
            FilterChip(
                selected = s.language == "fr",
                onClick = { vm.setLanguage("fr") },
                label = { Text(stringResource(R.string.language_fr)) },
            )
            // Єдиний тег із регіоном: ресурси лежать у values-pt-rBR, а тут і в
            // locales_config.xml той самий рядок мусить бути в формі BCP-47.
            FilterChip(
                selected = s.language == "pt-BR",
                onClick = { vm.setLanguage("pt-BR") },
                label = { Text(stringResource(R.string.language_pt)) },
            )
        }

        Spacer(Modifier.height(Spacing.xxl))
        HorizontalDivider(color = dividerLine)
        Spacer(Modifier.height(Spacing.xl))

        // --- 2. Керування та Жести ---
        Text(stringResource(R.string.section_controls), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.m))

        Text(stringResource(R.string.skip_back_setting), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf(10, 15, 30).forEach { sec ->
                FilterChip(
                    selected = s.skipBackMs == sec * 1000,
                    onClick = { vm.skipBack(sec * 1000) },
                    label = { Text(stringResource(R.string.seconds_format, sec)) },
                )
            }
        }

        Spacer(Modifier.height(Spacing.m))
        Text(stringResource(R.string.skip_forward_setting), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf(15, 30, 60).forEach { sec ->
                FilterChip(
                    selected = s.skipForwardMs == sec * 1000,
                    onClick = { vm.skipForward(sec * 1000) },
                    label = { Text(stringResource(R.string.seconds_format, sec)) },
                )
            }
        }

        Spacer(Modifier.height(Spacing.m))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.double_tap_setting), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.double_tap_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.doubleTapSeek, onCheckedChange = vm::doubleTapSeek)
        }

        Spacer(Modifier.height(Spacing.m))
        Text(stringResource(R.string.headset_actions), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.headset_actions_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.s))
        HeadsetActionPicker(
            title = stringResource(R.string.headset_double_tap),
            selected = s.headsetDoubleTapAction,
            skipForwardMs = s.skipForwardMs,
            skipBackMs = s.skipBackMs,
            onSelect = vm::headsetDoubleTap,
        )

        Spacer(Modifier.height(Spacing.m))
        HeadsetActionPicker(
            title = stringResource(R.string.headset_triple_tap),
            selected = s.headsetTripleTapAction,
            skipForwardMs = s.skipForwardMs,
            skipBackMs = s.skipBackMs,
            onSelect = vm::headsetTripleTap,
        )

        Spacer(Modifier.height(Spacing.m))
        Row(
            Modifier.fillMaxWidth().let { if (isPro) it else it.clickable(onClick = onRequestPro) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_bookmark_bluetooth), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isPro) stringResource(R.string.auto_bookmark_bluetooth_desc) else lockedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.autoBookmarkBluetooth, onCheckedChange = vm::autoBookmarkBluetooth, enabled = isPro)
        }

        Spacer(Modifier.height(Spacing.xxl))
        HorizontalDivider(color = dividerLine)
        Spacer(Modifier.height(Spacing.xl))

        // --- 3. Сон та Таймер ---
        Text(stringResource(R.string.section_sleep), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.m))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fade_on_sleep), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.fade_on_sleep_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.fadeOnSleep, onCheckedChange = vm::fade)
        }

        Spacer(Modifier.height(Spacing.m))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.shake_sleep), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.shake_sleep_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.shakeToExtendSleep, onCheckedChange = vm::shakeToExtendSleep)
        }

        Spacer(Modifier.height(Spacing.xxl))
        HorizontalDivider(color = dividerLine)
        Spacer(Modifier.height(Spacing.xl))

        // --- 4. Аудіо-рушій ---
        Text(stringResource(R.string.section_audio), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.m))

        Text(stringResource(R.string.default_speed), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                FilterChip(
                    selected = kotlin.math.abs(s.defaultSpeed - speed) < 0.01f,
                    onClick = { vm.speed(speed) },
                    label = { Text(speed.formatSpeed()) },
                )
            }
        }

        Spacer(Modifier.height(Spacing.m))
        Row(
            Modifier.fillMaxWidth().let { if (isPro) it else it.clickable(onClick = onRequestPro) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.skip_silence), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isPro) stringResource(R.string.skip_silence_desc) else lockedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.skipSilence, onCheckedChange = vm::skipSilence, enabled = isPro)
        }

        // Пресети замість колишнього тумблера «чіткість мовлення»: він керував тим
        // самим апаратним Equalizer, що й пресет, і два контроли одного ефекту
        // глушили один одного залежно від того, який створився останнім.
        Spacer(Modifier.height(Spacing.m))
        Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.equalizer_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (isPro) stringResource(R.string.speech_clarity_desc) else lockedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                listOf(
                    ua.nichnyk.listen.data.VoicePreset.OFF to stringResource(R.string.eq_preset_off),
                    ua.nichnyk.listen.data.VoicePreset.SPEECH_CLARITY to stringResource(R.string.eq_preset_clarity),
                    ua.nichnyk.listen.data.VoicePreset.WARM to stringResource(R.string.eq_preset_warm),
                    ua.nichnyk.listen.data.VoicePreset.TREBLE_CUT to stringResource(R.string.eq_preset_treble_cut),
                ).forEach { (preset, label) ->
                    FilterChip(
                        selected = s.voicePreset == preset,
                        onClick = { if (isPro) vm.voicePreset(preset) else onRequestPro() },
                        label = { Text(label) },
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.m))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mono_audio), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.mono_audio_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.monoAudio, onCheckedChange = vm::monoAudio)
        }

        Spacer(Modifier.height(Spacing.m))
        Row(
            Modifier.fillMaxWidth().let { if (isPro) it else it.clickable(onClick = onRequestPro) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.volume_boost), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isPro) stringResource(R.string.volume_boost_desc) else lockedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.volumeBoost, onCheckedChange = vm::volumeBoost, enabled = isPro)
        }

        Spacer(Modifier.height(Spacing.m))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.smart_rewind), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.smart_rewind_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.smartRewind, onCheckedChange = vm::smartRewind)
        }


        Spacer(Modifier.height(Spacing.xxl))
        HorizontalDivider(color = dividerLine)
        Spacer(Modifier.height(Spacing.xl))

        // --- 5. Головні папки бібліотеки (нові книги) ---
        Text(stringResource(R.string.section_library_roots), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.library_roots_desc, UserPrefs.MAX_LIBRARY_ROOTS),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.m))
        if (s.libraryRootUris.isEmpty()) {
            Text(
                stringResource(R.string.library_roots_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.m))
        } else {
            s.libraryRootUris.forEach { rootUri ->
                val label = remember(rootUri) {
                    runCatching {
                        DocumentFile.fromTreeUri(context, rootUri.toUri())?.name
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: rootUri.substringAfterLast(':').substringAfterLast('/').ifBlank { rootUri }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { vm.removeLibraryRoot(rootUri) }) {
                        Text(stringResource(R.string.library_roots_remove))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.s))
        }
        if (s.libraryRootUris.size < UserPrefs.MAX_LIBRARY_ROOTS) {
            OutlinedButton(
                onClick = {
                    runCatching { pickLibraryRoot.launch(null) }
                        .onFailure {
                            scope.launch {
                                snack.showSnackbar(context.getString(R.string.file_picker_unavailable))
                            }
                        }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.library_roots_add))
            }
        } else {
                Text(
                    stringResource(R.string.library_roots_limit, UserPrefs.MAX_LIBRARY_ROOTS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }

        Spacer(Modifier.height(Spacing.xxl))
        HorizontalDivider(color = dividerLine)
        Spacer(Modifier.height(Spacing.xl))

        // --- 6. Полиця та Статистика ---
        Text(stringResource(R.string.section_stats), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.m))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.show_stats_shelf), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.show_stats_shelf_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.showStatsOnShelf, onCheckedChange = vm::showStatsOnShelf)
        }

        Spacer(Modifier.height(Spacing.m))
        OutlinedButton(
            onClick = onOpenHistory,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.History, contentDescription = null)
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.history_title))
        }

        Spacer(Modifier.height(Spacing.xxl))
        HorizontalDivider(color = dividerLine)
        Spacer(Modifier.height(Spacing.xl))

        // --- 7. Резервне копіювання та Експорт ---
        Text(stringResource(R.string.section_backup), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.backup_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            // Формування й запис файла — на IO; на головному потоці
                            // лишається тільки показ системного вибору застосунку.
                            val file = withContext(Dispatchers.IO) {
                                val json = vm.exportBackupJson()
                                val dir = java.io.File(context.cacheDir, "backup").apply { mkdirs() }
                                java.io.File(dir, "bookvoices-backup.json").apply { writeText(json) }
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, "BookVoices Backup")
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, "backup", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }.onFailure {
                            ua.nichnyk.listen.AppLog.w("SettingsScreen: експорт бекапу", it)
                            snack.showSnackbar(backupFailedMsg)
                        }
                    }
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.btn_backup))
            }

            OutlinedButton(
                onClick = { confirmRestore = true },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.btn_restore))
            }
        }

        Spacer(Modifier.height(Spacing.m))
        val shareFailedMsg = stringResource(R.string.share_failed)
        OutlinedButton(
            onClick = {
                scope.launch {
                    // Через shareText, а не EXTRA_TEXT: каталог великої полиці не
                    // вміщався в транзакцію Binder і знімав застосунок.
                    val ok = context.shareText(
                        text = vm.exportCatalogMarkdown(),
                        fileName = "bookvoices-catalog.md",
                        mimeType = "text/plain",
                        subject = "BookVoices Catalog",
                    )
                    if (!ok) snack.showSnackbar(shareFailedMsg)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.export_catalog))
        }

        Spacer(Modifier.height(Spacing.xxl))
        HorizontalDivider(color = dividerLine)
        Spacer(Modifier.height(Spacing.xl))

        // --- 8. Хмарна синхронізація (WebDAV) ---
        Text(stringResource(R.string.section_cloud), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.cloud_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.m))

        // Форма живе у власному стані й записується лише кнопкою «Зберегти».
        // Раніше поле було привʼязане до потоку налаштувань і зберігалося на кожен
        // символ: асинхронна емісія з попереднім значенням відкочувала введений текст,
        // а server.trim() не давав ввести навіть пробіл.
        var davServer by remember { mutableStateOf("") }
        var davUser by remember { mutableStateOf("") }
        var davPass by remember { mutableStateOf("") }
        var davSavedPass by remember { mutableStateOf("") }
        var davLoaded by remember { mutableStateOf(false) }
        var davLoading by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            davServer = s.webDavServer
            davUser = s.webDavUser
            davPass = vm.loadWebDavPassword()
            davSavedPass = davPass
            davLoaded = true
        }

        // Пароль теж рахується зміною: доти можна було виправити лише його,
        // піти з екрана — і не побачити жодного попередження про незбережене.
        val dirty = davLoaded &&
            (davServer != s.webDavServer || davUser != s.webDavUser || davPass != davSavedPass)

        OutlinedTextField(
            value = davServer,
            onValueChange = { davServer = it },
            label = { Text(stringResource(R.string.webdav_server)) },
            placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/") },
            singleLine = true,
            enabled = davLoaded,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.s))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(
                value = davUser,
                onValueChange = { davUser = it },
                label = { Text(stringResource(R.string.webdav_username)) },
                singleLine = true,
                enabled = davLoaded,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = davPass,
                onValueChange = { davPass = it },
                label = { Text(stringResource(R.string.webdav_password)) },
                singleLine = true,
                enabled = davLoaded,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.webdav_password_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xxs),
        )

        if (!s.secretsEncrypted) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                stringResource(R.string.webdav_insecure_storage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (s.webDavAuthFailed) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                stringResource(R.string.webdav_auth_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val savedMsg = stringResource(R.string.webdav_saved)
        Spacer(Modifier.height(Spacing.m))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = {
                    davServer = davServer.trim()
                    davUser = davUser.trim()
                    vm.saveWebDav(davServer, davUser, davPass, s.webDavAutoSync)
                    davSavedPass = davPass
                    scope.launch { snack.showSnackbar(savedMsg) }
                },
                enabled = davLoaded,
            ) { Text(stringResource(R.string.webdav_save_btn)) }
            if (dirty) {
                Spacer(Modifier.width(Spacing.s))
                Text(
                    stringResource(R.string.webdav_unsaved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(Spacing.m))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.webdav_auto_sync), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.webdav_auto_sync_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = s.webDavAutoSync,
                onCheckedChange = { vm.setWebDavAutoSync(it) },
            )
        }

        if (s.webDavLastSyncTime > 0L) {
            val syncFmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
            Text(
                stringResource(R.string.webdav_last_sync, syncFmt.format(java.util.Date(s.webDavLastSyncTime))),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }

        val testSuccessMsg = stringResource(R.string.webdav_test_success)
        val testFailMsg = stringResource(R.string.webdav_test_failed)
        val uploadSuccessMsg = stringResource(R.string.webdav_upload_success)
        val uploadFailMsg = stringResource(R.string.webdav_upload_failed)
        val downloadSuccessMsg = stringResource(R.string.webdav_download_success)
        val downloadFailMsg = stringResource(R.string.webdav_download_failed)

        // Спільний обробник: заблокувати кнопки, виконати запит, показати результат.
        // try/finally і зовнішній runCatching: syncUploadWebDav готує бекап ще до
        // того, як з'явиться Result, тож виняток звідти пролітав повз обидві гілки —
        // davLoading лишався true, і всі три кнопки блокувалися до перестворення екрана.
        fun runDav(failPrefix: String, okMsg: String, call: suspend () -> Result<Unit>) {
            if (davServer.isBlank() || davLoading) return
            scope.launch {
                davLoading = true
                val res = try {
                    call()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    davLoading = false
                }
                res.onSuccess { snack.showSnackbar(okMsg) }
                    .onFailure { snack.showSnackbar("$failPrefix: ${context.webDavMessage(it)}") }
            }
        }

        Spacer(Modifier.height(Spacing.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(
                onClick = { runDav(testFailMsg, testSuccessMsg) { vm.testWebDav(davServer, davUser, davPass) } },
                enabled = !davLoading && davServer.isNotBlank(),
            ) {
                Text(stringResource(R.string.webdav_test_btn))
            }

            FilledTonalButton(
                onClick = { runDav(uploadFailMsg, uploadSuccessMsg) { vm.syncUploadWebDav(davServer, davUser, davPass) } },
                enabled = !davLoading && davServer.isNotBlank(),
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.webdav_upload_btn))
            }

            OutlinedButton(
                onClick = { runDav(downloadFailMsg, downloadSuccessMsg) { vm.syncDownloadWebDav(davServer, davUser, davPass) } },
                enabled = !davLoading && davServer.isNotBlank(),
            ) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.webdav_download_btn))
            }
        }

        Spacer(Modifier.height(Spacing.xxl))
        Text("BookVoices", style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.about_app_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.s))
        Text(stringResource(R.string.version_format, ua.nichnyk.listen.BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Лише debug: Play Billing відповідає тільки застосунку, встановленому з
        // Play, тож у сайдлоуд-збірці платні екрани інакше не відкрити. У release
        // гілка недосяжна — BuildConfig.DEBUG там константно false.
        if (ua.nichnyk.listen.BuildConfig.DEBUG) {
            Spacer(Modifier.height(Spacing.l))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Debug: Pro увімкнено", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Обходить Play Billing. Лише для перевірки платних екранів.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.isPro, onCheckedChange = vm::debugPro)
            }
        }

        if (confirmRestore) {
            AlertDialog(
                onDismissRequest = { confirmRestore = false },
                title = { Text(stringResource(R.string.restore_confirm_title)) },
                text = { Text(stringResource(R.string.restore_confirm_msg)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRestore = false
                        runCatching {
                            restorePicker.launch(arrayOf("application/json", "text/*", "*/*"))
                        }
                    }) { Text(stringResource(R.string.btn_restore)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }

    }
    }
}

/**
 * Ряд варіантів для одного жесту гарнітури.
 *
 * Підписи «вперед» і «назад» беруть крок із налаштувань перемотування —
 * ті самі рядки, що й у віджеті. Зашиті «30 с» і «15 с» тут були б четвертою
 * копією числа, яке v1.4.0 звело до однієї.
 */
@Composable
private fun HeadsetActionPicker(
    title: String,
    selected: HeadsetAction,
    skipForwardMs: Int,
    skipBackMs: Int,
    onSelect: (HeadsetAction) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(Spacing.xs))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        HeadsetAction.entries.forEach { action ->
            val label = when (action) {
                HeadsetAction.NONE -> stringResource(R.string.headset_action_none)
                HeadsetAction.PLAY_PAUSE -> stringResource(R.string.headset_action_play_pause)
                HeadsetAction.FORWARD -> stringResource(R.string.forward_sec, skipForwardMs / 1000)
                HeadsetAction.REWIND -> stringResource(R.string.rewind_sec, skipBackMs / 1000)
                HeadsetAction.NEXT_CHAPTER -> stringResource(R.string.headset_action_next_chapter)
                HeadsetAction.PREVIOUS_CHAPTER -> stringResource(R.string.headset_action_prev_chapter)
                HeadsetAction.BOOKMARK -> stringResource(R.string.headset_action_bookmark)
            }
            FilterChip(
                selected = selected == action,
                onClick = { onSelect(action) },
                label = { Text(label) },
            )
        }
    }
}
