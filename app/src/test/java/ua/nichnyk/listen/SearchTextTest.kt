package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.SearchText.matches
import ua.nichnyk.listen.data.SearchText.normalize
import ua.nichnyk.listen.data.SearchText.tokens

/**
 * Пошук на полиці, в закладках і голосовий в Android Auto. Хибний «не знайшов» у машині
 * гірший, ніж на екрані: водій не може переписати запит, тож нормалізація має бути щедрою.
 */
class SearchTextTest {

    @Test
    fun caseAndAccentsDoNotMatter() {
        assertTrue(matches("кобзар", "Кобзар"))
        assertTrue(matches("CAFE", "Café de Paris"))
        assertTrue(matches("muller", "Wilhelm Müller"))
        assertTrue(matches("zolw", "Zolw"))
    }

    @Test
    fun yoAndYeAreTheSameLetter() {
        assertTrue(matches("все", "Всё о книге"))
        assertTrue(matches("всё", "Все о книге"))
        assertTrue(matches("ёж", "Ежик в тумане"))
    }

    @Test
    fun everyKindOfApostropheMatchesEveryOther() {
        val title = "Прив’яз до землі"
        assertTrue(matches("прив'яз", title))
        assertTrue(matches("привʼяз", title))
        assertTrue(matches("привяз", title))
        assertTrue(matches("Prive'z", "Privez"))
    }

    @Test
    fun allWordsOfTheQueryMustBePresentAcrossFields() {
        // Назва в одному полі, автор у другому — разом знаходять.
        assertTrue(matches("кобзар шевченко", "Кобзар", "Тарас Шевченко", null))
        assertTrue(matches("шевченко кобзар", "Кобзар", "Тарас Шевченко", null))
        // Одного слова бракує — не знаходить.
        assertFalse(matches("кобзар франко", "Кобзар", "Тарас Шевченко", null))
    }

    @Test
    fun partOfAWordStillMatches() {
        assertTrue(matches("шевч", "Тарас Шевченко"))
        assertTrue(matches("тигр", "Тигролови"))
    }

    @Test
    fun punctuationInTextAndQueryIsIgnored() {
        assertTrue(matches("tigrolovy", "Tigrolovy!"))
        assertTrue(matches("шевченко - кобзар", "Кобзар", "Шевченко"))
        assertTrue(matches("a b", "a, b."))
    }

    @Test
    fun blankQueryMatchesEverythingAndAQueryOfOnlyPunctuationIsSearchedAsIs() {
        assertTrue(matches("", "будь-що"))
        assertTrue(matches("   ", "будь-що"))
        assertTrue(matches("?", "Що це?"))
        // Лише розділові знаки: не показуємо всю полицю, а шукаємо сам символ.
        assertFalse(matches("?", "Кобзар"))
    }

    @Test
    fun nullAndEmptyFieldsAreSkippedSafely() {
        assertFalse(matches("кобзар", null, "", "Інша книга"))
        assertTrue(matches("кобзар", null, "Кобзар"))
    }

    @Test
    fun normalizeAndTokensAreConsistent() {
        assertEquals("все о книге", normalize("  ВСЁ,   о книге!  "))
        assertEquals(listOf("все", "о", "книге"), tokens("Всё о книге"))
        assertEquals(emptyList<String>(), tokens("?!"))
        assertEquals("", normalize(""))
    }

    @Test
    fun digitsAreKeptSoYearsAndVolumesStillSearch() {
        assertTrue(matches("1984", "Орвелл", "1984"))
        assertTrue(matches("том 2", "Назва. Том 2"))
        assertFalse(matches("том 3", "Назва. Том 2"))
    }
}
