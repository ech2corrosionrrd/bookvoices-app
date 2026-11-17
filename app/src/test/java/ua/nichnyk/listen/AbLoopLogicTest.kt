package ua.nichnyk.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.nichnyk.listen.playback.AbLoopLogic
import ua.nichnyk.listen.playback.AbLoopState

class AbLoopLogicTest {

    @Test
    fun threeTapCycleSetsStartThenEndWithSeekThenClears() {
        val tapA = AbLoopLogic.nextOnToggle(current = null, chapterIndex = 2, positionMs = 10_000L)
        assertEquals(AbLoopState(chapterIndex = 2, startMs = 10_000L, endMs = null), tapA.state)
        assertNull(tapA.seekToMs)
        assertFalse(tapA.state!!.isComplete)

        val tapB = AbLoopLogic.nextOnToggle(current = tapA.state, chapterIndex = 2, positionMs = 25_000L)
        assertEquals(AbLoopState(chapterIndex = 2, startMs = 10_000L, endMs = 25_000L), tapB.state)
        assertEquals(10_000L, tapB.seekToMs)
        assertTrue(tapB.state!!.isComplete)

        val tapClear = AbLoopLogic.nextOnToggle(current = tapB.state, chapterIndex = 2, positionMs = 14_000L)
        assertNull(tapClear.state)
        assertNull(tapClear.seekToMs)
    }

    @Test
    fun doubleTapAtSamePositionCancelsInsteadOfZeroLengthLoop() {
        val tapA = AbLoopLogic.nextOnToggle(current = null, chapterIndex = 0, positionMs = 5_000L)
        val doubleTap = AbLoopLogic.nextOnToggle(current = tapA.state, chapterIndex = 0, positionMs = 5_300L)
        assertNull(doubleTap.state)
    }

    @Test
    fun tapBeforeStartSwapsPointsIntoValidOrderedRange() {
        val tapA = AbLoopLogic.nextOnToggle(current = null, chapterIndex = 1, positionMs = 30_000L)
        val tapBefore = AbLoopLogic.nextOnToggle(current = tapA.state, chapterIndex = 1, positionMs = 12_000L)
        assertEquals(AbLoopState(chapterIndex = 1, startMs = 12_000L, endMs = 30_000L), tapBefore.state)
        assertEquals(12_000L, tapBefore.seekToMs)
    }

    @Test
    fun shouldLoopBackTriggersAtEndOrAutoChapterRollover() {
        // B у самому кінці 55-секундного розділу.
        val loop = AbLoopState(chapterIndex = 3, startMs = 40_000L, endMs = 55_000L, chapterDurationMs = 55_000L)
        assertTrue(loop.endsAtChapterEnd)
        assertNull(AbLoopLogic.shouldLoopBack(loop, currentChapterIndex = 3, currentPositionMs = 54_999L))
        assertEquals(3 to 40_000L, AbLoopLogic.shouldLoopBack(loop, currentChapterIndex = 3, currentPositionMs = 55_000L))
        // Якщо точка B стояла в самому кінці розділу й ExoPlayer встиг перейти на наступний
        assertEquals(3 to 40_000L, AbLoopLogic.shouldLoopBack(loop, currentChapterIndex = 4, currentPositionMs = 400L))
        assertNull(AbLoopLogic.shouldLoopBack(loop, currentChapterIndex = 5, currentPositionMs = 0L))
    }

    @Test
    fun endRolloverStaysOnlyWhilePlaybackWantsToContinue() {
        val loop = AbLoopState(chapterIndex = 3, startMs = 40_000L, endMs = 55_000L, chapterDurationMs = 55_000L)
        assertTrue(AbLoopLogic.holdsEndRollover(loop, currentChapterIndex = 4, currentPositionMs = 400L, playWhenReady = true))
        assertFalse(AbLoopLogic.holdsEndRollover(loop, currentChapterIndex = 4, currentPositionMs = 400L, playWhenReady = false))
        val mid = loop.copy(endMs = 50_000L, chapterDurationMs = 600_000L)
        assertFalse(AbLoopLogic.holdsEndRollover(mid, currentChapterIndex = 4, currentPositionMs = 400L, playWhenReady = true))
    }

    @Test
    fun nextChapterFromShadeLeavesMidChapterLoop() {
        // B посеред 10-хвилинного розділу: «наступний розділ» зі шторки чи гарнітури —
        // це вихід із петлі, а не повернення в A.
        val loop = AbLoopState(chapterIndex = 3, startMs = 40_000L, endMs = 55_000L, chapterDurationMs = 600_000L)
        assertFalse(loop.endsAtChapterEnd)
        assertNull(AbLoopLogic.shouldLoopBack(loop, currentChapterIndex = 4, currentPositionMs = 400L))
        assertTrue(AbLoopLogic.isStale(loop, currentChapterIndex = 4))
        assertFalse(AbLoopLogic.isStale(loop, currentChapterIndex = 3))
        assertFalse(AbLoopLogic.isStale(null, currentChapterIndex = 4))
    }

    @Test
    fun unknownDurationNeverTreatsRolloverAsLoop() {
        val loop = AbLoopState(chapterIndex = 0, startMs = 1_000L, endMs = 5_000L)
        assertFalse(loop.endsAtChapterEnd)
        assertNull(AbLoopLogic.shouldLoopBack(loop, currentChapterIndex = 1, currentPositionMs = 100L))
    }

    @Test
    fun toggleRecordsChapterDurationWithB() {
        val a = AbLoopLogic.nextOnToggle(null, chapterIndex = 2, positionMs = 10_000L, chapterDurationMs = 60_000L)
        val b = AbLoopLogic.nextOnToggle(a.state, chapterIndex = 2, positionMs = 59_500L, chapterDurationMs = 60_000L)
        assertEquals(60_000L, b.state!!.chapterDurationMs)
        assertTrue(b.state!!.endsAtChapterEnd)
    }
}
