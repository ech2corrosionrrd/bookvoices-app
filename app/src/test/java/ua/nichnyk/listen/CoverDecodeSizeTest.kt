package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.ui.components.coverSampleSize
import ua.nichnyk.listen.ui.components.coverTargetPx

/**
 * Розмір декодування обкладинки береться з рамки, у яку її малюють: інакше
 * дрібна обкладинка у списку тримала б у кеші картинку для екрана плеєра.
 */
class CoverDecodeSizeTest {

    @Test
    fun `цільова сторона - степінь двійки не менша за намальований розмір`() {
        assertEquals(128, coverTargetPx(0))
        assertEquals(128, coverTargetPx(100))
        assertEquals(256, coverTargetPx(216)) // 72 dp на xxhdpi
        assertEquals(512, coverTargetPx(500))
        assertEquals(512, coverTargetPx(512))
        assertEquals(1024, coverTargetPx(513))
    }

    @Test
    fun `цільова сторона має стелю щоб величезний екран не роздував кеш`() {
        assertEquals(1024, coverTargetPx(4000))
    }

    @Test
    fun `малий розмір не потребує семплінгу коли джерело вже мале`() {
        assertEquals(1, coverSampleSize(300, 300, 256))
        assertEquals(1, coverSampleSize(256, 512, 256))
    }

    @Test
    fun `семплінг лишає меншу сторону не меншою за ціль`() {
        val width = 3000
        val height = 2000
        for (target in listOf(128, 256, 512, 1024)) {
            val sample = coverSampleSize(width, height, target)
            val shortSide = minOf(width, height) / sample
            assertTrue("ціль $target, sample $sample, коротка сторона $shortSide", shortSide >= target)
            // і це найбільший такий степінь двійки
            assertTrue(minOf(width, height) / (sample * 2) < target)
        }
    }

    @Test
    fun `велика обкладинка у списку декодується меншою ніж на плеєрі`() {
        val list = coverSampleSize(3000, 3000, coverTargetPx(216))
        val player = coverSampleSize(3000, 3000, coverTargetPx(900))
        assertTrue("список $list, плеєр $player", list > player)
    }

    @Test
    fun `семпл завжди степінь двійки й не менший за одиницю`() {
        for (side in listOf(1, 50, 400, 777, 4096)) {
            val sample = coverSampleSize(side, side, 256)
            assertTrue(sample >= 1)
            assertEquals(0, sample and (sample - 1))
        }
    }
}
