package com.rife.androidtv.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePtsTest {

    @Test
    fun `mid points split the pair into thirds`() {
        assertEquals(10_000L, OfflinePts.midPtsUs(0L, 30_000L, 1))
        assertEquals(20_000L, OfflinePts.midPtsUs(0L, 30_000L, 2))
        assertEquals(2L, OfflinePts.midPtsUs(0L, 6L, 1))
        assertEquals(4L, OfflinePts.midPtsUs(0L, 6L, 2))
    }

    @Test
    fun `mid points land strictly between anchors for odd gaps`() {
        val a = OfflinePts.midPtsUs(10_000L, 31_000L, 1)
        val b = OfflinePts.midPtsUs(10_000L, 31_000L, 2)
        assertTrue(a > 10_000L)
        assertTrue(a < b)
        assertTrue(b < 31_000L)
    }

    @Test
    fun `frame count is 3n-2 so the span never grows`() {
        assertEquals(0, OfflinePts.outputFrameCount(0))
        assertEquals(1, OfflinePts.outputFrameCount(1))
        assertEquals(4, OfflinePts.outputFrameCount(2))
        assertEquals(7, OfflinePts.outputFrameCount(3))
        assertEquals(298, OfflinePts.outputFrameCount(100))
    }

    @Test
    fun `negative input produces zero frames`() {
        assertEquals(0, OfflinePts.outputFrameCount(-1))
        assertEquals(0, OfflinePts.outputFrameCount(-100))
    }

    @Test
    fun `schedule spans exactly the original duration in order`() {
        // Anchors hold their PTS, so the last output frame is the last source frame.
        val pts = mutableListOf(0L)
        var prev = 0L
        for (frame in 1..9) {
            val next = frame * 40_000L
            pts += OfflinePts.midPtsUs(prev, next, 1)
            pts += OfflinePts.midPtsUs(prev, next, 2)
            pts += next
            prev = next
        }
        assertEquals(3 * 10 - 2, pts.size)
        assertEquals(9L * 40_000L, pts.last())
        assertEquals(pts, pts.sorted())
    }

    @Test
    fun `mid index outside the pair is rejected`() {
        for (bad in listOf(0, 3, -1)) {
            val thrown = runCatching { OfflinePts.midPtsUs(0L, 30_000L, bad) }
            assertTrue("index $bad should fail", thrown.isFailure)
        }
    }
}
