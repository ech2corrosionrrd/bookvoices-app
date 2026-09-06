package ua.nichnyk.listen.data

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import ua.nichnyk.listen.BuildConfig
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("nichnyk_prefs")

enum class ShelfViewMode {
    GRID,
    LIST,
}

enum class AppTheme {
    NIGHT,
    PAPER,
    OLED,
}

/**
 * Пресет голосового еквалайзера.
 *
 * Живе тут, а не в playback, бо це збережене налаштування — як [AppTheme] чи
 * [HeadsetAction]. Мапінг пресета у смуги — `playback.voiceBandGains`.
 *
 * [SPEECH_CLARITY] замінив окремий тумблер «чіткість мовлення»: обидва керували
 * тим самим апаратним Equalizer на тій самій аудіосесії й глушили один одного.
 */
enum class VoicePreset {
    OFF,
    SPEECH_CLARITY,
    WARM,
    TREBLE_CUT,
}

/**
 * Дія на подвійний і потрійний тап кнопки гарнітури.
 *
 * [NONE] — не просто «порожній вибір», а стан за замовчуванням: доки обидві дії
 * стоять у ньому, PlaybackService взагалі не перехоплює медіакнопку, і пауза
 * спрацьовує миттєво. Розпізнавання подвійного тапу неминуче додає паузі
 * затримку в третину секунди, тож це має вмикати той, кому воно потрібне.
 *
 * FORWARD і REWIND без чисел у назві навмисно: крок береться з налаштувань
 * перемотування, а не зашитий у дію.
 */
enum class HeadsetAction {
    NONE,
    PLAY_PAUSE,
    FORWARD,
    REWIND,
    NEXT_CHAPTER,
    PREVIOUS_CHAPTER,
    BOOKMARK,
}

data class UserSettings(
    val skipBackMs: Int = 15_000,
    val skipForwardMs: Int = 30_000,
    val paperTheme: Boolean = false,
    val themeMode: AppTheme = AppTheme.NIGHT,
    val fadeOnSleep: Boolean = true,
    val defaultSpeed: Float = 1f,
    val skipSilence: Boolean = false,
    val voicePreset: VoicePreset = VoicePreset.OFF,
    /** Висота тону, 0.8..1.2. Швидкість окремо: вона ще й пише в книгу. */
    val pitch: Float = 1f,
    val monoAudio: Boolean = false,
    val volumeBoost: Boolean = false,
    val smartRewind: Boolean = true,
    val sortOrder: BookSortOrder = BookSortOrder.LastPlayed,
    val shelfViewMode: ShelfViewMode = ShelfViewMode.GRID,
    val doubleTapSeek: Boolean = true,
    val shakeToExtendSleep: Boolean = true,
    val showStatsOnShelf: Boolean = true,
    val autoBookmarkBluetooth: Boolean = true,
    val headsetDoubleTapAction: HeadsetAction = HeadsetAction.NONE,
    val headsetTripleTapAction: HeadsetAction = HeadsetAction.NONE,
    val language: String = "",
    val webDavServer: String = "",
    val webDavUser: String = "",
    val webDavAutoSync: Boolean = false,
    val webDavLastSyncTime: Long = 0L,
    /** Остання фонова синхронізація завершилася помилкою автентифікації. */
    val webDavAuthFailed: Boolean = false,
    /** false — пароль WebDAV не вдалося зашифрувати (немає доступу до Keystore). */
    val secretsEncrypted: Boolean = true,
    /**
     * Чи активна ліцензія Pro.
     *
     * Тут, а не лише в UI: значення поля вище вже **замасковані** цим прапорцем,
     * тож PlaybackService і PlayerManager дістають межу Freemium автоматично й
     * не мусять знати про біллінг. Один тумблер у UI, який «забули» перевірити,
     * інакше відкривав би платну фічу повністю.
     */
    val isPro: Boolean = false,
    /**
     * Головні папки бібліотеки (SAF tree URI), у яких шукаємо **нові книги**.
     * Порожній список — авто-скан полиці вимкнено, доки користувач не вкаже папку.
     */
    val libraryRootUris: List<String> = emptyList(),
)

/**
 * Пароля WebDAV у [UserSettings] свідомо немає: він лежить у [SecretStore] і читається
 * лише там, де реально потрібен (екран налаштувань, воркер синхронізації).
 * Раніше кожна емісія налаштувань розшифровувала його в контексті збирача,
 * тобто зазвичай на головному потоці.
 */
class UserPrefs(context: Context, private val secrets: SecretStore) {
    private val appContext = context.applicationContext
    private val store = appContext.dataStore

    /**
     * Дзеркало теми у звичайних SharedPreferences: DataStore читається асинхронно,
     * а windowBackground треба обрати синхронно в Activity.onCreate до першого кадру.
     */
    private val mirror = appContext.getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE)

    private val secretsEncrypted = MutableStateFlow(true)

    /**
     * Локальна відповідь на «чи є Pro». Засівається з [SecretStore] на старті
     * (без звертання до Play), а далі перезаписується справжнім станом покупки,
     * коли SettingsViewModel підключає біллінг.
     */
    private val proEntitled = MutableStateFlow(false)

    fun setProEntitled(value: Boolean) {
        proEntitled.value = value
    }

    val settings: Flow<UserSettings> = combine(store.data, secretsEncrypted, proEntitled) { p, encrypted, entitled ->
        // Єдина точка, де вирішується межа Freemium: нижче `pro` читають і UI, і
        // PlaybackService. Обхід живе лише в debug — у release `BuildConfig.DEBUG`
        // константно false, тож R8 викидає і перевірку, і читання ключа.
        val pro = entitled || (BuildConfig.DEBUG && p[DEBUG_PRO] == true)
        val sortName = p[SORT_ORDER] ?: BookSortOrder.LastPlayed.name
        val sort = runCatching { BookSortOrder.valueOf(sortName) }.getOrDefault(BookSortOrder.LastPlayed)
        val viewModeName = p[SHELF_VIEW_MODE] ?: ShelfViewMode.GRID.name
        val viewMode = runCatching { ShelfViewMode.valueOf(viewModeName) }.getOrDefault(ShelfViewMode.GRID)
        val doubleTapName = p[HEADSET_DOUBLE_TAP] ?: HeadsetAction.NONE.name
        val doubleTapAct = runCatching { HeadsetAction.valueOf(doubleTapName) }.getOrDefault(HeadsetAction.NONE)
        val tripleTapName = p[HEADSET_TRIPLE_TAP] ?: HeadsetAction.NONE.name
        val tripleTapAct = runCatching { HeadsetAction.valueOf(tripleTapName) }.getOrDefault(HeadsetAction.NONE)

        // Пресета ще немає, але старий тумблер «чіткість мовлення» стояв увімкнений —
        // переносимо вибір, а не скидаємо його в OFF.
        val presetName = p[VOICE_PRESET]
        val preset = when {
            presetName != null -> runCatching { VoicePreset.valueOf(presetName) }.getOrDefault(VoicePreset.OFF)
            p[SPEECH_CLARITY] == true -> VoicePreset.SPEECH_CLARITY
            else -> VoicePreset.OFF
        }

        val themeName = p[THEME_MODE]
        val theme = when {
            themeName != null -> runCatching { AppTheme.valueOf(themeName) }.getOrDefault(AppTheme.NIGHT)
            p[PAPER] == true -> AppTheme.PAPER
            else -> AppTheme.NIGHT
        }

        UserSettings(
            skipBackMs = p[SKIP_BACK] ?: 15_000,
            skipForwardMs = p[SKIP_FORWARD] ?: 30_000,
            paperTheme = (theme == AppTheme.PAPER),
            themeMode = theme,
            fadeOnSleep = p[FADE] ?: true,
            defaultSpeed = p[SPEED] ?: 1f,
            // Замасковані Pro-значення: без ліцензії віддаємо дефолт, а не
            // збережений вибір. Так фіча гасне і в сервісі відтворення, а не лише
            // в інтерфейсі, — і не оживає сама, якщо ліцензію відкликали.
            skipSilence = pro && (p[SKIP_SILENCE] ?: false),
            voicePreset = if (pro) preset else VoicePreset.OFF,
            pitch = if (pro) (p[PITCH] ?: 1f).coerceIn(0.8f, 1.2f) else 1f,
            monoAudio = p[MONO_AUDIO] ?: false,
            volumeBoost = pro && (p[VOLUME_BOOST] ?: false),
            smartRewind = p[SMART_REWIND] ?: true,
            sortOrder = sort,
            shelfViewMode = viewMode,
            doubleTapSeek = p[DOUBLE_TAP] ?: true,
            shakeToExtendSleep = p[SHAKE_SLEEP] ?: true,
            showStatsOnShelf = p[SHOW_STATS] ?: true,
            autoBookmarkBluetooth = pro && (p[AUTO_BOOKMARK_BT] ?: true),
            headsetDoubleTapAction = doubleTapAct,
            headsetTripleTapAction = tripleTapAct,
            language = p[LANGUAGE] ?: "",
            webDavServer = p[WEBDAV_SERVER] ?: "",
            webDavUser = p[WEBDAV_USER] ?: "",
            webDavAutoSync = p[WEBDAV_AUTO] ?: false,
            webDavLastSyncTime = p[WEBDAV_LAST_SYNC] ?: 0L,
            webDavAuthFailed = p[WEBDAV_AUTH_FAILED] ?: false,
            secretsEncrypted = encrypted,
            isPro = pro,
            libraryRootUris = (p[LIBRARY_ROOTS] ?: emptySet()).sorted(),
        )
    }

    /** Читається синхронно в MainActivity до super.onCreate(), щоб обрати колір вікна. */
    fun themeModeBlocking(): AppTheme {
        val name = mirror.getString(MIRROR_THEME, null)
        return when {
            name != null -> runCatching { AppTheme.valueOf(name) }.getOrDefault(AppTheme.NIGHT)
            mirror.getBoolean(MIRROR_PAPER, false) -> AppTheme.PAPER
            else -> AppTheme.NIGHT
        }
    }

    suspend fun setSkipBack(ms: Int) { store.edit { it[SKIP_BACK] = ms } }
    suspend fun setSkipForward(ms: Int) { store.edit { it[SKIP_FORWARD] = ms } }
    suspend fun setPaperTheme(value: Boolean) {
        val mode = if (value) AppTheme.PAPER else AppTheme.NIGHT
        setThemeMode(mode)
    }
    suspend fun setThemeMode(mode: AppTheme) {
        mirror.edit {
            putString(MIRROR_THEME, mode.name)
            putBoolean(MIRROR_PAPER, mode == AppTheme.PAPER)
        }
        store.edit {
            it[THEME_MODE] = mode.name
            it[PAPER] = (mode == AppTheme.PAPER)
        }
    }
    suspend fun setFadeOnSleep(value: Boolean) { store.edit { it[FADE] = value } }
    suspend fun setDefaultSpeed(value: Float) { store.edit { it[SPEED] = value } }
    suspend fun setSkipSilence(value: Boolean) { store.edit { it[SKIP_SILENCE] = value } }
    suspend fun setVoicePreset(preset: VoicePreset) {
        store.edit {
            it[VOICE_PRESET] = preset.name
            // Легасі-ключ більше нічого не означає; лишати його — значить лишити
            // друге джерело правди, яке перекриє пресет після наступного апдейту.
            it.remove(SPEECH_CLARITY)
        }
    }
    suspend fun setPitch(value: Float) { store.edit { it[PITCH] = value.coerceIn(0.8f, 1.2f) } }
    suspend fun setMonoAudio(value: Boolean) { store.edit { it[MONO_AUDIO] = value } }
    suspend fun setVolumeBoost(value: Boolean) { store.edit { it[VOLUME_BOOST] = value } }
    suspend fun setSmartRewind(value: Boolean) { store.edit { it[SMART_REWIND] = value } }
    suspend fun setDebugPro(value: Boolean) { store.edit { it[DEBUG_PRO] = value } }
    suspend fun setSortOrder(order: BookSortOrder) { store.edit { it[SORT_ORDER] = order.name } }
    suspend fun setShelfViewMode(mode: ShelfViewMode) { store.edit { it[SHELF_VIEW_MODE] = mode.name } }
    suspend fun setDoubleTapSeek(value: Boolean) { store.edit { it[DOUBLE_TAP] = value } }
    suspend fun setShakeToExtendSleep(value: Boolean) { store.edit { it[SHAKE_SLEEP] = value } }
    suspend fun setShowStatsOnShelf(value: Boolean) { store.edit { it[SHOW_STATS] = value } }
    suspend fun setAutoBookmarkBluetooth(value: Boolean) { store.edit { it[AUTO_BOOKMARK_BT] = value } }

    /**
     * Додати головну папку бібліотеки. Повертає false, якщо вже є або досягнуто
     * [MAX_LIBRARY_ROOTS].
     */
    suspend fun addLibraryRoot(uri: String): Boolean {
        val clean = uri.trim()
        if (clean.isEmpty()) return false
        var added = false
        store.edit { prefs ->
            val current = prefs[LIBRARY_ROOTS] ?: emptySet()
            if (clean in current || current.size >= MAX_LIBRARY_ROOTS) return@edit
            prefs[LIBRARY_ROOTS] = current + clean
            added = true
        }
        return added
    }

    suspend fun removeLibraryRoot(uri: String) {
        store.edit { prefs ->
            val current = prefs[LIBRARY_ROOTS] ?: emptySet()
            prefs[LIBRARY_ROOTS] = current - uri
        }
    }

    suspend fun setHeadsetDoubleTapAction(action: HeadsetAction) { store.edit { it[HEADSET_DOUBLE_TAP] = action.name } }
    suspend fun setHeadsetTripleTapAction(action: HeadsetAction) { store.edit { it[HEADSET_TRIPLE_TAP] = action.name } }
    suspend fun setLanguage(lang: String) { store.edit { it[LANGUAGE] = lang } }

    /** Адреса й логін. Пароль зберігається окремо — див. [setWebDavPassword]. */
    suspend fun setWebDavConfig(server: String, user: String, autoSync: Boolean) {
        store.edit {
            it[WEBDAV_SERVER] = server.trim()
            it[WEBDAV_USER] = user.trim()
            it[WEBDAV_AUTO] = autoSync
            it.remove(WEBDAV_PASS)
        }
    }

    /** Порожній рядок стирає збережений пароль. */
    suspend fun setWebDavPassword(pass: String) {
        if (pass.isEmpty()) secrets.clearWebDavPassword() else secrets.setWebDavPassword(pass)
        store.edit { it.remove(WEBDAV_PASS) }
    }

    suspend fun getWebDavPassword(): String = secrets.getWebDavPassword()

    suspend fun setWebDavLastSyncTime(timeMs: Long) {
        store.edit {
            it[WEBDAV_LAST_SYNC] = timeMs
            it[WEBDAV_AUTH_FAILED] = false
        }
    }

    suspend fun setWebDavAuthFailed(failed: Boolean) {
        store.edit { it[WEBDAV_AUTH_FAILED] = failed }
    }

    suspend fun setLastBookId(id: String?) {
        store.edit {
            if (id == null) it.remove(LAST_BOOK) else it[LAST_BOOK] = id
        }
    }

    val lastBookId: Flow<String?> = store.data.map { it[LAST_BOOK] }

    /**
     * Чи вже показували системний запит на сповіщення.
     *
     * Без цієї позначки запит вискакував би при кожному поверненні до застосунку
     * з увімкненим відтворенням: збирач у MainActivity перезапускається на
     * кожному STARTED, а `isPlaying` тоді ж і віддає своє поточне `true`.
     */
    suspend fun notificationPermissionAsked(): Boolean =
        store.data.first()[NOTIFY_ASKED] ?: false

    suspend fun setNotificationPermissionAsked() {
        store.edit { it[NOTIFY_ASKED] = true }
    }

    /**
     * Виконується один раз при старті: прогріває Keystore поза головним потоком
     * і переносить пароль, який до версії 1.0.1 лежав у DataStore відкритим текстом.
     */
    suspend fun initSecrets() {
        secretsEncrypted.value = secrets.warmUp()
        // Кеш ліцензії, а не запит до Play: сервіс відтворення може підняти процес
        // без жодного екрана, і межа Freemium має діяти вже там.
        proEntitled.value = secrets.isProCached()
        store.edit { prefs ->
            val leftover = prefs[WEBDAV_PASS]
            if (!leftover.isNullOrBlank() && secrets.getWebDavPassword().isBlank()) {
                secrets.setWebDavPassword(leftover)
            }
            prefs.remove(WEBDAV_PASS)
        }
    }

    companion object {
        const val MAX_LIBRARY_ROOTS = 10

        private const val MIRROR_FILE = "nichnyk_prefs_mirror"
        private const val MIRROR_PAPER = "paper"
        private const val MIRROR_THEME = "theme_mode"

        private val SKIP_BACK = intPreferencesKey("skip_back")
        private val SKIP_FORWARD = intPreferencesKey("skip_forward")
        private val PAPER = booleanPreferencesKey("paper")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val FADE = booleanPreferencesKey("fade")
        private val SPEED = floatPreferencesKey("speed")
        private val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        /** Прибраний тумблер; читається лише щоб перенести вибір у VOICE_PRESET. */
        private val SPEECH_CLARITY = booleanPreferencesKey("speech_clarity")
        private val VOICE_PRESET = stringPreferencesKey("voice_preset")
        private val PITCH = floatPreferencesKey("voice_pitch")
        private val MONO_AUDIO = booleanPreferencesKey("mono_audio")
        private val VOLUME_BOOST = booleanPreferencesKey("volume_boost")
        private val SMART_REWIND = booleanPreferencesKey("smart_rewind")

        /** Лише для debug-збірки: вмикає Pro без Play. У release не читається. */
        private val DEBUG_PRO = booleanPreferencesKey("debug_pro")
        private val SORT_ORDER = stringPreferencesKey("sort_order")
        private val SHELF_VIEW_MODE = stringPreferencesKey("shelf_view_mode")
        private val DOUBLE_TAP = booleanPreferencesKey("double_tap")
        private val SHAKE_SLEEP = booleanPreferencesKey("shake_sleep")
        private val SHOW_STATS = booleanPreferencesKey("show_stats")
        private val AUTO_BOOKMARK_BT = booleanPreferencesKey("auto_bookmark_bt")
        private val HEADSET_DOUBLE_TAP = stringPreferencesKey("headset_double_tap")
        private val HEADSET_TRIPLE_TAP = stringPreferencesKey("headset_triple_tap")
        private val LANGUAGE = stringPreferencesKey("language")
        private val LAST_BOOK = stringPreferencesKey("last_book")
        private val NOTIFY_ASKED = booleanPreferencesKey("notify_permission_asked")
        private val WEBDAV_SERVER = stringPreferencesKey("webdav_server")
        private val WEBDAV_USER = stringPreferencesKey("webdav_user")
        private val WEBDAV_PASS = stringPreferencesKey("webdav_pass")
        private val WEBDAV_AUTO = booleanPreferencesKey("webdav_auto")
        private val WEBDAV_LAST_SYNC = longPreferencesKey("webdav_last_sync")
        private val WEBDAV_AUTH_FAILED = booleanPreferencesKey("webdav_auth_failed")
        private val LIBRARY_ROOTS = stringSetPreferencesKey("library_root_uris")
    }
}

/** Результат додавання головної папки бібліотеки в налаштуваннях. */
enum class AddLibraryRootResult {
    ADDED,
    DUPLICATE,
    LIMIT,
    PERMISSION_FAILED,
}
