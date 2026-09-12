package ua.nichnyk.listen.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Міграції БД. Раніше в app/schemas лежала лише схема останньої версії, тому такий тест
 * не можна було написати взагалі: помилка в MIGRATION_* виявилася б тільки на пристрої
 * користувача, який оновлюється зі старої версії, — і у вигляді втраченої полиці.
 *
 * Потребує емулятора або пристрою: `gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ListenDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To8_keepsBooksAndChapters() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed)
                VALUES ('b1', 'Кобзар', 'Шевченко', NULL, 1000, 2000, 180000, 4000, 0, 1.0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chapters (id, bookId, "index", title, uri, durationMs)
                VALUES ('c1', 'b1', 0, 'Розділ 1', 'file:///a.mp3', 180000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO bookmarks
                (id, bookId, chapterId, chapterTitle, positionMs, note, createdAt)
                VALUES ('m1', 'b1', 'c1', 'Розділ 1', 45000, 'нотатка', 3000)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(dbName, 8, true, *allMigrations())

        val db = openMigratedDatabase()
        try {
            val cursor = db.query("SELECT title, positionMs FROM books WHERE id = 'b1'")
            cursor.use {
                assertTrue(it.moveToFirst())
                assertEquals("Кобзар", it.getString(0))
                assertEquals(4000L, it.getLong(1))
            }
            // MIGRATION_2_3 додає startMs/endMs зі значенням за замовчуванням 0.
            db.query("SELECT startMs, endMs FROM chapters WHERE id = 'c1'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
                assertEquals(0L, it.getLong(1))
            }
            db.query("SELECT COUNT(*) FROM bookmarks").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM daily_listening").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM deleted_books").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            assertEmptyNewTables(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2To8_keepsDailyListening() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL("INSERT INTO daily_listening (date, durationMs) VALUES ('2026-01-01', 555)")
        }

        helper.runMigrationsAndValidate(
            dbName,
            8,
            true,
            ListenDatabase.MIGRATION_2_3,
            ListenDatabase.MIGRATION_3_4,
            ListenDatabase.MIGRATION_4_5,
            ListenDatabase.MIGRATION_5_6,
            ListenDatabase.MIGRATION_6_7,
            ListenDatabase.MIGRATION_7_8,
        )

        val db = openMigratedDatabase()
        try {
            db.query("SELECT durationMs FROM daily_listening WHERE date = '2026-01-01'").use {
                assertTrue(it.moveToFirst())
                assertEquals(555L, it.getLong(0))
            }
            assertEmptyNewTables(db)
        } finally {
            db.close()
        }
    }

    /**
     * Оновлення з версії 3 — шлях більшості старих установок: у 3 вже все, крім
     * надгробків і пізніших таблиць.
     */
    @Test
    fun migrate3To8_keepsShelfAndAddsTombstones() {
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed)
                VALUES ('b1', 'Місто', 'Підмогильний', NULL, 1000, 2000, 180000, 4000, 0, 1.5, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chapters (id, bookId, "index", title, uri, durationMs, startMs, endMs)
                VALUES ('c1', 'b1', 0, 'Розділ 1', 'file:///a.m4b', 180000, 500, 1500)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            dbName,
            8,
            true,
            ListenDatabase.MIGRATION_3_4,
            ListenDatabase.MIGRATION_4_5,
            ListenDatabase.MIGRATION_5_6,
            ListenDatabase.MIGRATION_6_7,
            ListenDatabase.MIGRATION_7_8,
        )

        val db = openMigratedDatabase()
        try {
            db.query("SELECT title, positionMs, playbackSpeed FROM books WHERE id = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Місто", it.getString(0))
                assertEquals(4000L, it.getLong(1))
                assertEquals(1.5f, it.getFloat(2), 0.001f)
            }
            db.query("SELECT startMs, endMs FROM chapters WHERE id = 'c1'").use {
                assertTrue(it.moveToFirst())
                assertEquals(500L, it.getLong(0))
                assertEquals(1500L, it.getLong(1))
            }
            db.execSQL("INSERT INTO deleted_books (id, deletedAt) VALUES ('gone', 42)")
            db.query("SELECT deletedAt FROM deleted_books WHERE id = 'gone'").use {
                assertTrue(it.moveToFirst())
                assertEquals(42L, it.getLong(0))
            }
            assertEmptyNewTables(db)
        } finally {
            db.close()
        }
    }

    /**
     * Шлях користувача v1.2.1 (Room 4) → поточна збірка (Room 12).
     * Без цього тесту міграції 4→5 … 7→8 перевірялися б лише на чужому телефоні.
     */
    @Test
    fun migrate4To8_keepsShelfAndAddsTagsAndSessions() {
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed)
                VALUES ('b1', 'Тигролови', 'Багряний', NULL, 1000, 2000, 180000, 4000, 0, 1.0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chapters (id, bookId, "index", title, uri, durationMs, startMs, endMs)
                VALUES ('c1', 'b1', 0, 'Розділ 1', 'file:///a.mp3', 180000, 0, 0)
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO deleted_books (id, deletedAt) VALUES ('gone', 42)")
        }

        helper.runMigrationsAndValidate(
            dbName,
            8,
            true,
            ListenDatabase.MIGRATION_4_5,
            ListenDatabase.MIGRATION_5_6,
            ListenDatabase.MIGRATION_6_7,
            ListenDatabase.MIGRATION_7_8,
        )

        val db = openMigratedDatabase()
        try {
            db.query("SELECT title, positionMs FROM books WHERE id = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Тигролови", it.getString(0))
                assertEquals(4000L, it.getLong(1))
            }
            db.query("SELECT deletedAt FROM deleted_books WHERE id = 'gone'").use {
                assertTrue(it.moveToFirst())
                assertEquals(42L, it.getLong(0))
            }
            assertEmptyNewTables(db)
            // Зовнішній ключ міток має жити після міграції.
            db.execSQL("INSERT INTO book_tags (bookId, tag) VALUES ('b1', 'класика')")
            db.query("SELECT tag FROM book_tags WHERE bookId = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("класика", it.getString(0))
            }
        } finally {
            db.close()
        }
    }

    /**
     * Головне в міграції 6→7 — не нова таблиця, а склеювання історії.
     *
     * До версії 7 рядок писався на кожні 30 секунд відтворення, тож година
     * прослуховування давала сто двадцять карток. Міграція має звести сусідні
     * відрізки однієї книги в одну сесію й лишити окремими ті, між якими
     * реальна перерва або інша книга.
     */
    @Test
    fun migrate6To7_compactsSessionsAndAddsQueue() {
        val base = 1_000_000_000_000L
        val step = 30_000L
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed)
                VALUES ('b1', 'Тіні', 'Коцюбинський', NULL, 1000, 2000, 180000, 0, 0, 1.0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed)
                VALUES ('b2', 'Земля', 'Кобилянська', NULL, 1000, 2000, 180000, 0, 0, 1.0, 0)
                """.trimIndent(),
            )
            // Три суміжні відрізки однієї книги — одна сесія.
            for (i in 1..3) {
                db.execSQL(
                    "INSERT INTO listening_sessions " +
                        "(id, bookId, bookTitle, author, timestamp, durationMs) " +
                        "VALUES ('s$i', 'b1', 'Тіні', 'Коцюбинський', ${base + i * step}, $step)",
                )
            }
            // Та сама книга, але через годину — окрема сесія.
            db.execSQL(
                "INSERT INTO listening_sessions " +
                    "(id, bookId, bookTitle, author, timestamp, durationMs) " +
                    "VALUES ('s4', 'b1', 'Тіні', 'Коцюбинський', ${base + 3_600_000L}, $step)",
            )
            // Інша книга впритул — теж окрема сесія.
            db.execSQL(
                "INSERT INTO listening_sessions " +
                    "(id, bookId, bookTitle, author, timestamp, durationMs) " +
                    "VALUES ('s5', 'b2', 'Земля', 'Кобилянська', ${base + 3_630_000L}, $step)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 7, true, ListenDatabase.MIGRATION_6_7)

        val db = openMigratedDatabase()
        try {
            db.query("SELECT COUNT(*) FROM listening_sessions").use {
                assertTrue(it.moveToFirst())
                assertEquals(3, it.getInt(0))
            }
            // Перша сесія: три відрізки по 30 с злилися, а час початку зсунувся
            // з «кінця першого відрізка» на справжній початок.
            db.query(
                "SELECT timestamp, durationMs FROM listening_sessions WHERE id = 's1'",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(base, it.getLong(0))
                assertEquals(3 * step, it.getLong(1))
            }
            db.query("SELECT durationMs FROM listening_sessions WHERE id = 's4'").use {
                assertTrue(it.moveToFirst())
                assertEquals(step, it.getLong(0))
            }
            db.query("SELECT bookId FROM listening_sessions WHERE id = 's5'").use {
                assertTrue(it.moveToFirst())
                assertEquals("b2", it.getString(0))
            }

            // 7→8 додає колонки з дефолтами; склеєна історія не чіпається.
            db.query("SELECT pinned, series, seriesOrder FROM books WHERE id = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
            }

            // Черга: рядок живе, доки жива книга.
            db.execSQL("INSERT INTO queue_items (bookId, position) VALUES ('b2', 0)")
            db.execSQL("DELETE FROM books WHERE id = 'b2'")
            db.query("SELECT COUNT(*) FROM queue_items").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /**
     * Шлях користувача v1.4.0 (Room 7) → поточна збірка (Room 12).
     * Полиця лишається, пін і серія зʼявляються порожніми.
     */
    @Test
    fun migrate7To8_addsPinnedAndSeries() {
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed)
                VALUES ('b1', 'Кобзар', 'Шевченко', NULL, 1000, 2000, 180000, 4000, 0, 1.0, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(dbName, 8, true, ListenDatabase.MIGRATION_7_8)

        val db = openMigratedDatabase()
        try {
            db.query("SELECT title, positionMs, pinned, series, seriesOrder FROM books WHERE id = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Кобзар", it.getString(0))
                assertEquals(4000L, it.getLong(1))
                assertEquals(0, it.getInt(2))
                assertTrue(it.isNull(3))
                assertTrue(it.isNull(4))
            }
        } finally {
            db.close()
        }
    }

    /**
     * Шлях користувача v1.8.0 (Room 8) → поточна збірка (Room 12).
     *
     * Полиця й прогрес лишаються, `notes` зʼявляється порожнім, `book_characters`
     * створюється з робочим зовнішнім ключем і каскадом.
     */
    @Test
    fun migrate8To9_addsNotesAndCharacters() {
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed, pinned)
                VALUES ('b1', 'Тіні забутих предків', 'Коцюбинський', NULL, 1000, 2000, 180000, 4000, 0, 1.0, 0, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(dbName, 9, true, ListenDatabase.MIGRATION_8_9)

        val db = openMigratedDatabase()
        try {
            db.query("SELECT title, positionMs, notes FROM books WHERE id = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Тіні забутих предків", it.getString(0))
                assertEquals(4000L, it.getLong(1))
                assertTrue(it.isNull(2))
            }

            db.execSQL(
                "INSERT INTO book_characters (id, bookId, name, role, description, createdAt) " +
                    "VALUES ('ch1', 'b1', 'Іван', 'головний герой', NULL, 5000)",
            )
            db.query("SELECT name, role FROM book_characters WHERE bookId = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Іван", it.getString(0))
                assertEquals("головний герой", it.getString(1))
            }

            // Каскад: видалення книги забирає її персонажів. Без нього список
            // дійових осіб переживав би книгу й ламав наступний імпорт того ж id.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM books WHERE id = 'b1'")
            db.query("SELECT COUNT(*) FROM book_characters").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /**
     * Шлях користувача v1.9.0 (Room 9) → поточна збірка (Room 12).
     *
     * Надгробки персонажів навмисно без зовнішнього ключа: вони мають пережити
     * видалення самої книги, інакше CASCADE забрав би їх разом із нею й
     * видалення персонажа не доїхало б у хмару.
     */
    @Test
    fun migrate9To10_addsCharacterTombstones() {
        helper.createDatabase(dbName, 9).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed, pinned)
                VALUES ('b1', 'Місто', 'Підмогильний', NULL, 1000, 2000, 180000, 4000, 0, 1.0, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO book_characters (id, bookId, name, role, description, createdAt) " +
                    "VALUES ('ch1', 'b1', 'Степан Радченко', NULL, NULL, 5000)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 10, true, *allMigrations())

        val db = openMigratedDatabase()
        try {
            // Наявні персонажі міграцію переживають.
            db.query("SELECT name FROM book_characters WHERE id = 'ch1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Степан Радченко", it.getString(0))
            }

            db.execSQL("INSERT INTO deleted_characters (id, bookId, deletedAt) VALUES ('ch1', 'b1', 9000)")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM books WHERE id = 'b1'")

            // Персонаж пішов за книгою через CASCADE, а надгробок лишився:
            // саме він повідомить іншим пристроям, що видалення було.
            db.query("SELECT COUNT(*) FROM book_characters").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT bookId, deletedAt FROM deleted_characters WHERE id = 'ch1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("b1", it.getString(0))
                assertEquals(9000L, it.getLong(1))
            }
        } finally {
            db.close()
        }
    }

    /**
     * 10 → 11: надгробки закладок і міток плюс `books.renamedAt`.
     *
     * До цієї версії надгробки мали лише книги й персонажі, тож закладка чи мітка,
     * прибрана на одному пристрої, поверталася з хмари наступним же злиттям.
     *
     * `renamedAt` заповнюється нулем, а не поточним часом: інакше вся вже наявна
     * полиця виглядала б щойно перейменованою й перезаписала б назви на другому
     * пристрої при першому ж злитті.
     */
    @Test
    fun migrate10To11_addsBookmarkAndTagTombstonesAndRenamedAt() {
        helper.createDatabase(dbName, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed, pinned, notes)
                VALUES ('b1', 'Тигролови', 'Багряний', NULL, 1000, 2000, 180000, 4000, 0, 1.0, 0, 0, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO bookmarks (id, bookId, chapterId, chapterTitle, positionMs, note, createdAt) " +
                    "VALUES ('bm1', 'b1', 'c1', 'Розділ 1', 5000, 'цитата', 6000)",
            )
            db.execSQL("INSERT INTO book_tags (bookId, tag) VALUES ('b1', 'класика')")
        }

        helper.runMigrationsAndValidate(dbName, 11, true, *allMigrations())

        val db = openMigratedDatabase()
        try {
            // Полиця, закладки й мітки міграцію переживають.
            db.query("SELECT note FROM bookmarks WHERE id = 'bm1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("цитата", it.getString(0))
            }
            db.query("SELECT tag FROM book_tags WHERE bookId = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("класика", it.getString(0))
            }

            // Наявні книги не вважаються перейменованими.
            db.query("SELECT renamedAt FROM books WHERE id = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }

            // Обидва надгробки переживають CASCADE від книги — саме вони повідомлять
            // іншим пристроям, що видалення було.
            db.execSQL("INSERT INTO deleted_bookmarks (id, bookId, deletedAt) VALUES ('bm1', 'b1', 9000)")
            db.execSQL("INSERT INTO deleted_tags (bookId, tag, deletedAt) VALUES ('b1', 'класика', 9000)")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM books WHERE id = 'b1'")

            db.query("SELECT COUNT(*) FROM bookmarks").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM book_tags").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT bookId, deletedAt FROM deleted_bookmarks WHERE id = 'bm1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("b1", it.getString(0))
                assertEquals(9000L, it.getLong(1))
            }
            db.query("SELECT deletedAt FROM deleted_tags WHERE bookId = 'b1' AND tag = 'класика'").use {
                assertTrue(it.moveToFirst())
                assertEquals(9000L, it.getLong(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate11To12_addsSourceFolderColumns() {
        helper.createDatabase(dbName, 11).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed, pinned, notes, renamedAt)
                VALUES ('b1', 'Тигролови', 'Багряний', NULL, 1000, 2000, 180000, 4000, 0, 1.0, 0, 0, NULL, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(dbName, 12, true, *allMigrations())

        val db = openMigratedDatabase()
        try {
            db.query("SELECT sourceTreeUri, sourceFolderDocId FROM books WHERE id = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertTrue(it.isNull(1))
            }
        } finally {
            db.close()
        }
    }

    /**
     * Найдовший шлях, який узагалі можливий у користувача: версія 1 -> поточна.
     *
     * Решта тестів перевіряє окремі сходинки або доходить до проміжної версії.
     * Але оновлюється не сходинка, а людина, яка поставила перший реліз і
     * повернулася через рік: у неї всі одинадцять міграцій виконуються поспіль в
     * одній транзакції, і зламати полицю може саме їхнє поєднання — наприклад,
     * колонка, яку пізніша міграція чекає з типом, що його змінила раніша.
     *
     * Перевіряємо не лише те, що база відкрилася, а що дані дійшли: назва,
     * позиція, закладка й розділ — усе, чим людина дорожить.
     */
    @Test
    fun migrate1ToCurrent_keepsEverythingTheUserCaresAbout() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                (id, title, author, coverPath, addedAt, lastPlayedAt, durationMs, positionMs,
                 currentChapterIndex, playbackSpeed, completed)
                VALUES ('b1', 'Лісова пісня', 'Леся Українка', NULL, 1000, 2000, 180000, 4000, 0, 1.5, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chapters (id, bookId, "index", title, uri, durationMs)
                VALUES ('c1', 'b1', 0, 'Дія перша', 'file:///a.mp3', 180000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO bookmarks
                (id, bookId, chapterId, chapterTitle, positionMs, note, createdAt)
                VALUES ('m1', 'b1', 'c1', 'Дія перша', 45000, 'Той, хто греблі рве', 3000)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(dbName, ListenDatabase.VERSION, true, *allMigrations())

        val db = openMigratedDatabase()
        try {
            db.query(
                "SELECT title, author, positionMs, playbackSpeed, completed FROM books WHERE id = 'b1'",
            ).use {
                assertTrue("книга не пережила повний ланцюг міграцій", it.moveToFirst())
                assertEquals("Лісова пісня", it.getString(0))
                assertEquals("Леся Українка", it.getString(1))
                assertEquals(4000L, it.getLong(2))
                assertEquals(1.5f, it.getFloat(3), 0.001f)
                assertEquals(0, it.getInt(4))
            }
            db.query("SELECT title, uri FROM chapters WHERE id = 'c1'").use {
                assertTrue("розділ не пережив повний ланцюг", it.moveToFirst())
                assertEquals("Дія перша", it.getString(0))
                assertEquals("file:///a.mp3", it.getString(1))
            }
            db.query("SELECT note, positionMs FROM bookmarks WHERE id = 'm1'").use {
                assertTrue("закладка не пережила повний ланцюг", it.moveToFirst())
                assertEquals("Той, хто греблі рве", it.getString(0))
                assertEquals(45000L, it.getLong(1))
            }
            // Колонки, додані найпізнішими міграціями, мають бути на місці й порожні.
            db.query("SELECT renamedAt, sourceTreeUri, sourceFolderDocId FROM books WHERE id = 'b1'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
                assertTrue("sourceTreeUri має лишитися порожнім", it.isNull(1))
                assertTrue("sourceFolderDocId має лишитися порожнім", it.isNull(2))
            }
            assertEmptyNewTables(db)
        } finally {
            db.close()
        }
    }

    private fun assertEmptyNewTables(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        for (table in listOf("book_tags", "listening_sessions", "queue_items", "book_characters")) {
            db.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    /**
     * Усі міграції, які знає збірка.
     *
     * Список має доходити до поточної версії бази: [openMigratedDatabase] відкриває
     * базу справжнім Room, а той вимагає шлях до @Database(version = …).
     * Коли тут бракувало останньої міграції, весь цей клас падав не на схемі,
     * а на «A migration from N to N+1 was required but not found».
     */
    private fun allMigrations() = arrayOf(
        ListenDatabase.MIGRATION_1_2,
        ListenDatabase.MIGRATION_2_3,
        ListenDatabase.MIGRATION_3_4,
        ListenDatabase.MIGRATION_4_5,
        ListenDatabase.MIGRATION_5_6,
        ListenDatabase.MIGRATION_6_7,
        ListenDatabase.MIGRATION_7_8,
        ListenDatabase.MIGRATION_8_9,
        ListenDatabase.MIGRATION_9_10,
        ListenDatabase.MIGRATION_10_11,
        ListenDatabase.MIGRATION_11_12,
    )

    /** Відкриття справжнім Room перевіряє, що підсумкова схема збігається з оголошеною версією. */
    private fun openMigratedDatabase() =
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ListenDatabase::class.java,
            dbName,
        )
            .addMigrations(*allMigrations())
            .build()
            .also { helper.closeWhenFinished(it) }
            .openHelper
            .writableDatabase
}
