package com.rife.androidtv.rife

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Main SVP-inspired pipeline coordinator.
 * Orchestrates the interpolation pipeline components.
 */
@UnstableApi
class SvpPipelineCoordinator(
    private val context: Context,
    private val frameStore: TemporalFrameStore = TemporalFrameStoreImpl(),
    private val sceneDetector: SceneChangeDetector = SceneChangeDetectorImpl(),
    private val motionAnalyzer: MotionQualityAnalyzer = MotionQualityAnalyzerImpl(),
    private val scheduler: InterpolationScheduler = InterpolationSchedulerImpl(
        sceneDetector = SceneChangeDetectorImpl(),
        motionAnalyzer = MotionQualityAnalyzerImpl(),
        outputQueue = BoundedOutputQueue(),
        config = InterpolationSchedulerConfig()
    ),
    private val synthesisBackend: SynthesisBackend = RifeSynthesisBackend(context),
    private val outputQueue: OutputQueue = BoundedOutputQueue(),
    private val config: SchedulerConfig = SchedulerConfig()
) {

    private val _state = MutableStateFlow<PipelineState>(PipelineState.IDLE)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _stats = MutableStateFlow<PipelineStats>(
        PipelineStats(0f, 0f, 0, 0, 0)
    )
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    private var coroutineScope: CoroutineScope? = null
    private var job: Job? = null

    /** Start the pipeline */
    fun start() {
        if (_state.value != PipelineState.IDLE && _state.value != PipelineState.STOPPED) return

        _state.value = PipelineState.BUFFERING

        // Initialize backend
        val config = SynthesisConfig(
            backendType = BackendType.RIFE_VULKAN,
            maxResolutionWidth = config.maxResolutionWidth,
            maxResolutionHeight = config.maxResolutionHeight,
            enableVulkan = true,
            numThreads = config.numThreads,
            modelPath = "rife-v2.4"
        )

        if (!synthesisBackend.initialize(config)) {
            _state.value = PipelineState.ERROR
            return
        }

        _state.value = PipelineState.READY
    }

    /** Stop the pipeline and release resources */
    fun stop() {
        job?.cancel()
        job = null
        coroutineScope = null

        outputQueue.complete()
        synthesisBackend.release()
        frameStore.clear()
        scheduler.reset()
        sceneDetector.reset()
        motionAnalyzer.reset()

        _state.value = PipelineState.STOPPED
    }

    /** Submit a frame for processing */
    fun submitFrame(metadata: FrameMetadata): Boolean {
        return frameStore.addFrame(metadata)
    }

    /** Process a frame pair if available */
    fun processFramePair(): Boolean {
        val framePair = frameStore.getLatestFramePair() ?: return false

        // Check for scene change
        val sceneChange = sceneDetector.detect(framePair.previous, framePair.current)
        if (sceneChange.isSceneChange) {
            // Scene change - don't interpolate
            outputQueue.tryEnqueue(OutputFrame(
                frameMetadata = framePair.current,
                isIntermediate = false,
                timestampUs = framePair.current.presentationTimeUs,
                data = ByteArray(0), // Placeholder
                width = framePair.current.width,
                height = framePair.current.height,
                format = framePair.current.format
            ))
            return true
        }

        // Analyze motion quality
        val motionQuality = motionAnalyzer.analyze(framePair.previous, framePair.current)

        // Schedule interpolation
        val decision = scheduler.schedule(framePair.copy(
            previous = framePair.previous,
            current = framePair.current,
            frameIntervalUs = framePair.frameIntervalUs
        ).also { it.motionQuality = motionQuality; it.sceneChange = sceneChange })

        if (decision.shouldSkip || decision.numIntermediates == 0) {
            // No interpolation needed
            outputQueue.tryEnqueue(OutputFrame(
                frameMetadata = framePair.current,
                isIntermediate = false,
                timestampUs = framePair.current.presentationTimeUs,
                data = ByteArray(0),
                width = framePair.current.width,
                height = framePair.current.height,
                format = framePair.current.format
            ))
            return true
        }

        // Submit synthesis
        synthesisBackend.synthesize(
            framePair = framePair,
            interpolationTimes = decision.interpolationTimes,
            targetWidth = decision.targetWidth,
            targetHeight = decision.targetHeight,
            outputCallback = { intermediates ->
                intermediates.forEach { frame ->
                    outputQueue.tryEnqueue(OutputFrame(
                        frameMetadata = FrameMetadata(
                            frameId = 0, // Would be assigned properly
                            presentationTimeUs = frame.timestampUs,
                            width = frame.width,
                            height = frame.height,
                            format = frame.format
                        ),
                        isIntermediate = true,
                        interpolationTime = frame.interpolationTime,
                        timestampUs = frame.timestampUs,
                        data = frame.data,
                        width = frame.width,
                        height = frame.height,
                        format = frame.format
                    ))
                }

                scheduler.onSynthesisComplete(decision, SynthesisResult(
                    success = true,
                    intermediateFrames = intermediates,
                    actualSynthesisTimeUs = 0
                ))
            }
        )

        return true
    }

    /** Get output frame for display */
    fun getNextOutputFrame(): OutputFrame? {
        return outputQueue.tryDequeue()
    }

    /** Get current pipeline status */
    fun getStatus(): PipelineStatus {
        return PipelineStatus(
            state = _state.value,
            schedulerState = scheduler.getState(),
            backendStatus = synthesisBackend.getStatus(),
            queueSize = outputQueue.size(),
            queueCapacity = outputQueue.capacity(),
            frameStoreSize = frameStore.size(),
            framesProcessed = scheduler.getState().completedFrames,
            framesDropped = scheduler.getState().droppedFrames,
            currentResolution = scheduler.getState().currentResolution
        )
    }

    /** Get statistics flow */
    fun getStats(): StateFlow<PipelineStats> = _stats

    /** Configuration data class */
    data class SchedulerConfig(
        val maxResolutionWidth: Int = 1920,
        val maxResolutionHeight: Int = 1080,
        val numThreads: Int = 4
    )
}