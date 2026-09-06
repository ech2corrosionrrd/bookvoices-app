package ua.nichnyk.listen.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Читання й запис `bookvoices_sync.json`.
 *
 * Винесено з LibraryRepository окремо і без залежності від Context/Room: це єдина частина
 * синхронізації, яку можна перевірити звичайним JVM-тестом, а саме тут ламається найбільше —
 * чужий або старий файл, відсутні поля, розділи без книги.
 */
object BackupCodec {

    /**
     * 2 — `deletedBooks`.
     * 3 — `tags` і `listeningSessions`, згодом `characters`, `notes` і
     *     `deletedCharacters`.
     * 4 — `deletedBookmarks`, `deletedTags` і `renamedAt` у книзі.
     *
     * Номер підняли саме тому, що до цього так не робили: `characters`, `notes`
     * і `deletedCharacters` доклали до третьої версії, не змінивши число, — і воно
     * перестало щось означати. Три різні набори полів під одним «3» читаються, але
     * подивитися на версію й зрозуміти, що всередині, вже не можна було.
     *
     * На сумісність номер не впливає й ніде не перевіряється: усі поля читаються
     * через `opt*` / `isNull`, тож старий файл дає дефолти, а старіша збірка просто
     * не побачить нових масивів. Це запис у журналі, а не перемикач.
     */
    const val VERSION = 4

    data class Payload(
        val version: Int,
        val exportedAt: Long,
        val books: List<BookEntity>,
        val chapters: List<ChapterEntity>,
        val bookmarks: List<BookmarkEntity>,
        val listening: List<DailyListeningEntity>,
        val tombstones: List<DeletedBookEntity> = emptyList(),
        val tags: List<BookTagEntity> = emptyList(),
        val sessions: List<ListeningSessionEntity> = emptyList(),
        val characters: List<BookCharacterEntity> = emptyList(),
        val characterTombstones: List<DeletedCharacterEntity> = emptyList(),
        val bookmarkTombstones: List<DeletedBookmarkEntity> = emptyList(),
        val tagTombstones: List<DeletedTagEntity> = emptyList(),
    )

    /** Назви за замовчуванням приходять ззовні, щоб кодек лишався без Context. */
    data class Fallbacks(
        val unknownAuthor: String,
        val chapterTitle: (Int) -> String,
    )

    fun encode(
        books: List<BookWithChapters>,
        bookmarks: List<BookmarkEntity>,
        listening: List<DailyListeningEntity>,
        tombstones: List<DeletedBookEntity> = emptyList(),
        tags: List<BookTagEntity> = emptyList(),
        sessions: List<ListeningSessionEntity> = emptyList(),
        characters: List<BookCharacterEntity> = emptyList(),
        characterTombstones: List<DeletedCharacterEntity> = emptyList(),
        bookmarkTombstones: List<DeletedBookmarkEntity> = emptyList(),
        tagTombstones: List<DeletedTagEntity> = emptyList(),
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", exportedAt)

        val booksArray = JSONArray()
        for (item in books) {
            val b = item.book
            val bObj = JSONObject()
            bObj.put("id", b.id)
            bObj.put("title", b.title)
            bObj.put("author", b.author)
            bObj.put("coverPath", b.coverPath ?: JSONObject.NULL)
            bObj.put("addedAt", b.addedAt)
            bObj.put("lastPlayedAt", b.lastPlayedAt ?: JSONObject.NULL)
            bObj.put("durationMs", b.durationMs)
            bObj.put("positionMs", b.positionMs)
            bObj.put("currentChapterIndex", b.currentChapterIndex)
            bObj.put("playbackSpeed", b.playbackSpeed.toDouble())
            bObj.put("completed", b.completed)
            bObj.put("pinned", b.pinned)
            bObj.put("series", b.series ?: JSONObject.NULL)
            bObj.put("seriesOrder", b.seriesOrder?.toDouble() ?: JSONObject.NULL)
            bObj.put("notes", b.notes ?: JSONObject.NULL)
            bObj.put("renamedAt", b.renamedAt)

            val chArray = JSONArray()
            for (ch in item.chapters.sortedBy { it.index }) {
                chArray.put(
                    JSONObject().apply {
                        put("id", ch.id)
                        put("bookId", ch.bookId)
                        put("index", ch.index)
                        put("title", ch.title)
                        put("uri", ch.uri)
                        put("durationMs", ch.durationMs)
                        put("startMs", ch.startMs)
                        put("endMs", ch.endMs)
                    },
                )
            }
            bObj.put("chapters", chArray)
            booksArray.put(bObj)
        }
        root.put("books", booksArray)

        val bmArray = JSONArray()
        for (bm in bookmarks) {
            bmArray.put(
                JSONObject().apply {
                    put("id", bm.id)
                    put("bookId", bm.bookId)
                    put("chapterId", bm.chapterId)
                    put("chapterTitle", bm.chapterTitle)
                    put("positionMs", bm.positionMs)
                    put("note", bm.note)
                    put("createdAt", bm.createdAt)
                },
            )
        }
        root.put("bookmarks", bmArray)

        val listenArray = JSONArray()
        for (day in listening) {
            listenArray.put(
                JSONObject().apply {
                    put("date", day.date)
                    put("durationMs", day.durationMs)
                },
            )
        }
        root.put("dailyListening", listenArray)

        val deletedArray = JSONArray()
        for (stone in tombstones) {
            deletedArray.put(
                JSONObject().apply {
                    put("id", stone.id)
                    put("deletedAt", stone.deletedAt)
                },
            )
        }
        root.put("deletedBooks", deletedArray)

        val tagsArray = JSONArray()
        for (t in tags) {
            tagsArray.put(
                JSONObject().apply {
                    put("bookId", t.bookId)
                    put("tag", t.tag)
                },
            )
        }
        root.put("tags", tagsArray)

        val sessionsArray = JSONArray()
        for (s in sessions) {
            sessionsArray.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("bookId", s.bookId)
                    put("bookTitle", s.bookTitle)
                    put("author", s.author)
                    put("timestamp", s.timestamp)
                    put("durationMs", s.durationMs)
                },
            )
        }
        root.put("listeningSessions", sessionsArray)

        val charactersArray = JSONArray()
        for (c in characters) {
            charactersArray.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("bookId", c.bookId)
                    put("name", c.name)
                    put("role", c.role ?: JSONObject.NULL)
                    put("description", c.description ?: JSONObject.NULL)
                    put("createdAt", c.createdAt)
                },
            )
        }
        root.put("characters", charactersArray)

        // Окремий масив, а не прапорець у самих персонажах: надгробок живе далі
        // після того, як запис зник, і прив'язати його нема до чого.
        val deletedCharactersArray = JSONArray()
        for (stone in characterTombstones) {
            deletedCharactersArray.put(
                JSONObject().apply {
                    put("id", stone.id)
                    put("bookId", stone.bookId)
                    put("deletedAt", stone.deletedAt)
                },
            )
        }
        root.put("deletedCharacters", deletedCharactersArray)

        val deletedBookmarksArray = JSONArray()
        for (stone in bookmarkTombstones) {
            deletedBookmarksArray.put(
                JSONObject().apply {
                    put("id", stone.id)
                    put("bookId", stone.bookId)
                    put("deletedAt", stone.deletedAt)
                },
            )
        }
        root.put("deletedBookmarks", deletedBookmarksArray)

        val deletedTagsArray = JSONArray()
        for (stone in tagTombstones) {
            deletedTagsArray.put(
                JSONObject().apply {
                    put("bookId", stone.bookId)
                    put("tag", stone.tag)
                    put("deletedAt", stone.deletedAt)
                },
            )
        }
        root.put("deletedTags", deletedTagsArray)

        return root.toString(2)
    }

    /**
     * Кидає [org.json.JSONException] на файлі, який не є бекапом BookVoices.
     * Книга без обов'язкових полів (id, title) пропускається разом зі своїми розділами,
     * а не валить увесь імпорт: половина відновленої полиці краща за нуль.
     */
    fun decode(json: String, fallbacks: Fallbacks, now: Long = System.currentTimeMillis()): Payload {
        val root = JSONObject(json)
        val booksArray = root.getJSONArray("books")

        val books = mutableListOf<BookEntity>()
        val chapters = mutableListOf<ChapterEntity>()

        for (i in 0 until booksArray.length()) {
            val bObj = booksArray.optJSONObject(i) ?: continue
            val bookId = bObj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = bObj.optString("title").takeIf { it.isNotBlank() } ?: continue

            books += BookEntity(
                id = bookId,
                title = title,
                author = bObj.optString("author").ifBlank { fallbacks.unknownAuthor },
                coverPath = if (bObj.isNull("coverPath")) null else bObj.optString("coverPath").ifBlank { null },
                addedAt = bObj.optLong("addedAt", now),
                lastPlayedAt = if (bObj.isNull("lastPlayedAt")) null else bObj.optLong("lastPlayedAt"),
                durationMs = bObj.optLong("durationMs", 0L),
                positionMs = bObj.optLong("positionMs", 0L),
                currentChapterIndex = bObj.optInt("currentChapterIndex", 0),
                playbackSpeed = bObj.optDouble("playbackSpeed", 1.0).toFloat(),
                completed = bObj.optBoolean("completed", false),
                pinned = bObj.optBoolean("pinned", false),
                series = if (bObj.isNull("series")) null else bObj.optString("series").trim().ifBlank { null },
                seriesOrder = if (bObj.isNull("seriesOrder") || !bObj.has("seriesOrder")) null else {
                    val d = bObj.optDouble("seriesOrder")
                    if (d.isNaN()) null else d.toFloat()
                },
                notes = if (bObj.isNull("notes")) null else bObj.optString("notes").trim().ifBlank { null },
                // Відсутнє поле = 0 = «ніколи не перейменовували». Файл, зроблений
                // до появи поля, не має права виглядати свіжішим за локальну назву.
                renamedAt = bObj.optLong("renamedAt", 0L),
            )

            val chArray = bObj.optJSONArray("chapters") ?: continue
            for (j in 0 until chArray.length()) {
                val cObj = chArray.optJSONObject(j) ?: continue
                val uri = cObj.optString("uri").takeIf { it.isNotBlank() } ?: continue
                chapters += ChapterEntity(
                    id = cObj.optString("id").ifBlank { newId() },
                    bookId = bookId,
                    index = cObj.optInt("index", j),
                    title = cObj.optString("title").ifBlank { fallbacks.chapterTitle(j + 1) },
                    uri = uri,
                    durationMs = cObj.optLong("durationMs", 0L),
                    startMs = cObj.optLong("startMs", 0L),
                    endMs = cObj.optLong("endMs", 0L),
                )
            }
        }

        val knownBooks = books.mapTo(mutableSetOf()) { it.id }
        val bookmarks = mutableListOf<BookmarkEntity>()
        val bmArray = root.optJSONArray("bookmarks")
        if (bmArray != null) {
            for (i in 0 until bmArray.length()) {
                val mObj = bmArray.optJSONObject(i) ?: continue
                val id = mObj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val bookId = mObj.optString("bookId").takeIf { it.isNotBlank() } ?: continue
                // Закладка без книги порушила б зовнішній ключ і зірвала б усю транзакцію.
                if (bookId !in knownBooks) continue
                bookmarks += BookmarkEntity(
                    id = id,
                    bookId = bookId,
                    chapterId = mObj.optString("chapterId", ""),
                    chapterTitle = mObj.optString("chapterTitle", ""),
                    positionMs = mObj.optLong("positionMs", 0L),
                    note = mObj.optString("note", ""),
                    createdAt = mObj.optLong("createdAt", now),
                )
            }
        }

        val listening = mutableListOf<DailyListeningEntity>()
        val listenArray = root.optJSONArray("dailyListening")
        if (listenArray != null) {
            for (i in 0 until listenArray.length()) {
                val dObj = listenArray.optJSONObject(i) ?: continue
                val date = dObj.optString("date").takeIf { it.isNotBlank() } ?: continue
                listening += DailyListeningEntity(date, dObj.optLong("durationMs", 0L))
            }
        }

        val tombstones = mutableListOf<DeletedBookEntity>()
        val deletedArray = root.optJSONArray("deletedBooks")
        if (deletedArray != null) {
            for (i in 0 until deletedArray.length()) {
                val dObj = deletedArray.optJSONObject(i) ?: continue
                val id = dObj.optString("id").takeIf { it.isNotBlank() } ?: continue
                tombstones += DeletedBookEntity(id, dObj.optLong("deletedAt", now))
            }
        }

        val characterTombstones = mutableListOf<DeletedCharacterEntity>()
        val deletedCharsArray = root.optJSONArray("deletedCharacters")
        if (deletedCharsArray != null) {
            for (i in 0 until deletedCharsArray.length()) {
                val dObj = deletedCharsArray.optJSONObject(i) ?: continue
                val id = dObj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val bookId = dObj.optString("bookId").takeIf { it.isNotBlank() } ?: continue
                characterTombstones += DeletedCharacterEntity(id, bookId, dObj.optLong("deletedAt", now))
            }
        }

        val tags = mutableListOf<BookTagEntity>()
        val tagsArray = root.optJSONArray("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tObj = tagsArray.optJSONObject(i) ?: continue
                val bookId = tObj.optString("bookId").takeIf { it.isNotBlank() } ?: continue
                val tag = tObj.optString("tag").trim().takeIf { it.isNotBlank() } ?: continue
                if (bookId in knownBooks) {
                    tags += BookTagEntity(bookId, tag)
                }
            }
        }

        // Сесія — знімок назви, не зовнішній ключ: книгу вже могли видалити.
        val sessions = mutableListOf<ListeningSessionEntity>()
        val sessionsArray = root.optJSONArray("listeningSessions")
        if (sessionsArray != null) {
            for (i in 0 until sessionsArray.length()) {
                val sObj = sessionsArray.optJSONObject(i) ?: continue
                val id = sObj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val bookId = sObj.optString("bookId").takeIf { it.isNotBlank() } ?: continue
                val title = sObj.optString("bookTitle").takeIf { it.isNotBlank() } ?: continue
                sessions += ListeningSessionEntity(
                    id = id,
                    bookId = bookId,
                    bookTitle = title,
                    author = sObj.optString("author", ""),
                    timestamp = sObj.optLong("timestamp", now),
                    durationMs = sObj.optLong("durationMs", 0L),
                )
            }
        }

        val characters = mutableListOf<BookCharacterEntity>()
        val charactersArray = root.optJSONArray("characters")
        if (charactersArray != null) {
            for (i in 0 until charactersArray.length()) {
                val cObj = charactersArray.optJSONObject(i) ?: continue
                val id = cObj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val bookId = cObj.optString("bookId").takeIf { it.isNotBlank() } ?: continue
                val name = cObj.optString("name").takeIf { it.isNotBlank() } ?: continue
                if (bookId in knownBooks) {
                    characters += BookCharacterEntity(
                        id = id,
                        bookId = bookId,
                        name = name,
                        role = if (cObj.isNull("role")) null else cObj.optString("role").ifBlank { null },
                        description = if (cObj.isNull("description")) null else cObj.optString("description").ifBlank { null },
                        createdAt = cObj.optLong("createdAt", now),
                    )
                }
            }
        }

        val bookmarkTombstones = mutableListOf<DeletedBookmarkEntity>()
        root.optJSONArray("deletedBookmarks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val bookId = o.optString("bookId").takeIf { it.isNotBlank() } ?: continue
                bookmarkTombstones += DeletedBookmarkEntity(
                    id = id,
                    bookId = bookId,
                    deletedAt = o.optLong("deletedAt", now),
                )
            }
        }

        val tagTombstones = mutableListOf<DeletedTagEntity>()
        root.optJSONArray("deletedTags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val bookId = o.optString("bookId").takeIf { it.isNotBlank() } ?: continue
                val tag = o.optString("tag").takeIf { it.isNotBlank() } ?: continue
                tagTombstones += DeletedTagEntity(
                    bookId = bookId,
                    tag = tag,
                    deletedAt = o.optLong("deletedAt", now),
                )
            }
        }

        return Payload(
            version = root.optInt("version", VERSION),
            exportedAt = root.optLong("exportedAt", now),
            books = books,
            chapters = chapters,
            bookmarks = bookmarks,
            listening = listening,
            tombstones = tombstones,
            characterTombstones = characterTombstones,
            bookmarkTombstones = bookmarkTombstones,
            tagTombstones = tagTombstones,
            tags = tags,
            sessions = sessions,
            characters = characters,
        )
    }

    /**
     * Що записати поверх локальної книги під час злиття.
     * null означає «локальна версія свіжіша, не чіпати».
     *
     * Прогрес виграє той бік, у якого пізніший lastPlayedAt; за рівності перемагає хмара,
     * щоб повторне злиття того самого файла було ідемпотентним.
     */
    /**
     * Чи слід прибрати локальну книгу через надгробок із хмари.
     *
     * Видалення не є беззастережним: якщо після [deletedAt] книгу тут слухали, то
     * інший пристрій просто не знав про це — і стерти її означало б знищити свіжий
     * прогрес. За рівності часу перемагає видалення: воно новіше за замовчуванням.
     */
    fun tombstoneWins(local: BookEntity, deletedAt: Long): Boolean {
        val touched = local.lastPlayedAt ?: return true
        return touched <= deletedAt
    }

    /**
     * Чи перемагає надгробок персонажа над записом, що прийшов із бекапу.
     *
     * У персонажа немає позначки останньої зміни — лише [BookCharacterEntity.createdAt].
     * Тому арбітраж такий: запис, створений ПІСЛЯ видалення, — це нове додавання
     * на іншому пристрої, і воно має право воскресити id. Створений до видалення
     * — той самий запис, який ми й прибрали, тож лишається видаленим.
     */
    fun characterTombstoneWins(incoming: BookCharacterEntity, deletedAt: Long): Boolean =
        incoming.createdAt <= deletedAt

    fun mergeBook(local: BookEntity, incoming: BookEntity): BookEntity? {
        val localLast = local.lastPlayedAt ?: 0L
        val cloudLast = incoming.lastPlayedAt ?: 0L
        // Прогрес: за рівності перемагає хмара, щоб повторне злиття того самого
        // файла було ідемпотентним.
        val cloudWinsProgress = cloudLast >= localLast
        // Назва й автор мають власний арбітраж — див. BookEntity.renamedAt.
        val cloudWinsIdentity = incoming.renamedAt > local.renamedAt

        val merged = local.copy(
            lastPlayedAt = if (cloudWinsProgress) incoming.lastPlayedAt else local.lastPlayedAt,
            currentChapterIndex = if (cloudWinsProgress) incoming.currentChapterIndex else local.currentChapterIndex,
            positionMs = if (cloudWinsProgress) incoming.positionMs else local.positionMs,
            playbackSpeed = if (cloudWinsProgress) incoming.playbackSpeed else local.playbackSpeed,
            completed = if (cloudWinsProgress) incoming.completed else local.completed,
            title = if (cloudWinsIdentity) incoming.title else local.title,
            author = if (cloudWinsIdentity) incoming.author else local.author,
            renamedAt = maxOf(local.renamedAt, incoming.renamedAt),
            // Закріплення додається, а не заміщується. Файл, зроблений до появи
            // `pinned`, поля просто не має, optBoolean дає false — і злиття
            // мовчки поскидало б усі піни на пристрої. Та сама пастка, що з
            // чергою при відновленні: бекап нічого не знав про сутність, а
            // виглядало так, ніби він знав і сказав «порожньо».
            pinned = incoming.pinned || local.pinned,
            series = incoming.series ?: local.series,
            seriesOrder = incoming.seriesOrder ?: local.seriesOrder,
            // Так само, як серія: заповнене з хмари перемагає, порожнє не стирає
            // локальне. Без цього рядка нотатки їхали в бекап і читалися з нього,
            // але при злитті `local.copy` просто лишав локальне значення — тобто
            // між пристроями вони не переносилися взагалі.
            notes = incoming.notes ?: local.notes,
        )
        // null означає «нічого не змінилося, не чіпати рядок». Раніше цю роль грав
        // ранній вихід по lastPlayedAt — і разом із прогресом він відкидав пін,
        // серію та нотатки, які до прогресу стосунку не мають: книга, послухана
        // тут пізніше, назавжди лишалася без нотатки, доданої на іншому пристрої.
        return merged.takeIf { it != local }
    }
}
