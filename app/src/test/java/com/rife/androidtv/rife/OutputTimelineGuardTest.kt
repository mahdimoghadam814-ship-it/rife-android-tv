package com.rife.androidtv.rife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timestamp-ordering rule for every output present. The rule is what makes pairing defects
 * visible as [OutputTimelineGuard.regressions] instead of as unexplained stutter, so the
 * behaviour encoded here must not drift.
 */
class OutputTimelineGuardTest {

    private val guard = OutputTimelineGuard()

    @Test
    fun firstPresentArmsTheTimelineWithoutJudgingIt() {
        assertTrue(guard.note(10_000_000L))
        assertEquals(0L, guard.regressions)
        assertEquals(10_000_000L, guard.lastTimestampNs)
    }

    @Test
    fun strictlyIncreasingPresentsAreAccepted() {
        assertTrue(guard.note(10_000_000L))
        assertTrue(guard.note(20_000_000L))
        assertTrue(guard.note(30_000_000L))
        assertEquals(0L, guard.regressions)
        assertEquals(30_000_000L, guard.lastTimestampNs)
    }

    @Test
    fun duplicateTimestampCountsAsRegressionAndDoesNotMoveTheTimeline() {
        assertTrue(guard.note(10_000_000L))
        assertFalse(guard.note(10_000_000L))
        assertEquals(1L, guard.regressions)
        assertEquals(10_000_000L, guard.lastTimestampNs)
    }

    @Test
    fun backwardsTimestampCountsAsRegressionAndIsNotAdopted() {
        assertTrue(guard.note(20_000_000L))
        assertFalse(guard.note(10_000_000L))
        assertEquals(1L, guard.regressions)
        // The timeline never moves backwards: the next present is still judged against the
        // newest frame actually accepted, so one bad present cannot hide the ones after it.
        assertEquals(20_000_000L, guard.lastTimestampNs)
        assertFalse(guard.note(15_000_000L))
        assertEquals(2L, guard.regressions)
        assertTrue(guard.note(25_000_000L))
        assertEquals(2L, guard.regressions)
    }

    @Test
    fun nonPositiveTimestampCarriesNoTimelineInformation() {
        assertTrue(guard.note(0L))
        assertTrue(guard.note(-1L))
        assertEquals(0L, guard.lastTimestampNs)
        assertEquals(0L, guard.regressions)
        // Still unarmed, so the first real present arms rather than being judged against 0.
        assertTrue(guard.note(1_000L))
        assertEquals(1_000L, guard.lastTimestampNs)
    }

    @Test
    fun rearmMakesTheNextPresentStartANewStream() {
        assertTrue(guard.note(600_000_000_000L))
        guard.rearm()
        assertEquals(0L, guard.lastTimestampNs)
        // After a seek the timeline restarts far behind the previous stream; that cut must not
        // read as a regression.
        assertTrue(guard.note(1_000L))
        assertEquals(0L, guard.regressions)
        assertEquals(1_000L, guard.lastTimestampNs)
    }

    @Test
    fun repeatedDuplicatesAreEachCounted() {
        assertTrue(guard.note(10_000_000L))
        assertFalse(guard.note(10_000_000L))
        assertFalse(guard.note(10_000_000L))
        assertEquals(2L, guard.regressions)
    }
}
