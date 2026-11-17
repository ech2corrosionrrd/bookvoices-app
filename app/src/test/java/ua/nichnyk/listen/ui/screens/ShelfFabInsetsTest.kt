package ua.nichnyk.listen.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Відступи плаваючих кнопок полиці. Горизонтальний телефон (Redmi Note 11 Pro: 392 dp
 * висоти) лишає полиці ~200 dp, і з відступом 96 dp кнопка «Додати» накривала пошук у шапці.
 */
class ShelfFabInsetsTest {

    @Test
    fun portraitPhoneKeepsRoomyInsets() {
        val insets = shelfFabInsets(screenHeightDp = 873)
        assertEquals(96.dp, insets.addBottom)
        assertEquals(168.dp, insets.scrollTopBottom)
    }

    @Test
    fun landscapePhoneUsesCompactInsets() {
        val insets = shelfFabInsets(screenHeightDp = 392)
        assertEquals(16.dp, insets.addBottom)
        // «Вгору» над «Додати» (56 dp) з тим самим проміжком і обидві нижче за ~200 dp полиці.
        assertTrue(insets.scrollTopBottom >= insets.addBottom + 56.dp)
        assertTrue(insets.scrollTopBottom + 40.dp < 200.dp)
    }

    @Test
    fun tabletLandscapeIsNotCompact() {
        // Планшет 10" горизонтально — ~800 dp висоти, місця вистачає.
        assertEquals(96.dp, shelfFabInsets(screenHeightDp = 800).addBottom)
    }
}
