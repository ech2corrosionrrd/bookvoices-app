package ua.nichnyk.listen.playback

import android.content.ComponentName
import androidx.concurrent.futures.await
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ua.nichnyk.listen.ListenApp
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.ChapterEntity

/**
 * Дерево, яке бачить Android Auto.
 *
 * Перевіряти його через PlayerManager неможливо: той ходить у сесію як контролер
 * відтворення й вузлів каталогу не бачить взагалі. Тут піднімається справжній
 * [MediaBrowser] — той самий шлях, яким приходить машина, — і обходить дерево.
 *
 * Найважливіше тут — сторінкування. `onGetChildren` довго ігнорував page/pageSize
 * і віддавав усю полицю одним Binder-пакетом; на кількасот книг це TransactionTooLarge
 * у машині, тобто в найгіршому місці для відлагодження.
 */
@RunWith(AndroidJUnit4::class)
class BrowseTreeTest {

    private lateinit var app: ListenApp
    private var browser: MediaBrowser? = null

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        clearShelf()
        insertBook(
            "b1", "Тіні забутих предків", "Коцюбинський",
            lastPlayedAt = 5_000L, completed = false,
            // 3 глави по 20 с при тривалості 60 с: друга глава плюс 10 с — рівно половина книги
            chapterIndex = 1, positionMs = 10_000L,
            series = "Карпатський цикл",
        )
        // Дослухана книга стоїть саме там, де її лишає кінець відтворення:
        // остання глава, остання секунда. Інакше перевірка «вмикається спочатку»
        // проходила б і без виправлення.
        insertBook(
            "b2", "Земля", "Кобилянська",
            lastPlayedAt = 9_000L, completed = true,
            chapterIndex = 2, positionMs = 20_000L,
        )
        insertBook("b3", "Місто", "Підмогильний", lastPlayedAt = null, completed = false)
        browser = connect()
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { browser?.release() }
        browser = null
        clearShelf()
    }

    private suspend fun clearShelf() {
        app.container.db.library().deleteAllBooks()
    }

    private suspend fun insertBook(
        id: String,
        title: String,
        author: String,
        lastPlayedAt: Long?,
        completed: Boolean,
        chapterIndex: Int = 0,
        positionMs: Long = 0L,
        series: String? = null,
    ) {
        val dao = app.container.db.library()
        dao.upsertBook(
            BookEntity(
                id = id,
                title = title,
                author = author,
                coverPath = null,
                addedAt = 0L,
                lastPlayedAt = lastPlayedAt,
                durationMs = 60_000L,
                positionMs = positionMs,
                currentChapterIndex = chapterIndex,
                playbackSpeed = 1f,
                completed = completed,
                series = series,
            ),
        )
        dao.upsertChapters(
            (0 until 3).map { index ->
                ChapterEntity(
                    id = "$id-$index",
                    bookId = id,
                    index = index,
                    title = "Розділ ${index + 1}",
                    uri = "file:///$id-$index.mp3",
                    durationMs = 20_000L,
                )
            },
        )
    }

    private suspend fun connect(): MediaBrowser = withContext(Dispatchers.Main) {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        withTimeout(20_000) {
            MediaBrowser.Builder(app, token).buildAsync().await()
        }
    }

    private suspend fun children(
        parentId: String,
        page: Int = 0,
        pageSize: Int = 100,
    ): ImmutableList<MediaItem> = withContext(Dispatchers.Main) {
        val result = withTimeout(20_000) {
            browser!!.getChildren(parentId, page, pageSize, null).await()
        }
        assertTrue("getChildren($parentId) повернув помилку ${result.resultCode}", result.resultCode == LibraryResult.RESULT_SUCCESS)
        result.value ?: ImmutableList.of()
    }

    private fun titles(items: List<MediaItem>) = items.map { it.mediaMetadata.title.toString() }

    @Test
    fun rootIsCategoriesNotFlatShelf() = runBlocking {
        val root = children(PlaybackService.ROOT_ID)
        assertEquals(
            listOf(
                PlaybackService.CONTINUE_ID,
                PlaybackService.ALL_BOOKS_ID,
                PlaybackService.AUTHORS_ID,
            ),
            root.map { it.mediaId },
        )
        // Категорію не можна «увімкнути» — інакше машина спробує її грати.
        assertTrue(root.all { it.mediaMetadata.isBrowsable == true })
        assertTrue(root.none { it.mediaMetadata.isPlayable == true })
    }

    /** «Слухаю» — книги, які почали й не дослухали. Саме заради цього вузла все й робилося. */
    @Test
    fun continueNodeHasOnlyStartedUnfinishedBooks() = runBlocking {
        val items = children(PlaybackService.CONTINUE_ID)
        assertEquals(listOf("b1"), items.map { it.mediaId })
    }

    @Test
    fun allBooksNodeHasWholeShelf() = runBlocking {
        val items = children(PlaybackService.ALL_BOOKS_ID)
        assertEquals(setOf("b1", "b2", "b3"), items.map { it.mediaId }.toSet())
        assertTrue(items.all { it.mediaMetadata.isPlayable == true })
    }

    @Test
    fun authorsNodeListsDistinctAuthorsAndDrillsDown() = runBlocking {
        val authors = children(PlaybackService.AUTHORS_ID)
        assertEquals(listOf("Кобилянська", "Коцюбинський", "Підмогильний"), titles(authors))

        val byAuthor = children(PlaybackService.AUTHOR_PREFIX + "Коцюбинський")
        assertEquals(listOf("b1"), byAuthor.map { it.mediaId })
    }

    @Test
    fun bookNodeHasChaptersInOrder() = runBlocking {
        val chapters = children("b1")
        assertEquals(listOf("b1-0", "b1-1", "b1-2"), chapters.map { it.mediaId })
        // URI тут свідомо не перевіряється: media3 зрізає localConfiguration на
        // межі сесії, і браузер його не бачить принципово. Саме тому адреси глав
        // підставляє onAddMediaItems уже на боці сервісу — за одним лише mediaId.
        // book_id в extras — те, за чим плеєр упізнає книгу, коли главу
        // запустили з машини, а не з полиці.
        assertEquals("b1", chapters.first().mediaMetadata.extras?.getString("book_id"))
    }

    /**
     * Сторінкування. Раніше page/pageSize просто ігнорувалися, і клієнт отримував
     * усе одним пакетом незалежно від того, що просив.
     */
    @Test
    fun childrenAreServedPageByPage() = runBlocking {
        val first = children(PlaybackService.ALL_BOOKS_ID, page = 0, pageSize = 2)
        val second = children(PlaybackService.ALL_BOOKS_ID, page = 1, pageSize = 2)
        val third = children(PlaybackService.ALL_BOOKS_ID, page = 2, pageSize = 2)

        assertEquals(2, first.size)
        assertEquals(1, second.size)
        assertEquals(0, third.size)
        // Сторінки не перетинаються й разом дають усю полицю.
        val all = (first + second).map { it.mediaId }
        assertEquals(3, all.toSet().size)
    }

    @Test
    fun unknownNodeIsAnErrorNotEmptyList() = runBlocking {
        val result = withContext(Dispatchers.Main) {
            withTimeout(20_000) {
                browser!!.getChildren("немає такого вузла", 0, 10, null).await()
            }
        }
        assertTrue("невідомий вузол мав дати помилку", result.resultCode != LibraryResult.RESULT_SUCCESS)
    }

    @Test
    fun categoryItemDescribesItself() = runBlocking {
        val result = withContext(Dispatchers.Main) {
            withTimeout(20_000) {
                browser!!.getItem(PlaybackService.CONTINUE_ID).await()
            }
        }
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertNotNull(result.value)
        assertTrue(result.value!!.mediaMetadata.isBrowsable == true)
    }

    // --- як Auto показує полицю -------------------------------------------

    /**
     * Смужка «де я зупинився» на картці книги.
     *
     * Auto малює її з двох extras, і без них усі книги в машині виглядають
     * однаково незачепленими — тобто зникає єдина підказка, потрібна за кермом,
     * де читати назви ніколи.
     */
    @Test
    fun bookCardCarriesListeningProgress() = runBlocking {
        val byId = children(PlaybackService.ALL_BOOKS_ID).associateBy { it.mediaId }

        val started = byId.getValue("b1").mediaMetadata.extras!!
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
            started.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        assertEquals(0.5, started.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)

        val finished = byId.getValue("b2").mediaMetadata.extras!!
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
            finished.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        // У дослуханої книги позиція скинута на нуль, і збережений дріб дав би
        // «прослухано 0 %» під позначкою «дослухано». Відсоток веде за статусом.
        assertEquals(1.0, finished.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)

        val untouched = byId.getValue("b3").mediaMetadata.extras!!
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED,
            untouched.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        assertEquals(0.0, untouched.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)
    }

    /**
     * Книга, яку питають поштучно, — та сама картка.
     *
     * `onGetItem` довго будував власну копію без extras, тож книга, про яку Auto
     * питає окремо, приходила без позначки прослуханого — тобто виглядала
     * незачепленою поряд із тією самою карткою зі списку.
     */
    @Test
    fun bookAskedByIdCarriesTheSameProgress() = runBlocking {
        val item = withContext(Dispatchers.Main) {
            withTimeout(20_000) { browser!!.getItem("b1").await() }
        }
        assertEquals(LibraryResult.RESULT_SUCCESS, item.resultCode)
        val extras = item.value!!.mediaMetadata.extras!!
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
            extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        assertEquals(0.5, extras.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)
    }

    /**
     * Стиль кореневого списку їде в LibraryParams.
     *
     * Android Auto під'єднується як MediaBrowserCompat, а для нього media3 будує
     * BrowserRoot із params.extras. Підказка, покладена в MediaMetadata кореня,
     * туди не потрапляє взагалі — і лишалася мертвою.
     */
    @Test
    fun rootAnswersWithContentStyleInParams() = runBlocking {
        val root = withContext(Dispatchers.Main) {
            withTimeout(20_000) { browser!!.getLibraryRoot(null).await() }
        }
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        assertEquals(
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            root.params!!.extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )
    }

    /**
     * Книги — сіткою, назви — списком.
     *
     * Підказка стоїть на батьківському вузлі й стосується його дітей. Книга
     * водночас browsable і playable, тож у сітку її відправляє саме
     * BROWSABLE-підказка — тому перевіряємо її, а не PLAYABLE.
     */
    @Test
    fun bookNodesAskForGridAndNameNodesForList() = runBlocking {
        suspend fun browsableStyle(mediaId: String): Int {
            val item = withContext(Dispatchers.Main) {
                withTimeout(20_000) { browser!!.getItem(mediaId).await() }
            }
            assertEquals(LibraryResult.RESULT_SUCCESS, item.resultCode)
            return item.value!!.mediaMetadata.extras!!
                .getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
        }

        assertEquals(
            "«Слухаю» має показувати обкладинки",
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            browsableStyle(PlaybackService.CONTINUE_ID),
        )
        assertEquals(
            "«Усі книги» має показувати обкладинки",
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            browsableStyle(PlaybackService.ALL_BOOKS_ID),
        )
        assertEquals(
            "у авторів немає картинок — сітка була б порожньою",
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            browsableStyle(PlaybackService.AUTHORS_ID),
        )
    }

    /**
     * Порожня полиця пояснює себе, а не вдає три робочі категорії.
     *
     * Рядок навмисно ні browsable, ні playable: Auto показує його неактивним і
     * нікуди по ньому не веде.
     */
    @Test
    fun emptyShelfExplainsItselfInsteadOfDeadCategories() = runBlocking {
        clearShelf()

        val root = children(PlaybackService.ROOT_ID)
        assertEquals(1, root.size)
        assertEquals(PlaybackService.EMPTY_PREFIX + PlaybackService.ROOT_ID, root.single().mediaId)
        assertTrue("рядок-пояснення не має вести нікуди", root.single().mediaMetadata.isBrowsable != true)
        assertTrue("рядок-пояснення не має грати", root.single().mediaMetadata.isPlayable != true)
    }

    /** Книги є, але жодної не починали — «Слухаю» так само не мовчить. */
    @Test
    fun continueNodeExplainsItselfWhenNothingStarted() = runBlocking {
        clearShelf()
        insertBook("b9", "Хіба ревуть воли", "Мирний", lastPlayedAt = null, completed = false)

        val items = children(PlaybackService.CONTINUE_ID)
        assertEquals(
            listOf(PlaybackService.EMPTY_PREFIX + PlaybackService.CONTINUE_ID),
            items.map { it.mediaId },
        )
    }

    /**
     * Вузол, відкритий повз корінь, теж не буває німим.
     *
     * З порожньої полиці в корені видно лише рядок-пояснення, тож зайти у «Всі
     * книги» звідти не можна. Але Auto відновлює стек перегляду з кешу й питає
     * вміст вузла напряму — і там був порожній екран.
     */
    @Test
    fun nodeOpenedDirectlyExplainsItselfToo() = runBlocking {
        clearShelf()

        val items = children(PlaybackService.ALL_BOOKS_ID)
        assertEquals(
            listOf(PlaybackService.EMPTY_PREFIX + PlaybackService.ALL_BOOKS_ID),
            items.map { it.mediaId },
        )
    }

    /** Рядок-пояснення можна й описати: Auto питає getItem по видимих елементах. */
    @Test
    fun emptyRowDescribesItself() = runBlocking {
        clearShelf()

        val id = PlaybackService.EMPTY_PREFIX + PlaybackService.ROOT_ID
        val item = withContext(Dispatchers.Main) {
            withTimeout(20_000) { browser!!.getItem(id).await() }
        }
        assertEquals(LibraryResult.RESULT_SUCCESS, item.resultCode)
        assertEquals(id, item.value!!.mediaId)
    }

    /**
     * Зміна полиці доходить до браузера сама.
     *
     * MediaBrowserCompat не перепитує вміст вузла: він малює прочитане й чекає
     * `notifyChildrenChanged`. Без цього смужка на картці застигала на значенні,
     * яке було в мить відкриття списку.
     */
    @Test
    fun shelfChangeReachesTheBrowser() = runBlocking {
        val notified = CompletableDeferred<Unit>()
        val listener = object : MediaBrowser.Listener {
            override fun onChildrenChanged(
                browser: MediaBrowser,
                parentId: String,
                itemCount: Int,
                params: LibraryParams?,
            ) {
                if (parentId == PlaybackService.ALL_BOOKS_ID) notified.complete(Unit)
            }
        }
        val watcher = withContext(Dispatchers.Main) {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            withTimeout(20_000) {
                MediaBrowser.Builder(app, token).setListener(listener).buildAsync().await()
            }
        }
        try {
            withContext(Dispatchers.Main) {
                withTimeout(20_000) {
                    watcher.subscribe(PlaybackService.ALL_BOOKS_ID, null).await()
                }
            }
            // Зсув на цілий відсоток: дрібніші зміни огрублюються навмисно, щоб
            // тикер позиції не смикав дерево кілька разів на секунду.
            insertBook(
                "b1", "Тіні забутих предків", "Коцюбинський",
                lastPlayedAt = 5_000L, completed = false,
                chapterIndex = 1, positionMs = 16_000L,
            )
            assertNotNull(
                "браузер так і не дізнався, що полиця змінилася",
                withTimeoutOrNull(15_000) { notified.await() },
            )
        } finally {
            withContext(Dispatchers.Main) { watcher.release() }
        }
    }

    // --- що саме вмикається з браузера ------------------------------------

    private suspend fun withController(block: suspend (MediaController) -> Unit) {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val c = withContext(Dispatchers.Main) {
            withTimeout(20_000) { MediaController.Builder(app, token).buildAsync().await() }
        }
        try {
            block(c)
        } finally {
            withContext(Dispatchers.Main) { c.release() }
        }
    }

    /** Опис із браузера: id є, URI немає — саме так приходить тап у машині. */
    private fun descriptionOf(mediaId: String) = MediaItem.Builder().setMediaId(mediaId).build()

    /**
     * Чекає, доки черга контролера набуде очікуваного розміру.
     *
     * Колбеки сесії — асинхронні: `setMediaItem` лише надсилає запит, а
     * `onSetMediaItems` розкриває його в корутині й повертає результат назад
     * уже наступними повідомленнями. Прочитати `mediaItemCount` одразу після
     * виклику означає прочитати стан **до** розкриття — тобто рівно один
     * елемент, і тест падав би незалежно від того, справний код чи ні.
     */
    /**
     * Переконується, що черга **лишається** заданого розміру.
     *
     * Окремо від [awaitQueueOf] і саме тому, що контролер media3 маскує зміну
     * локально: одразу після `setMediaItems` він показує те, що надіслали, і
     * лише згодом підміняє відповіддю сесії. Перевірка «дочекатися розміру 3»
     * тут проходила б миттєво на маскованому значенні — і не помітила б, що
     * сесія слідом розкриє чергу в дев'ять елементів.
     */
    private suspend fun MediaController.assertQueueStaysAt(expected: Int, forMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + forMs
        while (System.currentTimeMillis() < deadline) {
            assertEquals(
                "черга змінилася вже після відповіді сесії",
                expected,
                withContext(Dispatchers.Main) { mediaItemCount },
            )
            delay(100)
        }
    }

    private suspend fun MediaController.awaitQueueOf(expected: Int) {
        val ok = withTimeoutOrNull(10_000) {
            while (withContext(Dispatchers.Main) { mediaItemCount } != expected) {
                delay(50)
            }
            true
        }
        assertTrue(
            "черга так і не стала розміром $expected " +
                "(зараз ${withContext(Dispatchers.Main) { mediaItemCount }})",
            ok == true,
        )
    }

    /**
     * Тап по книзі продовжує з місця, де слухача перервали.
     *
     * Доти PlaybackService не мав onSetMediaItems, тож media3 брав значення за
     * замовчуванням — перша глава, нульова секунда. Картка показувала смужку
     * прослуханого, а натискання відкидало на початок.
     */
    @Test
    fun tappingBookResumesWhereItStopped() = runBlocking {
        withController { c ->
            withContext(Dispatchers.Main) { c.setMediaItem(descriptionOf("b1")) }
            c.awaitQueueOf(3)
            withContext(Dispatchers.Main) {
                assertEquals("b1 збережено на другій главі", 1, c.currentMediaItemIndex)
                assertEquals(10_000L, c.currentPosition)
            }
        }
    }

    /** Тап по конкретній главі — з її початку, як jumpToChapter на телефоні. */
    @Test
    fun tappingChapterStartsThatChapterFromItsBeginning() = runBlocking {
        withController { c ->
            withContext(Dispatchers.Main) { c.setMediaItem(descriptionOf("b1-2")) }
            c.awaitQueueOf(3)
            withContext(Dispatchers.Main) {
                assertEquals("вибрано третю главу", 2, c.currentMediaItemIndex)
                assertEquals("вибір глави — це не «продовжити»", 0L, c.currentPosition)
            }
        }
    }

    /**
     * Голосове «увімкни таку-то книгу».
     *
     * media3 доставляє це як елемент з порожнім id і запитом у requestMetadata.
     * Доти він не впізнавався ніяк: у чергу лягав елемент без URI, тобто
     * голосовий пошук мовчки не грав.
     */
    @Test
    fun voiceSearchRequestResolvesToTheBook() = runBlocking {
        val request = MediaItem.Builder()
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setSearchQuery("Тіні забутих").build(),
            )
            .build()
        withController { c ->
            withContext(Dispatchers.Main) { c.setMediaItem(request) }
            c.awaitQueueOf(3)
            withContext(Dispatchers.Main) {
                assertEquals("і теж продовжити з збереженого місця", 1, c.currentMediaItemIndex)
            }
        }
    }

    /**
     * Готова черга проходить наскрізь, а не розкривається вдруге.
     *
     * Власний PlayerManager надсилає вже зібрані глави з URI і явним місцем
     * старту. Якби PlaybackService розкривав і їх, кожна з N глав перетворилася
     * б на цілу книгу — N² елементів у черзі.
     */
    @Test
    fun readyMadeQueuePassesThroughUnexpanded() = runBlocking {
        val ready = (0 until 3).map { i ->
            MediaItem.Builder().setMediaId("b1-$i").setUri("file:///b1-$i.mp3").build()
        }
        withController { c ->
            withContext(Dispatchers.Main) { c.setMediaItems(ready, 2, 4_000L) }
            // Саме 3, а не 9: розкриття кожної з трьох глав у цілу книгу дало б N².
            c.assertQueueStaysAt(3)
            withContext(Dispatchers.Main) {
                assertEquals("явний індекс від контролера має лишитися", 2, c.currentMediaItemIndex)
                assertEquals("явна позиція від контролера теж", 4_000L, c.currentPosition)
            }
        }
    }

    /**
     * Дослухана книга вмикається спочатку, а не з останньої секунди.
     *
     * Її збережене місце — кінець останньої глави, бо саме так її записує кінець
     * відтворення. Без арбітражу [ua.nichnyk.listen.data.replayTarget] тап по
     * такій книзі в машині давав секунду звуку й одразу кінець; на телефоні цей
     * випадок закривався давно.
     */
    @Test
    fun tappingFinishedBookStartsOverInsteadOfEnding() = runBlocking {
        withController { c ->
            withContext(Dispatchers.Main) { c.setMediaItem(descriptionOf("b2")) }
            c.awaitQueueOf(3)
            withContext(Dispatchers.Main) {
                assertEquals("дослухану книгу треба вмикати з початку", 0, c.currentMediaItemIndex)
                assertEquals(0L, c.currentPosition)
            }
        }
    }

    /**
     * Пошук у машині знає про серії — як і пошук на полиці.
     *
     * ShelfQuery.filter шукає за назвою, автором і серією; сесія довго шукала
     * лише за двома першими, тож голосовий запит назвою циклу не знаходив нічого.
     */
    @Test
    fun searchFindsBookBySeriesName() = runBlocking {
        val result = withContext(Dispatchers.Main) {
            withTimeout(20_000) { browser!!.getSearchResult("Карпатський", 0, 10, null).await() }
        }
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(listOf("b1"), result.value!!.map { it.mediaId })
    }

    /**
     * Голе «увімкни» без назви.
     *
     * Assistant надсилає його як порожній запит, а не як відсутній. Розумна
     * відповідь одна — остання слухана книга, та сама, яку віддає відновлення
     * після перезапуску. Тут це b2: у неї найсвіжіший lastPlayedAt.
     */
    @Test
    fun bareVoicePlayFallsBackToTheLastBook() = runBlocking {
        val request = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build())
            .build()
        withController { c ->
            withContext(Dispatchers.Main) { c.setMediaItem(request) }
            c.awaitQueueOf(3)
            withContext(Dispatchers.Main) {
                assertEquals("b2-0", c.currentMediaItem?.mediaId)
            }
        }
    }

    /**
     * playFromUri: ідентифікатора немає зовсім, файл лежить у requestMetadata.
     * Доти такий запит клав у чергу елемент без URI — тобто мовчав.
     */
    @Test
    fun playFromUriResolvesToTheChapterThatOwnsIt() = runBlocking {
        val request = MediaItem.Builder()
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri("file:///b3-1.mp3".toUri())
                    .build(),
            )
            .build()
        withController { c ->
            withContext(Dispatchers.Main) { c.setMediaItem(request) }
            c.awaitQueueOf(3)
            withContext(Dispatchers.Main) {
                assertEquals("b3-1", c.currentMediaItem?.mediaId)
            }
        }
    }
}
