package com.rife.androidtv.rife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairPacingTest {

    private val intervalUs = 41_667L // 24 fps source

    @Test
    fun `no motion samples never declines`() {
        val pacing = PairPacing()
        repeat(1_000) {
            assertFalse(pacing.shouldDeclinePair(intervalUs))
        }
    }

    @Test
    fun `motion within the interval never declines`() {
        val pacing = PairPacing()
        repeat(1_000) {
            pacing.noteMotionCost(30_000L)
            assertFalse(pacing.shouldDeclinePair(intervalUs))
        }
    }

    @Test
    fun `mild overrun declines about the deficit rate with a bounded period`() {
        val pacing = PairPacing()
        val motionUs = 46_700L // measured 1080p full-engine
        repeat(50) {
            pacing.noteMotionCost(motionUs)
            pacing.shouldDeclinePair(intervalUs)
        }
        var declines = 0
        var lastDecline = -1
        var maxGap = 0
        repeat(1_000) { i ->
            pacing.noteMotionCost(motionUs)
            if (pacing.shouldDeclinePair(intervalUs)) {
                declines++
                if (lastDecline >= 0) maxGap = maxOf(maxGap, i - lastDecline)
                lastDecline = i
            }
        }
        // Steady-state decline rate is 1 - interval/motion ~= 10.7%.
        assertTrue("declines=$declines", declines in 80..130)
        assertTrue("maxGap=$maxGap", maxGap in 5..12)
    }

    @Test
    fun `heavy overrun declines about the deficit rate`() {
        val pacing = PairPacing()
        val motionUs = 76_600L // measured 4K full-engine
        repeat(50) {
            pacing.noteMotionCost(motionUs)
            pacing.shouldDeclinePair(intervalUs)
        }
        var declines = 0
        repeat(1_000) {
            pacing.noteMotionCost(motionUs)
            if (pacing.shouldDeclinePair(intervalUs)) declines++
        }
        // Steady-state decline rate is 1 - interval/motion ~= 45.6%.
        assertTrue("declines=$declines", declines in 400..520)
    }

    @Test
    fun `cost collapse stops declining immediately`() {
        val pacing = PairPacing()
        val motionUs = 76_600L
        repeat(50) {
            pacing.noteMotionCost(motionUs)
            pacing.shouldDeclinePair(intervalUs)
        }
        repeat(100) {
            pacing.noteMotionCost(10_000L)
            pacing.shouldDeclinePair(intervalUs)
        }
        repeat(500) {
            pacing.noteMotionCost(10_000L)
            assertFalse(pacing.shouldDeclinePair(intervalUs))
        }
    }

    @Test
    fun `reset clears estimator and credit`() {
        val pacing = PairPacing()
        repeat(50) {
            pacing.noteMotionCost(76_600L)
            pacing.shouldDeclinePair(intervalUs)
        }
        pacing.reset()
        repeat(100) {
            assertFalse(pacing.shouldDeclinePair(intervalUs))
        }
    }

    @Test
    fun `zero and negative intervals are rejected`() {
        val pacing = PairPacing()
        pacing.noteMotionCost(76_600L)
        assertFalse(pacing.shouldDeclinePair(0L))
        assertFalse(pacing.shouldDeclinePair(-1L))
    }

    @Test
    fun `zero and negative costs are ignored`() {
        val pacing = PairPacing()
        pacing.noteMotionCost(0L)
        pacing.noteMotionCost(-5L)
        repeat(100) {
            assertFalse(pacing.shouldDeclinePair(intervalUs))
        }
    }

    @Test
    fun `declines are periodic not clustered`() {
        val pacing = PairPacing()
        val motionUs = 46_700L
        repeat(50) {
            pacing.noteMotionCost(motionUs)
            pacing.shouldDeclinePair(intervalUs)
        }
        val pattern = mutableListOf<Boolean>()
        repeat(100) {
            pacing.noteMotionCost(motionUs)
            pattern.add(pacing.shouldDeclinePair(intervalUs))
        }
        val gaps = pattern.withIndex()
            .filter { it.value }
            .map { it.index }
            .windowed(2)
            .map { it[1] - it[0] }
        assertTrue(gaps.isNotEmpty())
        assertEquals("gap spread too wide: $gaps", true, gaps.max() - gaps.min() <= 2)
    }
}
