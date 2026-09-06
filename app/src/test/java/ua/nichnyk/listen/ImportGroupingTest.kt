package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.AudioImporter.Companion.bookFolderTitle
import ua.nichnyk.listen.data.AudioImporter.Companion.findBookFolders
import ua.nichnyk.listen.data.AudioImporter.Companion.groupKey
import ua.nichnyk.listen.data.AudioImporter.Companion.isDiscFolder

/**
 * Розкладання вмісту обраної теки по книгах.
 *
 * Раніше різався лише перший рівень від вибору: Автор/Місто і Автор/Драма
 * ставали однією книгою, а вказівка на книгу з CD1/CD2 давала дві книги-диски.
 */
class ImportGroupingTest {

    /** Як імпорт розкладе цей набір шляхів: ключ книги -> її файли. */
    private fun group(paths: List<String>): Map<String, List<String>> {
        val folders = findBookFolders(paths)
        return paths.groupBy { groupKey(it, folders) }
    }

    @Test
    fun folderWithLooseFilesIsOneBook() {
        // Користувач указав саме на книгу.
        val result = group(listOf("01.mp3", "02.mp3", "03.mp3"))
        assertEquals(setOf(""), result.keys)
        assertEquals(3, result.getValue("").size)
    }

    @Test
    fun libraryFolderSplitsIntoBookPerSubfolder() {
        val result = group(
            listOf(
                "Кобзар/01.mp3",
                "Кобзар/02.mp3",
                "Лісова пісня/01.mp3",
                "Тигролови/01.mp3",
                "Тигролови/02.mp3",
            ),
        )
        assertEquals(setOf("Кобзар", "Лісова пісня", "Тигролови"), result.keys)
        assertEquals(2, result.getValue("Кобзар").size)
        assertEquals(1, result.getValue("Лісова пісня").size)
    }

    @Test
    fun discSubfoldersStayInsideOneBook() {
        val result = group(
            listOf(
                "Тигролови/CD1/01.mp3",
                "Тигролови/CD1/02.mp3",
                "Тигролови/CD2/01.mp3",
            ),
        )
        assertEquals(setOf("Тигролови"), result.keys)
        assertEquals(3, result.getValue("Тигролови").size)
    }

    @Test
    fun selectedBookWithOnlyDiscsIsOneBook() {
        // Кнопка «Папка з розділами»: ткнули в саму книгу, не в бібліотеку.
        val result = group(listOf("CD1/01.mp3", "CD1/02.mp3", "CD2/01.mp3"))
        assertEquals(setOf(""), result.keys)
        assertEquals(3, result.getValue("").size)
    }

    @Test
    fun authorFoldersDescendIntoEachBook() {
        val result = group(
            listOf(
                "Підмогильний/Місто/01.mp3",
                "Підмогильний/Невеличка драма/01.mp3",
                "Підмогильний/Невеличка драма/02.mp3",
            ),
        )
        assertEquals(setOf("Підмогильний/Місто", "Підмогильний/Невеличка драма"), result.keys)
        assertEquals(1, result.getValue("Підмогильний/Місто").size)
        assertEquals(2, result.getValue("Підмогильний/Невеличка драма").size)
    }

    @Test
    fun genreAuthorBookDescendsToTheBook() {
        val result = group(
            listOf(
                "Класика/Багряний/Тигролови/CD1/a.mp3",
                "Класика/Багряний/Тигролови/CD2/b.mp3",
                "Класика/Шевченко/Кобзар/01.mp3",
            ),
        )
        assertEquals(
            setOf("Класика/Багряний/Тигролови", "Класика/Шевченко/Кобзар"),
            result.keys,
        )
    }

    @Test
    fun deeplyNestedChapterFoldersStayInsideTheBook() {
        // «Частина 1» — диск, не окрема книга.
        val result = group(listOf("Книга/Частина 1/Розділ 1/01.mp3"))
        assertEquals(setOf("Книга"), result.keys)
    }

    @Test
    fun looseFilesInRootMakeWholeSelectionOneBook() {
        val result = group(listOf("01.mp3", "02.mp3", "Бонуси/інтерв'ю.mp3"))
        assertEquals(setOf(""), result.keys)
        assertEquals(3, result.getValue("").size)
    }

    @Test
    fun yearFolderIsABookNotADisc() {
        assertFalse(isDiscFolder("1984"))
        assertTrue(isDiscFolder("01"))
        assertTrue(isDiscFolder("CD2"))
        assertTrue(isDiscFolder("Диск 3"))
        val result = group(listOf("Орвелл/1984/01.mp3"))
        assertEquals(setOf("Орвелл/1984"), result.keys)
    }

    @Test
    fun groupKeyPicksTheLongestBookFolder() {
        val folders = setOf("Автор/Місто", "Автор/Драма")
        assertEquals("Автор/Місто", groupKey("Автор/Місто/cover.jpg", folders))
        assertEquals("Автор/Драма", groupKey("Автор/Драма/CD1/01.mp3", folders))
    }

    @Test
    fun bookFolderTitleUsesTheLeafName() {
        assertEquals("Місто", bookFolderTitle("Підмогильний/Місто", "Аудіокниги"))
        assertEquals("Аудіокниги", bookFolderTitle("", "Аудіокниги"))
    }

    @Test
    fun groupKeyIsStableForSingleFile() {
        assertEquals("", groupKey("01.mp3", findBookFolders(listOf("01.mp3"))))
        assertEquals("Книга", groupKey("Книга/01.mp3", findBookFolders(listOf("Книга/01.mp3"))))
    }

    @Test
    fun parseFolderAuthorAndTitleSplitsHyphenAndDash() {
        val (a1, t1) = ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle("Шевченко - Кобзар", null)
        assertEquals("Шевченко", a1)
        assertEquals("Кобзар", t1)

        val (a2, t2) = ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle("Тарас Шевченко — Кобзар", null)
        assertEquals("Тарас Шевченко", a2)
        assertEquals("Кобзар", t2)

        val (a3, t3) = ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle("Іван Багряний – Тигролови", null)
        assertEquals("Іван Багряний", a3)
        assertEquals("Тигролови", t3)
    }

    @Test
    fun parseFolderAuthorAndTitleInfersFromParentFolder() {
        val (a1, t1) = ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle("Підмогильний/Місто", null)
        assertEquals("Підмогильний", a1)
        assertEquals("Місто", t1)

        val (a2, t2) = ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle("Класика/Шевченко/Кобзар", null)
        assertEquals("Шевченко", a2)
        assertEquals("Кобзар", t2)
    }

    @Test
    fun parseFolderAuthorAndTitleReturnsNullAuthorWhenNoSeparator() {
        val (a, t) = ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle("Кобзар", null)
        assertEquals(null, a)
        assertEquals("Кобзар", t)
    }
}
