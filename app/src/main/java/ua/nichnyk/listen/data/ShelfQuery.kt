package ua.nichnyk.listen.data

/**
 * Пошук, фільтр і сортування полиці.
 *
 * Це чиста функція від списку книг — і вона нею була й раніше, просто лежала методом
 * усередині LibraryRepository, тобто перевірити її можна було лише з Context і живою
 * базою. Тут вона поруч із SeekLogic і BackupCodec: без залежностей і під тестами.
 */
object ShelfQuery {

    fun apply(
        books: List<BookWithChapters>,
        query: String,
        filter: LibraryFilter,
        sortOrder: BookSortOrder = BookSortOrder.LastPlayed,
        selectedTag: String? = null,
        bookTagsMap: Map<String, Set<String>> = emptyMap(),
        selectedSeries: String? = null,
        missingBookIds: Set<String> = emptySet(),
    ): List<BookWithChapters> =
        sort(filter(books, query, filter, selectedTag, bookTagsMap, selectedSeries, missingBookIds), sortOrder)

    fun filter(
        books: List<BookWithChapters>,
        query: String,
        filter: LibraryFilter,
        selectedTag: String? = null,
        bookTagsMap: Map<String, Set<String>> = emptyMap(),
        selectedSeries: String? = null,
        missingBookIds: Set<String> = emptySet(),
    ): List<BookWithChapters> {
        val q = query.trim().lowercase()
        return books.filter { item ->
            val matchesFilter = when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Listening -> item.book.lastPlayedAt != null && !item.book.completed
                LibraryFilter.Finished -> item.book.completed
                LibraryFilter.Unavailable -> item.book.id in missingBookIds
            }
            val matchesTag = selectedTag == null || bookTagsMap[item.book.id]?.contains(selectedTag) == true
            val matchesSeries = selectedSeries == null || item.book.series.equals(selectedSeries, ignoreCase = true)
            val matchesQuery = q.isEmpty() ||
                item.book.title.lowercase().contains(q) ||
                item.book.author.lowercase().contains(q) ||
                item.book.series?.lowercase()?.contains(q) == true
            matchesFilter && matchesTag && matchesSeries && matchesQuery
        }
    }

    fun sort(books: List<BookWithChapters>, sortOrder: BookSortOrder): List<BookWithChapters> {
        val pinned = books.filter { it.book.pinned }
        val unpinned = books.filter { !it.book.pinned }
        return sortGroup(pinned, sortOrder) + sortGroup(unpinned, sortOrder)
    }

    private fun sortGroup(books: List<BookWithChapters>, sortOrder: BookSortOrder): List<BookWithChapters> =
        when (sortOrder) {
            BookSortOrder.LastPlayed -> books.sortedWith(
                compareByDescending<BookWithChapters> { it.book.lastPlayedAt ?: 0L }
                    .thenByDescending { it.book.addedAt },
            )
            BookSortOrder.Title -> books.sortedWith { a, b ->
                val cmp = AudioImporter.naturalCompare(a.book.title, b.book.title)
                if (cmp != 0) cmp else (a.book.seriesOrder ?: 0f).compareTo(b.book.seriesOrder ?: 0f)
            }
            BookSortOrder.Author -> books.sortedWith { a, b ->
                val cmp = AudioImporter.naturalCompare(a.book.author, b.book.author)
                if (cmp != 0) cmp else AudioImporter.naturalCompare(a.book.title, b.book.title)
            }
            // Цикл, далі номер у циклі, далі назва. Книги поза циклами йдуть у
            // кінець одним блоком: інакше вони розсипалися б поміж циклами, і
            // заголовки на полиці чергувалися б із «поза циклами» без потреби.
            BookSortOrder.Series -> books.sortedWith { a, b ->
                val sa = a.book.series?.takeIf { it.isNotBlank() }
                val sb = b.book.series?.takeIf { it.isNotBlank() }
                when {
                    sa == null && sb == null -> AudioImporter.naturalCompare(a.book.title, b.book.title)
                    sa == null -> 1
                    sb == null -> -1
                    else -> {
                        val cmp = AudioImporter.naturalCompare(sa, sb)
                        if (cmp != 0) {
                            cmp
                        } else {
                            val orderCmp = (a.book.seriesOrder ?: Float.MAX_VALUE)
                                .compareTo(b.book.seriesOrder ?: Float.MAX_VALUE)
                            if (orderCmp != 0) orderCmp
                            else AudioImporter.naturalCompare(a.book.title, b.book.title)
                        }
                    }
                }
            }
            BookSortOrder.Progress -> books.sortedByDescending { it.progress() }
            BookSortOrder.AddedAt -> books.sortedByDescending { it.book.addedAt }
        }

    /** Книги одного циклу, відсортовані за номером у серії. */
    fun booksInSeries(books: List<BookWithChapters>, seriesName: String): List<BookWithChapters> =
        books
            .filter { it.book.series.equals(seriesName, ignoreCase = true) }
            .sortedWith { a, b ->
                val orderCmp = (a.book.seriesOrder ?: Float.MAX_VALUE).compareTo(b.book.seriesOrder ?: Float.MAX_VALUE)
                if (orderCmp != 0) orderCmp else AudioImporter.naturalCompare(a.book.title, b.book.title)
            }
}
