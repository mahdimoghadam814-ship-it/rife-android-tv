package com.rife.androidtv.rife

import androidx.media3.common.util.UnstableApi

/**
 * Basic interpolation scheduler implementation.
 * Makes scheduling decisions based on frame timing and motion quality.
 */
@UnstableApi
class InterpolationSchedulerImpl(
    private var config: InterpolationSchedulerConfig = InterpolationSchedulerConfig()
) : InterpolationScheduler {

    private var state = SchedulerState(
        currentMode = config.defaultMode,
        pendingDecisions = 0,
        completedFrames = 0,
        droppedFrames = 0,
        averageSynthesisTimeUs = 0,
        currentResolution = "Unknown"
    )

    override fun schedule(framePair: FramePair): ScheduleDecision {
        // Check if we should skip due to timing constraints
        val shouldSkip = framePair.frameIntervalUs < config.minFrameIntervalUs
        
        // For now, use conservative scheduling
        val decision = ScheduleDecision(
            framePair = framePair,
            sceneChange = SceneChangeResult(isSceneChange = false, confidence = 0f),
            motionQuality = MotionQuality.conservative(),
            mode = if (shouldSkip) InterpolationMode.SAFE else config.defaultMode,
            numIntermediates = if (shouldSkip) 0 else min(config.maxIntermediates, 1),
            interpolationTimes = if (shouldSkip) emptyList() else listOf(0.5f),
            targetWidth = framePair.current.width,
            targetHeight = framePair.current.height,
            estimatedSynthesisTimeUs = 10_000,
            shouldSkip = shouldSkip
        )

        state = state.copy(pendingDecisions = state.pendingDecisions + 1)
        return decision
    }

    override fun onSynthesisComplete(decision: ScheduleDecision, result: SynthesisResult) {
        state = state.copy(
            pendingDecisions = maxOf(0, state.pendingDecisions - 1),
            completedFrames = state.completedFrames + decision.numIntermediates.toLong(),
            averageSynthesisTimeUs = result.actualSynthesisTimeUs
        )
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
    }
}