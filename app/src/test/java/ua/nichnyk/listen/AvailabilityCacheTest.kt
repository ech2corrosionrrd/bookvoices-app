package ua.nichnyk.listen

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ua.nichnyk.listen.data.AvailabilityCache
import ua.nichnyk.listen.data.BookEntity
import ua.nichnyk.listen.data.BookWithChapters
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.withoutGone

class AvailabilityCacheTest {

    private var now = 0L
    private var probes = 0

    private fun cache(gone: Set<String> = setOf("a")) = AvailabilityCache(
        probe = { _ ->
            probes++
            gone
        },
        ttlMs = 1_000L,
        clock = { now },
    )

    @Test
    fun secondCallWithinTtlDoesNotProbeAgain() = runBlocking {
        val c = cache()
        assertEquals(setOf("a"), c.inaccessible(listOf("a", "b")))
        now = 999L
        assertEquals(setOf("a"), c.inaccessible(listOf("b", "a")))
        assertEquals(1, probes)
    }

    @Test
    fun callAfterTtlProbesAgain() = runBlocking {
        val c = cache()
        c.inaccessible(listOf("a"))
        now = 1_000L
        c.inaccessible(listOf("a"))
        assertEquals(2, probes)
    }

    @Test
    fun changedFileSetProbesAgain() = runBlocking {
        val c = cache()
        c.inaccessible(listOf("a"))
        c.inaccessible(listOf("a", "b"))
        assertEquals(2, probes)
    }

    @Test
    fun invalidateForcesNewProbe() = runBlocking {
        val c = cache()
        c.inaccessible(listOf("a"))
        c.invalidate()
        c.inaccessible(listOf("a"))
        assertEquals(2, probes)
    }

    private fun book(id: String, vararg uris: String) = BookWithChapters(
        BookEntity(
            id = id,
            title = id,
            author = "",
            coverPath = null,
            addedAt = 0L,
            lastPlayedAt = null,
            durationMs = 0L,
            positionMs = 0L,
            currentChapterIndex = 0,
            playbackSpeed = 1f,
            completed = false,
        ),
        uris.mapIndexed { i, uri ->
            ChapterEntity(id = "$id-$i", bookId = id, index = i, title = "$i", uri = uri, durationMs = 1L)
        },
    )

    @Test
    fun bookWithEveryFileGoneIsDropped() {
        val shelf = listOf(book("b1", "a", "b"), book("b2", "c"))
        assertEquals(listOf("b2"), shelf.withoutGone(setOf("a", "b")).map { it.book.id })
    }

    @Test
    fun bookWithSomeFilesLeftStays() {
        val shelf = listOf(book("b1", "a", "b"))
        assertEquals(listOf("b1"), shelf.withoutGone(setOf("a")).map { it.book.id })
    }

    @Test
    fun oneFileSplitIntoChaptersCountsOnce() {
        // m4b: усі розділи — один URI; зник файл — зникла вся книга.
        val shelf = listOf(book("b1", "a", "a", "a"))
        assertEquals(emptyList<String>(), shelf.withoutGone(setOf("a")).map { it.book.id })
    }

    @Test
    fun bookWithoutChaptersIsNeverConsideredGone() {
        val shelf = listOf(book("b1"))
        assertEquals(listOf("b1"), shelf.withoutGone(setOf("a")).map { it.book.id })
    }

    @Test
    fun nothingGoneReturnsSameList() {
        val shelf = listOf(book("b1", "a"))
        assertSame(shelf, shelf.withoutGone(emptySet()))
    }
}
