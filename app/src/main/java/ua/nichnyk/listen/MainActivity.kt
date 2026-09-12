package ua.nichnyk.listen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.nichnyk.listen.ui.components.MiniPlayerBar
import ua.nichnyk.listen.ui.screens.BookDetailViewModel
import ua.nichnyk.listen.ui.screens.BookScreen
import ua.nichnyk.listen.ui.screens.BookmarksScreen
import ua.nichnyk.listen.ui.screens.BookmarksViewModel
import ua.nichnyk.listen.ui.screens.LibraryScreen
import ua.nichnyk.listen.ui.screens.LibraryViewModel
import ua.nichnyk.listen.ui.screens.PlayerScreen
import ua.nichnyk.listen.ui.screens.PlayerViewModel
import ua.nichnyk.listen.ui.screens.SettingsScreen
import ua.nichnyk.listen.ui.screens.SettingsViewModel
import ua.nichnyk.listen.ui.theme.ListenTheme

/**
 * AppCompatActivity, а не ComponentActivity: на API < 33 перемикач мови працює лише
 * через AppCompatDelegate, а той застосовує локаль тільки до AppCompat-екранів
 * (разом із AppLocalesMetadataHolderService у маніфесті).
 */
class MainActivity : AppCompatActivity() {
    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as ListenApp).container
        // Колір вікна вибираємо до першого кадру, інакше «паперова» тема
        // стартує з темного блимання, а OLED — з посвітлілого.
        when (container.prefs.themeModeBlocking()) {
            ua.nichnyk.listen.data.AppTheme.PAPER -> setTheme(R.style.Theme_BookVoices_Paper)
            ua.nichnyk.listen.data.AppTheme.OLED -> setTheme(R.style.Theme_BookVoices_Oled)
            ua.nichnyk.listen.data.AppTheme.NIGHT -> Unit
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermissionOnFirstPlayback(container)
        syncEntitlementWithPlay(container)
        if (savedInstanceState == null) {
            handleIncoming(intent, container)
        }
        setContent {
            val settings by container.prefs.settings.collectAsStateWithLifecycle(
                initialValue = ua.nichnyk.listen.data.UserSettings(),
            )
            ListenTheme(theme = settings.themeMode) {
                ListenAppRoot(container)
            }
        }
    }

    /**
     * Дозвіл на сповіщення просимо тоді, коли він уперше знадобився, — на першому
     * запуску відтворення.
     *
     * Доти запит вискакував першим кадром після встановлення: пояснити, навіщо він,
     * там нема де, а відмова означає медіазастосунок без керування зі шторки та
     * екрана блокування — і виправити це вже нічим, бо вдруге система діалог не
     * показує.
     */
    /**
     * Звірка покупки з Play — один раз на запуск інтерфейсу.
     *
     * ListenApp підписується на межу Freemium лише тим, у кого ліцензія вже
     * закешована: процес, піднятий системою під Android Auto чи шторку, у Play
     * стукати не має. Але після перевстановлення кеш порожній, і власник
     * покупки бачив пейволл, доки сам не відкриє «Налаштування», — бо саме там
     * уперше створювався клієнт Play.
     *
     * Тут інша умова: є екран, тобто є людина. Клієнт піднімається з тієї ж
     * `by lazy`, тож для headless-процесу нічого не змінюється.
     */
    private fun syncEntitlementWithPlay(container: AppContainer) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.billing.resolvedIsPro.collect { container.prefs.setProEntitled(it) }
            }
        }
    }

    private fun askNotificationPermissionOnFirstPlayback(container: AppContainer) {
        if (Build.VERSION.SDK_INT < 33) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Позначка в налаштуваннях, а не поле активності: збирач нижче
                // піднімається на кожному STARTED, а `isPlaying` одразу віддає
                // своє поточне значення — без неї запит вискакував би при
                // кожному поверненні до застосунку, поки грає книга.
                if (container.prefs.notificationPermissionAsked()) return@repeatOnLifecycle
                container.player.state
                    .map { it.isPlaying }
                    .distinctUntilChanged()
                    .first { it }
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    container.prefs.setNotificationPermissionAsked()
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent, (application as ListenApp).container)
    }

    private fun handleIncoming(intent: Intent?, container: AppContainer) {
        val uris = collectIncomingUris(intent)
        if (uris.isNotEmpty()) container.offerImport(uris)
    }

    private fun collectIncomingUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
            Intent.ACTION_SEND -> {
                extraStream(intent)?.let { uris += it }
                intent.data?.let { uris += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> uris += extraStreamList(intent)
        }
        return uris.distinct()
    }

    private fun extraStream(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extraStreamList(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return list.orEmpty()
    }
}

private object Dest {
    const val Library = "library"
    const val Bookmarks = "bookmarks"
    const val Settings = "settings"
    const val Player = "player"
    const val History = "history"
    const val Book = "book/{id}"
    fun book(id: String) = "book/$id"
    const val Author = "author/{name}"
    fun author(name: String) = "author/${Uri.encode(name)}"
    const val Series = "series/{name}"
    fun series(name: String) = "series/${Uri.encode(name)}"
}

/**
 * testTag стає resource-id для UiAutomator лише після цього прапорця.
 * Потрібен генератору Baseline Profile: підписи вкладок локалізовані, і шукати
 * їх за текстом означало б мовчки нічого не знайти на іншій мові.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ListenAppRoot(container: AppContainer) {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    val app = LocalContext.current.applicationContext as android.app.Application
    val libraryVm: LibraryViewModel = viewModel(factory = container.vmFactory)
    val playerVm: PlayerViewModel = viewModel(factory = container.vmFactory)
    val settingsVm: SettingsViewModel = viewModel(factory = container.vmFactory)
    val bookmarksVm: BookmarksViewModel = viewModel(factory = container.vmFactory)
    // stable, а не state: повний стан несе позицію й міняється 2,5 рази на секунду.
    // Поки він читався тут, кожен тик відтворення інвалідував корінь навігації —
    // тобто Scaffold, нижню панель і NavHost цілком, на всіх екранах застосунку.
    val playerState by playerVm.stable.collectAsStateWithLifecycle()
    // collectAsStateWithLifecycle() саме по собі значення не читає — повертає State. Читання
    // лишається в лямбді нижче, всередині смужки міні-плеєра.
    val playerTick = playerVm.tick.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val hideChrome = route == Dest.Player
    val scope = rememberCoroutineScope()

    // Paywall піднято на рівень хоста: до нього ведуть заблоковані елементи на
    // екранах книги, плеєра й налаштувань. Друга копія на екрані налаштувань
    // дублювала і шторку, і обробник відновлення покупки — лишилася ця одна.
    var showPaywall by rememberSaveable { mutableStateOf(false) }
    val paywallIsPro by settingsVm.isPro.collectAsStateWithLifecycle()
    val paywallPrice by settingsVm.formattedPrice.collectAsStateWithLifecycle()
    if (showPaywall) {
        ua.nichnyk.listen.ui.components.PaywallBottomSheet(
            isPro = paywallIsPro,
            formattedPrice = paywallPrice,
            onBuy = { activity ->
                settingsVm.buyPro(activity)
                showPaywall = false
            },
            onRestore = {
                settingsVm.restorePurchases { restored ->
                    scope.launch {
                        snack.showSnackbar(
                            if (restored) app.forAppLocale().getString(R.string.pro_restore_success)
                            else app.forAppLocale().getString(R.string.pro_restore_not_found)
                        )
                    }
                }
            },
            onDismiss = { showPaywall = false },
        )
    }

    // Помилки відтворення (переміщений/видалений файл) з усіх екранів — в один снекбар.
    LaunchedEffect(playerVm, bookmarksVm) {
        launch { playerVm.messages.collect { snack.showSnackbar(it) } }
        launch { bookmarksVm.messages.collect { snack.showSnackbar(it) } }
    }

    // Тут, а не на екрані налаштувань: `billing.events` — канал з одним читачем,
    // тож поки збирач жив у тілі SettingsScreen, покупка з paywall'у, відкритого
    // з екрана книги, лежала в буфері й вистрілювала снекбаром «Покупку завершено»
    // наступного разу, коли користувач зайде в налаштування.
    val purchaseSuccessMsg = stringResource(R.string.pro_purchase_success)
    val purchaseUnavailableMsg = stringResource(R.string.pro_purchase_unavailable)
    val purchaseFailedMsg = stringResource(R.string.pro_purchase_failed)
    LaunchedEffect(settingsVm) {
        settingsVm.billingEvents.collect { event ->
            val message = when (event) {
                ua.nichnyk.listen.billing.BillingEvent.PurchaseSuccess -> purchaseSuccessMsg
                ua.nichnyk.listen.billing.BillingEvent.Unavailable -> purchaseUnavailableMsg
                is ua.nichnyk.listen.billing.BillingEvent.Error -> purchaseFailedMsg
                // Користувач сам закрив вікно оплати — повідомляти нема про що.
                ua.nichnyk.listen.billing.BillingEvent.UserCanceled -> null
            }
            if (message != null) snack.showSnackbar(message)
        }
    }

    fun openPlayer() {
        nav.navigate(Dest.Player) {
            launchSingleTop = true
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!hideChrome) {
                Column {
                    MiniPlayerBar(
                        state = playerState,
                        progress = { playerTick.value.bookProgress },
                        onExpand = { openPlayer() },
                        onPlayPause = playerVm::playPause,
                        visible = true,
                    )
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        data class Tab(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)
                        // Мітка = маршрут: стабільна, не залежить від мови інтерфейсу.
                        val tabs = listOf(
                            Tab(Dest.Library, R.string.tab_shelf, Icons.AutoMirrored.Outlined.MenuBook),
                            Tab(Dest.Bookmarks, R.string.tab_bookmarks, Icons.Outlined.BookmarkBorder),
                            Tab(Dest.Settings, R.string.tab_settings, Icons.Outlined.Settings),
                        )
                        tabs.forEach { tab ->
                            val title = stringResource(tab.labelRes)
                            NavigationBarItem(
                                modifier = Modifier.testTag("tab_${tab.route}"),
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { androidx.compose.material3.Icon(tab.icon, contentDescription = title) },
                                label = { Text(title) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->

        NavHost(
            navController = nav,
            startDestination = Dest.Library,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Library) {
                LibraryScreen(
                    vm = libraryVm,
                    onOpenBook = { nav.navigate(Dest.book(it)) },
                    onPlayNow = { openPlayer() },
                    snack = { msg -> scope.launch { snack.showSnackbar(msg) } },
                    onOpenHistory = { nav.navigate(Dest.History) },
                    onOpenSeries = { nav.navigate(Dest.series(it)) },
                )
            }
            composable(Dest.Bookmarks) {
                BookmarksScreen(bookmarksVm, onPlayed = { openPlayer() })
            }
            composable(Dest.Settings) {
                SettingsScreen(
                    settingsVm,
                    onOpenHistory = { nav.navigate(Dest.History) },
                    onRequestPro = { showPaywall = true },
                )
            }
            composable(Dest.Player) {
                PlayerScreen(
                    playerVm,
                    onBack = { nav.popBackStack() },
                    onRequestPro = { showPaywall = true },
                )
            }
            composable(Dest.History) {
                val historyVm: ua.nichnyk.listen.ui.screens.HistoryViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            ua.nichnyk.listen.ui.screens.HistoryViewModel(container.repo, container.player) as T
                    },
                )
                ua.nichnyk.listen.ui.screens.HistoryScreen(
                    vm = historyVm,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Dest.book(it)) },
                )
            }
            composable(Dest.Book, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val bookVm: BookDetailViewModel = viewModel(
                    key = id,
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            BookDetailViewModel(app, container.repo, container.player, container.prefs, id) as T
                    },
                )
                LaunchedEffect(bookVm) {
                    bookVm.messages.collect { snack.showSnackbar(it) }
                }
                BookScreen(
                    vm = bookVm,
                    onRequestPro = { showPaywall = true },
                    onBack = { nav.popBackStack() },
                    onOpenPlayer = { openPlayer() },
                    onOpenAuthor = { authorName -> nav.navigate(Dest.author(authorName)) },
                    onOpenSeries = { seriesName -> nav.navigate(Dest.series(seriesName)) },
                )
            }
            composable(Dest.Author, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
                val rawName = entry.arguments?.getString("name") ?: return@composable
                val authorName = Uri.decode(rawName)
                ua.nichnyk.listen.ui.screens.AuthorScreen(
                    authorName = authorName,
                    libraryVm = libraryVm,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Dest.book(it)) },
                )
            }
            composable(Dest.Series, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
                val rawName = entry.arguments?.getString("name") ?: return@composable
                val seriesName = Uri.decode(rawName)
                ua.nichnyk.listen.ui.screens.SeriesScreen(
                    seriesName = seriesName,
                    libraryVm = libraryVm,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Dest.book(it)) },
                )
            }
        }
    }
}
