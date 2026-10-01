package com.rife.androidtv.rife

import android.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Lightweight scene change detector using metadata and compact statistics.
 * 
 * Detection signals (in order of preference):
 * 1. Keyframe (I-frame) detection via decoder metadata
 * 2. Frame property changes (resolution, format)
 * 3. Frame size / QP discontinuities from decoder metadata
 * 4. [Future] Compact luminance histogram if pixel data available
 * 
 * Uses hysteresis/debouncing to avoid flickering on noisy boundaries.
 */
@UnstableApi
class SceneChangeDetectorImpl(
    private val config: SceneChangeConfig = SceneChangeConfig()
) : SceneChangeDetector {

    // State for hysteresis
    private var consecutiveNonChanges = 0
    private var consecutivePotentialChanges = 0
    private var lastSceneChangeFrameId: Long = -1
    private var lastSignificantChangeScore: Float = 0f

    override fun detect(previous: FrameMetadata, current: FrameMetadata): SceneChangeResult {
        var score = 0.0f
        val reasons = mutableListOf<String>()

        // Signal 1: Keyframe (I-frame) detection - strongest signal
        val currentIsKeyFrame = current.isKeyFrame || isLikelyKeyFrame(current)
        val previousWasKeyFrame = previous.isKeyFrame || isLikelyKeyFrame(previous)

        if (currentIsKeyFrame && !previousWasKeyFrame) {
            score += config.keyFrameWeight
            reasons.add("keyframe_transition")
        }

        // Signal 2: Frame property discontinuities
        if (current.width != previous.width || current.height != previous.height) {
            score += config.resolutionChangeWeight
            reasons.add("resolution_change")
        }
        if (current.format != previous.format) {
            score += config.formatChangeWeight
            reasons.add("format_change")
        }

        // Signal 3: Decoder metadata analysis (frame size, QP, frame type)
        val metaScore = analyzeDecoderMetadata(previous, current)
        if (metaScore > 0f) {
            score += metaScore * config.metadataWeight
            reasons.add("metadata_discontinuity")
        }

        // Signal 4: Timestamp anomaly (large frame interval)
        val intervalUs = current.presentationTimeUs - previous.presentationTimeUs
        if (intervalUs > 0 && intervalUs > config.timestampAnomalyThresholdUs) {
            score += config.timestampAnomalyWeight
            reasons.add("timestamp_anomaly")
        }

        // Apply hysteresis/debouncing
        val (finalScore, isSceneChange) = applyHysteresis(score, current.frameId)

        // Build result
        val changeType = when {
            !isSceneChange -> SceneChangeType.NONE
            currentIsKeyFrame && !previousWasKeyFrame -> SceneChangeType.HARD_CUT
            score > config.gradualTransitionThreshold -> SceneChangeType.GRADUAL_TRANSITION
            else -> SceneChangeType.HARD_CUT
        }

        val confidence = (finalScore / config.maxPossibleScore).coerceIn(0f, 1f)

        // Log only significant events
        if (isSceneChange) {
            Log.d("SceneChangeDetector", "Scene change at frame ${current.frameId}: " +
                "score=${String.format("%.2f", finalScore)} conf=${String.format("%.2f", confidence)} " +
                "type=$changeType reasons=${reasons.joinToString(",")}")
        }

        return SceneChangeResult(
            isSceneChange = isSceneChange,
            confidence = confidence,
            changeType = changeType
        )
    }

    private fun isLikelyKeyFrame(metadata: FrameMetadata): Boolean {
        // Check decoder metadata for frame type
        return metadata.decoderMetadata["frame_type"]?.lowercase() == "i" ||
               metadata.decoderMetadata["frame_type"]?.lowercase() == "idr" ||
               metadata.decoderMetadata["frame_type"]?.lowercase() == "keyframe"
    }

    private fun analyzeDecoderMetadata(previous: FrameMetadata, current: FrameMetadata): Float {
        var score = 0f

        // Frame size discontinuity
        val prevSizeStr = previous.decoderMetadata["frame_size"]
        val currSizeStr = current.decoderMetadata["frame_size"]
        if (prevSizeStr != null && currSizeStr != null) {
            val prevSize = prevSizeStr.toLongOrNull() ?: 0L
            val currSize = currSizeStr.toLongOrNull() ?: 0L
            if (prevSize > 0) {
                val ratio = currSize.toFloat() / prevSize.toFloat()
                if (ratio > 2.0f || ratio < 0.5f) {
                    score += 0.5f
                }
            }
        }

        // QP discontinuity
        val prevQp = previous.decoderMetadata["qp"]?.toIntOrNull()
        val currQp = current.decoderMetadata["qp"]?.toIntOrNull()
        if (prevQp != null && currQp != null) {
            val qpDiff = abs(currQp - prevQp)
            if (qpDiff > 10) {
                score += 0.3f
            }
        }

        // Frame type change
        val prevType = previous.decoderMetadata["frame_type"]?.lowercase() ?: ""
        val currType = current.decoderMetadata["frame_type"]?.lowercase() ?: ""
        if (prevType != currType && currType.isNotEmpty()) {
            if (currType == "i" || currType == "idr" || currType == "keyframe") {
                score += 0.4f
            }
        }

        return score.coerceAtMost(1f)
    }

    private fun applyHysteresis(score: Float, frameId: Long): Pair<Float, Boolean> {
        val effectiveThreshold = config.baseThreshold

        if (score >= effectiveThreshold) {
            consecutivePotentialChanges++
            consecutiveNonChanges = 0
        } else {
            consecutiveNonChanges++
            consecutivePotentialChanges = 0
        }

        // Require N consecutive frames above threshold to confirm scene change
        val isSceneChange = consecutivePotentialChanges >= config.confirmationFrames

        // Debounce: don't trigger again too quickly after a confirmed change
        if (isSceneChange && frameId - lastSceneChangeFrameId < config.minFramesBetweenChanges) {
            return Pair(score, false)
        }

        if (isSceneChange) {
            lastSceneChangeFrameId = frameId
            lastSignificantChangeScore = score
        }

        return Pair(score, isSceneChange)
    }

    override fun reset() {
        consecutiveNonChanges = 0
        consecutivePotentialChanges = 0
        lastSceneChangeFrameId = -1
        lastSignificantChangeScore = 0f
    }
}

/**
 * Configuration for scene change detection.
 */
@UnstableApi
data class SceneChangeConfig(
    /** Base threshold for scene change score [0, 1] */
    val baseThreshold: Float = 0.5f,
    /** Minimum frames above threshold to confirm scene change (debouncing) */
    val confirmationFrames: Int = 2,
    /** Minimum frames between confirmed scene changes (debouncing) */
    val minFramesBetweenChanges: Int = 5,
    /** Weight for keyframe transition signal */
    val keyFrameWeight: Float = 0.5f,
    /** Weight for resolution change signal */
    val resolutionChangeWeight: Float = 0.3f,
    /** Weight for format change signal */
    val formatChangeWeight: Float = 0.2f,
    /** Weight for decoder metadata signal */
    val metadataWeight: Float = 0.4f,
    /** Weight for timestamp anomaly signal */
    val timestampAnomalyWeight: Float = 0.1f,
    /** Threshold for timestamp anomaly (microseconds) */
    val timestampAnomalyThresholdUs: Long = 100_000, // 100ms
    /** Threshold for gradual transition classification */
    val gradualTransitionThreshold: Float = 0.7f,
    /** Maximum possible score for normalization */
    val maxPossibleScore: Float = 2.0f
) {
    companion object {
        /** Default configuration suitable for most content */
        val DEFAULT = SceneChangeConfig()
    }
}