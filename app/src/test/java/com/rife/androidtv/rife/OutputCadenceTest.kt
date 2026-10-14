package com.rife.androidtv.rife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [CADENCE] presentation arithmetic. The line's whole purpose is to name the hold/skip
 * pattern and beat of an uneven output rate on the panel, so the three real-world cases
 * are locked here: 24fps x2 on 60Hz (the observed 1080p judder shape - one double-hold per
 * four frames, a 12 Hz wobble), the even cases the other sources produce, and the 3x
 * skip pattern the other selectable multiplier gives the same 24fps source.
 */
class OutputCadenceTest {

    @Test
    fun twentyFourFpsAt2xOn60HzHoldsOneFrameInFour() {
        val report = OutputCadence.analyze(24.0, 2f, 60.0)
        requireNotNull(report)
        assertFalse(report.even)
        assertEquals(48.0, report.outFps, 1e-9)
        // 48fps on 60Hz: three single-vsync holds then one double hold, twelve times a second.
        assertEquals("1x36,2x12", report.holdsSummary)
        assertEquals(12.0, report.beatHz, 1e-9)
    }

    @Test
    fun thirtyFpsAt2xOn60HzIsEven() {
        val report = OutputCadence.analyze(30.0, 2f, 60.0)
        requireNotNull(report)
        assertTrue(report.even)
        assertEquals("1x48", report.holdsSummary)
        assertEquals(0.0, report.beatHz, 1e-9)
    }

    @Test
    fun twentyFourFpsAt3xOn60HzSkipsOneFrameInSix() {
        val report = OutputCadence.analyze(24.0, 3f, 60.0)
        requireNotNull(report)
        assertFalse(report.even)
        // 72fps on 60Hz: the display cannot show every frame; a 0 hold is a skipped frame.
        assertEquals("0x8,1x40", report.holdsSummary)
        assertEquals(12.0, report.beatHz, 1e-9)
    }

    @Test
    fun twentyFourFpsAtIdealEvenMultiplierIsEven() {
        // Validates the advisory: x2.5 lands 24fps content exactly on 60Hz.
        val report = OutputCadence.analyze(24.0, 2.5f, 60.0)
        requireNotNull(report)
        assertTrue(report.even)
        assertEquals("1x48", report.holdsSummary)
    }

    @Test
    fun broadcastRatesAtIdealMultiplierAreEven() {
        // 23.976 x2.5 = 59.94 lands on a 59.94Hz panel as exactly one hold per frame.
        val report = OutputCadence.analyze(23.976, 2.5f, 59.94)
        requireNotNull(report)
        assertTrue(report.even)
    }

    @Test
    fun nonIntegerRefreshStaysUnevenForMismatchedRate() {
        // 48fps on 59.94Hz cannot divide evenly; the beat must be positive, the pattern not.
        val report = OutputCadence.analyze(24.0, 2f, 59.94)
        requireNotNull(report)
        assertFalse(report.even)
        assertTrue(report.beatHz > 0.0)
    }

    @Test
    fun unusableInputsReturnNull() {
        assertNull(OutputCadence.analyze(0.0, 2f, 60.0))
        assertNull(OutputCadence.analyze(-24.0, 2f, 60.0))
        assertNull(OutputCadence.analyze(24.0, 0f, 60.0))
        assertNull(OutputCadence.analyze(24.0, 2f, 0.0))
        assertNull(OutputCadence.analyze(24.0, 2f, -60.0))
    }

    @Test
    fun idealEvenMultiplierIsRefreshOverSource() {
        assertEquals(2.5, requireNotNull(OutputCadence.idealEvenMultiplier(24.0, 60.0)), 1e-9)
        assertEquals(2.0, requireNotNull(OutputCadence.idealEvenMultiplier(30.0, 60.0)), 1e-9)
        assertNull(OutputCadence.idealEvenMultiplier(0.0, 60.0))
        assertNull(OutputCadence.idealEvenMultiplier(24.0, 0.0))
    }
}
