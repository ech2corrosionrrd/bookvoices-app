package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.AudioImporter.Companion.bookFolderTitle
import ua.nichnyk.listen.data.AudioImporter.Companion.findBookFolders
import ua.nichnyk.listen.data.AudioImporter.Companion.groupKey
import ua.nichnyk.listen.data.AudioImporter.Companion.isDiscFolder
import ua.nichnyk.listen.data.AudioImporter.Companion.isJunkName
import ua.nichnyk.listen.data.AudioImporter.Companion.isJunkPath

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
    fun twoDiscsPlusABonusFolderAreOneBookNotThree() {
        // Раніше «Бонус» поряд із дисками змушував різати книгу на CD1, CD2 і Бонус.
        val result = group(listOf("Книга/CD1/01.mp3", "Книга/CD2/01.mp3", "Книга/Бонус/інтерв'ю.mp3"))
        assertEquals(setOf("Книга"), result.keys)
        assertEquals(3, result.getValue("Книга").size)
    }

    @Test
    fun twoDiscsPlusExtrasUnderTheSelectionRootIsOneBook() {
        val result = group(listOf("Disc 1/a.mp3", "Disc 2/a.mp3", "Extras/a.mp3"))
        assertEquals(setOf(""), result.keys)
        assertEquals(3, result.getValue("").size)
    }

    @Test
    fun aSingleNumberedFolderBesideOthersStillSplitsAsBefore() {
        // Одна тека-число поряд з іншими може бути бібліотекою, а не диском: старе правило.
        val result = group(listOf("Автор/01/a.mp3", "Автор/Місто/a.mp3"))
        assertEquals(setOf("Автор/01", "Автор/Місто"), result.keys)
    }

    @Test
    fun chapterFoldersStayInsideTheirBook() {
        // Раніше кожна «Глава N» ставала окремою книгою.
        for (name in listOf("Глава", "Розділ", "Chapter", "Раздел")) {
            val result = group(listOf("Книга/$name 1/01.mp3", "Книга/$name 2/01.mp3"))
            assertEquals("$name: ${result.keys}", setOf("Книга"), result.keys)
        }
        assertTrue(isDiscFolder("Глава 12"))
        assertTrue(isDiscFolder("chapter_3"))
        assertFalse("«Глава» без числа — це може бути назва книги", isDiscFolder("Глава"))
        assertFalse(isDiscFolder("Chapters of my life"))
    }

    @Test
    fun hiddenAndSystemEntriesAreJunk() {
        assertTrue(isJunkName(".trashed-1700000000-02.mp3"))
        assertTrue(isJunkName("._01.mp3"))
        assertTrue(isJunkName(".thumbnails"))
        assertTrue(isJunkName("__MACOSX"))
        assertTrue(isJunkName("__macosx"))
        assertFalse(isJunkName("01.mp3"))
        assertFalse(isJunkName("Книга. Том 2.mp3"))
        assertFalse(isJunkName("a.b.mp3"))
    }

    @Test
    fun anyJunkSegmentMakesTheWholePathJunk() {
        assertTrue(isJunkPath("__MACOSX/Книга/01.mp3"))
        assertTrue(isJunkPath("Книга/._01.mp3"))
        assertTrue(isJunkPath("Книга/.hidden/01.mp3"))
        assertTrue(isJunkPath("Книга\\.trashed-1-x.mp3"))
        assertFalse(isJunkPath("Книга/CD1/01.mp3"))
        assertFalse(isJunkPath("01.mp3"))
        assertFalse(isJunkPath(""))
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

    /**
     * Крапка в імені теки — не розширення файла. Ініціали авторів («H. G. Wells»,
     * «J. R. R. Tolkien») і «т. 2» у назві давали обрізану назву на полиці:
     * «H. G. Wells - The Time Machine» ставала книгою «H. G» без автора.
     */
    @Test
    fun parseFolderAuthorAndTitleKeepsDotsInFolderName() {
        val parse = ua.nichnyk.listen.data.AudioImporter.Companion::parseFolderAuthorAndTitle

        val (a1, t1) = parse("H. G. Wells - The Time Machine", null)
        assertEquals("H. G. Wells", a1)
        assertEquals("The Time Machine", t1)

        val (a2, t2) = parse("J. R. R. Tolkien - The Hobbit", null)
        assertEquals("J. R. R. Tolkien", a2)
        assertEquals("The Hobbit", t2)

        val (a3, t3) = parse("Harry Potter Vol. 2", null)
        assertEquals(null, a3)
        assertEquals("Harry Potter Vol. 2", t3)
    }

    /** Справжнє аудіо-розширення в імені (файл, а не тека) і далі знімається. */
    @Test
    fun parseFolderAuthorAndTitleStillStripsAudioExtension() {
        val (a, t) = ua.nichnyk.listen.data.AudioImporter.Companion
            .parseFolderAuthorAndTitle("", "Леся Українка - Лісова пісня.mp3")
        assertEquals("Леся Українка", a)
        assertEquals("Лісова пісня", t)
    }

    @Test
    fun parseFolderAuthorAndTitleReturnsNullAuthorWhenNoSeparator() {
        val (a, t) = ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle("Кобзар", null)
        assertEquals(null, a)
        assertEquals("Кобзар", t)
    }

    /**
     * Номер доріжки не автор.
     *
     * Той самий розбір «Автор - Назва» застосовується і до імені файла, коли в
     * тегах автора немає. «01 - Розділ перший» — найпоширеніше іменування
     * розділів аудіокниги — дає з нього автора «01», і це число перемагало
     * справжнього автора з імені теки: тека «Леся Українка - Лісова пісня»
     * давала книгу автора «01». Перевірено на пристрої перед виправленням.
     *
     * Відсіює це те саме правило, що відрізняє теку-диск від теки-книги.
     */
    @Test
    fun trackNumberFromFileNameIsNotAnAuthor() {
        val (fileAuthor, fileTitle) =
            ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle("", "01 - Розділ перший")
        assertEquals("01", fileAuthor)
        assertEquals("Розділ перший", fileTitle)
        assertTrue("голий номер доріжки має відсіюватися", isDiscFolder(fileAuthor!!))

        // А ім'я теки тієї ж книги несе справжнього автора.
        val (folderAuthor, folderTitle) =
            ua.nichnyk.listen.data.AudioImporter.Companion.parseFolderAuthorAndTitle(
                "Леся Українка - Лісова пісня",
                null,
            )
        assertEquals("Леся Українка", folderAuthor)
        assertEquals("Лісова пісня", folderTitle)
        assertFalse(isDiscFolder(folderAuthor!!))
    }

    /**
     * Двоцифрові номери відсіюються, а справжнє ім'я з цифрами — ні.
     * Межа та сама, що в [isDiscFolder]: «1984» це назва, а не номер.
     */
    @Test
    fun onlyBareTrackNumbersAreRejectedAsAuthor() {
        assertTrue(isDiscFolder("01"))
        assertTrue(isDiscFolder("7"))
        assertTrue(isDiscFolder("12"))
        assertFalse(isDiscFolder("1984"))
        assertFalse(isDiscFolder("Джордж Орвелл"))
    }
}
