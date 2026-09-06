package ua.nichnyk.listen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import ua.nichnyk.listen.data.AudioImporter
import ua.nichnyk.listen.data.CoverGenerator
import ua.nichnyk.listen.data.DemoFactory
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.ListenDatabase
import ua.nichnyk.listen.data.SecretStore
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.data.WebDavSyncScheduler
import ua.nichnyk.listen.playback.PlayerManager
import ua.nichnyk.listen.ui.screens.BookmarksViewModel
import ua.nichnyk.listen.ui.screens.LibraryViewModel
import ua.nichnyk.listen.ui.screens.PlayerViewModel
import ua.nichnyk.listen.ui.components.CoverCache
import ua.nichnyk.listen.ui.screens.SettingsViewModel

class AppContainer(private val app: Application) {
    val db = ListenDatabase.get(app)
    // Один SecretStore на процес: ініціалізація EncryptedSharedPreferences зачіпає
    // Keystore, і робити її двічі (окремо в UserPrefs) не було сенсу.
    private val secrets = SecretStore(app)
    val prefs = UserPrefs(app, secrets)
    private val covers = CoverGenerator(app)
    val repo = LibraryRepository(
        context = app,
        db = db,
        covers = covers,
        importer = AudioImporter(app),
        demo = DemoFactory(app),
    )
    val player = PlayerManager(app, repo, prefs)

    init {
        repo.onBookDeleted = player::clearIfCurrent
        repo.onLibraryReset = player::resetPlayback
        repo.onBookFilesChanged = player::clearIfCurrent
        repo.onQueueChangedExternally = player::reloadQueue
    }

    // Channel, а не SharedFlow(replay = 1): реплей означав, що при повторному створенні
    // LibraryViewModel той самий набір файлів імпортувався б удруге.
    private val pendingImports = Channel<List<Uri>>(Channel.BUFFERED)
    val importRequests: Flow<List<Uri>> = pendingImports.receiveAsFlow()

    fun offerImport(uris: List<Uri>) {
        val unique = uris.distinct()
        if (unique.isNotEmpty()) pendingImports.trySend(unique)
    }

    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * `by lazy`, а не eager: конструктор одразу біндиться до сервісу Play.
     * AppContainer створюється в Application.onCreate(), тобто й тоді, коли
     * процес підняла система під відтворення в Android Auto чи зі шторки, —
     * а там платити ніхто не збирається.
     */
    val billing: ua.nichnyk.listen.billing.ProEntitlementManager by lazy {
        ua.nichnyk.listen.billing.ProEntitlementManager(app, secrets, billingScope)
    }

    val vmFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return when {
                modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
                    LibraryViewModel(app, repo, player, prefs, importRequests) as T
                modelClass.isAssignableFrom(PlayerViewModel::class.java) ->
                    PlayerViewModel(player, repo, prefs) as T
                modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                    SettingsViewModel(prefs, repo, billing) as T
                modelClass.isAssignableFrom(BookmarksViewModel::class.java) ->
                    BookmarksViewModel(app, repo, player) as T
                else -> error("Unknown ViewModel ${modelClass.name}")
            }
        }
    }
}

class ListenApp : Application() {
    lateinit var container: AppContainer
        private set
    // Один незловлений виняток у стартовій корутині означав би, що застосунок
    // не відкривається взагалі: вона робить прогрів секретів, мову й планування
    // синхронізації ще до першого кадру.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + crashSafeHandler("ListenApp"),
    )

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.player.initialize()
        appScope.launch {
            container.prefs.initSecrets()
            // Раз на запуск, а не на кожен запис прослуховування: надгробки й сесії
            // старіють днями, а recordListening приходить кожні 30 секунд відтворення.
            runCatching { container.repo.pruneHistory() }
                .onFailure { AppLog.w("ListenApp: не вдалося прибрати стару історію", it) }
            val initial = container.prefs.settings.first()
            applyStoredLanguage(initial.language)
            // Межа Freemium перевіряється поза життям SettingsViewModel: доти
            // відкликану покупку помічав лише той, хто відкрив налаштування, а
            // хто не відкривав — лишався з кешем назавжди. Клієнт Play піднімаємо
            // тільки тим, у кого Pro справді закешований: процес, який система
            // підняла під Auto чи шторку в безкоштовного користувача, у Play так
            // і не стукає — саме заради цього `billing` лишається `by lazy`.
            if (initial.isPro) {
                appScope.launch {
                    container.billing.resolvedIsPro.collect { container.prefs.setProEntitled(it) }
                }
            }
            container.prefs.settings
                .map { it.webDavAutoSync }
                .distinctUntilChanged()
                .collect { enabled -> WebDavSyncScheduler.apply(this@ListenApp, enabled) }
        }
    }

    /**
     * Кеш обкладинок може тримати десятки мегабайтів. Саме тоді, коли система шукає,
     * кого вбити, застосунок зазвичай грає у фоні — і має вижити.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        CoverCache.trim(level)
    }

    /**
     * Мова, збережена в налаштуваннях, але ще не застосована.
     * Актуально після оновлення з версій, де перемикач на API < 33 не працював узагалі:
     * вибір лежав у DataStore, а система про нього не знала.
     */
    private suspend fun applyStoredLanguage(language: String) {
        if (language.isBlank()) return
        withContext(Dispatchers.Main) {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
            }
        }
    }
}
