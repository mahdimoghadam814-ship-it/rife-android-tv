package com.rife.androidtv.rife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence clamp is what stops a stalled, backwards or jumping source timestamp from firing a
 * burst of output frames or from carrying a non-monotonic base into the emission list. These tests
 * pin the classification the [PIPELINE] `cadDisc` counter reports and the cadence restart depends
 * on: only a span outside the pair window may count as a discontinuity.
 */
class PairCadenceTest {

    @Test
    fun steadyPairSpanIsNotADiscontinuity() {
        val cadence = VideoFrameProcessor.cadenceInterval(prevUs = 1_000_000L, nextUs = 1_041_667L)
        assertEquals(41_667L, cadence.intervalUs)
        assertFalse(cadence.discontinuity)
    }

    @Test
    fun backwardsTimestampIsADiscontinuityClampedToFloor() {
        val cadence = VideoFrameProcessor.cadenceInterval(prevUs = 2_000_000L, nextUs = 1_999_000L)
        assertEquals(1_000L, cadence.intervalUs)
        assertTrue(cadence.discontinuity)
    }

    @Test
    fun zeroSpanIsADiscontinuityClampedToFloor() {
        val cadence = VideoFrameProcessor.cadenceInterval(prevUs = 5_000_000L, nextUs = 5_000_000L)
        assertEquals(1_000L, cadence.intervalUs)
        assertTrue(cadence.discontinuity)
    }

    @Test
    fun multiSecondStallIsADiscontinuityClampedToCeiling() {
        val cadence = VideoFrameProcessor.cadenceInterval(prevUs = 0L, nextUs = 3_000_000L)
        assertEquals(1_000_000L, cadence.intervalUs)
        assertTrue(cadence.discontinuity)
    }

    @Test
    fun exactCeilingSpanStaysInsideTheWindow() {
        val cadence = VideoFrameProcessor.cadenceInterval(prevUs = 0L, nextUs = 1_000_000L)
        assertEquals(1_000_000L, cadence.intervalUs)
        assertFalse(cadence.discontinuity)
    }

    @Test
    fun exactFloorSpanStaysInsideTheWindow() {
        val cadence = VideoFrameProcessor.cadenceInterval(prevUs = 10_000L, nextUs = 11_000L)
        assertEquals(1_000L, cadence.intervalUs)
        assertFalse(cadence.discontinuity)
    }
}
