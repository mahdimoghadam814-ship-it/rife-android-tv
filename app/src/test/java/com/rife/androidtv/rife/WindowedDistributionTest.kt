package com.rife.androidtv.rife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The windowed interval summaries behind the [PACING] diagnostics line. The pulse
 * investigation reads these tokens as p50/p95/max/outliers, so a quantile or outlier
 * regression here would silently turn the on-device evidence into noise: a p95 that
 * stops tracking the slow tail, or an outlier count that fires on every sample, are both
 * ways to miss a recurring frame skip.
 */
class WindowedDistributionTest {

    @Test
    fun emptyWindowClosesToNull() {
        val distribution = WindowedDistribution()
        assertNull(distribution.closeWindow())
    }

    @Test
    fun singleSampleWindowReportsItEverywhere() {
        val distribution = WindowedDistribution()
        distribution.record(7_000L)
        val summary = distribution.closeWindow()
        requireNotNull(summary)
        assertEquals(7_000L, summary.p50)
        assertEquals(7_000L, summary.p95)
        assertEquals(7_000L, summary.max)
        assertEquals(1, summary.n)
        assertEquals(0, summary.outliers)
    }

    @Test
    fun quantilesUseNearestRankWithUpperMedian() {
        val distribution = WindowedDistribution()
        for (value in listOf(10L, 20L, 30L, 40L)) {
            distribution.record(value)
        }
        val summary = distribution.closeWindow()
        requireNotNull(summary)
        // p50 of an even-length window lands on the upper median (20), p95 on ceil(3.8)th.
        assertEquals(20L, summary.p50)
        assertEquals(40L, summary.p95)
        assertEquals(40L, summary.max)
        assertEquals(4, summary.n)
    }

    @Test
    fun outliersAreOnlySamplesAboveTwiceTheMedian() {
        val distribution = WindowedDistribution()
        for (value in listOf(10L, 10L, 10L, 25L)) {
            distribution.record(value)
        }
        val summary = distribution.closeWindow()
        requireNotNull(summary)
        assertEquals(10L, summary.p50)
        assertEquals(1, summary.outliers)
    }

    @Test
    fun closeWindowClearsSamplesButKeepsDeltaBaseline() {
        val distribution = WindowedDistribution()
        distribution.recordDelta(1_000_000_000L)
        distribution.recordDelta(1_000_000_500L)
        val first = distribution.closeWindow()
        requireNotNull(first)
        assertEquals(500L, first.p50)
        assertNull("samples must not leak into the next window", distribution.closeWindow())
        // The baseline survived: this delta is measured from the previous call, not skipped.
        distribution.recordDelta(1_000_001_000L)
        val second = distribution.closeWindow()
        requireNotNull(second)
        assertEquals(500L, second.p50)
    }

    @Test
    fun ringKeepsMostRecentSamplesAfterWrap() {
        val distribution = WindowedDistribution(capacity = 4)
        for (value in 1L..6L) {
            distribution.record(value)
        }
        val summary = distribution.closeWindow()
        requireNotNull(summary)
        // Last four recorded values survive regardless of ring position: 3, 4, 5, 6.
        assertEquals(4, summary.n)
        assertEquals(4L, summary.p50)
        assertEquals(6L, summary.p95)
        assertEquals(6L, summary.max)
    }

    @Test
    fun recordDeltaSkipsFirstCallAndFiltersBadGaps() {
        val distribution = WindowedDistribution()
        distribution.recordDelta(1_000_000L)
        assertNull("first call only stores the baseline", distribution.closeWindow())
        distribution.recordDelta(999_000L)
        assertNull("negative deltas are clock reuse, not intervals", distribution.closeWindow())
        // The rejected call still became the baseline: this delta is 1000ns from it.
        distribution.recordDelta(1_000_000L)
        val afterNegative = distribution.closeWindow()
        requireNotNull(afterNegative)
        assertEquals(1_000L, afterNegative.p50)
        val baseline = 10_000_000_000L
        distribution.recordDelta(baseline)
        distribution.recordDelta(baseline + WindowedDistribution.MAX_DELTA_NS + 1L)
        assertNull("gaps past the lifecycle threshold are not pipeline intervals", distribution.closeWindow())
        distribution.recordDelta(baseline + WindowedDistribution.MAX_DELTA_NS + 1L + 2_000L)
        val summary = distribution.closeWindow()
        requireNotNull(summary)
        assertEquals(2_000L, summary.p50)
    }

    @Test
    fun resetClearsBothSamplesAndBaseline() {
        val distribution = WindowedDistribution()
        distribution.recordDelta(1_000_000_000L)
        distribution.recordDelta(1_000_000_500L)
        distribution.reset()
        assertNull(distribution.closeWindow())
        distribution.recordDelta(2_000_000_000L)
        assertNull("after reset the next call is a baseline again", distribution.closeWindow())
    }
}
