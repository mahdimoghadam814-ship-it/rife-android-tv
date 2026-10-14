package com.rife.androidtv.rife

/**
 * Lease-bucket pacing for pair processing under a sustained motion overrun.
 *
 * When the motion search costs more than the source pair interval, a steady
 * processing pace is only reachable by declining some pairs. The bucket
 * accrues one interval per source pair and charges the measured motion cost
 * for every pair actually processed; a pair is declined while the accrued
 * credit sits below the motion cost, so declines settle into a fixed period
 * instead of landing wherever the capacity-4 overflow race happens to fire.
 *
 * Declined pairs advance the anchor chain without synthesis, so every
 * processed pair keeps the original one-interval span and the output stays on
 * a regular grid - the [PAIRGAP] clumping the drop-oldest path produced at
 * over-budget resolutions. While the motion fits the interval the estimator
 * holds no credit logic at all: a healthy pipeline never declines.
 */
class PairPacing {

    private var creditUs = 0L
    private var motionEwmaUs = 0L

    /** Feeds one processed pair's measured motion cost into the estimator. */
    fun noteMotionCost(costUs: Long) {
        if (costUs <= 0L) return
        motionEwmaUs = if (motionEwmaUs == 0L) costUs else (motionEwmaUs * 3 + costUs) / 4
    }

    /**
     * Whether the next pair should be declined: accrues one interval of
     * credit, then charges a processed pair. Returns false without touching
     * the credit while the estimator has no sample or the motion still fits
     * the interval.
     */
    fun shouldDeclinePair(intervalUs: Long): Boolean {
        val motionUs = motionEwmaUs
        if (intervalUs <= 0L || motionUs <= intervalUs) return false
        creditUs += intervalUs
        return if (creditUs >= motionUs) {
            creditUs -= motionUs
            false
        } else {
            true
        }
    }

    fun reset() {
        creditUs = 0L
        motionEwmaUs = 0L
    }
}
