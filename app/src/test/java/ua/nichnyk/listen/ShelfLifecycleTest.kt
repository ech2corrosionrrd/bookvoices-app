package ua.nichnyk.listen

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.nichnyk.listen.data.AudioImporter
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.CoverGenerator
import ua.nichnyk.listen.data.DemoFactory
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.ListenDatabase
import ua.nichnyk.listen.data.SecretStore
import ua.nichnyk.listen.data.UserPrefs
import ua.nichnyk.listen.playback.PlayerManager
import ua.nichnyk.listen.ui.screens.LibraryViewModel

/**
 * Полиця працює рівно тоді, коли її видно.
 *
 * Над `books` стоїть запит Room по всій бібліотеці, над ним — скан доступності
 * файлів через SAF, натуральне сортування regex-ом і скан головних папок (K1).
 * Поки все це лишається гарячим під час фонового відтворення, застосунок
 * годину поспіль робить роботу, результат якої нікому показати, — і це видно
 * лише на батареї, а не на збірці, не на тестах і не на рев'ю.
 *
 * Інваріант зривався двічі, обидва рази тихо. Спершу збирачі стояли просто в
 * `LibraryViewModel.init`, тобто підписувалися назавжди. Потім їх підняли на
 * прапорець `libraryActive`, але ставили його з `DisposableEffect(Unit)` —
 * а «Home» композицію з NavHost не знімає, тож у найтиповішому сценарії
 * аудіокниги (увімкнув книгу, згорнув, слухаю) прапорець лишався `true`.
 *
 * Тому перевірок дві, з різних боків:
 *
 * - [ShelfLifecycleTest] — сам прапорець: збирачі холодні, поки він `false`.
 * - [ShelfLifecycleWiringTest] — джерела екранів: прапорець приходить із
 *   lifecycle, а стан збирається через `collectAsStateWithLifecycle`.
 *
 * Друга без першої ловила б лише текст, перша без другої — лише ViewModel,
 * тобто рівно ту половину, яка обидва рази була справна.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
// Звичайний Application, а не ListenApp: справжній піднімає AppContainer і планує
// WebDAV-синхронізацію через WorkManager, якого в JVM-тесті ніхто не ініціалізував.
@Config(sdk = [33], application = android.app.Application::class)
class ShelfLifecycleTest {

    private val scheduler = TestCoroutineScheduler()

    private lateinit var app: Application
    private lateinit var db: ListenDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var prefs: UserPrefs
    private lateinit var player: PlayerManager
    private lateinit var vm: LibraryViewModel

    @Before
    fun setUp() {
        // Один планувальник на тест і на viewModelScope: інакше `advanceUntilIdle`
        // рухав би віртуальний час тесту, а збирачі ViewModel лишалися б у своєму.
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, ListenDatabase::class.java)
            .allowMainThreadQueries()
            // Прямі виконавці, а не типові: інакше Room розсилає інвалідацію зі
            // свого фонового потоку, і момент, коли `allTags` віддасть нове
            // значення, не має стосунку до віртуального часу тесту. Проба нижче
            // тримається саме на емісіях, тож без цього тест був би плавучим.
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = LibraryRepository(
            context = app,
            db = db,
            covers = CoverGenerator(app),
            importer = AudioImporter(app),
            demo = DemoFactory(app),
        )
        prefs = UserPrefs(app, SecretStore(app))
        // Конструктор PlayerManager нічого не піднімає — сервіс і MediaController
        // з'являються лише в initialize(), який тут не викликаємо.
        player = PlayerManager(app, repo, prefs)
        vm = LibraryViewModel(app, repo, player, prefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        File(app.filesDir, "covers").deleteRecursively()
    }

    /**
     * Прапорець вмикає й вимикає збирачі — обидва напрямки, і повторно.
     *
     * Пробою служить `dropStaleFilters`: він живе під `libraryActive` і знімає
     * фільтр за міткою, якої немає серед `allTags`.
     *
     * Кожна перевірка тут стоїть на **емісії** `allTags`, тобто на видаленні
     * мітки з бази. Інакше вона нічого не значила б: `dropStaleFilters` звіряє
     * фільтр лише тоді, коли список міток віддав нове значення, і сам по собі
     * `setSelectedTag` його не будить — перевірка «мітка лишилася» проходила б і
     * з гарячим збирачем, і з холодним.
     *
     * Третій крок — не зайвий: обидва зриви інваріанта були саме про **вихід** зі
     * стану «видно», а не про вхід.
     */
    @Test
    fun shelfCollectorsRunOnlyWhileShelfIsActive() = runTest(scheduler) {
        val bookId = insertBookWithTags("фентезі", "детектив")

        vm.setLibraryActive(false)
        vm.setSelectedTag("фентезі")
        repo.removeTag(bookId, "фентезі")
        advanceUntilIdle()
        assertEquals(
            "полиця неактивна — прибирати застарілий фільтр нікому",
            "фентезі",
            vm.selectedTag.value,
        )

        vm.setLibraryActive(true)
        advanceUntilIdle()
        assertNull(
            "полиця активна — застарілий фільтр мав зникнути",
            vm.selectedTag.value,
        )

        vm.setLibraryActive(false)
        vm.setSelectedTag("детектив")
        repo.removeTag(bookId, "детектив")
        advanceUntilIdle()
        assertEquals(
            "полиця знову неактивна — збирач мав охолонути, а не лишитися гарячим",
            "детектив",
            vm.selectedTag.value,
        )
    }

    /** Книга з мітками — «паливо» для `allTags`, більше від неї нічого не треба. */
    private suspend fun insertBookWithTags(vararg tags: String): String {
        val id = "book-1"
        db.library().upsertBook(
            BookEntity(
                id = id,
                title = "Кобзар",
                author = "Шевченко",
                coverPath = null,
                addedAt = 0L,
                lastPlayedAt = null,
                durationMs = 1000L,
                positionMs = 0L,
                currentChapterIndex = 0,
                playbackSpeed = 1f,
                completed = false,
            ),
        )
        tags.forEach { repo.addTag(id, it) }
        return id
    }

    /**
     * Вихід із полиці прибирає за собою.
     *
     * Стан, який має сенс лише на видимому екрані: незавершений скан головних
     * папок, його індикатор, діалог перейменування й режим множинного вибору.
     * Якби щось із цього переживало вихід, користувач повертався б на полицю в
     * стані, якого не залишав, — а `libraryRefreshing`, що лишився `true`,
     * ще й крутив би індикатор без жодного скану за ним.
     */
    @Test
    fun leavingTheShelfResetsWhatOnlyMakesSenseOnScreen() = runTest(scheduler) {
        vm.setLibraryActive(true)
        advanceUntilIdle()

        vm.startMultiSelect(initialId = "book-1")
        vm.requestRename(id = "book-1", title = "Кобзар", author = "Шевченко")
        advanceUntilIdle()
        assertTrue("режим множинного вибору мав увімкнутися", vm.isMultiSelect.value)

        vm.setLibraryActive(false)
        advanceUntilIdle()

        assertFalse("множинний вибір мав вимкнутися", vm.isMultiSelect.value)
        assertEquals("вибрані книги мали скинутися", emptySet<String>(), vm.selectedBookIds.value)
        assertNull("діалог перейменування мав закритися", vm.pendingRename.value)
        assertFalse("індикатор скану мав згаснути", vm.libraryRefreshing.value)
        assertEquals("кандидати в нові книги мали скинутися", 0, vm.newBookCount.value)
    }
}

/**
 * Проводка прапорця в екранах — як перевірка, а не як обіцянка в коментарі.
 *
 * Читає джерела як текст — той самий підхід, що в [BookTitleDisplayTest] і
 * [LocalizationResourcesTest]. Причина та сама: розбіжність не падає на збірці
 * й не видно на рев'ю, бо кожен окремий `collectAsState()` виглядає доречним, а
 * `DisposableEffect(Unit)` — тим паче.
 */
class ShelfLifecycleWiringTest {

    /** Робочий каталог юніт-тестів Gradle — каталог модуля, тобто `app/`. */
    private val mainDir = File("src/main/java/ua/nichnyk/listen")
    private val libraryScreen = File(mainDir, "ui/screens/LibraryScreen.kt")

    /**
     * `libraryActive` ставиться з lifecycle, а не з самого лише життя композиції.
     *
     * `DisposableEffect(Unit)` знімається, коли екран іде з NavHost, — тобто на
     * переході між екранами, але не на «Home». Саме через це прапорець колись
     * лишався `true` протягом усього фонового слухання.
     */
    @Test
    fun libraryActiveFollowsLifecycleNotJustComposition() {
        val text = libraryScreen.readText()
        assertTrue("не знайдено ${libraryScreen.path}", libraryScreen.isFile)

        for (event in listOf("Lifecycle.Event.ON_START", "Lifecycle.Event.ON_STOP")) {
            assertTrue(
                "LibraryScreen має ставити libraryActive з $event: " +
                    "інакше «Home» лишає полицю активною на весь час фонового відтворення",
                event in text,
            )
        }

        val effect = enclosingEffect(text, text.indexOf("setLibraryActive(true)"))
        assertEquals(
            "setLibraryActive має стояти в DisposableEffect(lifecycleOwner), а не " +
                "DisposableEffect(Unit): останній переживає згортання застосунку",
            "DisposableEffect(lifecycleOwner)",
            effect,
        )
    }

    /**
     * Стан екранів збирається з урахуванням lifecycle.
     *
     * `collectAsState()` тримає підписку, поки жива композиція, тож
     * `WhileSubscribed` під ним ніколи не доходить до нуля збирачів і не
     * охолоджується — скільки б прапорців не стояло у ViewModel.
     *
     * Канальні потоки (`vm.messages`, `billing.events`) сюди не входять і
     * входити не мають: `collectAsStateWithLifecycle` ковтав би події, поки
     * екран зупинений. Вони збираються в `LaunchedEffect` і тут не рахуються.
     *
     * Якщо колись знадобиться саме збирач без lifecycle — це рішення, а не
     * недогляд: міняти свідомо разом із цим тестом.
     */
    @Test
    fun screensCollectStateWithLifecycle() {
        val sources = mainDir.walkTopDown().filter { it.extension == "kt" }.toList()
        // Інакше зміна розкладки каталогів перетворила б тест на такий, що
        // мовчки не перевіряє нічого.
        assertTrue("не знайдено джерел у $mainDir", sources.size >= 30)

        val violations = sources.flatMap { file ->
            val text = file.readText()
            // «collectAsState(» не є підрядком «collectAsStateWithLifecycle(»:
            // між іменем і дужкою стоїть суфікс, тож зайвого збігу тут немає.
            Regex("""\bcollectAsState\(""").findAll(text).map { match ->
                "${file.name}:${text.take(match.range.first).count { it == '\n' } + 1}"
            }
        }

        assertEquals(
            "збирач без lifecycle: WhileSubscribed під ним не охолоне, поки жива " +
                "композиція — тобто й протягом усього фонового відтворення",
            emptyList<String>(),
            violations,
        )
    }

    /** Текст у дужках `SomeEffect(...)`, усередині якого лежить [index]. */
    private fun enclosingEffect(source: String, index: Int): String? =
        Regex("""\bDisposableEffect\([^)]*\)""")
            .findAll(source)
            .lastOrNull { it.range.last < index }
            ?.value
}
