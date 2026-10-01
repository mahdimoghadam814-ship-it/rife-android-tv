package com.rife.androidtv.rife

import android.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Lightweight motion quality analyzer using cheap metadata signals.
 *
 * Classification is based on measurable signals available without optical flow:
 * - Frame interval consistency (jitter)
 * - Keyframe frequency (I-frame interval)
 * - Decoder metadata (QP variance, frame size variance)
 * - Frame timing regularity
 *
 * Does NOT compute optical flow or use full-resolution analysis.
 * Classification is conservative by default.
 */
@UnstableApi
class MotionQualityAnalyzerImpl(
    private val config: MotionQualityConfig = MotionQualityConfig()
) : MotionQualityAnalyzer {

    // Rolling statistics for temporal analysis
    private var frameIntervals: MutableList<Long> = mutableListOf()
    private var frameSizes: MutableList<Long> = mutableListOf()
    private var qpValues: MutableList<Int> = mutableListOf()
    private var keyframeIntervals: MutableList<Int> = mutableListOf()
    private var framesSinceLastKeyframe = 0
    private var frameCount = 0

    override fun analyze(previous: FrameMetadata, current: FrameMetadata): MotionQuality {
        frameCount++

        // Update temporal statistics
        val intervalUs = current.presentationTimeUs - previous.presentationTimeUs
        if (intervalUs > 0) {
            frameIntervals.add(intervalUs)
            if (frameIntervals.size > config.historySize) frameIntervals.removeAt(0)
        }

        // Update frame size statistics from metadata
        current.decoderMetadata["frame_size"]?.toLongOrNull()?.let { size ->
            frameSizes.add(size)
            if (frameSizes.size > config.historySize) frameSizes.removeAt(0)
        }

        // Update QP statistics
        current.decoderMetadata["qp"]?.toIntOrNull()?.let { qp ->
            qpValues.add(qp)
            if (qpValues.size > config.historySize) qpValues.removeAt(0)
        }

        // Track keyframe intervals
        val currentIsKeyFrame = current.isKeyFrame || isLikelyKeyFrame(current)
        if (currentIsKeyFrame) {
            if (framesSinceLastKeyframe > 0) {
                keyframeIntervals.add(framesSinceLastKeyframe)
                if (keyframeIntervals.size > config.historySize) keyframeIntervals.removeAt(0)
            }
            framesSinceLastKeyframe = 0
        } else {
            framesSinceLastKeyframe++
        }

        // Compute quality metrics from accumulated statistics
        val intervalJitter = computeIntervalJitter()
        val qpVariance = computeQPVariance()
        val sizeVariance = computeSizeVariance()
        val keyframeRegularity = computeKeyframeRegularity()

        // Determine quality classification
        val (classification, confidence) = classifyQuality(
            intervalJitter = intervalJitter,
            qpVariance = qpVariance,
            sizeVariance = sizeVariance,
            keyframeRegularity = keyframeRegularity,
            frameCount = frameCount
        )

        // Compute individual metrics for the result
        val motionMagnitude = computeMotionMagnitude(intervalJitter, qpVariance, sizeVariance)
        val occlusionRatio = estimateOcclusionRatio(qpVariance, sizeVariance)
        val textureComplexity = estimateTextureComplexity(qpVariance, sizeVariance)
        val vectorConsistency = computeVectorConsistency(intervalJitter, qpVariance)

        val isSuitable = classification != MotionQualityClass.UNRELIABLE &&
                         classification != MotionQualityClass.LOW &&
                         confidence >= config.minConfidenceForSuitable

        val recommendedMode = when {
            classification == MotionQualityClass.HIGH && confidence > 0.7f -> InterpolationMode.BALANCED
            classification == MotionQualityClass.MEDIUM -> InterpolationMode.CONSERVATIVE
            classification == MotionQualityClass.LOW -> InterpolationMode.CONSERVATIVE
            else -> InterpolationMode.SAFE
        }

        val result = MotionQuality(
            motionMagnitude = motionMagnitude,
            confidence = confidence,
            occlusionRatio = occlusionRatio,
            textureComplexity = textureComplexity,
            vectorConsistency = vectorConsistency,
            isInterpolationSuitable = isSuitable,
            recommendedMode = recommendedMode,
            motionQualityClass = classification
        )

        // Log quality changes (not every frame)
        if (frameCount % 30 == 0 || classification != previousClassification) {
            Log.d("MotionQualityAnalyzer", "Quality: $classification conf=${String.format("%.2f", confidence)} " +
                "mag=${String.format("%.2f", motionMagnitude)} occ=${String.format("%.2f", occlusionRatio)} " +
                "mode=$recommendedMode frames=$frameCount")
        }
        previousClassification = classification

        return result
    }

    private var previousClassification: MotionQualityClass = MotionQualityClass.UNKNOWN

    private fun isLikelyKeyFrame(metadata: FrameMetadata): Boolean {
        return metadata.decoderMetadata["frame_type"]?.lowercase() == "i" ||
               metadata.decoderMetadata["frame_type"]?.lowercase() == "idr" ||
               metadata.decoderMetadata["frame_type"]?.lowercase() == "keyframe"
    }

    private fun computeIntervalJitter(): Float {
        if (frameIntervals.size < 3) return 0f
        val mean = frameIntervals.average()
        val variance = frameIntervals.map { (it - mean).toDouble() * (it - mean).toDouble() }.average()
        val stdDev = Math.sqrt(variance).toFloat()
        // Normalize: jitter as fraction of mean interval
        return if (mean > 0) (stdDev / mean).coerceAtMost(1f) else 1f
    }

    private fun computeQPVariance(): Float {
        if (qpValues.size < 3) return 0f
        val mean = qpValues.average()
        val variance = qpValues.map { (it - mean) * (it - mean) }.average()
        // Normalize: QP range is typically 0-51, variance > 100 is high
        return (variance / 100f).coerceAtMost(1f)
    }

    private fun computeSizeVariance(): Float {
        if (frameSizes.size < 3) return 0f
        val mean = frameSizes.average().toDouble()
        val variance = frameSizes.map { (it.toDouble() - mean) * (it.toDouble() - mean) }.average()
        // Normalize by mean size
        return if (mean > 0) (Math.sqrt(variance) / mean).toFloat().coerceAtMost(1f) else 1f
    }

    private fun computeKeyframeRegularity(): Float {
        if (keyframeIntervals.size < 3) return 0.5f // Unknown
        val mean = keyframeIntervals.average().toDouble()
        val variance = keyframeIntervals.map { (it.toDouble() - mean) * (it.toDouble() - mean) }.average()
        val stdDev = Math.sqrt(variance).toFloat()
        // Regular keyframes (low variance) = good for interpolation
        // Normalize: stdDev / mean, lower is better
        return if (mean > 0) (1f - (stdDev / mean).coerceAtMost(1f)) else 0.5f
    }

    private fun computeMotionMagnitude(intervalJitter: Float, qpVariance: Float, sizeVariance: Float): Float {
        // Weighted combination of motion indicators
        return (intervalJitter * 0.4f + qpVariance * 0.3f + sizeVariance * 0.3f).coerceAtMost(1f)
    }

    private fun estimateOcclusionRatio(qpVariance: Float, sizeVariance: Float): Float {
        // High QP variance or size variance often correlates with occlusion
        return (qpVariance * 0.6f + sizeVariance * 0.4f).coerceAtMost(1f)
    }

    private fun estimateTextureComplexity(qpVariance: Float, sizeVariance: Float): Float {
        // Low QP variance + high size variance = complex texture
        return (sizeVariance * 0.7f + (1f - qpVariance) * 0.3f).coerceAtMost(1f)
    }

    private fun computeVectorConsistency(intervalJitter: Float, qpVariance: Float): Float {
        // Consistent timing and QP = consistent motion vectors
        return (1f - intervalJitter * 0.7f - qpVariance * 0.3f).coerceIn(0f, 1f)
    }

    private fun classifyQuality(
        intervalJitter: Float,
        qpVariance: Float,
        sizeVariance: Float,
        keyframeRegularity: Float,
        frameCount: Int
    ): Pair<MotionQualityClass, Float> {
        // Warm-up period
        if (frameCount < config.minFramesForReliable) {
            return Pair(MotionQualityClass.UNKNOWN, 0.3f)
        }

        // Compute composite quality score (higher = better)
        val jitterScore = 1f - intervalJitter
        val qpScore = 1f - qpVariance
        val sizeScore = 1f - sizeVariance
        val kfScore = keyframeRegularity

        val compositeScore = (jitterScore * config.jitterWeight +
                             qpScore * config.qpWeight +
                             sizeScore * config.sizeWeight +
                             kfScore * config.kfWeight) / (config.jitterWeight + config.qpWeight + config.sizeWeight + config.kfWeight)

        val (classification, confidence) = when {
            compositeScore >= config.highThreshold -> Pair(MotionQualityClass.HIGH, compositeScore)
            compositeScore >= config.mediumThreshold -> Pair(MotionQualityClass.MEDIUM, compositeScore)
            compositeScore >= config.lowThreshold -> Pair(MotionQualityClass.LOW, compositeScore)
            else -> Pair(MotionQualityClass.UNRELIABLE, 1f - compositeScore)
        }

        return Pair(classification, confidence)
    }

    override fun reset() {
        frameIntervals.clear()
        frameSizes.clear()
        qpValues.clear()
        keyframeIntervals.clear()
        framesSinceLastKeyframe = 0
        frameCount = 0
        previousClassification = MotionQualityClass.UNKNOWN
    }
}

/**
 * Motion quality classification (replaces the float-based MotionQuality.isInterpolationSuitable).
 */
@UnstableApi
enum class MotionQualityClass {
    UNKNOWN,      // Not enough data yet
    LOW,          // Poor quality, interpolation likely to produce artifacts
    MEDIUM,       // Acceptable quality, conservative interpolation
    HIGH,         // Good quality, balanced/aggressive interpolation viable
    UNRELIABLE    // Highly variable, interpolation not recommended
}

/**
 * Configuration for motion quality analysis.
 */
@UnstableApi
data class MotionQualityConfig(
    /** History size for rolling statistics */
    val historySize: Int = 30,
    /** Minimum frames before quality assessment is reliable */
    val minFramesForReliable: Int = 15,
    /** Weight for interval jitter in composite score */
    val jitterWeight: Float = 0.3f,
    /** Weight for QP variance in composite score */
    val qpWeight: Float = 0.25f,
    /** Weight for size variance in composite score */
    val sizeWeight: Float = 0.2f,
    /** Weight for keyframe regularity in composite score */
    val kfWeight: Float = 0.25f,
    /** Threshold for HIGH quality */
    val highThreshold: Float = 0.75f,
    /** Threshold for MEDIUM quality */
    val mediumThreshold: Float = 0.5f,
    /** Threshold for LOW quality */
    val lowThreshold: Float = 0.3f,
    /** Minimum confidence for interpolation to be suitable */
    val minConfidenceForSuitable: Float = 0.4f
) {
    companion object {
        val DEFAULT = MotionQualityConfig()
    }
}