package com.rife.androidtv.rife

/**
 * Predicts how a constant-rate output stamp stream lands on a fixed-refresh display.
 *
 * The pipeline stamps each output with its media-timeline presentation time
 * (eglPresentationTimeANDROID), and SurfaceFlinger latches the newest due buffer once per
 * vsync. When the output rate divides the refresh evenly every frame occupies exactly one
 * vsync and panning motion advances uniformly. When it does not - the common case being a
 * 24 fps source interpolated 2x (48 fps) on a 60 Hz panel - some frames are held for two
 * vsyncs (output below refresh) or never shown (output above refresh), and apparent motion
 * speeds up and slows down at the beat frequency even though the submission rate looks
 * perfect. That mismatch is arithmetic, so it is computed here rather than measured: the
 * app can observe submissions ([PACING] submitUs) but never actual presentation without
 * platform tools, and this turns "FPS at target but motion pulses" into a log line naming
 * the exact hold/skip pattern and its rate.
 *
 * Pure and unit-tested; no Android types. Null-tolerant: unknown refresh (0) or unusable
 * frame rates return null and the caller suppresses the line.
 */
internal object OutputCadence {

    /** Frames simulated per analysis; 48 covers a full second of a 48 fps stream. */
    private const val WINDOW_FRAMES = 48

    /** Float slack in vsync slots; see the latch computation in [analyze]. */
    private const val LATCH_EPSILON = 1e-9

    /**
     * @param outFps requested output rate (source fps x multiplier).
     * @param refreshHz display refresh rate in Hz.
     * @param even true when every simulated frame occupies the same number of vsyncs.
     * @param holdsSummary distinct per-frame vsync counts with their frequency over the
     *   window, e.g. "1x36,2x12"; a count of 0 means frames the display never shows.
     * @param beatHz rate of the odd holds/skips per second - the wobble frequency the eye
     *   sees. 0 when [even].
     */
    data class Report(
        val outFps: Double,
        val refreshHz: Double,
        val even: Boolean,
        val holdsSummary: String,
        val beatHz: Double,
    )

    fun analyze(srcFps: Double, multiplier: Float, refreshHz: Double): Report? {
        if (srcFps <= 0.0 || multiplier <= 0f || refreshHz <= 0.0) return null
        val outFps = srcFps * multiplier.toDouble()
        if (outFps <= 0.0 || !outFps.isFinite() || !refreshHz.isFinite()) return null
        // Frame i is due at i/outFps and latches at the first vsync at or after that:
        // slot(i) = ceil(due * refresh). Its hold is slot(i+1) - slot(i); 0 means the next
        // frame was already due by that vsync and this one is never shown. The epsilon
        // absorbs the float error of the division (i/60*60 can land an ulp above i, which
        // would flip an exactly-even cadence into a fake 0/2 hold pair); real patterns sit
        // at fractions of at least 1/outFps away from a vsync boundary.
        val holds = IntArray(WINDOW_FRAMES)
        for (i in 0 until WINDOW_FRAMES) {
            val slotNow = Math.ceil(i / outFps * refreshHz - LATCH_EPSILON).toLong()
            val slotNext = Math.ceil((i + 1) / outFps * refreshHz - LATCH_EPSILON).toLong()
            holds[i] = (slotNext - slotNow).toInt()
        }
        val min = holds.min()
        val max = holds.max()
        val even = min == max
        val distinct = holds.distinct().sorted()
        val holdsSummary = distinct.joinToString(",") { h ->
            "${h}x${holds.count { it == h }}"
        }
        val beatHz = if (even) {
            0.0
        } else {
            // Primitive arrays have no groupingBy; 48 entries in a tiny map, once per change.
            val frequency = HashMap<Int, Int>(4)
            for (hold in holds) {
                frequency[hold] = (frequency[hold] ?: 0) + 1
            }
            val modal = frequency.entries.maxByOrNull { it.value }?.key ?: 0
            (WINDOW_FRAMES - (frequency[modal] ?: 0)) * outFps / WINDOW_FRAMES
        }
        return Report(
            outFps = outFps,
            refreshHz = refreshHz,
            even = even,
            holdsSummary = holdsSummary,
            beatHz = beatHz,
        )
    }

    /**
     * The multiplier that would make [srcFps] land evenly on [refreshHz], for the advisory
     * text of the [CADENCE] log line. Not necessarily a selectable setting - it names the
     * arithmetic ideal so a human can weigh it against the available options.
     */
    fun idealEvenMultiplier(srcFps: Double, refreshHz: Double): Double? {
        if (srcFps <= 0.0 || refreshHz <= 0.0) return null
        return refreshHz / srcFps
    }
}
