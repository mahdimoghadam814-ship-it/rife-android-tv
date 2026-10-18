package com.rife.androidtv.offline

/**
 * Presentation-time arithmetic of the offline 3x schedule.
 *
 * The output keeps the source timeline: every source frame stays an anchor at its original
 * timestamp, and each pair gains two synthesized frames at one third and two thirds of the
 * gap. A stream of N source frames therefore produces 3N-2 output frames spanning exactly the
 * original duration - which is also why audio and subtitle samples can be copied with their
 * original timestamps unchanged and still land in sync.
 */
object OfflinePts {

    const val MULTIPLIER = 3

    /** PTS of the [index]th synthesized frame of a pair, index in 1..2. */
    fun midPtsUs(prevUs: Long, nextUs: Long, index: Int): Long {
        require(index in 1 until MULTIPLIER) { "index $index" }
        return prevUs + (nextUs - prevUs) * index / MULTIPLIER
    }

    /** Output frame count for [inputFrames] source frames. */
    fun outputFrameCount(inputFrames: Int): Int =
        if (inputFrames <= 0) 0 else inputFrames * MULTIPLIER - 2
}
