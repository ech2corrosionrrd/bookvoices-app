package ua.nichnyk.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.ui.screens.showHistoryCard

class HistoryCardRuleTest {

    @Test
    fun showsNumbersWheneverThereAreStats() {
        assertTrue(showHistoryCard(showStatsOnShelf = true, hasStats = true, hasBooks = true, historyOpened = true))
    }

    @Test
    fun hintOnlyUntilHistoryIsOpenedOnce() {
        assertTrue(showHistoryCard(true, hasStats = false, hasBooks = true, historyOpened = false))
        assertFalse(showHistoryCard(true, hasStats = false, hasBooks = true, historyOpened = true))
    }

    @Test
    fun noHintOnEmptyShelf() {
        assertFalse(showHistoryCard(true, hasStats = false, hasBooks = false, historyOpened = false))
    }

    @Test
    fun switchedOffSettingWinsOverEverything() {
        assertFalse(showHistoryCard(false, hasStats = true, hasBooks = true, historyOpened = false))
    }
}
