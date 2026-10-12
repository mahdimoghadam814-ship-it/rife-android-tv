package com.rife.androidtv.rife

/**
 * The output presentation timeline, as a pure state machine.
 *
 * Every present must carry a timestamp later than the one before it. A present that does not
 * advance the timeline means two frame pairs claimed the same span - the signature of pairing
 * against a stale previous frame, which the encoder and the BufferQueue both punish with dropped
 * or repeated frames (stutter at a first glance even though the output rate looks correct).
 *
 * Deliberately free of Android, GL and time-source dependencies: [note] takes only the timestamp
 * it is judging, so the ordering rule is unit-testable and the caller owns both the wall clock
 * (for rate-limiting the log) and the re-arm point (pipeline reset, seek).
 */
internal class OutputTimelineGuard {

    /** How many presents failed to advance the timeline since construction or [rearm]. */
    var regressions: Long = 0L
        private set

    /** Timestamp of the last present that advanced the timeline; 0 before the first one. */
    var lastTimestampNs: Long = 0L
        private set

    /**
     * Records a present at [timestampNs].
     *
     * @return true when the present advanced (or armed) the timeline, false when it did not.
     *     A non-positive timestamp carries no timeline information and is ignored.
     */
    fun note(timestampNs: Long): Boolean {
        if (timestampNs <= 0L) return true
        if (lastTimestampNs == 0L) {
            // First present of the stream arms the check without judging it.
            lastTimestampNs = timestampNs
            return true
        }
        if (timestampNs > lastTimestampNs) {
            lastTimestampNs = timestampNs
            return true
        }
        regressions++
        return false
    }

    /**
     * Forgets the timeline so the next present arms it instead of being judged against the last
     * frame of the previous stream. A seek or a stream change carries no relation backwards, so
     * the cut must not read as a defect.
     */
    fun rearm() {
        lastTimestampNs = 0L
    }
}
