package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.RelinkMatcher
import ua.nichnyk.listen.data.RelinkMatcher.Candidate
import ua.nichnyk.listen.data.RelinkResult

/**
 * Зіставлення глав із файлами нової теки.
 *
 * Ціна помилки тут висока й несиметрична: не зіставити — прикро, але користувач
 * спробує іншу теку; зіставити з чужим файлом — тихо зіпсована книга, у якій
 * розділ 3 раптом читає інший текст.
 */
class RelinkMatcherTest {

    private fun saf(folder: String, name: String) =
        "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2F$folder%2F$name"

    @Test
    fun movedFolderMatchesByFileName() {
        val old = listOf(saf("Kobzar", "01.mp3"), saf("Kobzar", "02.mp3"))
        val candidates = listOf(
            Candidate("01.mp3", "content://new/1"),
            Candidate("02.mp3", "content://new/2"),
        )
        val result = RelinkMatcher.match(old, candidates)
        assertEquals("content://new/1", result[old[0]])
        assertEquals("content://new/2", result[old[1]])
    }

    @Test
    fun fileNameMatchIgnoresCase() {
        val old = listOf("file:///old/Chapter One.MP3")
        val result = RelinkMatcher.match(old, listOf(Candidate("chapter one.mp3", "content://new/1")))
        assertEquals("content://new/1", result[old[0]])
    }

    @Test
    fun extraFilesInTheNewFolderDoNotBreakMatching() {
        val old = listOf("file:///old/02.mp3")
        val candidates = listOf(
            Candidate("01.mp3", "content://new/1"),
            Candidate("02.mp3", "content://new/2"),
            Candidate("cover.jpg", "content://new/img"),
        )
        assertEquals("content://new/2", RelinkMatcher.match(old, candidates)[old[0]])
    }

    @Test
    fun singleFileBookIsMatchedOnce() {
        // m4b: усі глави дивляться в один файл, тому й зіставлення одне.
        val single = "file:///old/book.m4b"
        val old = listOf(single, single, single)
        val result = RelinkMatcher.match(old, listOf(Candidate("book.m4b", "content://new/b")))
        assertEquals(1, result.size)
        assertEquals("content://new/b", result[single])
    }

    @Test
    fun renamedFilesFallBackToNaturalOrderWhenCountsMatch() {
        val old = listOf("file:///old/01.mp3", "file:///old/02.mp3", "file:///old/10.mp3")
        val candidates = listOf(
            Candidate("Розділ 10.mp3", "content://new/10"),
            Candidate("Розділ 01.mp3", "content://new/1"),
            Candidate("Розділ 02.mp3", "content://new/2"),
        )
        val result = RelinkMatcher.match(old, candidates)
        assertEquals("content://new/1", result["file:///old/01.mp3"])
        assertEquals("content://new/2", result["file:///old/02.mp3"])
        // Натуральний порядок: 10 йде після 2, а не між 1 і 2.
        assertEquals("content://new/10", result["file:///old/10.mp3"])
    }

    @Test
    fun positionalFallbackIsRefusedWhenCountsDiffer() {
        // Три глави, два чужі файли — зшивати наосліп не можна.
        val old = listOf("file:///old/a.mp3", "file:///old/b.mp3", "file:///old/c.mp3")
        val candidates = listOf(Candidate("x.mp3", "content://new/x"), Candidate("y.mp3", "content://new/y"))
        assertTrue(RelinkMatcher.match(old, candidates).isEmpty())
    }

    @Test
    fun partialNameMatchLeavesTheRestAloneWhenCountsDiffer() {
        val old = listOf("file:///old/01.mp3", "file:///old/02.mp3", "file:///old/03.mp3")
        val candidates = listOf(
            Candidate("01.mp3", "content://new/1"),
            Candidate("хтозна.mp3", "content://new/z"),
        )
        val result = RelinkMatcher.match(old, candidates)
        // 01 зіставлено за іменем; лишилося дві глави й один файл — далі не гадаємо.
        assertEquals(1, result.size)
        assertEquals("content://new/1", result["file:///old/01.mp3"])
    }

    @Test
    fun oneCandidateIsNeverUsedTwice() {
        val old = listOf("file:///a/01.mp3", "file:///b/01.mp3")
        val candidates = listOf(Candidate("01.mp3", "content://new/1"), Candidate("02.mp3", "content://new/2"))
        val result = RelinkMatcher.match(old, candidates)
        assertEquals(2, result.values.toSet().size)
    }

    @Test
    fun emptyInputsGiveEmptyResult() {
        assertTrue(RelinkMatcher.match(emptyList(), listOf(Candidate("a.mp3", "u"))).isEmpty())
        assertTrue(RelinkMatcher.match(listOf("file:///a.mp3"), emptyList()).isEmpty())
    }

    @Test
    fun fileNameIsExtractedFromSafDocumentIds() {
        assertEquals("01.mp3", RelinkMatcher.fileNameOf(saf("Kobzar", "01.mp3")))
        assertEquals("book.m4b", RelinkMatcher.fileNameOf("file:///storage/Books/book.m4b"))
        assertEquals("a b.mp3", RelinkMatcher.fileNameOf("file:///storage/a%20b.mp3"))
        assertEquals("track.mp3", RelinkMatcher.fileNameOf("file:///storage/track.mp3?x=1"))
    }

    @Test
    fun resultReportsCompleteness() {
        assertTrue(RelinkResult(matched = 3, total = 3).isComplete)
        assertTrue(RelinkResult(matched = 0, total = 3).isEmpty)
        assertEquals(false, RelinkResult(matched = 2, total = 3).isComplete)
        assertEquals(false, RelinkResult(matched = 0, total = 0).isComplete)
    }

    @Test
    fun newFilesSkipsKnownUriAndFileName() {
        val known = listOf(saf("Kobzar", "01.mp3"), saf("Kobzar", "02.mp3"))
        val candidates = listOf(
            Candidate("01.mp3", "content://same/1", relativePath = "01.mp3"),
            Candidate("02.mp3", saf("Kobzar", "02.mp3"), relativePath = "02.mp3"),
            Candidate("03.mp3", "content://new/3", relativePath = "03.mp3"),
            Candidate("10.mp3", "content://new/10", relativePath = "10.mp3"),
        )
        val fresh = RelinkMatcher.newFiles(known, candidates)
        assertEquals(listOf("03.mp3", "10.mp3"), fresh.map { it.name })
    }

    @Test
    fun newFilesTreatsSingleM4bAsAlreadyKnown() {
        val single = "file:///old/book.m4b"
        val known = listOf(single, single, single)
        val fresh = RelinkMatcher.newFiles(
            known,
            listOf(
                Candidate("book.m4b", "content://elsewhere/book.m4b", relativePath = "book.m4b"),
                Candidate("bonus.mp3", "content://elsewhere/bonus.mp3", relativePath = "bonus.mp3"),
            ),
        )
        assertEquals(listOf("bonus.mp3"), fresh.map { it.name })
    }

    @Test
    fun newFilesKeepsSameBasenameOnDifferentDiscs() {
        val known = listOf(saf("Book/CD1", "01.mp3"))
        val candidates = listOf(
            Candidate("01.mp3", saf("Book/CD1", "01.mp3"), relativePath = "CD1/01.mp3"),
            Candidate("01.mp3", "content://new/cd2/01", relativePath = "CD2/01.mp3"),
        )
        val fresh = RelinkMatcher.newFiles(known, candidates)
        assertEquals(listOf("CD2/01.mp3"), fresh.map { it.relativePath })
    }
}
