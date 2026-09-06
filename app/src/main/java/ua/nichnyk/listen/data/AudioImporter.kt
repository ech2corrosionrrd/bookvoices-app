package ua.nichnyk.listen.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import ua.nichnyk.listen.AppLog
import ua.nichnyk.listen.R

data class ImportedChapter(
    val title: String,
    val uri: String,
    val durationMs: Long,
    val metaTitle: String?,
    val metaAuthor: String?,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
)

/**
 * Обкладинка описується посиланням, а не байтами.
 *
 * Раніше кожен ImportedChapter ніс власний embeddedCover (~2 МБ), хоча
 * використовувалася лише перша обкладинка книги. На великій теці це давало
 * сотні мегабайтів марного сміття і OutOfMemoryError просто в getEmbeddedPicture().
 * Тепер байти читаються по одній обкладинці за раз, уже під час запису книги в БД.
 */
data class ImportDraft(
    val title: String,
    val author: String,
    val chapters: List<ImportedChapter>,
    /** Аудіофайл, з якого пробувати витягти вбудовану обкладинку. */
    val coverFromAudio: String?,
    /** Окремий файл обкладинки в теці книги. */
    val coverImageUri: String?,
    /** Дерево SAF, з якого зібрано чернетку (лише імпорт/доливання папки). */
    val sourceTreeUri: String? = null,
    /** Тека цієї книги всередині [sourceTreeUri]; див. [BookEntity.sourceFolderDocId]. */
    val sourceFolderDocId: String? = null,
)

data class ImportProgress(
    val done: Int = 0,
    val total: Int = 0,
    val currentName: String? = null,
    val scanning: Boolean = false,
)

/**
 * Кандидат на нову книгу в головній папці бібліотеки (легкий скан без метаданих).
 */
data class LibraryBookCandidate(
    val treeUri: String,
    val folderDocId: String,
    val title: String,
    val author: String,
    val audioUris: List<String>,
)

class AudioImporter(private val context: Context) {

    suspend fun fromUris(
        uris: List<Uri>,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportDraft = withContext(Dispatchers.IO) {
        // Імена знімаємо до копіювання: ACTION_SEND дає file:// з UUID, і сортування після
        // persistOrCopy розкладає розділи в випадковому порядку.
        val rawEntries = uris.map { uri ->
            coroutineContext.ensureActive()
            uri to displayName(uri).orEmpty()
        }
        val imageEntries = rawEntries.filter { (uri, name) -> isImageName(name) || isImageMime(context.contentResolver.getType(uri)) }
        val bestImageUri = imageEntries.minByOrNull { (_, name) ->
            val n = name.lowercase()
            when {
                n.contains("cover") -> 0
                n.contains("folder") -> 1
                n.contains("front") -> 2
                else -> 10
            }
        }?.first?.toString()

        val audioEntries = rawEntries.filterNot { (uri, name) -> isImageName(name) || isImageMime(context.contentResolver.getType(uri)) }
        val ordered = (if (audioEntries.isNotEmpty()) audioEntries else rawEntries)
            .sortedWith { a, b -> naturalCompare(a.second, b.second) }
        val copied = mutableListOf<File>()
        try {
            val chapters = ordered.flatMapIndexed { index, (uri, name) ->
                coroutineContext.ensureActive()
                onProgress(ImportProgress(index, ordered.size, name.ifBlank { null }))
                val resolved = persistOrCopy(uri, name)
                resolved.copiedFile?.let { copied += it }
                readExpanded(resolved.uri, name.ifBlank { null })
            }
            onProgress(ImportProgress(ordered.size, ordered.size))
            toDraft(chapters, bestImageUri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            copied.forEach { runCatching { it.delete() } }
            throw e
        }
    }

    /**
     * Імпорт теки. Повертає список книг, а не одну.
     *
     * Правило те саме, що в хмарному імпортері, і застосовується на кожному рівні:
     * аудіо прямо в теці — ця тека є книгою (разом із «Бонусами»); лише підпапки-диски
     * (CD1, Диск 2) — теж одна книга; інакше спускаємося в кожну підпапку.
     * Раніше різався лише перший рівень від вибору, тож Автор/Місто і Автор/Драма
     * злипалися в одну книгу, а вказівка на книгу з CD1/CD2 давала дві книги-диски.
     */
    suspend fun fromTree(
        treeUri: Uri,
        onProgress: (ImportProgress) -> Unit = {},
    ): List<ImportDraft> = withContext(Dispatchers.IO) {
        // Дозвіл беремо на саме дерево: усі документи всередині лишаються доступними
        // разом із ним. Раніше дозвіл перевірявся для кожного дочірнього URI окремо,
        // той завжди відрізнявся від збереженого — і кожен файл копіювався
        // у сховище застосунку, хоча копіювати не було потреби.
        val treePersisted = persist(treeUri)
        onProgress(ImportProgress(scanning = true))
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: error(context.getString(R.string.import_folder_failed))

        val audioFiles = mutableListOf<TreeEntry>()
        val imageFiles = mutableListOf<TreeEntry>()
        collectTree(treeUri, rootId, relative = "", depth = 0, audio = audioFiles, images = imageFiles)
        if (audioFiles.isEmpty()) error(context.getString(R.string.import_folder_empty))

        val bookFolders = findBookFolders(audioFiles.map { it.relativePath })
        val ordered = audioFiles.sortedWith { a, b -> naturalCompare(a.relativePath, b.relativePath) }
        val groups = ordered.groupBy { groupKey(it.relativePath, bookFolders) }
        val imagesByGroup = imageFiles.groupBy { groupKey(it.relativePath, bookFolders) }
        val rootName = documentName(treeUri, rootId)?.substringBeforeLast('.')?.ifBlank { null }

        val copied = java.util.Collections.synchronizedList(mutableListOf<File>())
        val total = ordered.size
        val doneBefore = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val drafts = groups.map { (key, entries) ->
                coroutineContext.ensureActive()
                // Прогрес рахуємо наскрізно по всіх книгах, інакше лічильник
                // скидався б на кожній підпапці.
                val chapters = readChapters(entries, treePersisted, copied, total, doneBefore, onProgress)
                val cover = findBestFolderCover(imagesByGroup[key].orEmpty())
                    ?: findBestFolderCover(imagesByGroup[""].orEmpty())
                val draft = toDraft(chapters, cover)
                val (folderAuthor, folderTitle) = parseFolderAuthorAndTitle(key, rootName)
                val resolvedTitle = folderTitle ?: draft.title
                val resolvedAuthor = if ((draft.author == context.getString(R.string.unknown_author) || draft.author.isBlank()) && !folderAuthor.isNullOrBlank()) {
                    folderAuthor
                } else {
                    draft.author
                }
                // Тека саме цієї книги, не корінь бібліотеки: інакше авто-скан
                // бачив би сусідні томи під тим самим tree grant.
                val folderDocId = resolveFolderDocumentId(treeUri, rootId, key)
                draft.copy(
                    title = resolvedTitle,
                    author = resolvedAuthor,
                    sourceTreeUri = treeUri.toString(),
                    sourceFolderDocId = folderDocId,
                )
            }
            onProgress(ImportProgress(total, total))
            drafts
        } catch (e: kotlinx.coroutines.CancellationException) {
            copied.forEach { runCatching { it.delete() } }
            throw e
        }
    }

    /**
     * Усі аудіофайли в теці, рекурсивно — для перепривʼязки й авто-скану.
     *
     * [folderDocumentId] обмежує обхід підпапкою книги всередині збереженого
     * дерева. Без нього — від кореня [treeUri] (користувач обрав саме цю теку).
     * Метадані тут не читаються: потрібні лише імена й адреси.
     */
    suspend fun listAudioFiles(
        treeUri: Uri,
        folderDocumentId: String? = null,
    ): List<RelinkMatcher.Candidate> = withContext(Dispatchers.IO) {
        persist(treeUri)
        val rootId = folderDocumentId
            ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: error(context.getString(R.string.import_folder_failed))
        val audio = mutableListOf<TreeEntry>()
        val images = mutableListOf<TreeEntry>()
        collectTree(treeUri, rootId, relative = "", depth = 0, audio = audio, images = images)
        audio.map {
            RelinkMatcher.Candidate(
                name = it.name,
                uri = it.uri.toString(),
                relativePath = it.relativePath,
            )
        }
    }

    /** Чи лишається чинним збережений користувачем tree grant. */
    fun isTreePermissionGranted(treeUri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri == treeUri
        }
    }

    /** Зберегти дозвіл на дерево (для головних папок бібліотеки без миттєвого імпорту). */
    fun takeTreePermission(treeUri: Uri): Boolean = persist(treeUri)

    /**
     * Легкий перелік книг у дереві: імена тек і URI файлів, без MediaMetadataRetriever.
     * Для авто-скану «чи зʼявилися нові книги» на полиці.
     */
    suspend fun listBookCandidates(treeUri: Uri): List<LibraryBookCandidate> = withContext(Dispatchers.IO) {
        persist(treeUri)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: error(context.getString(R.string.import_folder_failed))
        val audio = mutableListOf<TreeEntry>()
        val images = mutableListOf<TreeEntry>()
        collectTree(treeUri, rootId, relative = "", depth = 0, audio = audio, images = images)
        if (audio.isEmpty()) return@withContext emptyList()
        val bookFolders = findBookFolders(audio.map { it.relativePath })
        val rootName = documentName(treeUri, rootId)?.substringBeforeLast('.')?.ifBlank { null }
        val unknown = context.getString(R.string.unknown_author)
        audio.groupBy { groupKey(it.relativePath, bookFolders) }.mapNotNull { (key, entries) ->
            val folderDocId = resolveFolderDocumentId(treeUri, rootId, key) ?: return@mapNotNull null
            val (folderAuthor, folderTitle) = parseFolderAuthorAndTitle(key, rootName)
            LibraryBookCandidate(
                treeUri = treeUri.toString(),
                folderDocId = folderDocId,
                title = folderTitle ?: entries.first().name.substringBeforeLast('.'),
                author = folderAuthor ?: unknown,
                audioUris = entries.map { it.uri.toString() },
            )
        }
    }

    /**
     * Повний імпорт однієї підпапки книги всередині вже дозволеного дерева.
     */
    suspend fun draftFromFolder(
        treeUri: Uri,
        folderDocId: String,
        title: String,
        author: String,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportDraft = withContext(Dispatchers.IO) {
        val treePersisted = persist(treeUri)
        onProgress(ImportProgress(scanning = true))
        val audio = mutableListOf<TreeEntry>()
        val images = mutableListOf<TreeEntry>()
        collectTree(treeUri, folderDocId, relative = "", depth = 0, audio = audio, images = images)
        if (audio.isEmpty()) error(context.getString(R.string.import_folder_empty))
        val ordered = audio.sortedWith { a, b -> naturalCompare(a.relativePath, b.relativePath) }
        val copied = java.util.Collections.synchronizedList(mutableListOf<File>())
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val chapters = readChapters(ordered, treePersisted, copied, ordered.size, done, onProgress)
            val cover = findBestFolderCover(images)
            toDraft(chapters, cover).copy(
                title = title,
                author = author,
                sourceTreeUri = treeUri.toString(),
                sourceFolderDocId = folderDocId,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            copied.forEach { runCatching { it.delete() } }
            throw e
        }
    }

    /**
     * Document id підпапки [relativeKey] від кореня дерева.
     * Порожній ключ — сам корінь (одна книга = обрана тека).
     * Якщо сегмент не знайдено — `null`: краще без авто-скану, ніж скан ширший
     * за теку книги.
     */
    private fun resolveFolderDocumentId(treeUri: Uri, rootId: String, relativeKey: String): String? {
        if (relativeKey.isEmpty()) return rootId
        var docId = rootId
        for (segment in relativeKey.split('/').filter { it.isNotEmpty() }) {
            val child = listChildren(treeUri, docId, relative = "")
                .firstOrNull { it.isDirectory && it.name == segment }
                ?: return null
            docId = runCatching { DocumentsContract.getDocumentId(child.uri) }.getOrNull()
                ?: return null
        }
        return docId
    }

    /**
     * Один запис із SAF-курсора. Усе потрібне (ім'я, MIME, тип) зчитується одним
     * запитом на теку — DocumentFile робив окремий запит на кожну властивість
     * кожного файла, тобто ~4 IPC на файл замість одного на теку.
     */
    private data class TreeEntry(
        val uri: Uri,
        val name: String,
        val mime: String?,
        val isDirectory: Boolean,
        val relativePath: String,
    )

    private fun listChildren(treeUri: Uri, parentDocumentId: String, relative: String): List<TreeEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = mutableListOf<TreeEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIdx < 0) return@use
                while (c.moveToNext()) {
                    val docId = c.getString(idIdx) ?: continue
                    val name = (if (nameIdx >= 0) c.getString(nameIdx) else null).orEmpty()
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                    out += TreeEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name = name,
                        mime = mime,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        relativePath = if (relative.isEmpty()) name else "$relative/$name",
                    )
                }
            }
        }
        return out
    }

    private fun documentName(treeUri: Uri, documentId: String): String? = runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private suspend fun collectTree(
        treeUri: Uri,
        documentId: String,
        relative: String,
        depth: Int,
        audio: MutableList<TreeEntry>,
        images: MutableList<TreeEntry>,
    ) {
        if (depth > 8) return
        coroutineContext.ensureActive()
        for (entry in listChildren(treeUri, documentId, relative)) {
            coroutineContext.ensureActive()
            when {
                entry.isDirectory -> {
                    val childId = runCatching { DocumentsContract.getDocumentId(entry.uri) }.getOrNull()
                    if (childId != null) {
                        collectTree(treeUri, childId, entry.relativePath, depth + 1, audio, images)
                    }
                }
                isAudioMime(entry.mime) || isAudioName(entry.name) -> audio += entry
                isImageMime(entry.mime) || isImageName(entry.name) -> images += entry
            }
        }
    }

    /**
     * Метадані читаються паралельно: MediaMetadataRetriever на кожен файл — це
     * основний час імпорту, і послідовно він масштабується лінійно з кількістю розділів.
     * Паралелізм обмежений, бо кожен retriever тримає буфери.
     */
    private suspend fun readChapters(
        ordered: List<TreeEntry>,
        treePersisted: Boolean,
        copied: MutableList<File>,
        total: Int,
        done: java.util.concurrent.atomic.AtomicInteger,
        onProgress: (ImportProgress) -> Unit,
    ): List<ImportedChapter> = coroutineScope {
        val gate = Semaphore(METADATA_PARALLELISM)
        ordered.map { entry ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    coroutineContext.ensureActive()
                    val resolved = if (treePersisted) {
                        ResolvedAudio(entry.uri)
                    } else {
                        persistOrCopy(entry.uri, entry.name)
                    }
                    resolved.copiedFile?.let { copied += it }
                    val chapters = readExpanded(resolved.uri, entry.name)
                    onProgress(ImportProgress(done.incrementAndGet(), total, entry.name))
                    chapters
                }
            }
        }.awaitAll().flatten()
    }

    private fun findBestFolderCover(images: List<TreeEntry>): String? {
        if (images.isEmpty()) return null
        val best = images.minByOrNull { entry ->
            val name = entry.name.lowercase()
            when {
                name.contains("cover") -> 0
                name.contains("folder") -> 1
                name.contains("front") -> 2
                name.contains("album") -> 3
                name.contains("art") -> 4
                else -> 10
            }
        } ?: return null
        return best.uri.toString()
    }

    private fun toDraft(chapters: List<ImportedChapter>, folderImageUri: String? = null): ImportDraft {
        require(chapters.isNotEmpty()) { context.getString(R.string.import_audio_failed) }
        val firstChapter = chapters.first()
        val defaultUnknown = context.getString(R.string.unknown_author)
        var author = chapters.mapNotNull { it.metaAuthor }.firstOrNull { it.isNotBlank() }
        var title = firstChapter.metaTitle?.takeIf { !it.isNullOrBlank() }

        if (author.isNullOrBlank()) {
            val (fileAuthor, fileTitle) = parseFolderAuthorAndTitle("", firstChapter.title)
            if (!fileAuthor.isNullOrBlank()) {
                author = fileAuthor
                if (title.isNullOrBlank() && !fileTitle.isNullOrBlank()) {
                    title = fileTitle
                }
            }
        }
        if (title.isNullOrBlank()) {
            title = firstChapter.title.substringBeforeLast('.')
        }

        val safeTitle = title.ifBlank { context.getString(R.string.new_book_title) }
        val safeAuthor = author?.ifBlank { defaultUnknown } ?: defaultUnknown
        return ImportDraft(
            title = safeTitle,
            author = safeAuthor,
            chapters = chapters,
            coverFromAudio = firstChapter.uri,
            coverImageUri = folderImageUri,
        )
    }

    /**
     * Байти обкладинки — рівно один раз, під час запису книги.
     * Спершу вбудована в перший розділ, потім окремий файл у теці книги.
     */
    suspend fun loadCover(draft: ImportDraft): ByteArray? = withContext(Dispatchers.IO) {
        draft.coverFromAudio?.let { uri ->
            val embedded = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri.toUri())
                    retriever.embeddedPicture
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
            if (embedded != null) return@withContext embedded
        }
        draft.coverImageUri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
            }.getOrNull()
        }
    }

    private fun read(uri: Uri, knownName: String? = null): ImportedChapter? {
        val name = knownName?.ifBlank { null } ?: displayName(uri) ?: return null
        if (!isAudioName(name) && !isAudioMime(uri)) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            // getEmbeddedPicture() тут свідомо не викликається: обкладинка потрібна
            // одна на книгу, і читається вона окремо в loadCover().
            ImportedChapter(
                title = chapterTitleOrNumber(name, title),
                uri = uri.toString(),
                durationMs = duration,
                metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: title,
                metaAuthor = author,
            )
        } catch (_: Exception) {
            ImportedChapter(
                title = chapterTitleOrNumber(name, null),
                uri = uri.toString(),
                durationMs = 0L,
                metaTitle = name.substringBeforeLast('.'),
                metaAuthor = null,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readExpanded(uri: Uri, knownName: String? = null): List<ImportedChapter> {
        val single = read(uri, knownName) ?: return emptyList()
        val name = (knownName ?: displayName(uri)).orEmpty().lowercase()
        val maybeChapters = name.endsWith(".m4b") || name.endsWith(".m4a") || name.endsWith(".mp4")
        if (!maybeChapters) return listOf(single)
        val ranges = parseM4bChapters(uri, single.durationMs)
        if (ranges.size < 2) return listOf(single)
        return ranges.map { (mark, duration) ->
            ImportedChapter(
                title = mark.title,
                uri = uri.toString(),
                durationMs = duration,
                metaTitle = single.metaTitle,
                metaAuthor = single.metaAuthor,
                startMs = mark.startMs,
                endMs = mark.startMs + duration,
            )
        }
    }

    private fun parseM4bChapters(uri: Uri, durationMs: Long): List<Pair<ChapterMark, Long>> {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val size = pfd.statSize.takeIf { it > 0 } ?: fis.channel.size()
                    val marks = Mp4ChapterParser.parse(fis.channel, size, durationMs) { n ->
                        context.getString(R.string.chapter_number, n)
                    }
                    Mp4ChapterParser.toRanges(marks, durationMs)
                }
            }.orEmpty()
        }.onFailure { AppLog.w("parseM4bChapters: глави не розібрано", it) }.getOrDefault(emptyList())
    }

    private data class ResolvedAudio(val uri: Uri, val copiedFile: File? = null)

    private fun persistOrCopy(uri: Uri, originalName: String = ""): ResolvedAudio {
        persist(uri)
        if (uri.scheme == "file") return ResolvedAudio(uri)
        if (isPersisted(uri)) return ResolvedAudio(uri)
        return copyToLocal(uri, originalName)
    }

    private fun isPersisted(uri: Uri): Boolean {
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
        if (granted.any { it.uri == uri }) return true
        // Документ усередині збереженого дерева доступний разом із деревом,
        // хоча його URI не збігається із записаним дозволом. Без цієї перевірки
        // кожен такий файл дарма копіювався у сховище застосунку.
        return granted.any { perm -> belongsToTree(uri, perm.uri) }
    }

    private fun copyToLocal(uri: Uri, originalName: String): ResolvedAudio {
        val rawName = originalName.ifBlank { displayName(uri) ?: "audio" }
        val ext = rawName.substringAfterLast('.', "mp3").lowercase().ifBlank { "mp3" }
        val stem = rawName.substringBeforeLast('.')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(60)
            .ifBlank { "audio" }
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        // Файли з ACTION_SEND часто не дають постійного дозволу, тож їх доводиться копіювати.
        // Аудіокнига буває на гігабайти: без цієї перевірки копіювання мовчки заповнювало
        // сховище й падало посеред процесу, лишаючи по собі обрізаний файл.
        val needed = sourceSize(uri)
        if (needed > 0L && availableSpace(dir) < needed + FREE_SPACE_MARGIN) {
            error(context.getString(R.string.import_no_space))
        }
        val dest = File(dir, "${UUID.randomUUID()}_$stem.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: error(context.getString(R.string.import_file_failed))
        } catch (e: Throwable) {
            // Обрізаний файл гірший за його відсутність: він пройде як «розділ» і зламає книгу.
            dest.delete()
            throw e
        }
        return ResolvedAudio(dest.toUri(), dest)
    }

    /** Назва глави з локалізованим запасним варіантом, коли ні метадані, ні імʼя файла не дали тексту. */
    private fun chapterTitleOrNumber(fileName: String, metaTitle: String?): String =
        prettyChapterTitle(fileName, metaTitle).ifBlank { context.getString(R.string.chapter_word) }

    /**
     * Скільки місця реально доступно. StorageManager враховує кеш, який система готова
     * звільнити, тому usableSpace тут — лише запасний варіант: інакше імпорт відмовляв би
     * на пристрої, де місце є, просто зайняте чужими кешами.
     */
    private fun availableSpace(dir: File): Long = runCatching {
        val storage = context.getSystemService(StorageManager::class.java)
        val uuid = storage.getUuidForPath(dir)
        storage.getAllocatableBytes(uuid)
    }.getOrElse { dir.usableSpace }

    /** Розмір джерела; 0 — невідомий (тоді перевірку місця пропускаємо). */
    private fun sourceSize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) return@runCatching c.getLong(idx)
        }
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: 0L
    }.getOrDefault(0L)

    /** true — дозвіл збережено, отже копіювати файл не потрібно. */
    private fun persist(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.onFailure {
            // Не помилка сама по собі: ACTION_SEND часто не дає постійного дозволу,
            // і тоді файл доведеться копіювати. Але саме звідси ростуть скарги
            // «книга зникла після перезавантаження», тож слід має лишатися.
            AppLog.w("AudioImporter.persist: постійний дозвіл не видано", it)
        }.getOrDefault(false)
    }

    private fun displayName(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        if (runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) {
            return runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
        }
        return uri.lastPathSegment
    }

    private fun isAudioMime(uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: return false
        return isAudioMime(mime)
    }

    companion object {
        /** Скільки файлів читати одночасно; більше — лише зайва пам'ять під буфери. */
        private const val METADATA_PARALLELISM = 4

        /** Запас понад розмір файла, щоб не заповнити сховище під нуль. */
        private const val FREE_SPACE_MARGIN = 32L * 1024 * 1024

        private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "wma", "mp4")
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

        fun isImageMime(mime: String?): Boolean {
            mime ?: return false
            return mime.startsWith("image/")
        }

        fun isImageName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in IMAGE_EXT
        }

        /**
         * Підпапка-диск (CD1, Диск 2, Part 3, «01»), а не окрема книга.
         *
         * Голі числа — лише до двох цифр: «01» це диск, «1984» — назва книги.
         */
        private val DISC_FOLDER = Regex(
            """^(?:(?:cd|disc|disk|part|vol|volume|диск|частина|часть|том)[\s._-]*\d+|\d{1,2})$""",
        )

        fun isDiscFolder(name: String): Boolean = DISC_FOLDER.matches(name.trim().lowercase())

        /**
         * Теки-книги в дереві відносних шляхів аудіо.
         *
         * Порожній ключ — книга це вся обрана тека. Інакше ключ = шлях до теки
         * книги від кореня вибору («Автор/Місто»).
         */
        fun findBookFolders(audioRelativePaths: List<String>): Set<String> {
            if (audioRelativePaths.isEmpty()) return emptySet()
            val out = linkedSetOf<String>()
            collectBookFolders("", audioRelativePaths, 0, out)
            return out
        }

        private fun collectBookFolders(
            prefix: String,
            paths: List<String>,
            depth: Int,
            out: MutableSet<String>,
        ) {
            if (depth > 8) {
                if (paths.any { it == prefix || prefix.isEmpty() || it.startsWith("$prefix/") }) {
                    out += prefix
                }
                return
            }
            val relative = if (prefix.isEmpty()) {
                paths
            } else {
                val head = "$prefix/"
                paths.mapNotNull { path ->
                    when {
                        path.startsWith(head) -> path.removePrefix(head)
                        else -> null
                    }
                }
            }
            val hasDirectAudio = relative.any { it.isNotEmpty() && !it.contains('/') }
            val childNames = relative.mapNotNull { rel ->
                rel.substringBefore('/').takeIf { it.isNotEmpty() && it != rel }
            }.toSet()
            when {
                hasDirectAudio -> out += prefix
                childNames.isNotEmpty() && childNames.all { isDiscFolder(it) } -> out += prefix
                else -> childNames.forEach { child ->
                    val childPrefix = if (prefix.isEmpty()) child else "$prefix/$child"
                    collectBookFolders(childPrefix, paths, depth + 1, out)
                }
            }
        }

        /**
         * До якої книги належить файл. Найдовший ключ-префікс: обкладинка в
         * «Автор/Місто/cover.jpg» не має впасти в «Автор», якщо книга — «Автор/Місто».
         */
        fun groupKey(relativePath: String, bookFolders: Set<String>): String {
            val matches = bookFolders.filter { key ->
                key.isEmpty() || relativePath == key || relativePath.startsWith("$key/")
            }
            return matches.maxByOrNull { it.length } ?: ""
        }

        /** Назва на полиці — імʼя теки книги, не весь шлях «Жанр/Автор/Назва». */
        fun bookFolderTitle(bookFolder: String, selectedFolderName: String?): String? =
            bookFolder.substringAfterLast('/').ifBlank { selectedFolderName }.orEmpty()
                .ifBlank { null }

        /**
         * Розбір «Автор - Назва» (або «Автор — Назва») з імені теки чи відносного шляху.
         *
         *  - «Шевченко - Кобзар» -> ("Шевченко", "Кобзар")
         *  - «Підмогильний/Місто» -> ("Підмогильний", "Місто")
         *  - «Кобзар» -> (null, "Кобзар")
         */
        fun parseFolderAuthorAndTitle(
            bookFolder: String,
            selectedFolderName: String?,
        ): Pair<String?, String?> {
            val rawName = bookFolderTitle(bookFolder, selectedFolderName) ?: return null to null
            val cleanName = rawName.substringBeforeLast('.')
            val parts = cleanName.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return parts[0].trim() to parts[1].trim()
            }
            if (bookFolder.contains('/')) {
                val segments = bookFolder.split('/').filter { it.isNotBlank() && !isDiscFolder(it) }
                if (segments.size >= 2) {
                    val authorGuess = segments[segments.size - 2].trim()
                    val titleGuess = segments.last().trim()
                    if (authorGuess.isNotBlank() && titleGuess.isNotBlank()) {
                        return authorGuess to titleGuess
                    }
                }
            }
            return null to cleanName
        }

        /**
         * Чи лежить документ усередині збереженого дерева.
         *
         * Саме цього бракувало: дозвіл беруть на дерево, а URI кожного документа
         * всередині нього інший, тож пряме порівняння давало false — і імпорт папки
         * копіював кожен файл у сховище застосунку.
         */
        fun belongsToTree(documentUri: Uri, treeUri: Uri): Boolean {
            if (documentUri.authority != treeUri.authority) return false
            val documentTreeId = runCatching {
                DocumentsContract.getTreeDocumentId(documentUri)
            }.getOrNull() ?: return false
            val grantedTreeId = runCatching {
                DocumentsContract.getTreeDocumentId(treeUri)
            }.getOrNull() ?: return false
            return documentTreeId == grantedTreeId
        }

        fun isAudioMime(mime: String?): Boolean {

            mime ?: return false
            return mime.startsWith("audio/") || mime == "application/ogg"
        }

        fun isAudioName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in AUDIO_EXT
        }

        /** Порожній результат означає «назви немає» — підпис підбирає викликач. */
        fun prettyChapterTitle(fileName: String, metaTitle: String?): String {
            val fromFile = fileName.substringBeforeLast('.')
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            val meta = metaTitle?.trim().orEmpty()
            return if (meta.isNotEmpty() && meta.length <= 80) meta else fromFile
        }

        fun naturalCompare(a: String, b: String): Int {
            val chunk = Regex("\\d+|\\D+")
            val pa = chunk.findAll(a.lowercase()).map { it.value }.toList()
            val pb = chunk.findAll(b.lowercase()).map { it.value }.toList()
            val n = minOf(pa.size, pb.size)
            for (i in 0 until n) {
                val ca = pa[i]
                val cb = pb[i]
                val na = ca.toLongOrNull()
                val nb = cb.toLongOrNull()
                val cmp = if (na != null && nb != null) na.compareTo(nb) else ca.compareTo(cb)
                if (cmp != 0) return cmp
            }
            return pa.size.compareTo(pb.size)
        }
    }
}

fun newId(): String = UUID.randomUUID().toString()
