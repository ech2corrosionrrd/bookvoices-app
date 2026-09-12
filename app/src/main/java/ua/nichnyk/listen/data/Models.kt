package ua.nichnyk.listen.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

// Індекси на author, pinned, series: фільтрація та сортування полиці.
@Entity(
    tableName = "books",
    indices = [
        Index("author"),
        Index("pinned"),
        Index("series"),
    ],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val addedAt: Long,
    val lastPlayedAt: Long?,
    val durationMs: Long,
    val positionMs: Long,
    val currentChapterIndex: Int,
    val playbackSpeed: Float,
    val completed: Boolean,
    val pinned: Boolean = false,
    val series: String? = null,
    val seriesOrder: Float? = null,
    val notes: String? = null,
    /**
     * Коли книгу востаннє перейменували вручну; 0 — ніколи.
     *
     * Модель синхронізації свідомо така: «хмара переносить прогрес, а не полицю»,
     * тож назву й автора не можна арбітрувати за `lastPlayedAt` — інакше книга,
     * яку просто слухали пізніше, перезаписувала б назву, виправлену на іншому
     * пристрої. Але й лишати назву назавжди локальною теж не можна: перейменування
     * — окрема дія користувача, і воно нікуди не доїжджало взагалі.
     *
     * Тому в назви власна мітка часу, і виграє той бік, який перейменовував пізніше.
     * Нуль з обох боків (ніхто не перейменовував) означає нічию — лишається локальне,
     * рівно як було до появи поля.
     */
    val renamedAt: Long = 0L,
    /**
     * SAF-дерево, на яке користувач дав дозвіл для цієї книги (імпорт папки,
     * доливання папки, перепривʼязка). `null` — книгу зібрано з окремих файлів
     * чи копій; авто-скан тоді неможливий і не вгадується з чужих дозволів.
     *
     * У бекап не їде: URI дозволу привʼязаний до пристрою.
     */
    val sourceTreeUri: String? = null,
    /**
     * Document id теки саме цієї книги всередині [sourceTreeUri].
     * Коли користувач вказав корінь бібліотеки, а книга — підпапка, скан іде
     * лише сюди, а не по сусідніх книгах. `null` при наявному [sourceTreeUri]
     * означає корінь того дерева (користувач обрав саме теку книги).
     */
    val sourceFolderDocId: String? = null,
)

@Entity(
    tableName = "book_characters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("bookId"),
        Index("name"),
    ],
)
data class BookCharacterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val role: String? = null,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("bookId")],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val index: Int,
    val title: String,
    val uri: String,
    val durationMs: Long,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val positionMs: Long,
    val note: String,
    val createdAt: Long,
)

@Entity(tableName = "daily_listening")
data class DailyListeningEntity(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val durationMs: Long,
)

/**
 * Надгробок видаленої книги.
 *
 * Без нього синхронізація вміла лише додавати: книга, видалена на телефоні,
 * поверталася з хмари наступним же злиттям, бо для іншого пристрою вона просто
 * «є в бекапі, а локально немає». Запис про видалення їздить у бекапі нарівні
 * з книгами й живе [TOMBSTONE_TTL_DAYS] днів — далі його ніхто вже не потребує.
 */
@Entity(tableName = "deleted_books")
data class DeletedBookEntity(
    @PrimaryKey val id: String,
    val deletedAt: Long,
)

/**
 * Надгробок персонажа. Без нього злиття не відрізняє «видалили на іншому
 * пристрої» від «там його ніколи не було», і видалений персонаж воскресав із
 * наступною синхронізацією.
 *
 * Без зовнішнього ключа навмисно: надгробок має пережити видалення самої книги,
 * інакше CASCADE забрав би його разом із нею й видалення не доїхало б у хмару.
 */
@Entity(tableName = "deleted_characters")
data class DeletedCharacterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val deletedAt: Long,
)

/**
 * Надгробок закладки. Та сама причина, що й у персонажів: без нього злиття вміє
 * лише додавати, і закладка, стерта на телефоні, поверталася з хмари.
 *
 * Без зовнішнього ключа навмисно — надгробок має пережити книгу.
 */
@Entity(tableName = "deleted_bookmarks")
data class DeletedBookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val deletedAt: Long,
)

/**
 * Надгробок мітки. Ключ складений, бо сама мітка не має id — вона і є пара
 * (книга, слово).
 *
 * Через це в мітки немає й позначки створення, тобто арбітражу «додали заново
 * після видалення» тут не побудувати. Замість нього надгробок знімається в
 * [LibraryRepository.addTag]: якщо ту саму мітку повісили вручну ще раз, запис
 * про видалення більше не діє.
 */
@Entity(tableName = "deleted_tags", primaryKeys = ["bookId", "tag"])
data class DeletedTagEntity(
    val bookId: String,
    val tag: String,
    val deletedAt: Long,
)

/** Скільки тримати надгробок, перш ніж прибрати його з бази й бекапу. */
const val TOMBSTONE_TTL_DAYS = 180L

@Entity(
    tableName = "book_tags",
    primaryKeys = ["bookId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("bookId"), Index("tag")],
)
data class BookTagEntity(
    val bookId: String,
    val tag: String,
)

/**
 * Одне «сидіння» з книгою: [timestamp] — коли почали, [durationMs] — скільки наслухали.
 *
 * Рядок не створюється на кожне скидання лічильника: сусідні відрізки тієї самої
 * книги зливаються в один, доки перерва менша за [SESSION_MERGE_GAP_MS].
 * Раніше запис ішов на кожен флаш, тобто рядок на кожні 30 секунд, і година
 * прослуховування давала сто двадцять однакових карток в історії.
 *
 * Індекс на timestamp: по ньому і сортує екран історії, і чистить pruneSessions.
 */
@Entity(tableName = "listening_sessions", indices = [Index("timestamp")])
data class ListeningSessionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val bookTitle: String,
    val author: String,
    val timestamp: Long,
    val durationMs: Long,
)

/** Найбільша перерва, після якої відтворення тієї самої книги — вже нова сесія. */
const val SESSION_MERGE_GAP_MS = 15 * 60_000L

/**
 * Черга «далі», збережена на диску.
 *
 * Раніше черга жила лише в PlayerUiState: систему нічого не тримає від того, щоб
 * прибрати процес застосунку на паузі, — і список наступних книг зникав мовчки.
 *
 * CASCADE: видалена книга йде з черги сама, без окремого прибирання в репозиторії.
 */
@Entity(
    tableName = "queue_items",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class QueueItemEntity(
    @PrimaryKey val bookId: String,
    val position: Int,
)

data class DayActivity(
    val date: String = "",
    val dayName: String = "",
    val durationMs: Long = 0L,
    val isToday: Boolean = false,
)

data class WeeklyStats(
    val todayMs: Long = 0L,
    val weekMs: Long = 0L,
    val completedBooks: Int = 0,
    val dailyActivity: List<DayActivity> = emptyList(),
)

data class BookWithChapters(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "bookId")
    val chapters: List<ChapterEntity>,
)

/**
 * Тривалість усього, що лежить перед главою під номером [chapterIndex].
 *
 * Рахуємо за полем `index`, а не за позицією в списку: `@Relation` порядку не
 * обіцяє, а `upsertChapters` з REPLACE міняє rowid — тобто після перепривʼязки
 * книги список цілком може прийти в іншому порядку. Це ж робить формулу
 * байдужою до сортування й до індексу поза межами списку.
 */
private fun msBeforeChapter(chapters: List<ChapterEntity>, chapterIndex: Int): Long =
    chapters.filter { it.index < chapterIndex }.sumOf { it.durationMs }

/**
 * Частка прослуханого від усієї книги, 0..1.
 *
 * Єдина формула прогресу в застосунку. Її читають полиця (через [progress]),
 * плеєр (із живої позиції), віджет і дерево Android Auto. Копії розійшлися б
 * тихо: смужка на екрані показувала б одне, картка в машині інше.
 */
fun bookProgressFraction(
    chapters: List<ChapterEntity>,
    chapterIndex: Int,
    positionMs: Long,
    durationMs: Long,
): Float {
    if (durationMs <= 0L) return 0f
    val before = msBeforeChapter(chapters, chapterIndex)
    return ((before + positionMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

data class BookmarkWithBook(
    val bookmark: BookmarkEntity,
    val bookTitle: String,
    val coverPath: String?,
)

data class BackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val books: List<BookWithChapters>,
    val bookmarks: List<BookmarkEntity>,
)

/**
 * Тривалість, розкладена на години, хвилини й секунди.
 *
 * Секунди потрібні для коротких значень: перші хвилини прослуховування
 * інакше показувалися як «0 хв», ніби нічого й не було.
 *
 * Одиниці підставляє UI з ресурсів (див. listeningTimeText): раніше «год»/«хв»
 * були зашиті в код і вибиралися за `locale.language == "en"`, тобто будь-яка
 * третя мова отримувала українські підписи.
 */
fun Long.hoursMinutesSeconds(): Triple<Long, Long, Long> {
    val totalSec = this.coerceAtLeast(0L) / 1000
    return Triple(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
}



enum class LibraryFilter { All, Listening, Finished, Unavailable }

/**
 * Скільки файлів книги не відкривається — і скільки їх усього.
 *
 * Рахуємо за унікальними URI, а не за розділами: один файл, розбитий на розділи
 * мітками глав, зник би один раз, а полиця показала б стільки втрат, скільки в
 * ньому розділів.
 */
data class MissingFiles(val missing: Int, val total: Int) {
    /** Не лишилося жодного доступного файла — книгу лікує лише перепривʼязка теки. */
    val isWhole: Boolean get() = missing >= total
}

/** Період для графіка активності на екрані історії. */
enum class StatsPeriod { Week, Month, Year }

enum class BookSortOrder(val titleRes: Int) {
    LastPlayed(ua.nichnyk.listen.R.string.sort_last_played),
    Title(ua.nichnyk.listen.R.string.sort_title),
    Author(ua.nichnyk.listen.R.string.sort_author),
    Series(ua.nichnyk.listen.R.string.sort_series),
    Progress(ua.nichnyk.listen.R.string.sort_progress),
    AddedAt(ua.nichnyk.listen.R.string.sort_added_at),
}

fun greetingRes(): Int {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> ua.nichnyk.listen.R.string.greeting_morning
        in 12..17 -> ua.nichnyk.listen.R.string.greeting_afternoon
        in 18..22 -> ua.nichnyk.listen.R.string.greeting_evening
        else -> ua.nichnyk.listen.R.string.greeting_night
    }
}


fun Long.formatClock(): String {
    if (this <= 0L) return "0:00"
    val totalSec = this / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun Float.formatSpeed(): String {
    val rounded = (Math.round(this * 100) / 100f)
    return if (rounded == rounded.toInt().toFloat()) {
        "${rounded.toInt()}×"
    } else if ((rounded * 10) == (rounded * 10).toInt().toFloat()) {
        "%.1f×".format(java.util.Locale.US, rounded)
    } else {
        "%.2f×".format(java.util.Locale.US, rounded)
    }
}


fun BookWithChapters.absolutePosition(): Long =
    msBeforeChapter(chapters, book.currentChapterIndex) + book.positionMs

/** Прогрес книги за збереженим станом — та сама формула, що й у плеєра. */
fun BookWithChapters.progress(): Float = bookProgressFraction(
    chapters = chapters,
    chapterIndex = book.currentChapterIndex,
    positionMs = book.positionMs,
    durationMs = book.durationMs,
)

/**
 * Якщо книгу вже дослухано і користувач натискає «грати» без вибору глави,
 * починаємо з початку — інакше ExoPlayer одразу знову дійде до ENDED.
 */
fun replayTarget(
    completed: Boolean,
    requestedChapter: Int,
    requestedPositionMs: Long,
    savedChapter: Int,
    savedPositionMs: Long,
): Pair<Int, Long> {
    if (completed && requestedChapter == savedChapter && requestedPositionMs == savedPositionMs) {
        return 0 to 0L
    }
    return requestedChapter to requestedPositionMs
}

