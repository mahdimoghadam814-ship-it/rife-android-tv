package com.rife.androidtv.rife

import kotlin.math.abs

/**
 * Picks the interpolation multiplier the pipeline should actually run.
 *
 * A constant-rate output stream presents evenly only when its rate divides the display
 * refresh: 24 fps x 2 (48) and x 3 (72) both miss 60 Hz, so some frames are held for two
 * vsyncs and motion beats at the arithmetic beat frequency no matter how fast the pipeline
 * is - the exact "output rate fine, motion pulses" failure [OutputCadence] documents. SVP's
 * own answer is the multiplier that lands on the panel: 24->60 is 2.5x, 30->60 is 2x,
 * 25->60 is 2.4x. This object computes that snap; the user's 2x/3x setting picks the
 * neighbourhood, the panel's arithmetic picks the exact rate inside it.
 *
 * Pure and unit-tested; no Android types. Null-tolerant: unknown refresh (0) or unknown
 * source rate return the user's setting unchanged.
 */
internal object MemcLevelSnap {

    /** Same band the processor clamps the user setting to. */
    const val MIN_MULTIPLIER = 2f
    const val MAX_MULTIPLIER = 4f

    /**
     * [user] setting snapped to the rate that lands evenly on [refreshHz], clamped into
     * [MIN_MULTIPLIER, MAX_MULTIPLIER]. Moves smaller than 0.01 are jitter in the measured
     * source rate, not a different cadence, and keep [user] so the emission phase is never
     * re-anchored for noise.
     */
    fun effective(user: Float, srcFps: Double, refreshHz: Float): Float {
        val u = user.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        if (refreshHz <= 0f || srcFps <= 0.0 || !srcFps.isFinite()) return u
        val even = refreshHz.toDouble() / srcFps
        if (!even.isFinite()) return u
        val clamped = even.coerceIn(MIN_MULTIPLIER.toDouble(), MAX_MULTIPLIER.toDouble())
        if (abs(clamped - u) < 0.01) return u
        return clamped.toFloat()
    }
}
