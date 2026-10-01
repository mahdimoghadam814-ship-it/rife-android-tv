package com.rife.androidtv.rife

import androidx.media3.common.util.UnstableApi

/**
 * Deterministic, conservative interpolation scheduler.
 *
 * Decision policy:
 * - Scene change detected -> BYPASS/PASS_THROUGH (REPEAT_ON_SCENE_CHANGE)
 * - Motion quality UNKNOWN/warmup -> PASS_THROUGH
 * - Motion quality UNRELIABLE -> PASS_THROUGH
 * - Motion quality LOW -> conservative interpolation
 * - Motion quality MEDIUM -> normal interpolation
 * - Motion quality HIGH -> interpolation eligible
 *
 * Backpressure: respects OutputQueue capacity, avoids scheduling when full
 * Timing: uses frame presentation timestamps only
 * Lifecycle: resets cleanly on start/stop/seek/discontinuity/EOS/error
 */
@UnstableApi
class InterpolationSchedulerImpl(
    private val sceneDetector: SceneChangeDetector,
    private val motionAnalyzer: MotionQualityAnalyzer,
    private val outputQueue: OutputQueue,
    private var config: InterpolationSchedulerConfig = InterpolationSchedulerConfig()
) : InterpolationScheduler {

    // Decision type enum for explicit policy
    private enum class DecisionType {
        BYPASS,           // Pass through without interpolation (scene change, unreliable)
        HOLD,             // Wait for more data (warmup)
        INTERPOLATE,      // Normal interpolation
        REPEAT_FRAME,     // Scene change: repeat current frame
        DRAIN,            // Drain and stop
    }

    private var state = SchedulerState(
        currentMode = config.defaultMode,
        pendingDecisions = 0,
        completedFrames = 0,
        droppedFrames = 0,
        averageSynthesisTimeUs = 0,
        currentResolution = "Unknown"
    )

    // Track consecutive warmup frames for debouncing
    private var consecutiveWarmupFrames = 0
    private var lastSceneChangeFrameId: Long = -1
    private var consecutiveSceneChanges = 0

    override fun schedule(framePair: FramePair): ScheduleDecision {
        // 1. Check timing constraints first
        val shouldSkipTiming = framePair.frameIntervalUs < config.minFrameIntervalUs

        // 2. Scene change detection (highest priority)
        val sceneChange = sceneDetector.detect(framePair.previous, framePair.current)

        if (sceneChange.isSceneChange) {
            // Scene change policy: never interpolate across boundary
            consecutiveSceneChanges++
            lastSceneChangeFrameId = framePair.current.frameId

            return ScheduleDecision(
                framePair = framePair,
                sceneChange = sceneChange,
                motionQuality = MotionQuality.conservative(),
                mode = InterpolationMode.SAFE,
                numIntermediates = 0,
                interpolationTimes = emptyList(),
                targetWidth = framePair.current.width,
                targetHeight = framePair.current.height,
                estimatedSynthesisTimeUs = 0,
                shouldSkip = false // Not skipping - we'll repeat the frame
            )
        } else {
            consecutiveSceneChanges = 0
        }

        // 3. Motion quality analysis
        val motionQuality = motionAnalyzer.analyze(framePair.previous, framePair.current)

        // 4. Backpressure check - if output queue is near capacity, avoid scheduling more work
        val queuePressure = outputQueue.size().toFloat() / outputQueue.capacity()
        val highPressure = queuePressure > 0.8f

        // 5. Decision policy based on motion quality classification
        val (decisionType, mode, numIntermediates, interpolationTimes) = when {
            // Scene change handled above
            sceneChange.isSceneChange -> {
                // Already handled above
                DecisionType.REPEAT_FRAME to InterpolationMode.SAFE to 0 to emptyList<Float>()
            }
            // Warmup / unknown quality -> hold/wait
            motionQuality.confidence < 0.3f || motionQuality.motionQualityClass == MotionQualityClass.UNKNOWN -> {
                consecutiveWarmupFrames++
                // After warmup period, allow conservative interpolation
                if (consecutiveWarmupFrames >= 15) {
                    DecisionType.INTERPOLATE to InterpolationMode.CONSERVATIVE to 1 to listOf(0.5f)
                } else {
                    DecisionType.HOLD to InterpolationMode.SAFE to 0 to emptyList<Float>()
                }
            }
            // Unreliable quality -> bypass
            motionQuality.motionQualityClass == MotionQualityClass.UNRELIABLE -> {
                DecisionType.BYPASS to InterpolationMode.SAFE to 0 to emptyList<Float>()
            }
            // Low quality -> conservative
            motionQuality.motionQualityClass == MotionQualityClass.LOW -> {
                if (highPressure) DecisionType.BYPASS else DecisionType.INTERPOLATE
                to InterpolationMode.CONSERVATIVE to 1 to listOf(0.5f)
            }
            // Medium quality -> normal conservative
            motionQuality.motionQualityClass == MotionQualityClass.MEDIUM -> {
                if (highPressure) DecisionType.HOLD else DecisionType.INTERPOLATE
                to InterpolationMode.CONSERVATIVE to min(config.maxIntermediates, 1) to listOf(0.5f)
            }
            // High quality -> eligible for balanced
            motionQuality.motionQualityClass == MotionQualityClass.HIGH -> {
                if (highPressure) DecisionType.HOLD else DecisionType.INTERPOLATE
                to config.defaultMode to min(config.maxIntermediates, 1) to listOf(0.5f)
            }
            // Fallback
            else -> DecisionType.BYPASS to InterpolationMode.SAFE to 0 to emptyList<Float>()
        }

        val shouldSkip = decisionType == DecisionType.BYPASS || decisionType == DecisionType.HOLD
        val shouldRepeat = decisionType == DecisionType.REPEAT_FRAME

        val decision = ScheduleDecision(
            framePair = framePair,
            sceneChange = SceneChangeResult(isSceneChange = false, confidence = 0f),
            motionQuality = motionQuality,
            mode = mode,
            numIntermediates = if (shouldSkip || shouldRepeat) 0 else numIntermediates,
            interpolationTimes = if (shouldSkip || shouldRepeat) emptyList() else interpolationTimes,
            targetWidth = framePair.current.width,
            targetHeight = framePair.current.height,
            estimatedSynthesisTimeUs = estimateSynthesisTime(mode, highPressure),
            shouldSkip = shouldSkip
        )

        state = state.copy(pendingDecisions = state.pendingDecisions + 1)
        return decision
    }

    private fun estimateSynthesisTime(mode: InterpolationMode, highPressure: Boolean): Long {
        return when (mode) {
            InterpolationMode.SAFE -> 0L
            InterpolationMode.CONSERVATIVE -> if (highPressure) 15_000L else 10_000L
            InterpolationMode.BALANCED -> if (highPressure) 20_000L else 12_000L
            InterpolationMode.AGGRESSIVE -> if (highPressure) 25_000L else 15_000L
        }
    }

    override fun onSynthesisComplete(decision: ScheduleDecision, result: SynthesisResult) {
        state = state.copy(
            pendingDecisions = maxOf(0, state.pendingDecisions - 1),
            completedFrames = state.completedFrames + decision.numIntermediates.toLong(),
            averageSynthesisTimeUs = result.actualSynthesisTimeUs
        )
        consecutiveWarmupFrames = 0 // Reset warmup counter on successful synthesis
    }

    override fun onSynthesisFailed(decision: ScheduleDecision, error: String) {
        state = state.copy(
            pendingDecisions = maxOf(0, state.pendingDecisions - 1),
            droppedFrames = state.droppedFrames + decision.numIntermediates.toLong()
        )
    }

    override fun updateConfig(newConfig: InterpolationSchedulerConfig) {
        config = newConfig
        state = state.copy(currentMode = newConfig.defaultMode)
    }

    override fun getState(): SchedulerState = state

    override fun reset() {
        state = SchedulerState(
            currentMode = config.defaultMode,
            pendingDecisions = 0,
            completedFrames = 0,
            droppedFrames = 0,
            averageSynthesisTimeUs = 0,
            currentResolution = "Unknown"
        )
        consecutiveWarmupFrames = 0
        lastSceneChangeFrameId = -1
        consecutiveSceneChanges = 0

        // Reset analysis components
        sceneDetector.reset()
        motionAnalyzer.reset()
    }
}