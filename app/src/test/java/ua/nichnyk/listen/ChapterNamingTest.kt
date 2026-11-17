package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.nichnyk.listen.data.ChapterEntity
import ua.nichnyk.listen.data.ChapterNaming

/** Назви глав від слухача: прибирання, нумерація, лише справжні зміни. */
class ChapterNamingTest {

    private fun ch(id: String, index: Int, title: String) =
        ChapterEntity(id = id, bookId = "b", index = index, title = title, uri = "u$id", durationMs = 1L)

    @Test
    fun cleanCollapsesSpacesAndRejectsBlank() {
        assertEquals("Пролог перший", ChapterNaming.clean("  Пролог \n  перший\t"))
        assertNull(ChapterNaming.clean("   \n "))
        assertEquals(ChapterNaming.MAX_LENGTH, ChapterNaming.clean("я".repeat(500))!!.length)
    }

    @Test
    fun numberedFollowsChapterOrderNotListOrder() {
        val chapters = listOf(ch("c", 2, "NIVEN 03"), ch("a", 0, "NIVEN 01"), ch("b", 1, "NIVEN 02"))
        assertEquals(
            mapOf("a" to "Розділ 1", "b" to "Розділ 2", "c" to "Розділ 3"),
            ChapterNaming.numbered(chapters) { "Розділ $it" },
        )
    }

    @Test
    fun changesKeepOnlyRealDifferencesOfThisBook() {
        val chapters = listOf(ch("a", 0, "Розділ 1"), ch("b", 1, "NIVEN 02"))
        assertEquals(
            mapOf("b" to "Розділ 2"),
            ChapterNaming.changes(chapters, mapOf("a" to "Розділ 1", "b" to "Розділ 2", "zzz" to "Чуже")),
        )
    }
}
