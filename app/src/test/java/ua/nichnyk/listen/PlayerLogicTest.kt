package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.progress
import ua.nichnyk.listen.playback.PlayerUiState
import ua.nichnyk.listen.playback.SeekAction
import ua.nichnyk.listen.playback.resolvePlayableStart
import ua.nichnyk.listen.playback.seekTarget

/**
 * Логіка плеєра, яку не перевірити через живий MediaController:
 * перемотування через межі глави та вибір стартової глави, коли частина файлів зникла.
 */
class PlayerLogicTest {

    // --- перемотування ----------------------------------------------------

    @Test
    fun seekStaysInsideChapter() {
        assertEquals(
            SeekAction.To(45_000L),
            seekTarget(30_000L, 600_000L, 15_000L, hasPrevious = true, hasNext = true),
        )
        assertEquals(
            SeekAction.To(15_000L),
            seekTarget(30_000L, 600_000L, -15_000L, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun seekBackBeforeStartGoesToPreviousChapter() {
        assertEquals(
            SeekAction.PreviousChapter,
            seekTarget(5_000L, 600_000L, -15_000L, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun seekBackInFirstChapterClampsToZero() {
        // Першої глави немає куди відмотувати — впираємось у нуль, а не в -10 с.
        assertEquals(
            SeekAction.To(0L),
            seekTarget(5_000L, 600_000L, -15_000L, hasPrevious = false, hasNext = true),
        )
    }

    @Test
    fun seekForwardPastEndGoesToNextChapter() {
        assertEquals(
            SeekAction.NextChapter,
            seekTarget(595_000L, 600_000L, 30_000L, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun seekForwardInLastChapterClampsToDuration() {
        assertEquals(
            SeekAction.To(600_000L),
            seekTarget(595_000L, 600_000L, 30_000L, hasPrevious = true, hasNext = false),
        )
    }

    @Test
    fun seekWithUnknownDurationNeverSkipsChapter() {
        // На початку буферизації duration ще 0/-1. Якби її вважали межею глави,
        // кнопка «вперед» гортала б книгу замість перемотування.
        assertEquals(
            SeekAction.To(30_000L),
            seekTarget(0L, 0L, 30_000L, hasPrevious = true, hasNext = true),
        )
        assertEquals(
            SeekAction.To(30_000L),
            seekTarget(0L, -1L, 30_000L, hasPrevious = false, hasNext = true),
        )
    }

    @Test
    fun seekExactlyAtChapterEndCountsAsOverrun() {
        // target == duration означає «за межею»: інакше плеєр застрягав би
        // на останній мілісекунді глави.
        assertEquals(
            SeekAction.NextChapter,
            seekTarget(570_000L, 600_000L, 30_000L, hasPrevious = false, hasNext = true),
        )
    }

    // --- вибір стартової глави -------------------------------------------

    @Test
    fun startKeepsPositionWhenRequestedChapterIsPlayable() {
        assertEquals(
            1 to 42_000L,
            resolvePlayableStart(1, 42_000L, listOf(true, true, true)),
        )
    }

    @Test
    fun startFallsBackToFirstPlayableAndDropsPosition() {
        // Файл збереженої глави зник (переміщений/видалений). Стара позиція для іншої
        // глави безглузда й легко вилітає за межі файла, тому починаємо з нуля.
        assertEquals(
            2 to 0L,
            resolvePlayableStart(0, 90_000L, listOf(false, false, true)),
        )
    }

    @Test
    fun startReturnsNullWhenNothingPlayable() {
        assertNull(resolvePlayableStart(0, 0L, listOf(false, false)))
        assertNull(resolvePlayableStart(0, 0L, emptyList()))
    }

    @Test
    fun startClampsOutOfRangeChapterIndex() {
        // currentChapterIndex із БД може вказувати за межі, якщо книгу перезібрали.
        assertEquals(2 to 5_000L, resolvePlayableStart(99, 5_000L, listOf(true, true, true)))
        assertEquals(0 to 5_000L, resolvePlayableStart(-3, 5_000L, listOf(true, true, true)))
    }

    @Test
    fun startNormalisesNegativePosition() {
        assertEquals(0 to 0L, resolvePlayableStart(0, -1_000L, listOf(true)))
    }

    // --- прогрес книги ----------------------------------------------------

    private fun state(chapterIndex: Int, positionMs: Long, totalMs: Long) = PlayerUiState(
        book = BookWithChapters(
            book = BookEntity(
                id = "b", title = "T", author = "A", coverPath = null,
                addedAt = 0L, lastPlayedAt = null, durationMs = totalMs,
                positionMs = 0L, currentChapterIndex = 0, playbackSpeed = 1f, completed = false,
            ),
            chapters = List(3) { i ->
                ChapterEntity("c$i", "b", i, "Ch$i", "file:///$i.mp3", 100_000L)
            },
        ),
        chapterIndex = chapterIndex,
        positionMs = positionMs,
    )

    @Test
    fun bookProgressCountsPreviousChapters() {
        // Друга глава, півхвилини від її початку: (100000 + 30000) / 300000
        assertEquals(0.4333f, state(1, 30_000L, 300_000L).bookProgress, 0.001f)
        assertEquals(0f, state(0, 0L, 300_000L).bookProgress, 0.0001f)
        assertEquals(1f, state(2, 100_000L, 300_000L).bookProgress, 0.0001f)
    }

    @Test
    fun bookProgressIsZeroWithoutDuration() {
        // Тривалість ще не порахована — шкала не має стрибати на 100 %.
        assertEquals(0f, state(1, 30_000L, 0L).bookProgress, 0.0001f)
    }

    @Test
    fun bookProgressNeverExceedsOne() {
        // Позиція більша за заявлену тривалість буває, коли метадані брешуть.
        assertEquals(1f, state(2, 500_000L, 300_000L).bookProgress, 0.0001f)
    }

    @Test
    fun currentChapterFollowsIndexAndSurvivesOutOfRange() {
        assertEquals("c1", state(1, 0L, 300_000L).chapter?.id)
        assertNull(state(7, 0L, 300_000L).chapter)
    }

    @Test
    fun bookProgressSurvivesIndexOutsideTheChapterList() {
        // Індекс глави приходить і з бази, і з плеєра, і жодне джерело не
        // обіцяє, що він у межах списку. `take()` з від'ємним аргументом кидає.
        assertEquals(1f, state(9, 0L, 300_000L).bookProgress, 0.0001f)
        assertEquals(0f, state(-2, 0L, 300_000L).bookProgress, 0.0001f)
    }

    // --- той самий прогрес, але зі збереженого стану -----------------------

    /**
     * Дерево Android Auto бере прогрес не з живого плеєра, а з бази: у машині
     * картка книги має показувати, де слухач зупинився минулого разу. Формула
     * спільна з [PlayerUiState.bookProgress] саме щоб ці два числа не розійшлися.
     */
    private fun stored(chapterIndex: Int, positionMs: Long, totalMs: Long) = BookWithChapters(
        book = BookEntity(
            id = "b", title = "T", author = "A", coverPath = null,
            addedAt = 0L, lastPlayedAt = null, durationMs = totalMs,
            positionMs = positionMs, currentChapterIndex = chapterIndex,
            playbackSpeed = 1f, completed = false,
        ),
        chapters = List(3) { i ->
            ChapterEntity("c$i", "b", i, "Ch$i", "file:///$i.mp3", 100_000L)
        },
    )

    @Test
    fun storedProgressMatchesLivePlayerForTheSamePosition() {
        assertEquals(
            state(1, 30_000L, 300_000L).bookProgress,
            stored(1, 30_000L, 300_000L).progress(),
            0.0001f,
        )
        assertEquals(0f, stored(0, 0L, 300_000L).progress(), 0.0001f)
        assertEquals(1f, stored(2, 100_000L, 300_000L).progress(), 0.0001f)
    }

    @Test
    fun storedProgressIgnoresTheOrderChaptersArriveIn() {
        // Room не обіцяє порядок у @Relation, а upsertChapters із REPLACE міняє
        // rowid — тобто після перепривʼязки книги список цілком може прийти
        // перевернутим. Формула рахує за полем index, тож їй усе одно.
        val shuffled = stored(1, 30_000L, 300_000L).let { it.copy(chapters = it.chapters.reversed()) }
        assertEquals(stored(1, 30_000L, 300_000L).progress(), shuffled.progress(), 0.0001f)
        assertEquals(0.4333f, shuffled.progress(), 0.001f)
    }
}
