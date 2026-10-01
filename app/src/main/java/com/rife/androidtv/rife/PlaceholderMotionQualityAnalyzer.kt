package com.rife.androidtv.rife

import androidx.media3.common.util.UnstableApi

/**
 * Placeholder motion quality analyzer.
 * Returns conservative quality metrics - real implementation would analyze motion vectors,
 * occlusion, texture, etc.
 */
@UnstableApi
class PlaceholderMotionQualityAnalyzer : MotionQualityAnalyzer {

    override fun analyze(previous: FrameMetadata, current: FrameMetadata): MotionQuality {
        // Return conservative quality - no interpolation by default
        return MotionQuality.conservative()
    }

    override fun reset() {
        // No state to reset
    }
}