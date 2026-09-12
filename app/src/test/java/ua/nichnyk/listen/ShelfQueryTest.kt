package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookSortOrder
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.LibraryFilter
import ua.nichnyk.listen.data.ShelfQuery

/**
 * Пошук, фільтри й сортування полиці.
 *
 * Досі це було методом LibraryRepository і не перевірялося взагалі: щоб дістатися
 * до чистої функції, довелося б підняти Context і базу.
 */
class ShelfQueryTest {

    private fun book(
        id: String,
        title: String,
        author: String = "Автор",
        lastPlayedAt: Long? = null,
        addedAt: Long = 0L,
        completed: Boolean = false,
        durationMs: Long = 1000L,
        positionMs: Long = 0L,
        series: String? = null,
        seriesOrder: Float? = null,
    ) = BookWithChapters(
        book = BookEntity(
            id = id,
            title = title,
            author = author,
            coverPath = null,
            addedAt = addedAt,
            lastPlayedAt = lastPlayedAt,
            durationMs = durationMs,
            positionMs = positionMs,
            currentChapterIndex = 0,
            playbackSpeed = 1f,
            completed = completed,
            series = series,
            seriesOrder = seriesOrder,
        ),
        chapters = listOf(
            ChapterEntity(
                id = "$id-c0",
                bookId = id,
                index = 0,
                title = "1",
                uri = "file:///$id",
                durationMs = durationMs,
            ),
        ),
    )

    private fun ids(list: List<BookWithChapters>) = list.map { it.book.id }

    private val shelf = listOf(
        book("a", "Кобзар", author = "Шевченко", lastPlayedAt = 300L, addedAt = 10L),
        book("b", "Місто", author = "Підмогильний", lastPlayedAt = null, addedAt = 30L),
        book("c", "Тигролови", author = "Багряний", lastPlayedAt = 100L, addedAt = 20L, completed = true),
    )

    @Test
    fun filterListeningExcludesFinishedAndUntouched() {
        val result = ShelfQuery.filter(shelf, "", LibraryFilter.Listening)
        assertEquals(listOf("a"), ids(result))
    }

    @Test
    fun filterFinishedKeepsOnlyCompleted() {
        val result = ShelfQuery.filter(shelf, "", LibraryFilter.Finished)
        assertEquals(listOf("c"), ids(result))
    }

    @Test
    fun queryMatchesTitleAndAuthorCaseInsensitively() {
        assertEquals(listOf("a"), ids(ShelfQuery.filter(shelf, "кобЗАР", LibraryFilter.All)))
        assertEquals(listOf("c"), ids(ShelfQuery.filter(shelf, "багрян", LibraryFilter.All)))
    }

    @Test
    fun blankQueryKeepsEverything() {
        assertEquals(3, ShelfQuery.filter(shelf, "   ", LibraryFilter.All).size)
    }

    @Test
    fun queryAndFilterApplyTogether() {
        // «Тигролови» підходять під запит, але вже дослухані.
        val result = ShelfQuery.apply(shelf, "тигро", LibraryFilter.Listening, BookSortOrder.Title)
        assertEquals(emptyList<String>(), ids(result))
    }

    @Test
    fun lastPlayedSortsUnplayedToTheEnd() {
        val result = ShelfQuery.sort(shelf, BookSortOrder.LastPlayed)
        assertEquals(listOf("a", "c", "b"), ids(result))
    }

    @Test
    fun lastPlayedBreaksTiesByAddedAt() {
        val tied = listOf(
            book("old", "Стара", lastPlayedAt = 500L, addedAt = 1L),
            book("new", "Нова", lastPlayedAt = 500L, addedAt = 9L),
        )
        assertEquals(listOf("new", "old"), ids(ShelfQuery.sort(tied, BookSortOrder.LastPlayed)))
    }

    @Test
    fun titleSortIsNatural() {
        val numbered = listOf(
            book("10", "Том 10"),
            book("2", "Том 2"),
            book("1", "Том 1"),
        )
        assertEquals(listOf("1", "2", "10"), ids(ShelfQuery.sort(numbered, BookSortOrder.Title)))
    }

    @Test
    fun authorSortUsesAuthorNotTitle() {
        val result = ShelfQuery.sort(shelf, BookSortOrder.Author)
        assertEquals(listOf("c", "b", "a"), ids(result))
    }

    @Test
    fun progressSortPutsFurthestFirst() {
        val partial = listOf(
            book("quarter", "Чверть", durationMs = 1000L, positionMs = 250L),
            book("half", "Половина", durationMs = 1000L, positionMs = 500L),
            book("none", "Нуль", durationMs = 1000L, positionMs = 0L),
        )
        assertEquals(listOf("half", "quarter", "none"), ids(ShelfQuery.sort(partial, BookSortOrder.Progress)))
    }

    /**
     * Порядок у циклі — не за назвою: третя книга циклу має стояти третьою, навіть
     * якщо її назва алфавітно перша. Саме заради цього `seriesOrder` і заведено.
     */
    @Test
    fun seriesSortFollowsSeriesThenOrderInIt() {
        val cycle = listOf(
            book("c", "Аврора", series = "Дюна", seriesOrder = 3f),
            book("a", "Ярина", series = "Дюна", seriesOrder = 1f),
            book("b", "Бук", series = "Дюна", seriesOrder = 2f),
            book("amber", "Будь-що", series = "Амбер", seriesOrder = 1f),
        )
        assertEquals(
            listOf("amber", "a", "b", "c"),
            ids(ShelfQuery.sort(cycle, BookSortOrder.Series)),
        )
    }

    /**
     * Книги поза циклами — одним блоком у кінці. Якби вони сортувалися нарівні,
     * заголовки «поза циклами» чергувалися б із назвами циклів через усю полицю.
     */
    @Test
    fun seriesSortPutsBooksWithoutSeriesLastAsOneBlock() {
        val mixed = listOf(
            book("loose-b", "Бук"),
            book("cycle", "Книга циклу", series = "Дюна", seriesOrder = 1f),
            book("loose-a", "Аврора"),
            book("blank", "Вітер", series = "   "),
        )
        val result = ids(ShelfQuery.sort(mixed, BookSortOrder.Series))
        assertEquals("книга циклу — перша", "cycle", result.first())
        assertEquals(
            "порожній рядок циклу рахується як його відсутність, решта — за назвою",
            listOf("loose-a", "loose-b", "blank"),
            result.drop(1),
        )
    }

    /** Без номера в циклі книга стає в кінець свого ж циклу, а не на початок. */
    @Test
    fun seriesSortPutsMissingOrderAfterNumberedOnes() {
        val cycle = listOf(
            book("no-order", "Аврора", series = "Дюна"),
            book("second", "Ярина", series = "Дюна", seriesOrder = 2f),
            book("first", "Бук", series = "Дюна", seriesOrder = 1f),
        )
        assertEquals(
            listOf("first", "second", "no-order"),
            ids(ShelfQuery.sort(cycle, BookSortOrder.Series)),
        )
    }

    @Test
    fun addedAtSortIsNewestFirst() {
        assertEquals(listOf("b", "c", "a"), ids(ShelfQuery.sort(shelf, BookSortOrder.AddedAt)))
    }

    @Test
    fun emptyShelfSurvivesEverySortOrder() {
        for (order in BookSortOrder.entries) {
            assertEquals(emptyList<String>(), ids(ShelfQuery.apply(emptyList(), "", LibraryFilter.All, order)))
        }
    }

    @Test
    fun filterByTagReturnsOnlyBooksWithTag() {
        val tagsMap = mapOf(
            "a" to setOf("поезія", "класика"),
            "b" to setOf("проза", "класика"),
            "c" to setOf("пригоди"),
        )
        val poetryResult = ShelfQuery.apply(shelf, "", LibraryFilter.All, BookSortOrder.Title, selectedTag = "поезія", bookTagsMap = tagsMap)
        assertEquals(listOf("a"), ids(poetryResult))

        val classicsResult = ShelfQuery.apply(shelf, "", LibraryFilter.All, BookSortOrder.Title, selectedTag = "класика", bookTagsMap = tagsMap)
        assertEquals(listOf("a", "b"), ids(classicsResult))

        val nonexistentResult = ShelfQuery.apply(shelf, "", LibraryFilter.All, BookSortOrder.Title, selectedTag = "фантастика", bookTagsMap = tagsMap)
        assertEquals(emptyList<String>(), ids(nonexistentResult))
    }

    @Test
    fun filterUnavailableKeepsOnlyMissingBooks() {
        val missing = setOf("b")
        val result = ShelfQuery.filter(shelf, "", LibraryFilter.Unavailable, missingBookIds = missing)
        assertEquals(listOf("b"), ids(result))
    }

    @Test
    fun booksInSeriesSortsBySeriesOrder() {
        val seriesShelf = listOf(
            book("s3", "Том 3", series = "Цикл", seriesOrder = 3f),
            book("s1", "Том 1", series = "Цикл", seriesOrder = 1f),
            book("s2", "Том 2", series = "Цикл", seriesOrder = 2f),
            book("x", "Інша"),
        )
        val result = ShelfQuery.booksInSeries(seriesShelf, "Цикл")
        assertEquals(listOf("s1", "s2", "s3"), ids(result))
    }
}
