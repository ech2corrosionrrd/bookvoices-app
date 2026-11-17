package ua.nichnyk.listen.ui.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.billing.ProEntitlementManager
import ua.nichnyk.listen.data.AddLibraryRootResult
import ua.nichnyk.listen.data.AppTheme
import ua.nichnyk.listen.data.FolderBackup
import ua.nichnyk.listen.data.FolderBackupScheduler
import ua.nichnyk.listen.data.HeadsetAction
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.ShelfViewMode
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.data.UserSettings
import ua.nichnyk.listen.data.VoicePreset
import ua.nichnyk.listen.data.WebDavClient
import ua.nichnyk.listen.launchSafely

class SettingsViewModel(
    private val prefs: UserPrefs,
    private val repo: LibraryRepository,
    val billing: ProEntitlementManager,
) : ViewModel() {
    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    /**
     * Межа Freemium, а не сирий стан біллінгу — те саме джерело, що в
     * [BookDetailViewModel] і на плеєрі.
     *
     * Раніше тут стояв `billing.isPro`, і екран розходився з рештою застосунку
     * двічі. У debug обхід Pro (`UserPrefs.DEBUG_PRO`) до Play не доходить за
     * визначенням, тож платні тумблери лишалися замкненими саме там, де їх
     * вмикають, хоча на плеєрі й екрані книги вже відкривалися. У release перша
     * емісія `billing.isPro` — це «поки не знаємо» у вигляді false, і власник Pro
     * бачив замкнені тумблери, доки Play не відповість; нижче в цьому ж класі
     * стоїть коментар, чому в prefs пишеться `resolvedIsPro`, — тут та сама
     * причина, але її не врахували.
     */
    val isPro = settings
        .map { it.isPro }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
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

    fun buyPro(activity: Activity) = billing.launchBillingFlow(activity)
    fun restorePurchases(onResult: (Boolean) -> Unit) = billing.restorePurchases(onResult)
    // Кожен перемикач — окремий запис у DataStore. Показати збій тут нема де,
    // тож єдиною реакцією на рідкісний IOException було б падіння на дотику
    // до тумблера. Тепер — рядок у logcat і незмінене значення.
    fun skipBack(ms: Int) = settingWrite("skipBack") { prefs.setSkipBack(ms) }
    fun skipForward(ms: Int) = settingWrite("skipForward") { prefs.setSkipForward(ms) }
    fun paper(value: Boolean) = settingWrite("paper") { prefs.setPaperTheme(value) }
    fun themeMode(mode: AppTheme) = settingWrite("themeMode") { prefs.setThemeMode(mode) }
    fun fade(value: Boolean) = settingWrite("fade") { prefs.setFadeOnSleep(value) }
    fun speed(value: Float) = settingWrite("speed") { prefs.setDefaultSpeed(value) }
    fun skipSilence(value: Boolean) = settingWrite("skipSilence") { prefs.setSkipSilence(value) }
    fun voicePreset(preset: VoicePreset) =
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
    fun resumeOnReconnect(value: Boolean) = settingWrite("resumeOnReconnect") { prefs.setResumeOnReconnect(value) }
    fun nightTimerEnabled(value: Boolean) = settingWrite("nightTimerEnabled") { prefs.setNightTimerEnabled(value) }
    fun nightTimerStart(minute: Int) = settingWrite("nightTimerStart") { prefs.setNightTimerStart(minute) }
    fun nightTimerEnd(minute: Int) = settingWrite("nightTimerEnd") { prefs.setNightTimerEnd(minute) }
    fun nightTimerMinutes(minutes: Int) = settingWrite("nightTimerMinutes") { prefs.setNightTimerMinutes(minutes) }
    fun shakeToExtendSleep(value: Boolean) = settingWrite("shakeSleep") { prefs.setShakeToExtendSleep(value) }
    fun showStatsOnShelf(value: Boolean) = settingWrite("showStats") { prefs.setShowStatsOnShelf(value) }
    fun historyOpened() = settingWrite("historyOpened") { prefs.setHistoryOpened() }

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
        repo.releaseLibraryRootPermission(
            uri = uri,
            keepForAutoBackup = uri == settings.value.autoBackupTreeUri,
        )
    }

    private fun settingWrite(name: String, block: suspend () -> Unit) {
        launchSafely("SettingsViewModel.$name") { block() }
    }

    fun setLanguage(lang: String) {
        launchSafely("SettingsViewModel.setLanguage") {
            // Спершу локаль, потім налаштування: сервіс відтворення перебудовує підписи
            // шторки, коли бачить нову мову в налаштуваннях, і на API 26–32 бере рядки через
            // forAppLocale() — тобто з AppCompat. Навпаки він встиг би зібрати їх старою мовою.
            if (lang.isBlank()) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
            }
            prefs.setLanguage(lang)
        }
    }

    suspend fun exportBackupJson(): String = repo.exportBackupJson()
    suspend fun importBackupJson(json: String): Boolean = repo.importBackupJson(json)
    suspend fun exportCatalogMarkdown(): String = repo.exportCatalogMarkdown()
    suspend fun exportHistoryCsv(): String = repo.exportHistoryCsv()

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

    /** Вибрали теку автокопії: дозвіл на запис, збереження й перша копія одразу. */
    fun setAutoBackupFolder(context: Context, uri: Uri, onResult: (Boolean) -> Unit) {
        launchSafely("SettingsViewModel.setAutoBackupFolder") {
            val previous = settings.value.autoBackupTreeUri
            val ok = FolderBackup.takePermission(context, uri)
            if (ok) {
                val next = uri.toString()
                prefs.setAutoBackupTree(next)
                if (previous.isNotBlank() && previous != next) {
                    repo.releaseAutoBackupPermission(previous, settings.value.libraryRootUris)
                }
                FolderBackupScheduler.runSoon(context)
            }
            onResult(ok)
        }
    }

    fun disableAutoBackup() {
        launchSafely("SettingsViewModel.disableAutoBackup") {
            val previous = settings.value.autoBackupTreeUri
            prefs.setAutoBackupTree("")
            if (previous.isNotBlank()) {
                repo.releaseAutoBackupPermission(previous, settings.value.libraryRootUris)
            }
        }
    }

    /** «Зберегти зараз»: той самий шлях, що й щоденний воркер. */
    suspend fun autoBackupNow(app: ListenApp): Boolean = FolderBackup.runOnce(app)

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
