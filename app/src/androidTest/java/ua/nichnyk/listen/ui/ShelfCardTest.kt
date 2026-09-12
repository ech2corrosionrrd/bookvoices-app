package ua.nichnyk.listen.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import ua.nichnyk.listen.R
import org.junit.Rule
import org.junit.Test
import ua.nichnyk.listen.data.AppTheme
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.MissingFiles
import ua.nichnyk.listen.ui.components.BookCard
import ua.nichnyk.listen.ui.components.BookRow
import ua.nichnyk.listen.ui.theme.ListenTheme

/**
 * Картки полиці — перевірка намальованого, а не даних під ним.
 *
 * Досі весь інтерфейс перевірявся очима: юніт-тести знали про межу Freemium,
 * сортування й локалі, але жоден не відкривав жодного екрана. Через це вада
 * «екран налаштувань питає біллінг замість межі» прожила два релізи — дані були
 * правильні, показувалося неправильне.
 *
 * Тут навмисно не цілий екран: [ua.nichnyk.listen.ui.screens.LibraryScreen]
 * тягне ViewModel, репозиторій, базу й плеєр. Картка — те місце, де рішення
 * стають пікселями, і саме її змінювали останні правки.
 */
class ShelfCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun book(
        id: String = "b1",
        title: String = "Лісова пісня",
        author: String = "Леся Українка",
        chapters: Int = 20,
    ) = BookWithChapters(
        book = BookEntity(
            id = id,
            title = title,
            author = author,
            coverPath = null,
            addedAt = 0L,
            lastPlayedAt = null,
            durationMs = 3_600_000L,
            positionMs = 0L,
            currentChapterIndex = 0,
            playbackSpeed = 1f,
            completed = false,
        ),
        chapters = (0 until chapters).map {
            ChapterEntity(
                id = "$id-c$it",
                bookId = id,
                index = it,
                title = "Розділ ${it + 1}",
                uri = "file:///$id/$it",
                durationMs = 180_000L,
            )
        },
    )

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { ListenTheme(AppTheme.NIGHT) { content() } }
    }

    /** Скільки саме файлів зникло — цифрою, а не самим трикутником. */
    @Test
    fun missingBadgeShowsHowManyFilesAreGone() {
        show {
            BookCard(book(), onClick = {}, missingFiles = MissingFiles(missing = 3, total = 20))
        }
        compose.onNodeWithText("3/20").assertIsDisplayed()
    }

    /** Зникла вся книга — те саме правило, без окремого німого вигляду. */
    @Test
    fun missingBadgeShowsWholeLossWithTheSameRule() {
        show {
            BookRow(book(), onClick = {}, missingFiles = MissingFiles(missing = 20, total = 20))
        }
        compose.onNodeWithText("20/20").assertIsDisplayed()
    }

    /** Книга ціла — бейджа немає взагалі. */
    @Test
    fun cardWithoutMissingFilesHasNoBadge() {
        show { BookCard(book(), onClick = {}) }
        compose.onAllNodesWithText("0/20").assertCountEquals(0)
    }

    /**
     * Читачам екрана — ціле речення, а не «3 навскіс 20».
     *
     * Перевіряємо не текст, а `contentDescription`: у бейджі вони навмисно різні.
     */
    @Test
    fun missingBadgeSpeaksAFullSentence() {
        // Очікуваний текст беремо з ресурсів, а не пишемо рядком: тест іде на
        // системній локалі пристрою, і зашите українське речення падало б на
        // англійському емуляторі — саме так цей тест і впав уперше.
        val expected = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.book_files_missing_count, 20, 3, 20)
        show {
            BookCard(book(), onClick = {}, missingFiles = MissingFiles(missing = 3, total = 20))
        }
        compose.onNodeWithContentDescription(expected).assertExists()
    }

    /**
     * Довга назва показується цілком.
     *
     * [ua.nichnyk.listen.BookTitleDisplayTest] стежить за цим у джерелах, шукаючи
     * `maxLines` поруч із назвою книги. Але обрізати можна й інакше — вузьким
     * контейнером чи `TextOverflow` без `maxLines`, — тож тут перевірка на
     * намальованому: вузол із повним текстом має існувати.
     */
    @Test
    fun longTitleIsDrawnInFull() {
        val long = "Літопис самовидця про війни Богдана Хмельницького та міжусобиці"
        show { BookRow(book(title = long), onClick = {}) }
        compose.onNodeWithText(long).assertIsDisplayed()
    }

    /** Автор і назва — різні рядки: підзаголовок обрізати можна, назву ні. */
    @Test
    fun cardShowsTitleAndAuthorSeparately() {
        show { BookRow(book(), onClick = {}) }
        compose.onNodeWithText("Лісова пісня").assertIsDisplayed()
        compose.onNodeWithText("Леся Українка", substring = true).assertIsDisplayed()
    }
}
