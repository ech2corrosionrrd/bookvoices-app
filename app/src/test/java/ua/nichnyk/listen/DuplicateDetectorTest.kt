package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.DuplicateDetector

/**
 * Розпізнавання вже доданої книги.
 *
 * Ціна помилки несиметрична: пропущений дублікат — це друга копія книги на полиці,
 * а хибне спрацювання — книга, яку користувач не може додати взагалі й не розуміє чому.
 */
class DuplicateDetectorTest {

    private fun existing(
        id: String,
        title: String,
        chapters: List<Triple<String, String, Long>>,
    ) = BookWithChapters(
        book = BookEntity(
            id = id,
            title = title,
            author = "Автор",
            coverPath = null,
            addedAt = 0L,
            lastPlayedAt = null,
            durationMs = chapters.sumOf { it.third },
            positionMs = 0L,
            currentChapterIndex = 0,
            playbackSpeed = 1f,
            completed = false,
        ),
        chapters = chapters.mapIndexed { i, (chTitle, uri, dur) ->
            ChapterEntity(
                id = "$id-$i",
                bookId = id,
                index = i,
                title = chTitle,
                uri = uri,
                durationMs = dur,
            )
        },
    )

    private val shelf = listOf(
        existing(
            "kobzar", "Кобзар",
            listOf(
                Triple("Розділ 1", "content://tree/kobzar/1.mp3", 1000L),
                Triple("Розділ 2", "content://tree/kobzar/2.mp3", 2000L),
            ),
        ),
    )

    @Test
    fun sameUriSetIsDuplicateEvenAfterRename() {
        val found = DuplicateDetector.findIn(
            books = shelf,
            title = "Зовсім інша назва",
            chapterTitles = listOf("байдуже", "байдуже"),
            durations = listOf(1L, 2L),
            uris = listOf("content://tree/kobzar/1.mp3", "content://tree/kobzar/2.mp3"),
        )
        assertEquals("kobzar", found?.book?.id)
    }

    @Test
    fun uriOrderDoesNotMatter() {
        val found = DuplicateDetector.findIn(
            books = shelf,
            title = "Кобзар",
            chapterTitles = listOf("Розділ 2", "Розділ 1"),
            durations = listOf(2000L, 1000L),
            uris = listOf("content://tree/kobzar/2.mp3", "content://tree/kobzar/1.mp3"),
        )
        assertNotNull(found)
    }

    @Test
    fun sameContentFromAnotherFolderIsDuplicate() {
        val found = DuplicateDetector.findIn(
            books = shelf,
            title = "кобзар",
            chapterTitles = listOf("Розділ 1", "Розділ 2"),
            durations = listOf(1000L, 2000L),
            uris = listOf("file:///sdcard/copy/1.mp3", "file:///sdcard/copy/2.mp3"),
        )
        assertEquals("kobzar", found?.book?.id)
    }

    @Test
    fun sameTitleButDifferentChapterCountIsNotDuplicate() {
        val found = DuplicateDetector.findIn(
            books = shelf,
            title = "Кобзар",
            chapterTitles = listOf("Розділ 1"),
            durations = listOf(1000L),
            uris = listOf("file:///sdcard/other/1.mp3"),
        )
        assertNull(found)
    }

    @Test
    fun sameTitleButDifferentDurationsIsNotDuplicate() {
        // Інше видання: ті самі назви розділів, інша начитка.
        val found = DuplicateDetector.findIn(
            books = shelf,
            title = "Кобзар",
            chapterTitles = listOf("Розділ 1", "Розділ 2"),
            durations = listOf(1111L, 2222L),
            uris = listOf("file:///sdcard/other/1.mp3", "file:///sdcard/other/2.mp3"),
        )
        assertNull(found)
    }

    @Test
    fun subsetOfUrisIsNotDuplicate() {
        // Половина тієї самої теки — це не та сама книга, і імпорт має пройти.
        val found = DuplicateDetector.findIn(
            books = shelf,
            title = "Кобзар, частина 1",
            chapterTitles = listOf("Розділ 1"),
            durations = listOf(1000L),
            uris = listOf("content://tree/kobzar/1.mp3"),
        )
        assertNull(found)
    }

    @Test
    fun emptyShelfNeverMatches() {
        val found = DuplicateDetector.findIn(
            books = emptyList(),
            title = "Кобзар",
            chapterTitles = listOf("Розділ 1"),
            durations = listOf(1000L),
            uris = listOf("content://tree/kobzar/1.mp3"),
        )
        assertNull(found)
    }

    @Test
    fun fingerprintIgnoresCaseAndSurroundingSpace() {
        assertEquals(
            DuplicateDetector.fingerprint(listOf(" Розділ 1 "), listOf(10L)),
            DuplicateDetector.fingerprint(listOf("розділ 1"), listOf(10L)),
        )
    }

    @Test
    fun fingerprintSeparatesTitleFromDuration() {
        // Без роздільника «Розділ 1» + 12 і «Розділ» + 112 дали б однаковий відбиток.
        val a = DuplicateDetector.fingerprint(listOf("Розділ 1"), listOf(12L))
        val b = DuplicateDetector.fingerprint(listOf("Розділ"), listOf(112L))
        org.junit.Assert.assertNotEquals(a, b)
    }

    @Test
    fun overlapsUrisWhenAnyChapterShared() {
        org.junit.Assert.assertTrue(
            DuplicateDetector.overlapsUris(
                shelf,
                listOf("content://tree/kobzar/1.mp3", "content://tree/other/9.mp3"),
            ),
        )
    }

    @Test
    fun overlapsUrisFalseWhenDisjoint() {
        org.junit.Assert.assertFalse(
            DuplicateDetector.overlapsUris(
                shelf,
                listOf("content://tree/newbook/1.mp3", "content://tree/newbook/2.mp3"),
            ),
        )
    }

    @Test
    fun overlapsUrisEmptyIncomingCountsAsOverlap() {
        org.junit.Assert.assertTrue(DuplicateDetector.overlapsUris(shelf, emptyList()))
    }
}
