package ua.nichnyk.listen.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Transaction
    @Query("SELECT * FROM books ORDER BY COALESCE(lastPlayedAt, 0) DESC, addedAt DESC")
    fun observeBooks(): Flow<List<BookWithChapters>>

    @Transaction
    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: String): Flow<BookWithChapters?>

    @Transaction
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: String): BookWithChapters?

    @Transaction
    @Query("SELECT * FROM books WHERE id = (SELECT bookId FROM chapters WHERE id = :chapterId LIMIT 1)")
    suspend fun getBookForChapter(chapterId: String): BookWithChapters?

    // Одноразові знімки. Раніше експорт і пошук дублікатів робили
    // observeBooks().firstOrNull() — тобто підписувалися на Room-запит заради одного читання.
    @Transaction
    @Query("SELECT * FROM books ORDER BY COALESCE(lastPlayedAt, 0) DESC, addedAt DESC")
    suspend fun getAllBooks(): List<BookWithChapters>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAllBookmarks(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("UPDATE books SET pinned = :pinned WHERE id = :bookId")
    suspend fun setPinned(bookId: String, pinned: Boolean)

    @Query("UPDATE books SET pinned = :pinned WHERE id IN (:bookIds)")
    suspend fun batchSetPinned(bookIds: List<String>, pinned: Boolean)

    @Query("UPDATE books SET series = :series, seriesOrder = :seriesOrder WHERE id = :bookId")
    suspend fun updateSeries(bookId: String, series: String?, seriesOrder: Float?)

    @Query("UPDATE books SET notes = :notes WHERE id = :bookId")
    suspend fun updateBookNotes(bookId: String, notes: String?)

    @Query("SELECT DISTINCT series FROM books WHERE series IS NOT NULL AND series != '' ORDER BY series ASC")
    fun observeAllSeries(): Flow<List<String>>

    @Query("SELECT * FROM book_characters WHERE bookId = :bookId ORDER BY createdAt ASC")
    fun observeCharactersForBook(bookId: String): Flow<List<BookCharacterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacter(character: BookCharacterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun batchInsertCharacters(characters: List<BookCharacterEntity>)

    @Query("SELECT * FROM book_characters WHERE id = :id")
    suspend fun getCharacter(id: String): BookCharacterEntity?

    @Query("DELETE FROM book_characters WHERE id = :id")
    suspend fun deleteCharacter(id: String)

    @Query("SELECT * FROM book_characters")
    suspend fun getAllCharacters(): List<BookCharacterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun batchInsertTags(tags: List<BookTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: String)

    @Query("UPDATE bookmarks SET note = :note WHERE id = :id")
    suspend fun updateBookmarkNote(id: String, note: String)

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getBookmark(id: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM daily_listening WHERE date = :date")
    suspend fun getDailyListening(date: String): DailyListeningEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyListening(entity: DailyListeningEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertListeningIfAbsent(entity: DailyListeningEntity)

    @Query("UPDATE daily_listening SET durationMs = durationMs + :deltaMs WHERE date = :date")
    suspend fun bumpListening(date: String, deltaMs: Long)

    /**
     * Атомарний додаток до денної статистики.
     *
     * Раніше репозиторій робив read-modify-write двома окремими викликами, а
     * flushListening() запускається з тікера, з паузи і з onDisconnected — два
     * паралельні скидання перезаписували одне одного й губили хвилини.
     *
     * UPDATE ... SET x = x + :delta замість UPSERT свідомо: `ON CONFLICT DO UPDATE`
     * зʼявився в SQLite 3.24, тобто лише з API 30, а minSdk тут 26.
     */
    @Transaction
    suspend fun addListening(date: String, deltaMs: Long) {
        insertListeningIfAbsent(DailyListeningEntity(date, 0L))
        bumpListening(date, deltaMs)
    }

    @Query("SELECT * FROM daily_listening WHERE date >= :sinceDate ORDER BY date ASC")
    fun observeListeningSince(sinceDate: String): Flow<List<DailyListeningEntity>>

    // Один рядок на день реального прослуховування — навіть за роки це сотні рядків.
    // Межу «тиждень» рахує ListeningStats, щоб дата не застигала у тексті запиту.
    @Query("SELECT * FROM daily_listening ORDER BY date ASC")
    fun observeAllDailyListening(): Flow<List<DailyListeningEntity>>

    @Query("SELECT COUNT(*) FROM books WHERE completed = 1")
    fun observeCompletedBooksCount(): Flow<Int>

    @Query("SELECT * FROM daily_listening")
    suspend fun getAllDailyListening(): List<DailyListeningEntity>

    @Transaction
    @Query("SELECT * FROM books WHERE author = :author ORDER BY COALESCE(lastPlayedAt, 0) DESC, addedAt DESC")
    fun observeBooksByAuthor(author: String): Flow<List<BookWithChapters>>

    // --- надгробки видалених книг ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(entity: DeletedBookEntity)

    @Query("SELECT * FROM deleted_books")
    suspend fun getAllTombstones(): List<DeletedBookEntity>

    @Query("SELECT * FROM deleted_books WHERE id = :id")
    suspend fun getTombstone(id: String): DeletedBookEntity?

    @Query("DELETE FROM deleted_books WHERE id = :id")
    suspend fun deleteTombstone(id: String)

    @Query("DELETE FROM deleted_books WHERE deletedAt < :olderThan")
    suspend fun pruneTombstones(olderThan: Long)

    @Query("DELETE FROM deleted_books")
    suspend fun deleteAllTombstones()

    // --- надгробки персонажів ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacterTombstone(entity: DeletedCharacterEntity)

    @Query("SELECT * FROM deleted_characters")
    suspend fun getAllCharacterTombstones(): List<DeletedCharacterEntity>

    @Query("DELETE FROM deleted_characters WHERE id = :id")
    suspend fun deleteCharacterTombstone(id: String)

    @Query("DELETE FROM deleted_characters WHERE deletedAt < :olderThan")
    suspend fun pruneCharacterTombstones(olderThan: Long)

    @Query("DELETE FROM deleted_characters")
    suspend fun deleteAllCharacterTombstones()

    // --- надгробки закладок ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmarkTombstone(entity: DeletedBookmarkEntity)

    @Query("SELECT * FROM deleted_bookmarks")
    suspend fun getAllBookmarkTombstones(): List<DeletedBookmarkEntity>

    @Query("DELETE FROM deleted_bookmarks WHERE id = :id")
    suspend fun deleteBookmarkTombstone(id: String)

    @Query("DELETE FROM deleted_bookmarks WHERE deletedAt < :olderThan")
    suspend fun pruneBookmarkTombstones(olderThan: Long)

    @Query("DELETE FROM deleted_bookmarks")
    suspend fun deleteAllBookmarkTombstones()

    // --- надгробки міток ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTagTombstone(entity: DeletedTagEntity)

    @Query("SELECT * FROM deleted_tags")
    suspend fun getAllTagTombstones(): List<DeletedTagEntity>

    @Query("DELETE FROM deleted_tags WHERE bookId = :bookId AND tag = :tag")
    suspend fun deleteTagTombstone(bookId: String, tag: String)

    @Query("DELETE FROM deleted_tags WHERE deletedAt < :olderThan")
    suspend fun pruneTagTombstones(olderThan: Long)

    @Query("DELETE FROM deleted_tags")
    suspend fun deleteAllTagTombstones()

    // --- мітки (теги) книг ---

    @Query("SELECT DISTINCT tag FROM book_tags ORDER BY tag ASC")
    fun observeAllTags(): Flow<List<String>>

    @Query("SELECT tag FROM book_tags WHERE bookId = :bookId ORDER BY tag ASC")
    fun observeTagsForBook(bookId: String): Flow<List<String>>

    @Query("SELECT * FROM book_tags")
    fun observeAllBookTags(): Flow<List<BookTagEntity>>

    @Query("SELECT * FROM book_tags")
    suspend fun getAllBookTags(): List<BookTagEntity>

    @Query("SELECT tag FROM book_tags WHERE bookId = :bookId ORDER BY tag ASC")
    suspend fun getTagsForBook(bookId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: BookTagEntity)

    @Query("DELETE FROM book_tags WHERE bookId = :bookId AND tag = :tag")
    suspend fun deleteTag(bookId: String, tag: String)

    @Query("DELETE FROM book_tags WHERE bookId = :bookId")
    suspend fun deleteTagsForBook(bookId: String)

    @Query("DELETE FROM book_tags")
    suspend fun deleteAllTags()

    // --- історія сесій прослуховування ---

    @Query("SELECT * FROM listening_sessions ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentSessions(limit: Int = 100): Flow<List<ListeningSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ListeningSessionEntity)

    @Query("SELECT * FROM listening_sessions ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastSession(): ListeningSessionEntity?

    @Query("UPDATE listening_sessions SET durationMs = durationMs + :deltaMs WHERE id = :id")
    suspend fun extendSession(id: String, deltaMs: Long)

    /**
     * Дописує [deltaMs] прослуханого до історії.
     *
     * Відрізок, який упритул продовжує останню сесію тієї самої книги, доливається
     * до неї; інакше починається нова сесія з часом початку [now] - [deltaMs].
     * Раніше тут був простий insert — і кожні 30 секунд зʼявлявся окремий рядок.
     *
     * Порівнюємо саме з **останньою** сесією, а не з останньою сесією цієї книги:
     * після переходу A → B → A відрізок має стати новою сесією A, а не подовжити
     * ту, що обірвалася книгою B.
     */
    @Transaction
    suspend fun appendSession(
        newId: String,
        bookId: String,
        bookTitle: String,
        author: String,
        now: Long,
        deltaMs: Long,
        mergeGapMs: Long = SESSION_MERGE_GAP_MS,
    ) {
        val startedAt = now - deltaMs
        val last = getLastSession()
        if (last != null &&
            last.bookId == bookId &&
            startedAt - (last.timestamp + last.durationMs) <= mergeGapMs
        ) {
            extendSession(last.id, deltaMs)
        } else {
            insertSession(
                ListeningSessionEntity(
                    id = newId,
                    bookId = bookId,
                    bookTitle = bookTitle,
                    author = author,
                    timestamp = startedAt,
                    durationMs = deltaMs,
                ),
            )
        }
    }

    @Query("DELETE FROM listening_sessions WHERE timestamp < :olderThan")
    suspend fun pruneSessions(olderThan: Long)

    @Query("DELETE FROM listening_sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT * FROM listening_sessions ORDER BY timestamp DESC")
    suspend fun getAllSessions(): List<ListeningSessionEntity>

    // --- черга «далі» ---

    @Transaction
    @Query(
        "SELECT b.* FROM books b JOIN queue_items q ON q.bookId = b.id ORDER BY q.position ASC",
    )
    suspend fun getQueue(): List<BookWithChapters>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueItems(items: List<QueueItemEntity>)

    @Query("DELETE FROM queue_items")
    suspend fun deleteQueue()

    /**
     * Черга коротка, тож простіше переписати її цілком, ніж вести дельти:
     * порядок задає позиція в списку, і після переміщення елемента нема чого звіряти.
     */
    @Transaction
    suspend fun replaceQueue(bookIds: List<String>) {
        deleteQueue()
        if (bookIds.isNotEmpty()) {
            insertQueueItems(bookIds.mapIndexed { index, id -> QueueItemEntity(id, index) })
        }
    }

    @Query("DELETE FROM books")
    suspend fun deleteAllBooks()

    @Query("DELETE FROM daily_listening")
    suspend fun deleteAllDailyListening()

    @Query("DELETE FROM book_characters")
    suspend fun deleteAllCharacters()

    @Transaction
    suspend fun replaceAll(
        books: List<BookEntity>,
        chapters: List<ChapterEntity>,
        bookmarks: List<BookmarkEntity>,
        listening: List<DailyListeningEntity> = emptyList(),
        tombstones: List<DeletedBookEntity> = emptyList(),
        tags: List<BookTagEntity> = emptyList(),
        sessions: List<ListeningSessionEntity> = emptyList(),
        characters: List<BookCharacterEntity> = emptyList(),
        characterTombstones: List<DeletedCharacterEntity> = emptyList(),
        bookmarkTombstones: List<DeletedBookmarkEntity> = emptyList(),
        tagTombstones: List<DeletedTagEntity> = emptyList(),
    ) {
        // Закладки, розділи, мітки, черга й персонажі прив'язані до книги
        // зовнішнім ключем із CASCADE, тож deleteAllBooks() забирає їх за собою.
        // Окремі deleteAll* нижче — для таблиць без цього зв'язку.
        deleteAllBooks()
        deleteAllDailyListening()
        deleteAllTombstones()
        deleteAllCharacterTombstones()
        deleteAllBookmarkTombstones()
        deleteAllTagTombstones()
        deleteAllTags()
        deleteAllSessions()
        deleteAllCharacters()
        books.forEach { upsertBook(it) }
        if (chapters.isNotEmpty()) upsertChapters(chapters)
        bookmarks.forEach { upsertBookmark(it) }
        listening.forEach { upsertDailyListening(it) }
        tombstones.forEach { upsertTombstone(it) }
        tags.forEach { insertTag(it) }
        sessions.forEach { insertSession(it) }
        characters.forEach { upsertCharacter(it) }
        characterTombstones.forEach { upsertCharacterTombstone(it) }
        bookmarkTombstones.forEach { upsertBookmarkTombstone(it) }
        tagTombstones.forEach { upsertTagTombstone(it) }
    }
}

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        DailyListeningEntity::class,
        DeletedBookEntity::class,
        BookTagEntity::class,
        ListeningSessionEntity::class,
        QueueItemEntity::class,
        BookCharacterEntity::class,
        DeletedCharacterEntity::class,
        DeletedBookmarkEntity::class,
        DeletedTagEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
abstract class ListenDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        @Volatile
        private var instance: ListenDatabase? = null

        // internal, а не private: androidTest перевіряє міграції напряму.
        internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_listening (date TEXT NOT NULL PRIMARY KEY, durationMs INTEGER NOT NULL)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN startMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chapters ADD COLUMN endMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deleted_books " +
                        "(id TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)",
                )
            }
        }

        internal val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS book_tags (" +
                        "bookId TEXT NOT NULL, " +
                        "tag TEXT NOT NULL, " +
                        "PRIMARY KEY(bookId, tag), " +
                        "FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_tags_bookId ON book_tags(bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_tags_tag ON book_tags(tag)")
            }
        }

        internal val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS listening_sessions (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "bookId TEXT NOT NULL, " +
                        "bookTitle TEXT NOT NULL, " +
                        "author TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "durationMs INTEGER NOT NULL" +
                        ")",
                )
            }
        }

        /**
         * Черга на диску, індекси під історію та екран автора — і одноразове
         * склеювання старих сесій.
         *
         * До цієї версії кожні 30 секунд прослуховування давали окремий рядок у
         * listening_sessions, тож історія виглядала як стрічка однакових карток по
         * пів хвилини. Нові записи зливає appendSession; наявні треба звести один раз
         * тут, інакше стара історія лишалася б нечитабельною ще пів року — до TTL.
         */
        internal val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Слово в слово як у схемі, яку генерує Room (app/schemas/…/7.json),
                // разом із ON UPDATE NO ACTION: розбіжність тут ловиться лише
                // валідацією на пристрої користувача, що оновлюється.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queue_items` (" +
                        "`bookId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`bookId`), " +
                        "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_listening_sessions_timestamp` " +
                        "ON `listening_sessions` (`timestamp`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_books_author` ON `books` (`author`)",
                )
                compactSessions(db)
            }
        }

        /**
         * Зводить сусідні відрізки однієї книги в одну сесію.
         *
         * У старому форматі timestamp був моментом запису, тобто кінцем відрізка;
         * у новому — початком сесії. Тому початок кожної групи зсуваємо на її
         * власну тривалість назад, і далі порівнюємо початок наступного відрізка
         * з кінцем групи — рівно так, як це робить appendSession.
         */
        private fun compactSessions(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            data class Merged(
                val id: String,
                val bookId: String,
                val title: String,
                val author: String,
                val startedAt: Long,
                var durationMs: Long,
            )

            val merged = mutableListOf<Merged>()
            db.query(
                "SELECT id, bookId, bookTitle, author, timestamp, durationMs " +
                    "FROM listening_sessions ORDER BY timestamp ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val duration = cursor.getLong(5)
                    val startedAt = cursor.getLong(4) - duration
                    val bookId = cursor.getString(1)
                    val last = merged.lastOrNull()
                    if (last != null &&
                        last.bookId == bookId &&
                        startedAt - (last.startedAt + last.durationMs) <= SESSION_MERGE_GAP_MS
                    ) {
                        last.durationMs += duration
                    } else {
                        merged += Merged(
                            id = cursor.getString(0),
                            bookId = bookId,
                            title = cursor.getString(2),
                            author = cursor.getString(3),
                            startedAt = startedAt,
                            durationMs = duration,
                        )
                    }
                }
            }
            db.execSQL("DELETE FROM listening_sessions")
            for (row in merged) {
                db.execSQL(
                    "INSERT INTO listening_sessions " +
                        "(id, bookId, bookTitle, author, timestamp, durationMs) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(row.id, row.bookId, row.title, row.author, row.startedAt, row.durationMs),
                )
            }
        }

        /**
         * Закріплення книг (pinned) та підтримка книжкових серій/циклів (series, seriesOrder).
         */
        internal val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `series` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `seriesOrder` REAL DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_pinned` ON `books` (`pinned`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_series` ON `books` (`series`)")
            }
        }

        /**
         * Список дійових осіб (book_characters) та особисті нотатки до книги (notes).
         */
        internal val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `notes` TEXT DEFAULT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_characters` (" +
                        "`id` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`role` TEXT DEFAULT NULL, " +
                        "`description` TEXT DEFAULT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_characters_bookId` ON `book_characters` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_characters_name` ON `book_characters` (`name`)")
            }
        }

        /**
         * Надгробки персонажів (deleted_characters).
         *
         * Досі злиття лише додавало персонажів: видалений на іншому пристрої
         * повертався з наступною синхронізацією, бо payload не відрізняв
         * «видалено» від «ніколи не було».
         */
        internal val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_characters` (" +
                        "`id` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`) )",
                )
            }
        }

        /**
         * Надгробки закладок і міток (deleted_bookmarks, deleted_tags) плюс
         * `books.renamedAt`.
         *
         * До цього надгробки мали лише книги й персонажі, тож закладка чи мітка,
         * прибрана на одному пристрої, поверталася з хмари наступним же злиттям.
         * `renamedAt` — власна мітка часу для назви й автора: арбітрувати їх за
         * `lastPlayedAt` не можна, бо назва — не прогрес.
         */
        internal val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_bookmarks` (" +
                        "`id` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`) )",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_tags` (" +
                        "`bookId` TEXT NOT NULL, " +
                        "`tag` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`bookId`, `tag`) )",
                )
                // 0 = «ніколи не перейменовували». Саме нуль, а не поточний час:
                // інакше вся наявна полиця виглядала б щойно перейменованою й
                // перезаписала б назви на другому пристрої при першому ж злитті.
                db.execSQL("ALTER TABLE books ADD COLUMN renamedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Тека джерела книги для авто-скану (I3): `sourceTreeUri` + `sourceFolderDocId`.
         *
         * Без цього скан вгадував tree grant за главами й міг обійти всю бібліотеку,
         * якщо дозвіл дали на батьківську теку. Тепер скануємо лише папку, яку
         * користувач явно вказав (імпорт / доливання / перепривʼязка).
         */
        internal val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceTreeUri TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN sourceFolderDocId TEXT")
            }
        }

        fun get(context: Context): ListenDatabase {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(context: Context): ListenDatabase =
            Room.databaseBuilder(context, ListenDatabase::class.java, "nichnyk.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                )
                // Тільки для пониження версії: поставити поверх свіжої бази старішу
                // збірку Room не вміє й кидає виняток — застосунок після цього не
                // відкривається взагалі, і полиця однаково втрачена. Це реальний
                // сценарій розробки (відкат на попередній APK), тож краще чисто
                // почати з нуля, ніж отримати цикл падінь при кожному запуску.
                // Оновлення це не зачіпає: там працюють MIGRATION_*.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}

