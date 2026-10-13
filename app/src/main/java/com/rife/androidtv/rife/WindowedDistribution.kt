package com.rife.androidtv.rife

/**
 * Fixed-capacity ring of interval samples, closed once per diagnostics window into a
 * p50/p95/max summary plus a count of outliers (samples above twice the median). Averages
 * hide the recurring dips this exists to catch: a pipeline whose pair time wanders around
 * the source interval looks healthy on average while every other second loses a frame.
 * Sorting at most [DEFAULT_CAPACITY] longs when the window closes costs nothing next to
 * the pipeline it observes.
 *
 * Not internally synchronised. Callers run everything on the worker thread that owns the
 * pipeline; the renderer's swap site is that same thread.
 */
internal class WindowedDistribution(capacity: Int = DEFAULT_CAPACITY) {

    private val samples = LongArray(capacity.coerceAtLeast(1))
    private var count = 0
    private var next = 0
    private var lastNs = 0L

    /**
     * Records the delta between [nowNs] and the previously recorded timestamp. The first
     * call only stores the baseline: a delta needs two events. Non-positive deltas (clock
     * reuse after teardown) and gaps beyond [MAX_DELTA_NS] (a pipeline reset, a paused
     * player) are ignored rather than poisoning the window with one enormous value.
     */
    fun recordDelta(nowNs: Long) {
        val baseline = lastNs
        lastNs = nowNs
        if (baseline == 0L) return
        val delta = nowNs - baseline
        if (delta <= 0L || delta > MAX_DELTA_NS) return
        record(delta)
    }

    fun record(value: Long) {
        samples[next] = value
        next = (next + 1) % samples.size
        if (count < samples.size) count++
    }

    /**
     * Clears both the samples and the [recordDelta] baseline. Used when the source of the
     * deltas is rebuilt (a new renderer), so the first interval after the rebuild cannot
     * span the teardown; [MAX_DELTA_NS] would filter that gap anyway, but an explicit
     * reset keeps the invariant obvious at the call site.
     */
    fun reset() {
        count = 0
        next = 0
        lastNs = 0L
    }

    /**
     * Closes the window: the summary of the samples recorded since the last close, or null
     * when nothing was recorded. The [recordDelta] baseline deliberately survives, so the
     * first delta of the next window is the true interval across the boundary. Outliers
     * are samples above twice the window's median: a 2x pair interval is a skipped pair, a
     * 2x queue age is the latency policy approaching a discard.
     */
    fun closeWindow(): Summary? {
        if (count == 0) return null
        // When the ring has wrapped, count == samples.size and this copies the whole ring;
        // before that, samples were only ever appended, so [0, count) is exactly them.
        val copy = samples.copyOf(count)
        copy.sort()
        val p50 = copy[percentileIndex(50, copy.size)]
        val p95 = copy[percentileIndex(95, copy.size)]
        val max = copy[copy.size - 1]
        var outliers = 0
        if (p50 > 0L) {
            val bound = p50 * 2L
            for (value in copy) {
                if (value > bound) outliers++
            }
        }
        count = 0
        next = 0
        return Summary(p50 = p50, p95 = p95, max = max, n = copy.size, outliers = outliers)
    }

    /** One window's worth of distribution statistics, in the unit the samples were recorded in. */
    data class Summary(
        val p50: Long,
        val p95: Long,
        val max: Long,
        val n: Int,
        val outliers: Int,
    )

    companion object {
        /** 96 samples cover four seconds of 24 fps arrivals, or one ratio-4 window of swaps. */
        const val DEFAULT_CAPACITY = 96

        /** Gaps longer than this are lifecycle events (reset, pause), not pipeline intervals. */
        const val MAX_DELTA_NS = 5_000_000_000L

        /**
         * Nearest-rank percentile on a sorted array: ceil(percent/100 * size), one-based.
         * p50 of an even-length window therefore lands on the upper median, which biases the
         * summary toward the slow side - the side that costs frames.
         */
        private fun percentileIndex(percent: Int, size: Int): Int =
            ((percent * size + 99) / 100 - 1).coerceIn(0, size - 1)
    }
}
