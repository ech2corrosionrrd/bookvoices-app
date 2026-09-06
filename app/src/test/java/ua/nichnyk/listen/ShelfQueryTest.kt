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
