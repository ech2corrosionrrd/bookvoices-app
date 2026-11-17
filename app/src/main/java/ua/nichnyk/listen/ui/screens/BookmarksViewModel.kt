package ua.nichnyk.listen.ui.screens

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.nichnyk.listen.R
import ua.nichnyk.listen.data.BookmarkEntity
import ua.nichnyk.listen.data.BookmarkWithBook
import ua.nichnyk.listen.data.LibraryRepository
import ua.nichnyk.listen.data.SearchText
import ua.nichnyk.listen.forAppLocale
import ua.nichnyk.listen.launchSafely
import ua.nichnyk.listen.playback.PlayerManager

class BookmarksViewModel(
    private val app: Application,
    private val repo: LibraryRepository,
    private val player: PlayerManager,
) : ViewModel() {
    val items = repo.observeBookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
    }

    // З `items`, а не з другого repo.observeBookmarks(): доти екран тримав два
    // однакові запити Room до тієї самої таблиці.
    val filteredItems: StateFlow<List<BookmarkWithBook>> = combine(
        items,
        _query,
    ) { bookmarks, q ->
        if (q.isBlank()) bookmarks
        else {
            bookmarks.filter {
                SearchText.matches(q, it.bookTitle, it.bookmark.note, it.bookmark.chapterTitle)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun play(bookId: String, chapterId: String, position: Long, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            val book = repo.getBook(bookId)
            if (book == null) {
                _messages.send(app.forAppLocale().getString(R.string.book_missing_title))
                return@launch
            }
            runCatching { player.playBookmark(book, chapterId, position) }
                .onSuccess { onStarted() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _messages.send(player.errorMessage(e))
                }
        }
    }

    /**
     * Видаляє закладку й повертає її саму — щоб екран міг запропонувати
     * «Скасувати». null означає, що видаляти вже не було чого.
     */
    suspend fun delete(id: String): BookmarkEntity? {
        val existing = repo.getBookmark(id) ?: return null
        repo.deleteBookmark(id)
        return existing
    }

    fun restore(bookmark: BookmarkEntity) {
        launchSafely("BookmarksViewModel.restore") { repo.restoreBookmark(bookmark) }
    }

    fun updateNote(id: String, note: String) {
        viewModelScope.launch { repo.updateBookmarkNote(id, note) }
    }

    suspend fun exportBookmarksMarkdown(): String = repo.exportBookmarksMarkdown()
}
