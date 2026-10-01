package com.rife.androidtv.rife

import androidx.media3.common.util.UnstableApi

/**
 * Frame metadata with timestamp and presentation information.
 * Immutable data class representing a decoded frame ready for interpolation processing.
 */
@UnstableApi
data class FrameMetadata(
    /** Unique frame identifier (monotonically increasing) */
    val frameId: Long,
    /** Media timestamp in microseconds */
    val presentationTimeUs: Long,
    /** Decoded frame width in pixels */
    val width: Int,
    /** Decoded frame height in pixels */
    val height: Int,
    /** Frame format (e.g., RGBA, YUV420, P010) */
    val format: FrameFormat,
    /** Whether this frame is a keyframe / IDR frame */
    val isKeyFrame: Boolean = false,
    /** Optional: decoder-specific metadata */
    val decoderMetadata: Map<String, String> = emptyMap()
) {
    /** Aspect ratio (width / height) as float */
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}

/** Supported frame formats for interpolation processing */
enum class FrameFormat {
    RGBA,
    RGB,
    YUV420,
    YUV422,
    YUV444,
    P010,
    NV12,
    NV21
}

/**
 * A frame pair representing two consecutive decoded frames.
 * This is the fundamental input for interpolation.
 */
@UnstableApi
data class FramePair(
    val previous: FrameMetadata,
    val current: FrameMetadata,
    /** Time interval between frames in microseconds */
    val frameIntervalUs: Long
) {
    val isValid: Boolean
        get() = previous.frameId < current.frameId && frameIntervalUs > 0
}

/**
 * Result of scene change detection.
 */
@UnstableApi
data class SceneChangeResult(
    /** Whether a scene change was detected between the frame pair */
    val isSceneChange: Boolean,
    /** Confidence score [0.0, 1.0] */
    val confidence: Float,
    /** Type of scene change if detected */
    val changeType: SceneChangeType = SceneChangeType.NONE
)

enum class SceneChangeType {
    NONE,
    HARD_CUT,
    GRADUAL_TRANSITION,
    FLASH,
    UNKNOWN
}

/**
 * Motion quality analysis result for a frame pair.
 * Provides metrics to guide interpolation decisions.
 */
@UnstableApi
data class MotionQuality(
    /** Overall motion magnitude [0.0, 1.0] normalized */
    val motionMagnitude: Float,
    /** Confidence in the motion estimation [0.0, 1.0] */
    val confidence: Float,
    /** Estimated occlusion ratio [0.0, 1.0] */
    val occlusionRatio: Float,
    /** Texture complexity measure [0.0, 1.0] */
    val textureComplexity: Float,
    /** Local motion vector consistency [0.0, 1.0] */
    val vectorConsistency: Float,
    /** Whether motion is suitable for interpolation */
    val isInterpolationSuitable: Boolean,
    /** Recommended interpolation mode based on motion quality */
    val recommendedMode: InterpolationMode = InterpolationMode.CONSERVATIVE,
    /** Motion quality classification */
    val motionQualityClass: MotionQualityClass = MotionQualityClass.UNKNOWN
) {
    companion object {
        /** Default conservative quality for unknown/unsafe frames */
        fun conservative(): MotionQuality = MotionQuality(
            motionMagnitude = 0f,
            confidence = 0f,
            occlusionRatio = 1f,
            textureComplexity = 1f,
            vectorConsistency = 0f,
            isInterpolationSuitable = false,
            recommendedMode = InterpolationMode.SAFE,
            motionQualityClass = MotionQualityClass.UNRELIABLE
        )
    }
}

/** Interpolation mode based on motion quality and scheduling constraints */
enum class InterpolationMode {
    /** No interpolation - pass through original frames */
    SAFE,
    /** Minimal interpolation - only when quality is high */
    CONSERVATIVE,
    /** Standard interpolation - balanced quality/performance */
    BALANCED,
    /** Aggressive interpolation - more frames when possible */
    AGGRESSIVE
}

/**
 * Scheduling decision for a frame pair.
 * Contains the interpolation decision and timing information.
 */
@UnstableApi
data class ScheduleDecision(
    /** The frame pair this decision applies to */
    val framePair: FramePair,
    /** Scene change analysis result */
    val sceneChange: SceneChangeResult,
    /** Motion quality analysis result */
    val motionQuality: MotionQuality,
    /** Selected interpolation mode */
    val mode: InterpolationMode,
    /** Number of intermediate frames to generate (0 = no interpolation) */
    val numIntermediates: Int,
    /** Target interpolation time fractions [0.0, 1.0] for each intermediate frame */
    val interpolationTimes: List<Float>,
    /** Target processing resolution (may differ from display resolution) */
    val targetWidth: Int,
    val targetHeight: Int,
    /** Estimated synthesis time in microseconds */
    val estimatedSynthesisTimeUs: Long,
    /** Whether to skip this frame pair due to discontinuity */
    val shouldSkip: Boolean = false
)

/**
 * State of the interpolation pipeline.
 */
enum class PipelineState {
    IDLE,
    BUFFERING,
    READY,
    PROCESSING,
    DRAINING,
    STOPPED,
    ERROR
}

/**
 * Callback interface for synthesis backend operations.
 */
@UnstableApi
interface SynthesisBackend {
    /** Initialize the backend with given configuration */
    fun initialize(config: SynthesisConfig): Boolean
    
    /** Synthesize intermediate frame(s) for the given frame pair */
    fun synthesize(
        framePair: FramePair,
        interpolationTimes: List<Float>,
        targetWidth: Int,
        targetHeight: Int,
        outputCallback: (intermediateFrames: List<IntermediateFrame>) -> Unit
    ): SynthesisResult
    
    /** Release all resources */
    fun release()
    
    /** Get current backend status */
    fun getStatus(): BackendStatus
}

/** Configuration for synthesis backend */
@UnstableApi
data class SynthesisConfig(
    val backendType: BackendType,
    val maxResolutionWidth: Int,
    val maxResolutionHeight: Int,
    val enableVulkan: Boolean,
    val numThreads: Int,
    val modelPath: String
)

enum class BackendType {
    RIFE_VULKAN,
    RIFE_CPU,
    PLACEHOLDER // For future backends (e.g., shader warping)
}

/** Result of a synthesis operation */
@UnstableApi
data class SynthesisResult(
    val success: Boolean,
    val intermediateFrames: List<IntermediateFrame> = emptyList(),
    val actualSynthesisTimeUs: Long = 0,
    val errorMessage: String? = null
)

/** An intermediate frame generated by synthesis */
@UnstableApi
data class IntermediateFrame(
    val timestampUs: Long,
    val interpolationTime: Float, // t in [0, 1]
    val width: Int,
    val height: Int,
    val format: FrameFormat,
    val data: ByteArray // Raw pixel data
)

/** Backend status information */
@UnstableApi
data class BackendStatus(
    val isInitialized: Boolean,
    val backendType: BackendType,
    val currentResolution: String,
    val lastSynthesisTimeUs: Long,
    val errorMessage: String? = null
)

/**
 * Bounded output queue for synthesized frames.
 * Provides backpressure and frame dropping policies.
 */
@UnstableApi
interface OutputQueue {
    /** Try to enqueue a frame, returns false if queue is full */
    fun tryEnqueue(frame: OutputFrame): Boolean
    
    /** Dequeue the next frame for display, blocks if empty */
    fun dequeue(): OutputFrame?
    
    /** Try to dequeue without blocking */
    fun tryDequeue(): OutputFrame?
    
    /** Current queue size */
    fun size(): Int
    
    /** Maximum queue capacity */
    fun capacity(): Int
    
    /** Clear all frames */
    fun clear()
    
    /** Signal end of stream */
    fun complete()
    
    /** Check if queue is completed */
    fun isCompleted(): Boolean
}

/** Frame ready for display output */
@UnstableApi
data class OutputFrame(
    val frameMetadata: FrameMetadata,
    val isIntermediate: Boolean,
    val interpolationTime: Float = 0f, // 0 for original, (0,1) for intermediate
    val timestampUs: Long,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val format: FrameFormat
)

/**
 * Configuration for the temporal frame store.
 */
@UnstableApi
data class TemporalFrameStoreConfig(
    /** Maximum number of frames to keep in history */
    val maxHistorySize: Int = 3,
    /** Maximum time to keep frames before forced eviction (microseconds) */
    val maxFrameAgeUs: Long = 500_000, // 500ms
    /** Whether to keep keyframes longer */
    val retainKeyFrames: Boolean = true
)

/**
 * Temporal frame store for managing frame history.
 * Maintains a bounded buffer of recent frames for interpolation.
 */
@UnstableApi
interface TemporalFrameStore {
    /** Add a new frame to the store */
    fun addFrame(metadata: FrameMetadata): Boolean
    
    /** Get the most recent frame pair for interpolation */
    fun getLatestFramePair(): FramePair?
    
    /** Get a specific frame by ID */
    fun getFrame(frameId: Long): FrameMetadata?
    
    /** Remove frames older than the given timestamp */
    fun evictOlderThan(timestampUs: Long): Int
    
    /** Clear all frames (e.g., on seek/discontinuity) */
    fun clear()
    
    /** Current number of frames in store */
    fun size(): Int
    
    /** Check if store has enough frames for interpolation */
    fun hasValidFramePair(): Boolean
    
    /** Get configuration */
    fun getConfig(): TemporalFrameStoreConfig
}

/**
 * Interface for scene change detection.
 */
@UnstableApi
interface SceneChangeDetector {
    /** Analyze a frame pair for scene changes */
    fun detect(previous: FrameMetadata, current: FrameMetadata): SceneChangeResult
    
    /** Reset detector state (e.g., on seek) */
    fun reset()
}

/**
 * Interface for motion quality analysis.
 */
@UnstableApi
interface MotionQualityAnalyzer {
    /** Analyze motion quality for a frame pair */
    fun analyze(previous: FrameMetadata, current: FrameMetadata): MotionQuality
    
    /** Reset analyzer state */
    fun reset()
}

/**
 * Configuration for the interpolation scheduler.
 */
@UnstableApi
data class InterpolationSchedulerConfig(
    /** Default interpolation mode */
    val defaultMode: InterpolationMode = InterpolationMode.CONSERVATIVE,
    /** Maximum intermediates per frame pair */
    val maxIntermediates: Int = 1,
    /** Minimum frame interval to consider for interpolation (microseconds) */
    val minFrameIntervalUs: Long = 8_000, // ~120fps max
    /** Maximum synthesis time budget per frame (microseconds) */
    val maxSynthesisTimeUs: Long = 16_000, // ~60fps
    /** Enable adaptive mode selection based on motion quality */
    val adaptiveMode: Boolean = true,
    /** Minimum motion confidence for interpolation */
    val minMotionConfidence: Float = 0.3f,
    /** Maximum occlusion ratio for interpolation */
    val maxOcclusionRatio: Float = 0.5f
)

/**
 * Interface for the interpolation scheduler.
 * Decides when and how to interpolate based on frame timing and quality.
 */
@UnstableApi
interface InterpolationScheduler {
    /** Schedule interpolation for a frame pair */
    fun schedule(framePair: FramePair): ScheduleDecision
    
    /** Called when a frame is successfully synthesized */
    fun onSynthesisComplete(decision: ScheduleDecision, result: SynthesisResult)
    
    /** Called when synthesis fails */
    fun onSynthesisFailed(decision: ScheduleDecision, error: String)
    
    /** Update scheduler configuration */
    fun updateConfig(config: InterpolationSchedulerConfig)
    
    /** Get current scheduler state */
    fun getState(): SchedulerState
    
    /** Reset scheduler (e.g., on seek) */
    fun reset()
}

/** Scheduler state information */
@UnstableApi
data class SchedulerState(
    val currentMode: InterpolationMode,
    val pendingDecisions: Int,
    val completedFrames: Long,
    val droppedFrames: Long,
    val averageSynthesisTimeUs: Long,
    val currentResolution: String
)

/**
 * Main pipeline coordinator state.
 */
@UnstableApi
data class PipelineStatus(
    val state: PipelineState,
    val schedulerState: SchedulerState,
    val backendStatus: BackendStatus,
    val queueSize: Int,
    val queueCapacity: Int,
    val frameStoreSize: Int,
    val framesProcessed: Long,
    val framesDropped: Long,
    val currentResolution: String
)

/**
 * Pipeline event callbacks for monitoring.
 */
@UnstableApi
interface PipelineListener {
    fun onStateChanged(newState: PipelineState)
    fun onFrameProcessed(frame: OutputFrame)
    fun onFrameDropped(reason: String)
    fun onError(error: String)
    fun onStatisticsUpdated(stats: PipelineStats)
}

/** Pipeline statistics for monitoring */
@UnstableApi
data class PipelineStats(
    val inputFps: Float,
    val outputFps: Float,
    val averageSynthesisTimeUs: Long,
    val queueDepth: Int,
    val memoryUsageMb: Long
)