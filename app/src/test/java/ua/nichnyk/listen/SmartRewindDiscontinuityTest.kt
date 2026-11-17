package ua.nichnyk.listen

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.data.SmartRewindLogic

/** Ручна перемотка під час паузи скидає мітку паузи смарт-відкату. */
class SmartRewindDiscontinuityTest {

    @Test
    fun userSeeksResetPause() {
        assertTrue(SmartRewindLogic.resetsPauseOnDiscontinuity(Player.DISCONTINUITY_REASON_SEEK))
        assertTrue(SmartRewindLogic.resetsPauseOnDiscontinuity(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT))
    }

    @Test
    fun otherDiscontinuitiesDoNot() {
        assertFalse(SmartRewindLogic.resetsPauseOnDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
        assertFalse(SmartRewindLogic.resetsPauseOnDiscontinuity(Player.DISCONTINUITY_REASON_SKIP))
        assertFalse(SmartRewindLogic.resetsPauseOnDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL))
    }
}