package ua.nichnyk.listen.data

import ua.nichnyk.listen.forAppLocale
import android.content.Context
import androidx.room.withTransaction
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.R

class DuplicateBookException(
    val existingId: String,
    val existingTitle: String,
) : Exception()

data class PendingRename(
    val id: String,
    val title: String,
    val author: String,
)

/**
 * Підсумок імпорту. Тека може містити кілька книг, і частина з них уже може бути
 * на полиці — тому це не одна книга й не виняток, а звіт про обидва боки.
 */
data class ImportOutcome(
    val imported: List<PendingRename>,
    val duplicates: List<String>,
) {
    /** Єдина щойно додана книга — тоді є сенс пропонувати перейменування. */
    val single: PendingRename? get() = imported.singleOrNull()
}


class LibraryRepository(
    private val context: Context,
    private val db: ListenDatabase,
    private val covers: CoverGenerator,
    private val importer: AudioImporter,
    private val demo: DemoFactory,
) {

    private val dao = db.library()

    /** Зупиняє плеєр, якщо видаляється поточна книга. Задається з AppContainer. */
    var onBookDeleted: suspend (String) -> Unit = {}

    /** Скидає відтворення перед повною заміною полиці (відновлення JSON). */
    var onLibraryReset: suspend () -> Unit = {}

    /** Перепривʼязка змінює адреси глав — завантажену чергу плеєра треба скинути. */
    var onBookFilesChanged: suspend (String) -> Unit = {}

    /** Черга в базі змінилася повз плеєр — його копію в памʼяті треба перечитати. */
    var onQueueChangedExternally: suspend () -> Unit = {}

    fun observeBooks(): Flow<List<BookWithChapters>> = dao.observeBooks()

    fun observeBook(id: String): Flow<BookWithChapters?> = dao.observeBook(id)

    suspend fun getBook(id: String): BookWithChapters? = dao.getBook(id)

    suspend fun getBookForChapter(chapterId: String): BookWithChapters? = dao.getBookForChapter(chapterId)

    fun observeBookmarks(): Flow<List<BookmarkWithBook>> =
        combine(dao.observeBookmarks(), dao.observeBooks()) { marks, books ->
            val byId = books.associateBy { it.book.id }
            marks.map { mark ->
                val book = byId[mark.bookId]
                BookmarkWithBook(
                    mark,
                    book?.book?.title ?: context.forAppLocale().getString(R.string.unknown_book),
                    book?.book?.coverPath,
                )
            }
        }

    fun observeBookmarksFor(bookId: String): Flow<List<BookmarkEntity>> = dao.observeBookmarksForBook(bookId)

    suspend fun importUris(
        uris: List<Uri>,
        title: String? = null,
        author: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportOutcome {
        val draft = importer.fromUris(uris, onProgress)
        return insertDrafts(listOf(draft), title, author)
    }

    suspend fun importTree(
        tree: Uri,
        title: String? = null,
        author: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportOutcome {
        // Тека може містити кілька книг — кожна підпапка окремо.
        val drafts = importer.fromTree(tree, onProgress)
        return insertDrafts(drafts, title, author)
    }

    /**
     * Доливання аудіофайлів у наявну книгу (A5).
     * Зберігає позицію, швидкість і закладки.
     */
    suspend fun appendUrisToBook(
        bookId: String,
        uris: List<Uri>,
        onProgress: (ImportProgress) -> Unit = {},
    ): Int {
        val draft = importer.fromUris(uris, onProgress)
        return appendChaptersToBook(bookId, draft.chapters)
    }

    suspend fun appendTreeToBook(
        bookId: String,
        treeUri: Uri,
        onProgress: (ImportProgress) -> Unit = {},
    ): Int {
        val drafts = importer.fromTree(treeUri, onProgress)
        val allChapters = drafts.flatMap { it.chapters }
        val added = appendChaptersToBook(bookId, allChapters)
        // Користувач явно вказав цю теку в діалозі — авто-скан лише в ній,
        // навіть якщо fromTree знайшов вкладені групи.
        val folderDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        dao.updateSource(bookId, treeUri.toString(), folderDocId)
        return added
    }

    fun takeLibraryRootPermission(uri: Uri): Boolean = importer.takeTreePermission(uri)

    /** Чи посилається хоча б одна книга на полиці на цю теку (як джерело або в URI глав). */
    suspend fun isSourceTreeUsed(treeUri: String): Boolean {
        if (treeUri.isBlank()) return false
        return dao.getAllBooks().any { item ->
            item.book.sourceTreeUri == treeUri || item.chapters.any { it.uri.startsWith(treeUri) }
        }
    }

    /** Звільняє постійний дозвіл на читання головної папки, якщо на неї більше ніщо не спирається. */
    suspend fun releaseLibraryRootPermission(uri: String, keepForAutoBackup: Boolean = false) {
        if (uri.isBlank() || keepForAutoBackup || isSourceTreeUsed(uri)) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /** Звільняє дозвіл на попередню теку автокопії, зберігаючи читання, якщо воно потрібне книгам. */
    suspend fun releaseAutoBackupPermission(treeUri: String, libraryRootUris: List<String>) {
        if (treeUri.isBlank()) return
        val keepRead = treeUri in libraryRootUris || isSourceTreeUsed(treeUri)
        FolderBackup.releasePermission(context, treeUri, keepRead = keepRead)
    }

    /** Чи лишається чинним хоча б один tree grant із списку головних папок. */
    fun anyLibraryRootGranted(rootUris: List<String>): Boolean =
        rootUris.any { importer.isTreePermissionGranted(it.toUri()) }

    /**
     * Легкий скан теки книги (I3): лише імена й URI, без читання метаданих.
     *
     * Сканує **виключно** теку, яку користувач указав для цієї книги
     * ([BookEntity.sourceTreeUri] / [BookEntity.sourceFolderDocId]) — імпорт
     * папки, доливання папки або перепривʼязка. Евристики «будь-який tree
     * grant, що містить глави» немає: інакше дозвіл на бібліотеку тягнув би
     * сусідні томи. Без збереженої теки або без чинного дозволу — порожньо.
     */
    suspend fun scanNewAudioInSourceTree(bookId: String): List<RelinkMatcher.Candidate> {
        val book = dao.getBook(bookId) ?: return emptyList()
        val treeStr = book.book.sourceTreeUri ?: return emptyList()
        val treeUri = treeStr.toUri()
        if (!importer.isTreePermissionGranted(treeUri)) return emptyList()
        val candidates = runCatching {
            importer.listAudioFiles(treeUri, book.book.sourceFolderDocId)
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            AppLog.w("LibraryRepository.scanNewAudioInSourceTree", it)
            return emptyList()
        }
        return RelinkMatcher.newFiles(book.chapters.map { it.uri }, candidates)
    }

    /**
     * Нові книги в головних папках бібліотеки (легкий скан).
     * Уже наявні на полиці (спільні URI) відсіюються.
     */
    suspend fun scanNewBooksInLibraryRoots(
        rootUris: List<String>,
    ): List<LibraryBookCandidate> {
        if (rootUris.isEmpty()) return emptyList()
        val shelf = dao.getAllBooks()
        val found = mutableListOf<LibraryBookCandidate>()
        val seenUris = shelf.flatMap { it.chapters.map { ch -> ch.uri } }.toMutableSet()
        for (root in rootUris) {
            val treeUri = root.toUri()
            if (!importer.isTreePermissionGranted(treeUri)) continue
            val candidates = runCatching { importer.listBookCandidates(treeUri) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                AppLog.w("LibraryRepository.scanNewBooksInLibraryRoots", it)
                emptyList()
            }
            for (candidate in candidates) {
                if (DuplicateDetector.overlapsUris(shelf, candidate.audioUris)) continue
                if (candidate.audioUris.any { it in seenUris }) continue
                found += candidate
                seenUris += candidate.audioUris
            }
        }
        return found
    }

    /** Імпорт кандидатів, знайдених [scanNewBooksInLibraryRoots]. */
    suspend fun importLibraryCandidates(
        candidates: List<LibraryBookCandidate>,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportOutcome {
        if (candidates.isEmpty()) return ImportOutcome(emptyList(), emptyList())
        val drafts = mutableListOf<ImportDraft>()
        for (candidate in candidates) {
            val draft = runCatching {
                importer.draftFromFolder(
                    treeUri = candidate.treeUri.toUri(),
                    folderDocId = candidate.folderDocId,
                    title = candidate.title,
                    author = candidate.author,
                    onProgress = onProgress,
                )
            }.getOrElse {
                // Скасування імпорту зупиняє цикл, а не стає «ще одним невдалим кандидатом».
                if (it is kotlinx.coroutines.CancellationException) throw it
                AppLog.w("LibraryRepository.importLibraryCandidates", it)
                null
            } ?: continue
            drafts += draft
        }
        return insertDrafts(drafts, title = null, author = null)
    }

    private suspend fun appendChaptersToBook(
        bookId: String,
        newChapters: List<ImportedChapter>,
    ): Int {
        if (newChapters.isEmpty()) return 0
        val book = dao.getBook(bookId) ?: return 0
        // Дедуп лише за URI: однакові імена на різних дисках (CD1/01.mp3 і
        // CD2/01.mp3) — різні файли. Повторне доливання тієї ж теки дає ті самі
        // URI, тож дублікати все одно відсіюються.
        val knownUris = book.chapters.map { it.uri }.toSet()
        val newFileUris = newChapters.map { it.uri }.distinct()
            .filter { it !in knownUris }
            .toSet()
        val chaptersToAdd = newChapters.filter { it.uri in newFileUris }
        if (chaptersToAdd.isEmpty()) return 0
        val startIndex = (book.chapters.maxOfOrNull { it.index } ?: -1) + 1
        val entities = chaptersToAdd.mapIndexed { idx, ch ->
            ChapterEntity(
                id = newId(),
                bookId = bookId,
                index = startIndex + idx,
                title = ch.title,
                uri = ch.uri,
                durationMs = ch.durationMs,
                startMs = ch.startMs,
                endMs = ch.endMs,
            )
        }
        val addedDuration = entities.sumOf { it.durationMs }
        db.withTransaction {
            dao.upsertChapters(entities)
            dao.extendBook(bookId, addedDuration)
        }
        withContext(Dispatchers.Main) { onBookFilesChanged(bookId) }
        return entities.size
    }

    fun observeAllTags(): Flow<List<String>> = dao.observeAllTags()

    fun observeTagsForBook(bookId: String): Flow<List<String>> = dao.observeTagsForBook(bookId)

    fun observeBookTagsMap(): Flow<Map<String, Set<String>>> =
        dao.observeAllBookTags().map { list ->
            list.groupBy({ it.bookId }, { it.tag }).mapValues { it.value.toSet() }
        }

    suspend fun getTagsForBook(bookId: String): List<String> = dao.getTagsForBook(bookId)

    suspend fun addTag(bookId: String, tag: String) {
        val clean = tag.trim()
        if (clean.isBlank()) return
        dao.insertTag(BookTagEntity(bookId, clean))
        // У мітки немає позначки створення, тож «додали заново після видалення»
        // видно тільки звідси. Без цього рядка надгробок глушив би мітку вічно:
        // повісив, видалив, повісив ще раз — і вона мовчки не доїжджала в хмару.
        dao.deleteTagTombstone(bookId, clean)
    }

    suspend fun removeTag(bookId: String, tag: String) {
        val clean = tag.trim()
        dao.deleteTag(bookId, clean)
        // Надгробок — інакше мітка повертається з хмари наступним же злиттям:
        // для іншого пристрою вона просто «є в бекапі, а локально немає».
        dao.upsertTagTombstone(DeletedTagEntity(bookId, clean, System.currentTimeMillis()))
    }

    fun observeCharactersForBook(bookId: String): Flow<List<BookCharacterEntity>> =
        dao.observeCharactersForBook(bookId)

    suspend fun addCharacter(bookId: String, name: String, role: String? = null, description: String? = null) {
        val cleanName = name.trim()
        if (cleanName.isNotBlank()) {
            val entity = BookCharacterEntity(
                id = java.util.UUID.randomUUID().toString(),
                bookId = bookId,
                name = cleanName,
                role = role?.trim()?.ifBlank { null },
                description = description?.trim()?.ifBlank { null },
            )
            dao.upsertCharacter(entity)
        }
    }

    suspend fun deleteCharacter(id: String) {
        // Надгробок ставимо ДО видалення: після нього bookId вже не дізнатися,
        // а без нього персонаж повернувся б із наступним злиттям.
        val existing = dao.getCharacter(id)
        dao.deleteCharacter(id)
        if (existing != null) {
            dao.upsertCharacterTombstone(
                DeletedCharacterEntity(id = id, bookId = existing.bookId, deletedAt = System.currentTimeMillis()),
            )
        }
    }

    suspend fun updateBookNotes(bookId: String, notes: String?) {
        dao.updateBookNotes(bookId, notes?.trim()?.ifBlank { null })
    }

    fun observeBooksByAuthor(author: String): Flow<List<BookWithChapters>> =
        dao.observeBooksByAuthor(author)

    /**
     * Заданими title/author перейменовуємо лише тоді, коли книга одна: інакше
     * усі книги з теки отримали б однакову назву.
     *
     * Дублікат більше не зриває весь імпорт — решта книг із теки додається,
     * а пропущені повертаються у звіті.
     */
    private suspend fun insertDrafts(
        drafts: List<ImportDraft>,
        title: String?,
        author: String?,
    ): ImportOutcome {
        val single = drafts.size == 1
        val imported = mutableListOf<PendingRename>()
        val duplicates = mutableListOf<String>()
        // Знімок полиці читаємо один раз і доповнюємо доданими книгами.
        val shelf = dao.getAllBooks().toMutableList()
        for (draft in drafts) {
            val prepared = if (single) {
                draft.copy(
                    title = title?.ifBlank { null } ?: draft.title,
                    author = author?.ifBlank { null } ?: draft.author,
                )
            } else {
                draft
            }
            val duplicate = DuplicateDetector.findIn(
                books = shelf,
                title = prepared.title,
                chapterTitles = prepared.chapters.map { it.title },
                durations = prepared.chapters.map { it.durationMs },
                uris = prepared.chapters.map { it.uri },
            )
            if (duplicate != null) {
                duplicates += duplicate.book.title
                cleanupOwnCopies(draft.chapters.map { it.uri })
                continue
            }
            val pending = insertDraft(prepared)
            imported += pending
            dao.getBook(pending.id)?.let { shelf += it }
        }
        if (imported.isEmpty() && duplicates.isNotEmpty()) {
            throw DuplicateBookException("", duplicates.first())
        }
        return ImportOutcome(imported, duplicates)
    }

    suspend fun importDemo(): ImportOutcome {
        val track = demo.createSpokenOrTone()
        val title = context.forAppLocale().getString(R.string.demo_book_title)
        val author = context.forAppLocale().getString(R.string.demo_book_author)
        val existing = findDuplicate(
            title = title,
            chapterTitles = listOf(track.title),
            durations = listOf(track.durationMs),
            uris = listOf(track.file.toUri().toString()),
        )
        if (existing != null) throw DuplicateBookException(existing.book.id, existing.book.title)
        val id = newId()
        val cover = covers.generate(id, title, author)
        val duration = track.durationMs
        val chapter = ChapterEntity(
            id = newId(),
            bookId = id,
            index = 0,
            title = track.title,
            uri = track.file.toUri().toString(),
            durationMs = duration,
        )
        // Одна транзакція: збій між записами не лишить книгу без глав.
        db.withTransaction {
            dao.upsertBook(
                BookEntity(
                    id = id,
                    title = title,
                    author = author,
                    coverPath = cover,
                    addedAt = System.currentTimeMillis(),
                    lastPlayedAt = null,
                    durationMs = duration,
                    positionMs = 0,
                    currentChapterIndex = 0,
                    playbackSpeed = 1f,
                    completed = false,
                )
            )
            dao.upsertChapters(listOf(chapter))
        }
        return ImportOutcome(listOf(PendingRename(id, title, author)), emptyList())
    }

    private suspend fun insertDraft(draft: ImportDraft): PendingRename {
        val duplicate = findDuplicate(
            title = draft.title,
            chapterTitles = draft.chapters.map { it.title },
            durations = draft.chapters.map { it.durationMs },
            uris = draft.chapters.map { it.uri },
        )
        if (duplicate != null) throw DuplicateBookException(duplicate.book.id, duplicate.book.title)
        val id = newId()
        // Байти обкладинки живуть лише всередині цього виклику: одразу після запису
        // на диск вони стають сміттям, тож у пам'яті ніколи не лежить більше однієї.
        val cover = importer.loadCover(draft)?.let { covers.fromEmbedded(id, it) }
            ?: covers.generate(id, draft.title, draft.author)
        val chapters = draft.chapters.mapIndexed { index, ch ->
            ChapterEntity(
                id = newId(),
                bookId = id,
                index = index,
                title = ch.title.ifBlank { "${index + 1}" },
                uri = ch.uri,
                durationMs = ch.durationMs,
                startMs = ch.startMs,
                endMs = ch.endMs,
            )
        }
        val duration = chapters.sumOf { it.durationMs }
        // В одній транзакції: скасування імпорту чи вбитий процес між двома записами
        // лишили б на полиці книгу без жодного розділу.
        db.withTransaction {
            dao.upsertBook(
                BookEntity(
                    id = id,
                    title = draft.title,
                    author = draft.author,
                    coverPath = cover,
                    addedAt = System.currentTimeMillis(),
                    lastPlayedAt = null,
                    durationMs = duration,
                    positionMs = 0,
                    currentChapterIndex = 0,
                    playbackSpeed = 1f,
                    completed = false,
                    sourceTreeUri = draft.sourceTreeUri,
                    sourceFolderDocId = draft.sourceFolderDocId,
                )
            )
            dao.upsertChapters(chapters)
        }
        return PendingRename(id, draft.title, draft.author)
    }

    /** Порівняння зі свіжим знімком полиці. Сама логіка — у DuplicateDetector. */
    private suspend fun findDuplicate(
        title: String,
        chapterTitles: List<String>,
        durations: List<Long>,
        uris: List<String>,
    ): BookWithChapters? =
        DuplicateDetector.findIn(dao.getAllBooks(), title, chapterTitles, durations, uris)

    /**
     * Нові назви глав (id глави → назва). Порожні й незмінні відкидаються.
     * Мітка `renamedAt` книги оновлюється — за нею синхронізація переносить назви на інші пристрої.
     * @return старі назви змінених глав; порожньо, якщо нічого не змінилось.
     */
    suspend fun renameChapters(bookId: String, titles: Map<String, String>): Map<String, String> {
        val book = dao.getBook(bookId) ?: return emptyMap()
        val cleaned = titles.mapNotNull { (id, t) -> ChapterNaming.clean(t)?.let { id to it } }.toMap()
        val changes = ChapterNaming.changes(book.chapters, cleaned)
        if (changes.isEmpty()) return emptyMap()
        val previous = book.chapters.filter { it.id in changes }.associate { it.id to it.title }
        dao.renameChapters(bookId, changes)
        return previous
    }

    /**
     * Перейменування разом із перемальовуванням згенерованої обкладинки.
     *
     * Раніше оновлювався лише текст у БД: книга «Кобзар», перейменована на «Гайдамаки»,
     * і далі показувала намальовану літеру «К» та старий підпис автора.
     */
    suspend fun renameBook(id: String, title: String, author: String) {
        val current = dao.getBook(id) ?: return
        val newTitle = title.trim().ifBlank { current.book.title }
        val newAuthor = author.trim()
        val book = current.book
        val changed = newTitle != book.title || newAuthor != book.author
        val cover = if (changed && covers.isGenerated(book.coverPath)) {
            val redrawn = runCatching { covers.generate(id, newTitle, newAuthor) }.getOrNull()
            if (redrawn != null) covers.delete(book.coverPath)
            redrawn ?: book.coverPath
        } else {
            book.coverPath
        }
        dao.updateIdentity(
            bookId = id,
            title = newTitle,
            author = newAuthor,
            coverPath = cover,
            // Мітка часу лише коли назва чи автор справді змінилися: діалог,
            // закритий кнопкою «Зберегти» без правок, не має вигравати
            // арбітраж проти пристрою, де перейменування було справжнім.
            renamedAt = if (changed) System.currentTimeMillis() else book.renamedAt,
        )
    }

    /**
     * Замінює обкладинку книги на обране користувачем зображення.
     * Попередній файл обкладинки прибирається, якщо на нього не посилається інша книга.
     */
    suspend fun updateBookCover(bookId: String, imageUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > ZipBookExtractor.MAX_COVER_BYTES) return@use null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return@withContext false
        updateBookCoverBytes(bookId, bytes)
    }

    suspend fun updateBookCoverBytes(bookId: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = dao.getBook(bookId) ?: return@withContext false
        val newCover = covers.fromCustomImage(bookId, bytes) ?: return@withContext false
        val oldCover = current.book.coverPath
        dao.updateIdentity(
            bookId = bookId,
            title = current.book.title,
            author = current.book.author,
            coverPath = newCover,
            renamedAt = current.book.renamedAt,
        )
        if (oldCover != null && oldCover != newCover) {
            val stillUsed = dao.getAllBooks().any { it.book.id != bookId && it.book.coverPath == oldCover }
            if (!stillUsed) covers.delete(oldCover)
        }
        true
    }

    /**
     * Зберігає позицію відтворення.
     *
     * [completed] == null означає «не чіпати поточне значення» — інакше кожен тик
     * таймера знімав би позначку «завершено» з дослуханої книги.
     * [touchLastPlayed] == false означає «не оновлювати час останнього прослуховування» —
     * інакше книга, яку лише підвантажили при старті, назавжди лишалася б угорі полиці.
     */
    suspend fun saveProgress(
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
        durationHint: Long,
        speed: Float,
        completed: Boolean? = null,
        touchLastPlayed: Boolean = true,
    ) {
        dao.updateProgress(
            bookId = bookId,
            chapterIndex = chapterIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            lastPlayedAt = if (touchLastPlayed) System.currentTimeMillis() else null,
            durationHint = durationHint,
            speed = speed,
            completed = completed,
        )
    }

    /**
     * Тривалість глави, якої не знав імпорт.
     *
     * `MediaMetadataRetriever` читає не кожен файл, і на невдачі глава лягала в
     * базу з нулем назавжди: імпорт більше до неї не повертається. А з цих
     * нулів рахується все — смужка на полиці й у машині, сортування «за
     * прогресом», «до кінця книги» в таймері сну, «прослухано» на екрані автора.
     * Плеєр справжню тривалість знає, тож перший же програш глави її й лікує.
     *
     * Тільки нулі: непорожнє значення з імпорту точніше за `player.duration`,
     * який на змінному бітрейті буває приблизним.
     */
    suspend fun repairChapterDuration(chapterId: String, durationMs: Long) {
        if (durationMs <= 0L) return
        val bookId = dao.getBookForChapter(chapterId)?.book?.id ?: return
        db.withTransaction {
            if (dao.fillChapterDuration(chapterId, durationMs) > 0) dao.recomputeBookDuration(bookId)
        }
    }

    suspend fun addBookmark(bookId: String, chapter: ChapterEntity, positionMs: Long, note: String) {
        dao.upsertBookmark(
            BookmarkEntity(
                id = newId(),
                bookId = bookId,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                positionMs = positionMs,
                note = note,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun updateBookmarkNote(id: String, note: String) {
        dao.updateBookmarkNote(id, note.trim())
    }

    /** Закладка перед видаленням — щоб було що повернути через «Скасувати». */
    suspend fun getBookmark(id: String): BookmarkEntity? = dao.getBookmark(id)

    /**
     * Повертає щойно видалену закладку.
     *
     * Разом із надгробком: без його зняття наступне злиття з хмарою видалило б
     * закладку вдруге — цього разу вже без можливості скасувати.
     */
    suspend fun restoreBookmark(bookmark: BookmarkEntity) {
        dao.upsertBookmark(bookmark)
        dao.deleteBookmarkTombstone(bookmark.id)
    }

    /**
     * Надгробок ставимо ДО видалення: після нього bookId вже не дізнатися.
     * Та сама причина, що й у персонажів — інакше закладка воскресає з хмари.
     */
    suspend fun deleteBookmark(id: String) {
        val existing = dao.getBookmark(id)
        dao.deleteBookmark(id)
        if (existing != null) {
            dao.upsertBookmarkTombstone(
                DeletedBookmarkEntity(id = id, bookId = existing.bookId, deletedAt = System.currentTimeMillis()),
            )
        }
    }

    /**
     * Скільки глав книги зараз недоступні.
     *
     * Різні URI перевіряються по одному разу: у m4b усі глави дивляться в один файл.
     */
    suspend fun missingChapterCount(bookId: String): Int {
        val book = dao.getBook(bookId) ?: return 0
        val uris = book.chapters.map { it.uri }.distinct()
        return uris.count { !isAudioAccessible(it) }
    }

    /**
     * Перепривʼязує книгу до файлів у новій теці, зберігаючи прогрес і закладки.
     *
     * Досі переміщена тека означала книгу, яку лишалося тільки видалити й імпортувати
     * заново — разом із позицією, швидкістю й усіма закладками.
     */
    suspend fun relinkBook(bookId: String, treeUri: Uri): RelinkResult {
        val book = dao.getBook(bookId) ?: return RelinkResult(0, 0)
        val chapters = book.chapters.sortedBy { it.index }
        val oldUris = chapters.map { it.uri }.distinct()
        if (oldUris.isEmpty()) return RelinkResult(0, 0)

        val candidates = importer.listAudioFiles(treeUri)
        val mapping = RelinkMatcher.match(oldUris, candidates)
        if (mapping.isEmpty()) return RelinkResult(0, oldUris.size)

        val updated = chapters.mapNotNull { ch -> mapping[ch.uri]?.let { ch.copy(uri = it) } }
        if (updated.isNotEmpty()) {
            val folderDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            db.withTransaction {
                dao.upsertChapters(updated)
                dao.updateSource(bookId, treeUri.toString(), folderDocId)
            }
            // Черга плеєра ще тримає старі адреси — вона більше не відповідає книзі.
            withContext(Dispatchers.Main) { onBookFilesChanged(bookId) }
        }
        return RelinkResult(mapping.size, oldUris.size)
    }

    /** Перевірка доступності файлу робить IO, тому виконується поза головним потоком. */
    suspend fun isAudioAccessible(uriStr: String): Boolean =
        withContext(Dispatchers.IO) { isAccessibleBlocking(uriStr) }

    /**
     * Ті з [uris], що зараз не відкриваються.
     *
     * Перевірки йдуть паралельно, бо кожна — не обчислення, а очікування: для
     * `content://` це Binder-виклик до DocumentsProvider, і потік стоїть у ньому
     * без діла. Послідовний обхід полиці зі 100 книг (1398 файлів) займав на
     * емуляторі API 30 близько 1,9 с, і весь цей час полиця стояла без жодної
     * позначки — результат віддається одним махом у кінці.
     *
     * Стеля потоків, а не «усі одразу»: пул Binder-потоків на клієнті скінченний,
     * і тисяча одночасних викликів упреться в нього, а не прискорить справу.
     *
     * Набір, а не список: URI, спільний для кількох книг (один файл, побитий на
     * глави мітками), перевіряється один раз.
     */
    suspend fun inaccessibleUris(uris: Collection<String>): Set<String> {
        val distinct = uris.toSet()
        if (distinct.isEmpty()) return emptySet()
        return withContext(Dispatchers.IO) {
            val gate = Semaphore(ACCESS_CHECK_PARALLELISM)
            val checks = distinct.map { uri ->
                async { uri.takeIf { !gate.withPermit { isAccessibleBlocking(it) } } }
            }
            checks.awaitAll().filterNotNullTo(mutableSetOf())
        }
    }

    /**
     * Сама перевірка, без перемикання диспетчера: викликається вже з IO.
     *
     * Помилку тут не логуємо кожну окремо. Зниклий файл — очікуваний випадок цієї
     * функції, а не аномалія: коли зникає тека, у logcat летіла тисяча стектрейсів
     * (на вимірі вище — 463 за один скан), і вони гнали з журналу те, заради чого
     * журнал і заведено.
     */
    private fun isAccessibleBlocking(uriStr: String): Boolean = runCatching {
        val uri = uriStr.toUri()
        if (uri.scheme == "file") {
            java.io.File(uri.path ?: return@runCatching false).exists()
        } else {
            // openAssetFileDescriptor, а не query: спокусливо здається, що запит
            // дешевший — адже дескриптор справді відкриває файл. Виміряно на
            // 1398 файлах: запит із проєкцією `null` дав 3237 мс проти 1104 мс.
            // Провайдер на запит збирає всі колонки, а на відкриття зниклого
            // файла відмовляє одразу.
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }
    }.getOrDefault(false)

    suspend fun deleteBook(id: String) {
        withContext(Dispatchers.Main) { onBookDeleted(id) }
        val current = dao.getBook(id)
        // Спершу база, потім файли: якщо запис не вдасться, книга лишається цілою, а не
        // без обкладинки й копій аудіо. Так само робить злиття з хмарою.
        db.withTransaction {
            dao.deleteBook(id)
            // Надгробок їде в бекап, інакше книга повернеться з хмари наступним злиттям.
            dao.upsertTombstone(DeletedBookEntity(id, System.currentTimeMillis()))
        }
        releaseBookFiles(current, dao.getAllBooks())
        pruneTombstones()
    }

    /**
     * Побічні ефекти видалення книги: дозволи SAF, скопійовані файли, обкладинка.
     *
     * Окремо від запису в базу, бо злиття з хмарою робить свої видалення всередині
     * транзакції Room, а туди це не можна: [android.content.ContentResolver] і
     * файлова система — робота поза базою, і виконавець транзакції на ній зупиняється.
     * Тому при злитті база змінюється в транзакції, а сюди приходить уже після коміту.
     *
     * Файли, обкладинки та дозволи звільняються лише тоді, коли на них більше не
     * посилається жодна книга з [remaining] (спільний файл або відновлення полиці).
     */
    private fun releaseBookFiles(
        current: BookWithChapters?,
        remaining: List<BookWithChapters> = emptyList(),
    ) {
        if (current == null) return
        releaseBooksFiles(listOf(current), remaining)
    }

    private fun releaseBooksFiles(
        removed: List<BookWithChapters>,
        remaining: List<BookWithChapters>,
    ) {
        if (removed.isEmpty()) return
        val keptUris = remaining.asSequence()
            .flatMap { it.chapters.asSequence() }
            .mapTo(mutableSetOf()) { it.uri }
        val keptCovers = remaining.mapNotNullTo(mutableSetOf()) { it.book.coverPath }

        // distinct(): у m4b усі глави посилаються на один файл.
        removed.asSequence()
            .flatMap { it.chapters.asSequence() }
            .map { it.uri }
            .distinct()
            .filter { it !in keptUris }
            .forEach { uriStr ->
                runCatching {
                    val uri = uriStr.toUri()
                    when (uri.scheme) {
                        "content" -> context.contentResolver.releasePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        // Файли, які імпорт скопіював до себе (filesDir/imports, filesDir/demo),
                        // раніше лишалися назавжди — сховище повільно забивалося.
                        "file" -> uri.path?.let { path -> deleteIfOwnCopy(java.io.File(path)) }
                    }
                }
            }
        removed.mapNotNull { it.book.coverPath }
            .distinct()
            .filter { it !in keptCovers }
            .forEach { covers.delete(it) }
    }

    /** Надгробки старші за TOMBSTONE_TTL_DAYS уже нікому нічого не повідомляють. */
    private suspend fun pruneTombstones() {
        val ttlMs = TOMBSTONE_TTL_DAYS * 24 * 3600 * 1000L
        val horizon = System.currentTimeMillis() - ttlMs
        dao.pruneTombstones(horizon)
        // Той самий горизонт для персонажів: інакше їхні надгробки росли б у
        // бекапі без меж, хоча сенс мають лише поки хтось може не знати про
        // видалення.
        dao.pruneCharacterTombstones(horizon)
        dao.pruneBookmarkTombstones(horizon)
        dao.pruneTagTombstones(horizon)
    }

    /** Історія сесій — той самий горизонт, що й надгробки: інакше JSON росте без меж. */
    private suspend fun pruneSessions() {
        val ttlMs = TOMBSTONE_TTL_DAYS * 24 * 3600 * 1000L
        dao.pruneSessions(System.currentTimeMillis() - ttlMs)
    }

    /** Видаляє файл, лише якщо він лежить у приватному сховищі додатка. Чужих файлів не чіпаємо. */
    private fun deleteIfOwnCopy(file: java.io.File) {
        val owned = listOf(
            java.io.File(context.filesDir, "imports"),
            java.io.File(context.filesDir, "demo"),
        )
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
        val insideOwnStorage = owned.any { dir ->
            val dirPath = runCatching { dir.canonicalFile }.getOrNull() ?: return@any false
            canonical.path.startsWith(dirPath.path + java.io.File.separator)
        }
        if (insideOwnStorage) runCatching { canonical.delete() }
    }

    private fun cleanupOwnCopies(uris: List<String>) {
        uris.distinct().forEach { uriStr ->
            val uri = uriStr.toUri()
            if (uri.scheme == "file") uri.path?.let { deleteIfOwnCopy(java.io.File(it)) }
        }
    }

    suspend fun recordListening(
        deltaMs: Long,
        bookId: String? = null,
        bookTitle: String? = null,
        author: String? = null,
    ) {
        if (deltaMs <= 0) return
        val now = System.currentTimeMillis()
        dao.addListening(ListeningStats.dateKey(now), deltaMs)
        if (bookId != null && !bookTitle.isNullOrBlank()) {
            // Не insert, а «долити до останньої сесії, якщо вона ще триває»:
            // сюди приходить кожні 30 секунд відтворення, і рядок на кожен виклик
            // перетворював історію на стрічку одноманітних півхвилинних карток.
            dao.appendSession(
                newId = newId(),
                bookId = bookId,
                bookTitle = bookTitle,
                author = author.orEmpty(),
                now = now,
                deltaMs = deltaMs,
            )
        }
    }

    /**
     * Прибирання застарілої історії. Викликається на старті застосунку й перед
     * тим, як історія кудись їде (експорт, злиття з хмарою).
     *
     * Раніше pruneSessions() стояв усередині запису прослуховування, тобто
     * DELETE по таблиці виконувався кожні 30 секунд відтворення.
     */
    suspend fun pruneHistory() {
        pruneTombstones()
        pruneSessions()
    }

    // --- черга «далі» ------------------------------------------------------

    /** Книги з черги в збереженому порядку. Ті, що зникли з полиці, відсіює CASCADE. */
    suspend fun loadQueue(): List<BookWithChapters> = dao.getQueue()

    suspend fun saveQueue(bookIds: List<String>) = dao.replaceQueue(bookIds)

    fun observeRecentSessions(limit: Int = 100): Flow<List<ListeningSessionEntity>> =
        dao.observeRecentSessions(limit)

    suspend fun clearListeningHistory() {
        dao.deleteAllSessions()
    }

    /**
     * Статистика з датою, що не застигає.
     *
     * Раніше «сьогодні» обчислювалося один раз — у момент створення потоку, тобто
     * при створенні ViewModel. Застосунок, відкритий через північ, показував учорашню
     * цифру як сьогоднішню. Тепер межу дня рахує ListeningStats на кожній емісії,
     * а окремий потік будить перерахунок рівно опівночі.
     */
    fun observeWeeklyStats(): Flow<WeeklyStats> = observeListeningStats(StatsPeriod.Week)

    fun observeListeningStats(period: StatsPeriod): Flow<WeeklyStats> = combine(
        midnightTicker(),
        dao.observeAllDailyListening(),
        dao.observeCompletedBooksCount(),
    ) { now, rows, completedCount ->
        ListeningStats.summarize(rows, completedCount, now, period)
    }

    /** Емітує поточний час одразу, далі — на кожній локальній півночі. */
    private fun midnightTicker(): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            kotlinx.coroutines.delay(ListeningStats.millisUntilNextMidnight(now))
        }
    }

    private fun fallbacks() = BackupCodec.Fallbacks(
        unknownAuthor = context.forAppLocale().getString(R.string.unknown_author),
        chapterTitle = { n -> context.forAppLocale().getString(R.string.chapter_number, n) },
    )

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        pruneSessions()
        BackupCodec.encode(
            books = dao.getAllBooks(),
            bookmarks = dao.getAllBookmarks(),
            listening = dao.getAllDailyListening(),
            tombstones = dao.getAllTombstones(),
            tags = dao.getAllBookTags(),
            sessions = dao.getAllSessions(),
            characters = dao.getAllCharacters(),
            characterTombstones = dao.getAllCharacterTombstones(),
            bookmarkTombstones = dao.getAllBookmarkTombstones(),
            tagTombstones = dao.getAllTagTombstones(),
        )
    }

    /** Повна заміна полиці вмістом файла. Повертає false, якщо файл не є бекапом BookVoices. */
    suspend fun importBackupJson(jsonStr: String): Boolean {
        val payload = withContext(Dispatchers.IO) {
            runCatching { BackupCodec.decode(jsonStr, fallbacks()) }
                .onFailure { AppLog.w("importBackupJson: файл не є бекапом BookVoices", it) }
                .getOrNull()
        } ?: return false
        // Файл, у якому є записи книг, але жоден не придатний (чужий JSON з полем «books»,
        // зіпсований файл), не може стати «заміною полиці»: порожній результат стер би
        // наявні книги заради нічого. Порожній масив «books» — це справжня порожня копія.
        val rawBooks = runCatching { org.json.JSONObject(jsonStr).optJSONArray("books")?.length() ?: 0 }.getOrDefault(0)
        if (rawBooks > 0 && payload.books.isEmpty()) {
            AppLog.w("importBackupJson: у файлі $rawBooks записів книг, жоден непридатний — полицю не чіпаємо")
            return false
        }
        // Черга в бекап не їде — вона про цей телефон, а не про полицю. Але
        // replaceAll стирає книги, і queue_items порожніє за ним через CASCADE:
        // список «далі» зникав після кожного відновлення, навіть коли всі його
        // книги в файлі були. Запамʼятовуємо порядок і повертаємо тих, хто вижив.
        val booksBefore = runCatching { dao.getAllBooks() }.getOrDefault(emptyList())
        val queueBefore = runCatching { dao.getQueue().map { it.book.id } }.getOrDefault(emptyList())
        // Плеєр зупиняємо лише після успішного розбору: раніше зіпсований файл
        // все одно збивав відтворення, хоча полиця лишалася незмінною.
        onLibraryReset()
        val restored = withContext(Dispatchers.IO) {
            runCatching {
                dao.replaceAll(
                    payload.books,
                    payload.chapters,
                    payload.bookmarks,
                    payload.listening,
                    payload.tombstones,
                    payload.tags,
                    payload.sessions,
                    payload.characters,
                    payload.characterTombstones,
                    payload.bookmarkTombstones,
                    payload.tagTombstones,
                )
                val survived = payload.books.mapTo(mutableSetOf()) { it.id }
                dao.replaceQueue(queueBefore.filter { it in survived })
                releaseBooksFiles(booksBefore, dao.getAllBooks())
                true
            }.getOrDefault(false)
        }
        if (restored) onQueueChangedExternally()
        return restored
    }

    /**
     * Злиття з хмарою: беремо свіжіший прогрес, нові книги й нові закладки.
     *
     * Усе, що торкається бази, — в одній транзакції. Досі це була довга низка
     * окремих викликів DAO, і виняток посередині (зовнішній ключ, зіпсований запис)
     * лишав полицю напівзлитою: частина книг оновлена, надгробки застосовані,
     * закладки ще ні. Функція при цьому повертала false, тобто про напівстан ніхто
     * не дізнавався — а WebDavSyncWorker довго ще й вивантажував його в хмару.
     *
     * Видалення книг тут не кличе [deleteBook]: та функція стрибає на
     * `Dispatchers.Main` і чіпає файлову систему, а всередині транзакції Room це
     * зупиняє її виконавця. Тому база змінюється в транзакції, а плеєр і файли
     * отримують своє після коміту — і лише якщо коміт відбувся.
     */
    suspend fun mergeBackupJson(json: String): Boolean = withContext(Dispatchers.IO) {
        val payload = runCatching { BackupCodec.decode(json, fallbacks()) }
            .onFailure { AppLog.w("mergeBackupJson: не розібрано файл із хмари", it) }
            .getOrNull()
            ?: return@withContext false

        // Книги, які прибрали чужі надгробки. Читається лише після успішного коміту:
        // при відкоті список може бути заповнений, але жодного рядка не видалено.
        var removedByCloud: List<BookWithChapters> = emptyList()
        val coverRedraws = mutableListOf<Triple<String, String, String>>()

        val merged = runCatching {
            db.withTransaction {
                val chaptersByBook = payload.chapters.groupBy { it.bookId }
                val removed = mutableListOf<BookWithChapters>()

                // 1. Надгробки з хмари. Книга, видалена на іншому пристрої, зникає й тут —
                //    але лише якщо після того видалення її тут не слухали: інакше ми
                //    знищили б прогрес, про який той пристрій просто не знав.
                for (stone in payload.tombstones) {
                    val local = dao.getBook(stone.id)
                    if (local != null && BackupCodec.tombstoneWins(local.book, stone.deletedAt)) {
                        // Знімок до видалення: після нього ні глав, ні шляху обкладинки
                        // вже не дізнатися, а звільняти дозволи й файли треба саме за ними.
                        removed += local
                        dao.deleteBook(stone.id)
                        // Надгробок кладемо чужий, із початковим часом: власний, із
                        // поточним, робив би видалення молодшим на кожному пристрої.
                        dao.upsertTombstone(stone)
                    } else if (local == null) {
                        // Книги вже немає, але надгробок треба зберегти й передати далі.
                        dao.upsertTombstone(stone)
                    }
                }
                removedByCloud = removed

                // 2. Що видалено тут — те не повертаємо, навіть якщо воно ще є в бекапі.
                val deletedHere = dao.getAllTombstones().associate { it.id to it.deletedAt }
                val incomingBookIds = payload.books.mapTo(mutableSetOf()) { it.id }
                val unmatchedLocalBooks = dao.getAllBooks()
                    .filterNot { it.book.id in incomingBookIds }
                    .toMutableList()
                // Якщо ту саму книгу імпортували на двох пристроях окремо, у неї різні UUID і
                // різні локальні URI, але однакова назва й відбиток глав. Зливаємо прогрес,
                // закладки, мітки й персонажів у наявну локальну книгу замість створення
                // дубліката з чужими недоступними адресами файлів.
                val remappedBookIds = mutableMapOf<String, String>()
                val remappedChapterIds = mutableMapOf<String, String>()

                for (incoming in payload.books) {
                    val stone = deletedHere[incoming.id]
                    if (stone != null && (incoming.lastPlayedAt ?: 0L) <= stone) continue
                    val existing = dao.getBook(incoming.id)
                    val incomingChapters = chaptersByBook[incoming.id].orEmpty().sortedBy { it.index }
                    if (existing == null) {
                        val localDuplicate = if (incomingChapters.isNotEmpty()) {
                            DuplicateDetector.findIn(
                                books = unmatchedLocalBooks,
                                title = incoming.title,
                                chapterTitles = incomingChapters.map { it.title },
                                durations = incomingChapters.map { it.durationMs },
                                uris = incomingChapters.map { it.uri },
                            )
                        } else {
                            null
                        }
                        if (localDuplicate != null) {
                            unmatchedLocalBooks.remove(localDuplicate)
                            val targetBookId = localDuplicate.book.id
                            remappedBookIds[incoming.id] = targetBookId
                            val localChaptersByIndex = localDuplicate.chapters.associateBy { it.index }
                            for (ch in incomingChapters) {
                                localChaptersByIndex[ch.index]?.let { localCh ->
                                    remappedChapterIds[ch.id] = localCh.id
                                }
                            }
                            if (stone != null) dao.deleteTombstone(incoming.id)
                            mergeIdentityAndChapters(
                                local = localDuplicate,
                                incoming = incoming.copy(id = targetBookId),
                                incomingChapters = incomingChapters,
                                coverRedraws = coverRedraws,
                            )
                        } else {
                            // Книгу слухали після нашого видалення — інший пристрій має рацію.
                            if (stone != null) dao.deleteTombstone(incoming.id)
                            dao.upsertBook(incoming)
                            chaptersByBook[incoming.id]?.takeIf { it.isNotEmpty() }?.let { dao.upsertChapters(it) }
                        }
                    } else {
                        mergeIdentityAndChapters(
                            local = existing,
                            incoming = incoming,
                            incomingChapters = incomingChapters,
                            coverRedraws = coverRedraws,
                        )
                    }
                }
                // Книга могла щойно зникнути через надгробок: закладка без книги
                // порушила б зовнішній ключ і зірвала б усе злиття.
                val allPresentBooks = dao.getAllBooks()
                val presentBooks = allPresentBooks.mapTo(mutableSetOf()) { it.book.id }
                val chapterTitlesById = allPresentBooks.asSequence()
                    .flatMap { it.chapters.asSequence() }
                    .associate { it.id to it.title }
                // Надгробки закладок — та сама послідовність, що й для книг і
                // персонажів: спершу застосовуємо чужі видалення, потім пропустити те,
                // що видалили тут. Без цього злиття вміло лише додавати, і стерта
                // закладка поверталася з наступною синхронізацією.
                for (rawStone in payload.bookmarkTombstones) {
                    val stone = remappedBookIds[rawStone.bookId]?.let { rawStone.copy(bookId = it) } ?: rawStone
                    val local = dao.getBookmark(stone.id)
                    // createdAt — єдина мітка часу закладки: створена ПІСЛЯ видалення
                    // означає, що на іншому пристрої її додали заново під тим самим id.
                    if (local != null && local.createdAt <= stone.deletedAt) {
                        dao.deleteBookmark(stone.id)
                    }
                    dao.upsertBookmarkTombstone(stone)
                }
                val bookmarksDeletedHere = dao.getAllBookmarkTombstones().associate { it.id to it.deletedAt }

                for (rawMark in payload.bookmarks) {
                    val resolvedChapterId = remappedChapterIds[rawMark.chapterId] ?: rawMark.chapterId
                    val mark = rawMark.copy(
                        bookId = remappedBookIds[rawMark.bookId] ?: rawMark.bookId,
                        chapterId = resolvedChapterId,
                        chapterTitle = chapterTitlesById[resolvedChapterId] ?: rawMark.chapterTitle,
                    )
                    if (mark.bookId !in presentBooks) continue
                    val stone = bookmarksDeletedHere[mark.id]
                    if (stone != null) {
                        if (mark.createdAt <= stone) continue
                        // Створена після нашого видалення — надгробок більше не діє.
                        dao.deleteBookmarkTombstone(mark.id)
                    }
                    val existing = dao.getBookmark(mark.id)
                    when {
                        existing == null -> dao.upsertBookmark(mark)
                        existing.note.isBlank() && mark.note.isNotBlank() ->
                            dao.updateBookmarkNote(mark.id, mark.note)
                    }
                }
                // Надгробки міток. Арбітражу «додали заново» тут немає — у мітки
                // немає власної мітки часу, вона і є пара (книга, слово). Надгробок
                // знімає лише повторне ручне додавання, у LibraryRepository.addTag.
                for (rawStone in payload.tagTombstones) {
                    val stone = remappedBookIds[rawStone.bookId]?.let { rawStone.copy(bookId = it) } ?: rawStone
                    dao.deleteTag(stone.bookId, stone.tag)
                    dao.upsertTagTombstone(stone)
                }
                val tagsDeletedHere = dao.getAllTagTombstones()
                    .mapTo(mutableSetOf()) { it.bookId to it.tag }

                for (rawTag in payload.tags) {
                    val tag = remappedBookIds[rawTag.bookId]?.let { rawTag.copy(bookId = it) } ?: rawTag
                    if (tag.bookId !in presentBooks) continue
                    if ((tag.bookId to tag.tag) in tagsDeletedHere) continue
                    dao.insertTag(tag)
                }
                // Персонажі — як мітки: id стабільний, тож REPLACE ідемпотентний, а
                // книга має існувати, інакше зовнішній ключ зірве всю транзакцію.
                // Раніше цього циклу не було зовсім: список дійових осіб їхав у
                // бекап, відновлювався з файла — і зникав при злитті з хмарою.
                //
                // Надгробки персонажів — дзеркало логіки для книг вище. Спершу
                // застосовуємо чужі видалення, потім пропускаємо те, що видалили тут:
                // без цього злиття вміло лише додавати, і прибраний персонаж
                // повертався з наступною синхронізацією.
                for (rawStone in payload.characterTombstones) {
                    val stone = remappedBookIds[rawStone.bookId]?.let { rawStone.copy(bookId = it) } ?: rawStone
                    val local = dao.getCharacter(stone.id)
                    if (local != null && BackupCodec.characterTombstoneWins(local, stone.deletedAt)) {
                        dao.deleteCharacter(stone.id)
                    }
                    // Надгробок зберігаємо в обох випадках: він має поїхати далі, навіть
                    // якщо запису тут уже немає.
                    dao.upsertCharacterTombstone(stone)
                }

                val charactersDeletedHere = dao.getAllCharacterTombstones().associate { it.id to it.deletedAt }
                for (rawCharacter in payload.characters) {
                    val character = remappedBookIds[rawCharacter.bookId]?.let { rawCharacter.copy(bookId = it) } ?: rawCharacter
                    if (character.bookId !in presentBooks) continue
                    val stone = charactersDeletedHere[character.id]
                    if (stone != null) {
                        if (BackupCodec.characterTombstoneWins(character, stone)) continue
                        // Створений після нашого видалення — інший пристрій додав його
                        // заново, надгробок більше не діє.
                        dao.deleteCharacterTombstone(character.id)
                    }
                    dao.upsertCharacter(character)
                }
                for (rawSession in payload.sessions) {
                    val session = remappedBookIds[rawSession.bookId]?.let { rawSession.copy(bookId = it) } ?: rawSession
                    dao.insertSession(session)
                }
                // Денна статистика: за кожну дату лишаємо більше з двох чисел.
                // Сума була б неідемпотентною — повторне злиття того самого файла
                // подвоювало б тиждень; а не брати нічого означало, що «за тиждень»
                // на полиці розходився з іншим пристроєм після кожного завантаження.
                // Точної суми не знає жоден бік: обидва рахували лише себе.
                val localListening = dao.getAllDailyListening().associate { it.date to it.durationMs }
                for (row in payload.listening) {
                    val local = localListening[row.date] ?: 0L
                    if (row.durationMs > local) dao.upsertDailyListening(row)
                }
                pruneTombstones()
                pruneSessions()
                true
            }
        }.onFailure {
            AppLog.w("mergeBackupJson: злиття скасовано, база лишилася без змін", it)
        }.getOrDefault(false)

        if (!merged) return@withContext false

        for ((id, title, author) in coverRedraws) {
            val fresh = dao.getBook(id) ?: continue
            if (!covers.isGenerated(fresh.book.coverPath)) continue
            val redrawn = runCatching { covers.generate(id, title, author) }.getOrNull() ?: continue
            val old = fresh.book.coverPath
            dao.updateBook(fresh.book.copy(coverPath = redrawn))
            if (old != null && old != redrawn) covers.delete(old)
        }

        // Тільки після коміту. Якщо транзакція відкотилася, книги на місці — і
        // файли з дозволами теж, тобто нічого не загублено й видалити їх можна
        // наступного разу.
        if (removedByCloud.isNotEmpty()) {
            val remaining = dao.getAllBooks()
            for (book in removedByCloud) {
                withContext(Dispatchers.Main) { onBookDeleted(book.book.id) }
            }
            releaseBooksFiles(removedByCloud, remaining)
        }
        true
    }

    /**
     * Назви глав і поля книги разом.
     *
     * Якщо розділів різна кількість, новіші назви глав не куди класти. Мітку
     * `renamedAt` тоді не просуваємо, доки не змінилися ще й назва чи автор:
     * інакше наступний синк із уже однаковою кількістю розділів вважав би
     * перейменування застосованим і ніколи б його не переніс.
     * [coverRedraws] — згенеровані обкладинки, які треба перемалювати вже після коміту.
     */
    private suspend fun mergeIdentityAndChapters(
        local: BookWithChapters,
        incoming: BookEntity,
        incomingChapters: List<ChapterEntity>,
        coverRedraws: MutableList<Triple<String, String, String>>,
    ) {
        val stampBlocked = mergeChapterTitlesIfNewer(local, incoming, incomingChapters)
        val incomingBook = if (
            stampBlocked &&
            incoming.title == local.book.title &&
            incoming.author == local.book.author
        ) {
            incoming.copy(renamedAt = local.book.renamedAt)
        } else {
            incoming
        }
        val merged = BackupCodec.mergeBook(local.book, incomingBook) ?: return
        dao.updateBook(merged)
        if ((merged.title != local.book.title || merged.author != local.book.author) &&
            covers.isGenerated(local.book.coverPath)
        ) {
            coverRedraws.add(Triple(merged.id, merged.title, merged.author))
        }
    }

    /**
     * Переносить новіші назви глав з іншого пристрою за їхнім номером, не чіпаючи
     * локальних id, URI файлів і структури книги.
     *
     * @return true, якщо мітка новіша, але назви не перенесено через різну кількість розділів.
     */
    private suspend fun mergeChapterTitlesIfNewer(
        local: BookWithChapters,
        incoming: BookEntity,
        incomingChapters: List<ChapterEntity>,
    ): Boolean {
        if (incoming.renamedAt <= local.book.renamedAt) return false
        if (incomingChapters.size != local.chapters.size) return true
        val incomingByIndex = incomingChapters.associateBy { it.index }
        val wanted = local.chapters.mapNotNull { localCh ->
            val remoteTitle = incomingByIndex[localCh.index]?.title?.let(ChapterNaming::clean) ?: return@mapNotNull null
            localCh.id to remoteTitle
        }.toMap()
        val changes = ChapterNaming.changes(local.chapters, wanted)
        if (changes.isNotEmpty()) {
            dao.renameChapters(local.book.id, changes, renamedAt = incoming.renamedAt)
        }
        return false
    }

    suspend fun setPinned(bookId: String, pinned: Boolean) {
        dao.setPinned(bookId, pinned)
    }

    /**
     * Позначка «дослухано» вручну.
     *
     * Доти цей прапорець ставило рівно одне місце — плеєр, який дійшов до кінця
     * останньої глави. На ньому тримаються фільтр «Дослухані», лічильник на
     * полиці й статус картки в Android Auto, тобто книгу, дослухану деінде або
     * покинуту на останніх хвилинах, у застосунку не можна було закрити ніяк.
     *
     * Позиція не чіпається: якщо позначку знімуть, слухач має повернутися туди,
     * де справді був. `lastPlayedAt` проставляється, бо дослухана книга без
     * жодної позначки часу виглядала б у машині як «не починали».
     */
    suspend fun setCompleted(bookId: String, completed: Boolean) {
        dao.updateCompleted(bookId, completed, System.currentTimeMillis())
    }

    suspend fun batchSetPinned(bookIds: List<String>, pinned: Boolean) {
        dao.batchSetPinned(bookIds, pinned)
    }

    suspend fun updateSeries(bookId: String, series: String?, seriesOrder: Float?) {
        dao.updateSeries(bookId, series?.trim()?.ifBlank { null }, seriesOrder)
    }

    /**
      * Пакетне видалення — це N звичайних видалень, а не окремий шлях.
      *
      * Раніше тут був свій код: надгробок, мітки, DELETE ... WHERE id IN (...).
      * Він не звільняв постійні дозволи SAF, не прибирав копії аудіо з
      * filesDir/imports і не видаляв обкладинки — тобто «видалити 20 книг»
      * лишало по собі 20 файлів обкладинок, копії аудіо й 20 утримуваних
      * дозволів. Дозволів у системи скінченний запас: коли він вичерпається,
      * імпорт теки мовчки перестане переживати перезавантаження.
      *
      * Один SQL замість N того не вартий: основна ціна тут — файлові операції,
      * а вони поштучні в будь-якому разі.
      */
    suspend fun batchDeleteBooks(bookIds: List<String>) {
        // Збій на одній книзі (файл не видалився, плеєр не зупинився) не має зупиняти решту: інакше
        // «видалити 20 книг» мовчки видаляло 7 і зупинялось, а виняток губився в launchSafely.
        var firstFailure: Throwable? = null
        for (id in bookIds) {
            try {
                deleteBook(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.w("batchDeleteBooks: книгу $id не вдалося видалити, ідемо далі", e)
                if (firstFailure == null) firstFailure = e
            }
        }
        // Про збій усе одно повідомляємо — але після того, як спробували всіх.
        firstFailure?.let { throw it }
    }

    suspend fun batchAssignTag(bookIds: List<String>, tag: String) {
        val cleanTag = tag.trim()
        if (cleanTag.isBlank()) return
        val entities = bookIds.map { BookTagEntity(it, cleanTag) }
        dao.batchInsertTags(entities)
    }

    fun observeAllSeries(): Flow<List<String>> = dao.observeAllSeries()

    fun search(
        books: List<BookWithChapters>,
        query: String,
        filter: LibraryFilter,
        sortOrder: BookSortOrder = BookSortOrder.LastPlayed,
        selectedTag: String? = null,
        bookTagsMap: Map<String, Set<String>> = emptyMap(),
        selectedSeries: String? = null,
        missingBookIds: Set<String> = emptySet(),
    ): List<BookWithChapters> = ShelfQuery.apply(
        books, query, filter, sortOrder, selectedTag, bookTagsMap, selectedSeries, missingBookIds,
    )

    suspend fun exportCatalogMarkdown(): String = withContext(Dispatchers.IO) {
        val books = dao.getAllBooks().sortedWith(compareBy({ it.book.author }, { it.book.title }))
        val now = java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val sb = StringBuilder()
        sb.appendLine("# ${context.forAppLocale().getString(R.string.export_catalog_title)}")
        sb.appendLine("${context.forAppLocale().getString(R.string.export_catalog_exported_at, now)}\n")

        val totalDurationMs = books.sumOf { it.book.durationMs }
        val totalHours = totalDurationMs / (1000 * 60 * 60)
        val totalMins = (totalDurationMs / (1000 * 60)) % 60
        val durFormatted = if (totalHours > 0) "${totalHours} ${context.forAppLocale().getString(R.string.unit_hours_short)} ${totalMins} ${context.forAppLocale().getString(R.string.unit_minutes_short)}" else "${totalMins} ${context.forAppLocale().getString(R.string.unit_minutes_short)}"

        sb.appendLine("- **${context.forAppLocale().getString(R.string.export_catalog_total_books, books.size)}**")
        sb.appendLine("- **${context.forAppLocale().getString(R.string.export_catalog_total_duration, durFormatted)}**\n")
        sb.appendLine("---")
        sb.appendLine()

        var currentAuthor = ""
        for (book in books) {
            val author = book.book.author.ifBlank { context.forAppLocale().getString(R.string.unknown_author) }
            if (author != currentAuthor) {
                currentAuthor = author
                sb.appendLine("## $currentAuthor\n")
            }
            val progressPercent = (book.progress() * 100).toInt()
            val status = if (book.book.completed) "✅ ${context.forAppLocale().getString(R.string.filter_finished)}" else "🎧 $progressPercent%"
            val seriesInfo = if (!book.book.series.isNullOrBlank()) {
                val order = book.book.seriesOrder?.let { " #$it" }.orEmpty()
                " [${book.book.series}$order]"
            } else ""
            val h = book.book.durationMs / (1000 * 60 * 60)
            val m = (book.book.durationMs / (1000 * 60)) % 60
            val dur = if (h > 0) "${h} ${context.forAppLocale().getString(R.string.unit_hours_short)} ${m} ${context.forAppLocale().getString(R.string.unit_minutes_short)}" else "${m} ${context.forAppLocale().getString(R.string.unit_minutes_short)}"

            sb.appendLine("- **${book.book.title}**$seriesInfo — $dur ($status)")
        }
        sb.toString()
    }

    suspend fun exportHistoryCsv(): String = withContext(Dispatchers.IO) {
        ListeningStats.exportHistoryCsv(
            sessions = dao.getAllSessions(),
            daily = dao.getAllDailyListening(),
        )
    }

    suspend fun exportBookmarksMarkdown(): String = withContext(Dispatchers.IO) {
        val bookmarks = dao.getAllBookmarks()
        val booksById = dao.getAllBooks().associateBy { it.book.id }
        val now = java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val sb = StringBuilder()
        sb.appendLine("# ${context.forAppLocale().getString(R.string.export_bookmarks_title)}")
        sb.appendLine("${context.forAppLocale().getString(R.string.export_catalog_exported_at, now)}\n")
        if (bookmarks.isEmpty()) {
            sb.appendLine(context.forAppLocale().getString(R.string.no_bookmarks))
            return@withContext sb.toString()
        }
        sb.appendLine("- **${context.forAppLocale().getString(R.string.export_bookmarks_total, bookmarks.size)}**\n")
        sb.appendLine("---\n")
        val grouped = bookmarks.groupBy { it.bookId }
        for ((bookId, marks) in grouped) {
            val book = booksById[bookId]
            val title = book?.book?.title ?: marks.first().chapterTitle
            val author = book?.book?.author?.takeIf { it.isNotBlank() }
            sb.appendLine("## $title")
            if (!author.isNullOrBlank()) sb.appendLine("*$author*\n")
            marks.sortedByDescending { it.createdAt }.forEach { mark ->
                val note = if (mark.note.isNotBlank()) " — «${mark.note}»" else ""
                val pos = mark.positionMs.formatClock()
                sb.appendLine("- **${mark.chapterTitle}** ($pos)$note")
            }
            sb.appendLine()
        }
        sb.toString()
    }

    /**
     * Експортує повний конспект однієї книги в Markdown: метадані, прогрес,
     * мітки, особисті нотатки, персонажів та закладки за порядком розділів.
     */
    suspend fun exportBookSummaryMarkdown(bookId: String): String? = withContext(Dispatchers.IO) {
        val item = dao.getBook(bookId) ?: return@withContext null
        val book = item.book
        val tags = dao.getTagsForBook(bookId)
        val characters = dao.getAllCharacters().filter { it.bookId == bookId }
        val bookmarks = dao.getAllBookmarks().filter { it.bookId == bookId }
        val chapterOrder = item.chapters.associate { it.id to it.index }

        val sb = StringBuilder()
        sb.appendLine("# ${book.title}")
        val author = book.author.ifBlank { context.forAppLocale().getString(R.string.unknown_author) }
        sb.appendLine("*$author*")
        if (!book.series.isNullOrBlank()) {
            val order = book.seriesOrder?.let {
                val num = if (it % 1f == 0f) it.toInt().toString() else it.toString()
                " #$num"
            }.orEmpty()
            sb.appendLine("${context.forAppLocale().getString(R.string.series)}: **${book.series}$order**")
        }
        val progressPercent = (item.progress() * 100).toInt()
        val status = if (book.completed) "✅ ${context.forAppLocale().getString(R.string.filter_finished)}" else "🎧 $progressPercent%"
        sb.appendLine("${context.forAppLocale().getString(R.string.author_stats_duration)}: ${book.durationMs.formatClock()} ($status)")
        if (tags.isNotEmpty()) {
            sb.appendLine(tags.joinToString(" ") { "#$it" })
        }
        sb.appendLine()

        if (!book.notes.isNullOrBlank()) {
            sb.appendLine("## ${context.forAppLocale().getString(R.string.book_notes_title)}")
            sb.appendLine(book.notes.trim())
            sb.appendLine()
        }

        if (characters.isNotEmpty()) {
            sb.appendLine("## ${context.forAppLocale().getString(R.string.characters_title)}")
            for (ch in characters) {
                val rolePart = ch.role?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                val descPart = ch.description?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                sb.appendLine("- **${ch.name}**$rolePart$descPart")
            }
            sb.appendLine()
        }

        if (bookmarks.isNotEmpty()) {
            sb.appendLine("## ${context.forAppLocale().getString(R.string.bookmarks_title)}")
            val sortedMarks = bookmarks.sortedWith(
                compareBy<BookmarkEntity> { chapterOrder[it.chapterId] ?: Int.MAX_VALUE }
                    .thenBy { it.positionMs }
                    .thenBy { it.createdAt },
            )
            for (mark in sortedMarks) {
                val chapterPart = mark.chapterTitle.ifBlank { context.forAppLocale().getString(R.string.chapter_word) }
                val note = if (mark.note.isNotBlank()) " — «${mark.note}»" else ""
                sb.appendLine("- **$chapterPart** (${mark.positionMs.formatClock()})$note")
            }
            sb.appendLine()
        }

        sb.toString().trimEnd() + "\n"
    }
    private companion object {
        /**
         * Скільки перевірок доступності тримати в польоті одночасно.
         *
         * Вісім, а не «скільки є»: типовий пул Binder-потоків — 16 на процес, і
         * ділити його наполовину з рештою застосунку вистачає, щоб сховати
         * затримку IPC, але не вистачає, щоб його вичерпати.
         */
        private const val ACCESS_CHECK_PARALLELISM = 8
    }

}


