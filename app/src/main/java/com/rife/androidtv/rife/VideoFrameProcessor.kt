package com.rife.androidtv.rife

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.FrameInfo
import androidx.media3.common.OnInputFrameProcessedListener
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.UnstableApi
import com.rife.androidtv.NativeEngine
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.media3.common.VideoFrameProcessor as Media3VideoFrameProcessor

/**
 * A single *real* decoded video frame.
 *
 * [pixels] is a direct, tightly packed RGBA (ARGB_8888) buffer read back from the Media3 input
 * surface through [OesFrameGrabber]. It never contains placeholder data.
 *
 * [timestampUs] comes from [SurfaceTexture.getTimestamp] of the frame the decoder had just queued.
 * For a surface-rendered producer that value is the producer's frame timestamp, **not** a verified
 * Media3 media presentation timestamp: it is used for ordering and logging only. Nothing in the
 * pipeline maps it onto the media timeline, so audio/video sync is left to the player, and no
 * synthetic `System.nanoTime()` mapping is introduced to disguise that.
 */
data class FrameData(
    val pixels: ByteBuffer,
    val timestampUs: Long,
    val width: Int,
    val height: Int
)

data class Statistics(
    val inputFps: Float,
    val outputFps: Float,
    /**
     * The instantaneous output frame rate over the last pair, in frames per second. This is the
     * number the encoder must be opened at: the windowed [outputFps] is smoothed over a second and
     * would open a 72 fps stream as 60.
     */
    val outputFrameRate: Float,
    val processingTimeMs: Long,
    val droppedFrames: Long,
    val currentResolution: String
)

/**
 * Frame processor for interpolation and the (scaffold) FastDVDnet pre-processing stage, built
 * on the real Media3 [androidx.media3.common.VideoFrameProcessor] surface-input contract.
 *
 * ```
 * MediaCodec (ExoPlayer) -> getInputSurface() -> SurfaceTexture (OES texture)
 *                                         -> OesFrameGrabber (FBO + glReadPixels, real pixels)
 *                                         -> frameQueue (bounded, backpressure)
 *                                         -> FastDVDnet scaffold (optional pre-processing)
 *                                         -> NativeEngine.interpolateFrameBuffers(in0, in1, ...)
 *                                         -> previous / interpolated / next -> output Surface
 * ```
 *
 * This implements the Media3 1.3.1 `VideoFrameProcessor` interface: `INPUT_TYPE_SURFACE`,
 * `getInputSurface()`, `registerInputStream()`, `registerInputFrame()`, `setOutputSurfaceInfo()`,
 * `flush()` and `release()`. Only surface input is supported.
 *
 * Surface ownership (one owner, no competing outputs):
 *  * the processor owns the input [Surface]; the player renders decoded frames into it while either
 *    processing stage is enabled. It is handed over through [onInputSurfaceCreated] and confirmed
 *    through [onInputSurfaceAttached];
 *  * the display surface is published through `setOutputSurfaceInfo()`, and
 *    released again when processing is switched off, which is what lets the owner restore normal
 *    `PlayerView` playback;
 *  * neither surface lifetime depends on `Player.setVideoSurface(null)` or on re-setting the player
 *    on a `PlayerView`.
 *
 * All frame, buffer and GL state lives on a single worker thread, so a buffer or bitmap is never
 * recycled while another stage could still be reading it.
 */
@UnstableApi
class VideoFrameProcessor(
    private val onStatisticsUpdated: (Statistics) -> Unit,
    private val onError: (String) -> Unit,
    private val onInputSurfaceCreated: (Surface) -> Unit = {},
    private val onInputSurfaceFailed: () -> Unit = {}
) : Media3VideoFrameProcessor {

    companion object {
        private const val TAG = "VideoFrameProcessor"

        /**
         * Bounded queue: the pipeline must never grow faster than it can interpolate.
         * Depth = previousFrame(1) + pair in flight(2) + pendingRenderQueue(2) + capturing(1) = 6.
         * 4 is enough for GPU/CPU overlap without accumulating latency.
         */
        private const val FRAME_QUEUE_CAPACITY = 4

        /** Pooled capture buffers. Bounded so a 4K stream cannot inflate the heap. */
        private const val MAX_POOLED_FRAME_BUFFERS = 2

        /**
         * Worst-case number of full-resolution RGBA frames the pipeline can own at the same
         * instant: 4 in the frame queue + 1 previous frame + 2 pooled + 3 working buffers
         * (input0, input1, output). The denoiser's 2 scratch buffers are added only while the
         * denoiser is on, because they are allocated only while it is on.
         */
        private const val LIVE_CAPTURE_FRAMES = 10L
        private const val LIVE_CAPTURE_FRAMES_DENOISED = 12L

        /**
         * Upper bound on that frame set before the pipeline gives up and passes frames through
         * instead of interpolating. It is sized so a 4K frame set (~330 MB) is attempted, and so
         * anything clearly beyond it (6K and up) is not. It is a refusal, never a downscale: the
         * capture size is never changed because of it.
         */
        private const val FRAME_MEMORY_BUDGET_BYTES = 512L * 1024L * 1024L

        /**
         * Cool-down before a stage that refused a size because a direct allocation threw is tried
         * again. An OutOfMemoryError here is usually the previous size's buffers still waiting on
         * their Cleaner rather than a size that can never fit, so waiting recovers; latching the
         * refusal for the rest of the stream does not. The over-budget refusal above is a property
         * of the size itself and is therefore still latched.
         */
        private const val ALLOCATION_RETRY_COOLDOWN_NS = 1_000_000_000L

        /** Bounds the log line that reports a refusal and the retry that follows it. */
        private const val ALLOCATION_LOG_INTERVAL_NS = 1_000_000_000L

        private const val WORKER_TASK_TIMEOUT_MS = 3000L

        /**
         * Per-stage timing is averaged over a window and reported as a single line. Logging every
         * stage of every frame would put thousands of Log.d calls into the profile being measured.
         */
        private const val TIMING_WINDOW_FRAMES = 30

        /**
         * Cadence of the stage heartbeat. The timing window only closes when a pair completes, so
         * a stalled pipeline would go silent exactly when there is something to report; this line
         * keeps emitting once per second while frames are flowing and logs the stall transition
         * when they stop.
         */
        private const val STAGE_HEARTBEAT_MS = 1000L

        /**
         * Bounds the frame-pair interval the interpolation level divides. The same [1ms, 1s] window the
         * AUTO policy already accepts, so a seek or a bogus timestamp cannot produce a step of
         * zero or a backlog of frames.
         */
        private const val MIN_PAIR_INTERVAL_US = 1_000L
        private const val MAX_PAIR_INTERVAL_US = 1_000_000L

        /** The supported interpolation ratios, matching the clamp in setMemcLevel(). */
        private const val MIN_INTERPOLATION_RATIO = 2.0
        private const val MAX_INTERPOLATION_RATIO = 4.0

        /** Hard stop on one pair's emission list, so a bad timestamp cannot loop forever. */
        private const val MAX_OUTPUTS_PER_PAIR = 8

        /**
         * How close the last point of a pair has to be to the end of that pair before it counts as
         * the pair's own frame. An eighth of the pair is far tighter than the rounding of the step
         * can ever be for a whole-number ratio, and far looser than a genuinely interpolated point
         * (which for a ratio above two sits a whole step back).
         */
        private const val OWN_FRAME_SNAP = 8f

        /**
         * Verbose per-frame diagnostics: checksums, EGL state dumps and dimension traces. They were
         * useful while bringing the pipeline up but cost several whole-buffer passes per frame and
         * drown the timing signal, so they are off by default.
         */
        private const val VERBOSE_DIAGNOSTICS = false

        /**
         * Only used to describe the input stream to Media3 before the real video size is known. It
         * is never used to size a readback or an inference buffer.
         */
        private const val FALLBACK_FRAME_WIDTH = 1920
        private const val FALLBACK_FRAME_HEIGHT = 1080

        /**
         * Maximum time a frame can wait in queue before being considered stale (ms).
         * At 60fps with 4x interpolation, budget per pair = 16.6ms / 4 = 4.15ms per output frame.
         * Keep 2 frames of latency headroom = ~8ms. Frames older than this are stale.
         */
        private const val MAX_QUEUE_LATENCY_MS = 16L

        /** Dataspace the source probe could not read: API below 33, or the getter threw. */
        private const val DATA_SPACE_NOT_QUERIED = -2
        private const val DATA_SPACE_UNKNOWN = -1

        /** How often a stable HDR source re-reports its evidence; SDR reports once. */
        private const val HDR_PROBE_REPEAT_NS = 10_000_000_000L
    }

    /**
     * Whether interpolation is active. Input-surface frames are only read back while interpolation or
     * the FastDVDnet stage is enabled.
     */
    @Volatile
    var isRifeEnabled = false
        private set

    /**
     * FastDVDnet pre-processing stage. This is a scaffold (history bookkeeping + pass-through),
     * not a neural denoiser; see [FastDvdNetEngine].
     */
    val fastDvdNetEngine = FastDvdNetEngine()

    @Volatile
    var resolution = RifeResolution.ORIGINAL

    /**
     * True when the player must render into this processor's input surface, i.e. state 2, 3 or 4.
     * When false the owner restores normal `PlayerView` playback (state 1).
     */
    val isProcessingEnabled: Boolean
        get() = isRifeEnabled || fastDvdNetEngine.isEnabled

    /** Media3 reporting listener; output frames are rendered automatically by this processor. */
    private val media3Listener = object : Media3VideoFrameProcessor.Listener {
        override fun onInputStreamRegistered(
            inputType: Int,
            format: Format,
            effects: MutableList<Effect>
        ) {
            Log.i(
                TAG,
                "Media3 input stream registered: type=$inputType ${format.width}x${format.height}"
            )
        }

        override fun onOutputSizeChanged(width: Int, height: Int) {
            Log.i(TAG, "Media3 output size changed: ${width}x$height")
        }

        override fun onOutputFrameRateChanged(frameRate: Float) {
            Log.i(TAG, "Media3 output frame rate changed: $frameRate")
        }

        override fun onOutputFrameAvailableForRendering(presentationTimeUs: Long, isRedrawnFrame: Boolean) {
            // Nothing to do: this processor renders each output frame as soon as it is produced.
        }

        override fun onError(exception: VideoFrameProcessingException) {
            val message = exception.message ?: "unknown Media3 video frame processing error"
            Log.e(TAG, "Media3 VideoFrameProcessor error: $message", exception)
            reportError("Frame processing failed: $message")
        }

        override fun onEnded() {
            Log.i(TAG, "Media3 input stream ended")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Worker thread + EGL / input surface ownership
    // ---------------------------------------------------------------------------------------

    private var workerThread: HandlerThread? = null

    /**
     * Read from the motion thread to decide where it may hand a finished result back, so it has to
     * be visible across threads.
     */
    @Volatile
    private var workerHandler: Handler? = null

    /** All of the following are only touched on the worker thread. */
    private var inputBundle: InputSurfaceBundle? = null

    /**
     * The bundle that [inputBundle] replaced. It is kept alive until the owner confirms it has
     * attached the new surface, so the player is never left rendering into a destroyed Surface.
     */
    private var pendingSupersededBundle: InputSurfaceBundle? = null

    private var frameGrabber: OesFrameGrabber? = null
    private var inputSurfaceEverAttached = false

    @Volatile
    private var createdInputSurface: Surface? = null

    /**
     * `false` while the input surface is being (re)created, so the owner can never attach a surface
     * that is about to be retired: [rifeInputSurface] reports `null` in that window and the owner
     * waits for [onInputSurfaceCreated] instead.
     */
    @Volatile
    private var inputSurfaceReady = false

    /**
     * The input surface the player must render decoded frames into, or `null` when none exists yet or
     * one is being replaced. The processor owns its lifecycle.
     */
    val rifeInputSurface: Surface?
        get() = if (inputSurfaceReady) createdInputSurface else null

    // ---------------------------------------------------------------------------------------
    // Interpolation pipeline state. Only ever mutated on the worker thread.
    // ---------------------------------------------------------------------------------------

    private val frameQueue = ArrayBlockingQueue<FrameData>(FRAME_QUEUE_CAPACITY)
    private val frameBufferPool = java.util.ArrayDeque<ByteBuffer>(MAX_POOLED_FRAME_BUFFERS)
    /** High-precision source textures live until their analysis buffer leaves the frame pipeline. */
    private val hdrTextureByAnalysisBuffer = IdentityHashMap<ByteBuffer, Int>()

    /**
     * Buffers the motion thread is reading right now, with a count per buffer. Worker-owned.
     *
     * Motion runs on its own executor, so `resetPipelineOnWorker()` can drain `previousFrame`
     * and `frameQueue` while a task is still walking those pixels. Returning such a buffer to
     * the pool lets `obtainFrameBuffer()` hand the very same memory to the capture path, which
     * then overwrites it with a newer frame underneath the reader - a torn motion field, which
     * shows up as warping rather than as anything the logs would call an error.
     */
    private val motionLeasedBuffers = IdentityHashMap<ByteBuffer, Int>()

    /**
     * Buffers whose release was deferred because a motion task still holds a lease on them.
     * Identity-keyed on purpose: `ByteBuffer.hashCode()`/`equals()` walk the whole pixel
     * payload, which for a 4K frame is a 33 MB hash on every deferral, and two frames holding
     * identical pixels would be mistaken for each other.
     */
    private val motionDeferredReleases: MutableSet<ByteBuffer> =
        java.util.Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())

    /** SVPlayer's CPU SAD search runs away from the decoder/GL handler. */
    private val motionExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SvpMotionWorker").apply { isDaemon = true }
    }

    /** CPU denoise (FastDVDnet) runs on a separate executor to keep the worker free for capture/render. */
    private val denoiseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DenoiseWorker").apply { isDaemon = true }
    }

    private var pairProcessing = false

    /** Snapshotted on the worker but compared from the motion thread, so it must be visible. */
    @Volatile
    private var streamGeneration = 0L

    private data class PreparedMotion(
        val field: ByteBuffer?,
        val ready: Boolean,
        val elapsedNs: Long
    )

    /** In-flight work item: motion computed, waiting for render on worker thread. */
    private data class InFlightWork(
        val prev: FrameData,
        val next: FrameData,
        val prepared: PreparedMotion,
        val generation: Long,
        val timestamps: LongArray,
    )

    /** Queue of work items whose motion is done, waiting for worker to render. */
    private val pendingRenderQueue = ArrayDeque<InFlightWork>()

    private var previousFrame: FrameData? = null

    private var frameCountInput = 0
    private var frameCountOutput = 0
    /** Decoder deliveries onto the input SurfaceTexture, counted before any early return. */
    private var frameCountArrival = 0
    private var droppedFrameCount = 0L
    private var droppedOutputFrameCount = 0L
    private var submittedOutputFrameCount = 0L
    private var surfaceRecoveryRequested = false
    private var lastCaptureContentLogNs = 0L
    private var lastRenderContentLogNs = 0L
    private var diagnosticOutputStartCount = 0L
    private var diagnosticWindowStartNs = 0L
    private var droppedOutputAtWindowStart = 0L
    private var lastStatsResetTime = SystemClock.elapsedRealtime()
    private var lastProcTimeMs = 0L
    private var statsCapturedAtStart = 0
    private var statsOutputAtStart = 0

    // Stage heartbeat: independent baselines so one consumer's reset cannot skew another's rate.
    private var stageHeartbeatNs = 0L
    private var stageArrivalAtBeat = 0
    private var stageCapturedAtBeat = 0
    private var stageOutputAtBeat = 0
    private var stageSubmittedAtBeat = 0L
    private var stageDroppedInAtBeat = 0L
    private var stageDroppedOutAtBeat = 0L
    private var stagePrevArrival = 0L

    private var cachedIn0Buf: ByteBuffer? = null
    private var cachedIn1Buf: ByteBuffer? = null
    private var cachedDenoised0Buf: ByteBuffer? = null
    private var cachedDenoised1Buf: ByteBuffer? = null
    private var cachedOutBuf: ByteBuffer? = null
    private var cachedTargetSize = 0
    private var rejectedTargetSize = 0

    /**
     * `System.nanoTime()` deadline after which [rejectedTargetSize] was set by an
     * `OutOfMemoryError` may be retried, or 0 when the refusal is a property of the size and is
     * therefore meant to last. Read and written on the worker only.
     */
    private var rejectRetryAfterNs = 0L

    /**
     * Frame size whose *interpolation* scratch set was refused (over the budget above, or an
     * allocation failure). At this size the pipeline keeps capturing and keeps showing video but
     * skips the interpolation call. Deliberately a different flag from [rejectedTargetSize]:
     * that one means the capture buffer itself could not be allocated, which leaves nothing to
     * read back and is what produces a black screen.
     */
    private var bypassTargetSize = 0
    private var bypassReportedForSize = 0

    /**
     * The mirror of [rejectRetryAfterNs] for [bypassTargetSize]: a non-zero deadline means the
     * refusal came from an allocation failure and is cleared once it passes.
     */
    private var bypassRetryAfterNs = 0L

    /** Rate-limiter for the single line that reports a refusal and the retry that follows it. */
    private var lastAllocationLogNs = 0L

    /**
     * Packed motion field for the GPU warp: four bytes per 16x16 block, so a few kilobytes even
     * at 1080p. Reallocated alongside the frame buffers because it tracks the processing size.
     */
    private var cachedMotionBuf: ByteBuffer? = null

    /** Dimensions of the last per-frame-dimension breadcrumb, so it is written on change only. */
    private var lastDimsLogW = 0
    private var lastDimsLogH = 0

    /**
     * Set once the motion-aligned denoiser fails for a stream, so the reason is logged a single
     * time instead of every cycle. The failure is usually permanent for that stream - the search
     * cannot supply a field at all - and a per-frame warning at 24 fps is worse than no warning.
     */
    private var denoiseUnavailableLogged = false

    /** Microseconds between the last pair's two source frames; 0 until a pair has been seen. */
    private var lastPairIntervalUs = 0L

    @Volatile private var sourceFrameRateHint = 0f
    fun setSourceFrameRate(frameRate: Float) {
        sourceFrameRateHint = frameRate.takeIf { it.isFinite() && it > 0f } ?: 0f
    }
    fun currentOutputFrameRate(): Float = if (sourceFrameRateHint > 0f) {
        sourceFrameRateHint * memcLevelMultiplier
    } else if (lastPairIntervalUs > 0L) {
        (1_000_000.0 / lastPairIntervalUs.toDouble() * memcLevelMultiplier).toFloat()
    } else 60f

    fun requestedRemoteOutputFrameRate(): Float = currentOutputFrameRate().coerceAtMost(60f)
    fun requestedInterpolationMultiplier(): Float = memcLevelMultiplier
    fun currentProcessingSize(): Pair<Int, Int> {
        if (processingWidth > 0 && processingHeight > 0) return processingWidth to processingHeight
        val sourceW = inputWidth
        val sourceH = inputHeight
        if (sourceW <= 0 || sourceH <= 0) return 0 to 0
        val size = calculateTargetDimensions(sourceW, sourceH, resolution)
        val surfaceW = outputRenderer?.outputSurfaceWidth ?: 0
        val surfaceH = outputRenderer?.outputSurfaceHeight ?: 0
        return if (surfaceW > 0 && surfaceH > 0) {
            fitWithin(size.first, size.second, surfaceW, surfaceH)
        } else {
            size
        }
    }
    fun invalidateProcessingSize() { processingWidth = 0; processingHeight = 0 }
    fun currentSourceFrameRate(): Float = sourceFrameRateHint.takeIf { it > 0f }
        ?: if (lastPairIntervalUs > 0L) 1_000_000f / lastPairIntervalUs else 0f
    fun remoteDroppedOutputFrames(): Long = outputRenderer?.droppedRemoteFrames ?: 0L
    fun setRemoteOutputFrameRate(frameRate: Float) {
        runOnWorker("setRemoteOutputFrameRate") { outputRenderer?.setRemoteOutputFrameRate(frameRate) }
    }

    /**
     * Interpolation ratio: how many output frames are synthesised per source frame. Two is one
     * interpolated frame between each pair, which is what the pipeline did before the level was
     * configurable, so two is the default and the pre-existing cadence is unchanged.
     *
     * Written from the controller thread and read on the worker, hence @Volatile: a level change
     * only has to take effect on the next cycle, so a lock is not worth the contention.
     */
    @Volatile
    private var memcLevelMultiplier = 2f

    /**
     * Strength of the motion-aligned denoiser's history blend, on the settings' own scale (one is
     * the balanced default). Handed to the renderer as a shader uniform, so it is only ever a
     * field on the renderer - this copy exists so a renderer created after the setting changed
     * still comes up with the value the user picked.
     */
    private var denoiseStrength = 1f

    /**
     * Absolute source timestamp of the next output frame to present, in microseconds. The phase
     * is carried from one pair to the next so a ratio that does not divide the source cadence
     * evenly still averages out instead of wobbling, and it is re-anchored whenever a pair turns
     * up that it does not fall inside: a seek, a stream change or a dropped pair has to restart
     * it rather than let a backlog of frames fire in one burst.
     */
    private var nextOutputUs = Long.MIN_VALUE

    /** Dimensions and mode of the last RES POLICY breadcrumb, so it is written on change only. */
    private var lastCaptureLogSrcW = -1
    private var lastCaptureLogSrcH = -1
    private var lastCaptureLogW = 0
    private var lastCaptureLogH = 0
    @Volatile private var processingWidth = 0
    @Volatile private var processingHeight = 0
    private var lastCaptureLogRes: RifeResolution? = null

    /**
     * GL blitter for the output surface. Replaces the previous `lockCanvas()` + `drawBitmap()`
     * path, which rasterised three full-screen bitmaps per interpolated pair on the CPU and was
     * one of the dominant costs on the TV box.
     */
    private var outputRenderer: GlOutputRenderer? = null

    private var pendingOutputSurfaceInfo: SurfaceInfo? = null

    /**
     * Non-null while Phase C encoding owns the output window: processed frames are rendered into
     * the encoder's input Surface instead of the player's, and the display surface kept in
     * [pendingOutputSurfaceInfo] stays untouched so stopping an encode restores preview exactly
     * as it was. Null during normal playback, which is the whole time.
     */
    private var encodeSurface: Surface? = null

    /**
     * Dataspace the output buffers are tagged with. `0` (UNKNOWN) leaves the platform default,
     * which is what SDR content wants; HDR sources get BT.2020 PQ/HLG so the panel applies the
     * matching transfer curve. Read on the worker thread only.
     */
    private var outputDataSpace: Int = 0
    @Volatile private var currentOutputDataSpace: Int = 0
    @Volatile private var sourceColorInfo: ColorInfo? = null
    private var hdrCaptureUnavailableLogged = false

    /**
     * Dataspace MediaCodec stamped on its own output window, read back through
     * [NativeEngine.getOutputDataSpace]. While a stage is running the decoder writes into our
     * input surface instead of the display, so this is the only place the value the bypass path
     * would have shown can be observed - and mirroring it is what keeps the processed picture
     * identical to the bypass one whatever range the codec chose. `0` means it has not been
     * read yet or the decoder left it unspecified, in which case [outputDataSpace] is used.
     * Read on the worker thread only.
     */
    private var codecDataSpace: Int = 0
    private var codecDataSpaceProbed = false
    private var codecDataSpaceProbeLogged = false

    /**
     * Source-side HDR evidence log state. Reset whenever the decoder's identity or the colour
     * metadata changes, so a new stream always reports fresh evidence instead of inheriting the
     * previous one's.
     */
    private var hdrProbeLogged = false
    private var hdrProbeLastKey = ""
    private var hdrProbeLastNs = 0L

    /** Observational only; measures the retained FP16 source without touching the render path. */
    private var hdrRepresentationProbe: HdrRepresentationProbe? = null
    private var hdrOutputInvariantReported = false

    /** Set once the GPU warp has refused a pair, so the fallback is reported without spamming. */
    private var warnedGpuWarpFallback = false

    @Volatile
    private var inputWidth = 0

    @Volatile
    private var inputHeight = 0

    @Volatile
    private var inputStreamRegistered = false

    private var endOfInputSignalled = false
    private var readbackInProgress = false
    private var released = false

    // Per-stage timing window (see TIMING_WINDOW_FRAMES). Interpolation is only a few ms per frame yet
    // playback lands far below real time on both the TV box and the Poco F7, so the remaining
    // cost has to be located in the GL readback / upload path rather than assumed.
    private var timingStartNs = 0L
    private var timingCycles = 0
    private var nsReadback = 0L
    private var nsCopy = 0L
    private var nsChecksum = 0L
    private var nsJni = 0L
    // nsJni alone could not say which half of the native work was expensive, so the two native
    // stages are timed separately: the motion search (which may have run on another thread and is
    // folded in through its own elapsed time) and the interpolator/warp called on this thread.
    private var nsMotion = 0L
    private var nsInterp = 0L
    // Time inside the readback-completion step only: fence check, PBO map and copy. Distinct from
    // nsReadback, which also covers submitting the next capture, so a stall here is a GPU/CPU
    // synchronisation cost and a stall there is a capture cost.
    private var nsPoll = 0L
    // Time between this callback returning and the next one starting. Anything not explained by
    // the measured stages lives here: handler dispatch, other messages on the worker, OS
    // scheduling.
    private var nsWorkerGap = 0L
    private var workerGapSamples = 0
    private var maxWorkerGapNs = 0L
    private var lastCallbackExitNs = 0L
    // Decoder cadence measured from callback arrivals: the wall-clock interval between one
    // decoded frame becoming available and the next. It is the only number here that is outside
    // this class's control, so it is what an in-process stage time has to be judged against.
    private var lastArrivalNs = 0L
    private var nsDecodeInterval = 0L
    private var decodeIntervalCount = 0
    private var maxDecodeIntervalNs = 0L
    private var nsRender = 0L
    private var nsPair = 0L
    private var nsQueueLatency = 0L
    private var queueLatencySamples = 0L
    // Per-phase split of nsRender, drained from GlOutputRenderer once per report window.
    private val renderBreakdown = LongArray(6)
    private var nsRenderCurrent = 0L
    private var nsRenderSetup = 0L
    private var nsRenderUpload = 0L
    private var nsRenderDraw = 0L
    private var nsRenderSwap = 0L
    private var capturedAtWindowStart = 0
    private var outputAtWindowStart = 0
    private var arrivalAtWindowStart = 0
    private var droppedAtWindowStart = 0L

    private val mainHandler: Handler? = try {
        Handler(Looper.getMainLooper())
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------------------------------------
    // Output surface: the SurfaceView the processed result is rendered to. The owner (the
    // player screen) publishes the surface through setOutputSurfaceInfo as it is created, resized
    // and destroyed, so the processor never has to observe a View's lifecycle itself.
    // ---------------------------------------------------------------------------------------

    private var displaySurfaceWidth = 0
    private var displaySurfaceHeight = 0

    private class InputSurfaceBundle(
        val display: EGLDisplay?,
        val context: EGLContext?,
        val surface: EGLSurface?,
        var textureId: Int,
        val texture: SurfaceTexture,
        val surfaceHandle: Surface
    ) {
        /** Set once the GL objects belonging to this bundle's EGL context have been deleted. */
        var glObjectsReleased = false
    }

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /** Starts the worker thread and creates the first Media3 input surface. */
    fun start() {
        if (workerThread == null) {
            workerThread = HandlerThread("RifeWorkerThread").apply {
                start()
                workerHandler = Handler(looper)
            }
        }

        // Reset the released flag so a previous stop() does not permanently disable the processor.
        released = false

        runOnWorker("start()") {
            stageHeartbeatNs = System.nanoTime()
            stageArrivalAtBeat = frameCountArrival
            stageCapturedAtBeat = frameCountInput
            stageOutputAtBeat = frameCountOutput
            stageSubmittedAtBeat = submittedOutputFrameCount
            stageDroppedInAtBeat = droppedFrameCount
            stageDroppedOutAtBeat = droppedOutputFrameCount
            stagePrevArrival = 0
            workerHandler?.removeCallbacks(stageHeartbeat)
            workerHandler?.postDelayed(stageHeartbeat, STAGE_HEARTBEAT_MS)
            createInputSurfaceOnWorker("start")
        }
    }

    /**
     * Enables or disables interpolation.
     *
     * OFF -> ON flushes all pipeline state and re-creates the Media3 input surface together with its
     * EGL context and SurfaceTexture, so a stale or stalled input surface can never be reused. The
     * new surface is reported through the `onInputSurfaceCreated` callback so the owner can attach
     * it to the player.
     *
     * ON -> OFF stops reading frames, drops all pending pipeline state and releases the output
     * surface via `setOutputSurfaceInfo(null)`, which lets the owner restore normal PlayerView
     * rendering.
     */
    fun setRifeEnabled(enabled: Boolean) {
        if (isRifeEnabled == enabled) {
            return
        }
        isRifeEnabled = enabled
        if (enabled) {
            enableProcessing("rife_enabled")
        } else {
            disableStage("rife_disabled")
        }
    }

    /**
     * Enables or disables the FastDVDnet pre-processing stage, i.e. the switch between
     * "Interpolation OFF + FastDVDnet ON" (state 2) and "Interpolation OFF + FastDVDnet OFF" (state 1).
     */
    fun setFastDvdNetEnabled(enabled: Boolean) {
        if (fastDvdNetEngine.isEnabled == enabled) {
            return
        }
        fastDvdNetEngine.isEnabled = enabled
        if (enabled) {
            enableProcessing("fastdvdnet_enabled")
        } else {
            disableStage("fastdvdnet_disabled")
        }
    }

    /**
     * Turning a stage on: the player has to render into the input surface, and no frame captured
     * before the toggle may be paired with a frame captured after it.
     *
     * The surface itself is only (re)created when the player does not hold it yet: a surface that is
     * already attached stays in place, so toggling a second stage cannot invalidate the surface the
     * decoder is currently writing into.
     */
    private fun enableProcessing(reason: String) {
        // Block the owner from attaching a surface that is about to be replaced.
        inputSurfaceReady = false
        runOnWorker("enableProcessing($reason)") {
            resetPipelineOnWorker(reason)
            if (inputBundle == null || !inputSurfaceEverAttached) {
                createInputSurfaceOnWorker(reason)
            } else {
                registerInputStreamOnWorker(currentFrameInfo())
                notifyInputSurfaceCreated(createdInputSurface!!)
            }
        }
    }

    /**
     * Turning a stage off: the output surface is handed back so the owner can restore normal
     * playback, and every frame that crossed the toggle boundary is dropped.
     */
    private fun disableStage(reason: String) {
        runOnWorker("disableStage($reason)") {
            pendingOutputSurfaceInfo = null
            resetPipelineOnWorker(reason)
        }
    }

    /**
     * Clears every piece of per-stream pipeline state: the buffered previous frame, the frame queue,
     * the cached capture/inference buffers, the FastDVDnet temporal history, the pending-readback
     * flag and the frame counters.
     *
     * Safe to call from any thread: the reset is serialized on the worker thread, which is the sole
     * owner of every frame buffer. No buffer or bitmap is recycled while another worker could still
     * be reading it, because there is only ever one worker.
     */
    fun resetPipeline(reason: String) {
        val handler = workerHandler
        if (handler == null) {
            Log.w(TAG, "resetPipeline($reason): worker thread is gone, nothing to reset")
            return
        }
        handler.post { resetPipelineOnWorker(reason) }
    }

    /**
     * Like [resetPipeline], but also re-registers the Media3 input stream so that no frame
     * registered against the previous media item can leak into the new one.
     */
    fun resetForNewStream(reason: String) {
        val handler = workerHandler
        if (handler == null) {
            Log.w(TAG, "resetForNewStream($reason): worker thread is gone, nothing to reset")
            return
        }
        handler.post {
            resetPipelineOnWorker(reason)
            registerInputStreamOnWorker(currentFrameInfo())
        }
    }

    /**
     * Records the decoded frame size reported by `Player.Listener.onVideoSizeChanged`, i.e. the size
     * of the frames the player renders into `getInputSurface`. The value drives the readback size,
     * so no fixed 1920x1080 processing size is assumed.
     */
    fun setInputFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (width == inputWidth && height == inputHeight) {
            return
        }
        inputWidth = width
        inputHeight = height
        invalidateProcessingSize()
        Log.i(TAG, "Input frame size updated: ${width}x$height")

        // A resolution change invalidates anything already buffered, and the SurfaceTexture has to
        // be told about the new producer buffer size.
        runOnWorker("setInputFrameSize") {
            inputBundle?.texture?.let { texture ->
                if (!texture.isReleased) {
                    texture.setDefaultBufferSize(width, height)
                }
            }
            resetPipelineOnWorker("input_size_changed")
        }
    }

    /** Current input frame width as reported by the decoder. */
    fun getInputWidth(): Int = inputWidth

    /** Current input frame height as reported by the decoder. */
    fun getInputHeight(): Int = inputHeight

    /**
     * Called by the owner once the surface reported through the `onInputSurfaceCreated` callback
     * has actually been attached to the player. Only then is the replaced EGL context /
     * SurfaceTexture / Surface released, so the player is never left rendering into a Surface that
     * has already been destroyed.
     */
    fun onInputSurfaceAttached() {
        runOnWorker("onInputSurfaceAttached()") {
            inputSurfaceEverAttached = true
            releaseInputSurfaceBundle(pendingSupersededBundle)
            pendingSupersededBundle = null
        }
    }

    /**
     * Called by the owner when it has taken the input surface away from the player, i.e. when normal
     * PlayerView playback is restored. The surface itself is kept alive and can be handed back to the
     * player by the next enable, but the processor must not assume the player is still writing into
     * it.
     */
    fun onInputSurfaceDetached() {
        runOnWorker("onInputSurfaceDetached()") {
            inputSurfaceEverAttached = false
        }
    }

    /** Stops the worker and releases every resource owned by the processor. */
    fun stop() {
        isRifeEnabled = false
        fastDvdNetEngine.isEnabled = false
        released = true

        val handler = workerHandler
        if (handler == null) {
            return
        }
        handler.removeCallbacks(stageHeartbeat)

        val latch = CountDownLatch(1)
        if (handler.post {
                try {
                    releaseStateOnWorker()
                } finally {
                    latch.countDown()
                }
            }) {
            latch.await(WORKER_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        createdInputSurface = null
        inputSurfaceReady = false
    }

    /**
     * One heartbeat per second on the worker thread: decoder arrivals, capture, processing and
     * submission rates over the last second, plus the queue depth and both drop counters. It runs
     * off its own baselines because the timing window only closes when a pair completes - if the
     * pipeline stalls, [reportStageTiming] stops firing exactly when the numbers are needed. The
     * line is printed only while something moved, or once when arrivals stop under an enabled
     * pipeline, so a paused player does not emit a stream of zeros.
     */
    private val stageHeartbeat = object : Runnable {
        override fun run() {
            if (released) return
            val now = System.nanoTime()
            val windowSec = ((now - stageHeartbeatNs).coerceAtLeast(1L)) / 1_000_000_000.0
            val arrival = (frameCountArrival - stageArrivalAtBeat).toLong()
            val captured = (frameCountInput - stageCapturedAtBeat).toLong()
            val processed = (frameCountOutput - stageOutputAtBeat).toLong()
            val submitted = submittedOutputFrameCount - stageSubmittedAtBeat
            val droppedIn = droppedFrameCount - stageDroppedInAtBeat
            val droppedOut = droppedOutputFrameCount - stageDroppedOutAtBeat
            val queue = frameQueue.size
            val moved = arrival > 0L || captured > 0L || processed > 0L || submitted > 0L ||
                droppedIn != 0L || droppedOut != 0L
            val stalled = isProcessingEnabled && stagePrevArrival > 0 && arrival == 0L
            if (moved || stalled) {
                val f = { v: Double -> String.format(java.util.Locale.US, "%.1f", v) }
                Log.i(
                    TAG,
                    "[STAGE] decoderArrivalFps=${f(arrival / windowSec)} " +
                        "capturedFps=${f(captured / windowSec)} " +
                        "processedFps=${f(processed / windowSec)} " +
                        "generatedOutFps=${f(submitted / windowSec)} " +
                        "droppedIn=$droppedIn droppedOut=$droppedOut queue=$queue" +
                        if (stalled) " | STALLED: decoder delivered no frames this second" else ""
                )
            }

            // Pipeline health check: if frames are arriving but none are being submitted to output,
            // the pipeline may be stuck. Trigger a recovery.
            if (isProcessingEnabled && arrival > 0L && submitted == 0L && processed == 0L && captured == 0L) {
                Log.w(TAG, "[HEALTH CHECK] Pipeline stalled: frames arriving but none processed/rendered. Triggering recovery.")
                resetPipelineOnWorker("health_check_stalled_pipeline")
            }

            stageHeartbeatNs = now
            stageArrivalAtBeat = frameCountArrival
            stageCapturedAtBeat = frameCountInput
            stageOutputAtBeat = frameCountOutput
            stageSubmittedAtBeat = submittedOutputFrameCount
            stageDroppedInAtBeat = droppedFrameCount
            stageDroppedOutAtBeat = droppedOutputFrameCount
            stagePrevArrival = arrival
            workerHandler?.postDelayed(this, STAGE_HEARTBEAT_MS)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Media3 VideoFrameProcessor implementation
    // ---------------------------------------------------------------------------------------

    override fun getInputSurface(): Surface {
        return checkNotNull(createdInputSurface) {
            "VideoFrameProcessor input surface is not available yet; call start() first"
        }
    }

    override fun registerInputStream(
        inputType: Int,
        format: Format,
        effects: List<Effect>,
        offsetToAddUs: Long
    ) {
        check(inputType == Media3VideoFrameProcessor.INPUT_TYPE_SURFACE) {
            "Interpolation requires an input Surface"
        }
        if (inputWidth <= 0 || inputHeight <= 0) {
            if (format.width > 0 && format.height > 0) {
                inputWidth = format.width
                inputHeight = format.height
            }
        }
        val handler = workerHandler ?: run {
            Log.w(TAG, "registerInputStream(): worker thread is gone, skipping")
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                registerInputStreamOnWorker(
                    FrameInfo(
                        Format.Builder()
                            .setColorInfo(format.colorInfo ?: sourceColorInfo ?: ColorInfo.SDR_BT709_LIMITED)
                            .setWidth(inputWidth)
                            .setHeight(inputHeight)
                            .build(),
                        0L
                    )
                )
            } finally {
                latch.countDown()
            }
        }
        // Never wait on the worker thread itself: that would deadlock.
        if (Thread.currentThread() !== workerThread) {
            latch.await(WORKER_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Registers one input frame.
     *
     * Media3 allows producers that do not control rendering (an ExoPlayer video renderer, for
     * instance) to register the input stream once and then render frames freely. That is the same
     * mode as `DefaultVideoFrameProcessor.Builder#setRequireRegisteringAllInputFrames(false)`.
     * This returns whether the processor is currently able to accept input surface frames.
     */
    override fun registerInputFrame(): Boolean {
        return !released && inputStreamRegistered && !endOfInputSignalled
    }

    override fun getPendingInputFrameCount(): Int = frameQueue.size

    override fun setOutputSurfaceInfo(outputSurfaceInfo: SurfaceInfo?) {
        runOnWorker("setOutputSurfaceInfo()") {
            pendingOutputSurfaceInfo = outputSurfaceInfo
            displaySurfaceWidth = outputSurfaceInfo?.width ?: 0
            displaySurfaceHeight = outputSurfaceInfo?.height ?: 0
            val display = bundleDisplay()
            if (display == null) {
                // The output window surface can only be created on the input bundle's EGL
                // display, and the renderer is (re)created together with that bundle, so there
                // is nothing to update while no bundle exists.
                Log.i(TAG, "Output surface update skipped: no input EGL display yet")
                return@runOnWorker
            }
            if (outputSurfaceInfo == null) {
                Log.i(TAG, "Output surface released")
                if (encodeSurface == null) {
                    outputRenderer?.setOutputSurface(display, null)
                } else {
                    outputRenderer?.setMirrorOutputSurface(null)
                }
            } else {
                Log.i(
                    TAG,
                    "Output surface set: ${outputSurfaceInfo.width}x${outputSurfaceInfo.height} " +
                        "(orientationDegrees=${outputSurfaceInfo.orientationDegrees})"
                )
                // While an encode owns the window the preview surface is only recorded: swapping
                // the renderer over to it mid-encode would tear the stream in half.
                if (encodeSurface == null) {
                    outputRenderer?.setOutputSurface(display, outputSurfaceInfo.surface)
                    applyOutputDataSpace()
                } else {
                    outputRenderer?.setMirrorOutputSurface(outputSurfaceInfo.surface)
                    applyOutputDataSpace()
                }
            }
        }
    }

    /**
     * Points the renderer at [surface] (a MediaCodec encoder input Surface) or, when null, back
     * at whatever surface the player published. Off by default: nothing encodes until this is
     * called, so normal playback never takes this path.
     */
    fun setEncodeSurface(surface: Surface?) {
        runOnWorker("setEncodeSurface()") {
            if (encodeSurface == surface) return@runOnWorker
            encodeSurface = surface
            val display = bundleDisplay()
            if (display == null) {
                Log.w(TAG, "setEncodeSurface(): no input EGL display yet, deferring")
                return@runOnWorker
            }
            val target = activeOutputSurface()
            Log.i(
                TAG,
                if (surface == null) "Encode target cleared, preview restored"
                else "Encode target set: ${target?.javaClass?.simpleName}"
            )
            if (target == null || !target.isValid) {
                outputRenderer?.setMirrorOutputSurface(null)
                outputRenderer?.setOutputSurface(display, null)
            } else {
                if (surface == null) outputRenderer?.setMirrorOutputSurface(null)
                else outputRenderer?.setMirrorOutputSurface(pendingOutputSurfaceInfo?.surface)
                outputRenderer?.setOutputSurface(display, target)
                applyOutputDataSpace()
            }
        }
    }

    /** The surface processed frames are currently drawn into: the encoder while one is active. */
    private fun activeOutputSurface(): Surface? =
        encodeSurface ?: pendingOutputSurfaceInfo?.surface

    fun setOutputDataSpace(dataSpace: Int) {
        currentOutputDataSpace = dataSpace
        runOnWorker("setOutputDataSpace()") {
            if (outputDataSpace != dataSpace) {
                outputDataSpace = dataSpace
                // A new value means a new stream, so the decoder's own answer is stale too.
                codecDataSpace = 0
                codecDataSpaceProbed = false
                codecDataSpaceProbeLogged = false
            }
            applyOutputDataSpace()
        }
    }

    fun outputDataSpaceForDiagnostics(): Int = currentOutputDataSpace

    fun setSourceColorInfo(colorInfo: ColorInfo?) {
        sourceColorInfo = colorInfo
        runOnWorker("setSourceColorInfo") {
            hdrCaptureUnavailableLogged = false
            hdrProbeLogged = false
            hdrRepresentationProbe?.reset()
            Log.i(TAG, "[HDR] inputColor=${colorInfo ?: "unknown"} outputDataSpace=$outputDataSpace")
        }
    }

    /**
     * The decoder's own identity: its MIME and the codec-capabilities string Media3 hands us,
     * which is where the profile and level live (for example `hvc1.2.4.L120.90` or `dvhe.08.06`).
     * Stored only so the source probe can quote evidence rather than infer HDR from branding.
     */
    @Volatile private var sourceMimeType: String? = null
    @Volatile private var sourceCodecs: String? = null

    fun setSourceStreamInfo(mime: String?, codecs: String?) {
        val changed = sourceMimeType != mime || sourceCodecs != codecs
        sourceMimeType = mime
        sourceCodecs = codecs
        if (changed) {
            runOnWorker("setSourceStreamInfo") { hdrProbeLogged = false }
        }
    }

    /** True when the current source is HDR (PQ or HLG), regardless of interpolation state. */
    private fun isHdrSource(): Boolean = ColorInfo.isTransferHdr(sourceColorInfo)

    /**
     * Sets how many output frames are synthesised per source frame. Read on every cycle, so the
     * next pair already emits at the new cadence; the emission phase is re-anchored from that
     * pair's own timestamps rather than carried over from the old ratio.
     */
    fun setMemcLevel(multiplier: Float) {
        val clamped = multiplier.coerceIn(2f, 4f)
        if (memcLevelMultiplier == clamped) return
        memcLevelMultiplier = clamped
        runOnWorker("setMemcLevel()") {
            nextOutputUs = Long.MIN_VALUE
        }
        Log.i(TAG, "Interpolation level: ${clamped}x")
    }

    /**
     * Sets the strength of the motion-aligned denoiser's history blend. It is a shader uniform,
     * so it applies to the next frame rendered rather than to the next pipeline reset.
     */
    fun setDenoiseLevel(strength: Float) {
        val clamped = strength.coerceIn(0.1f, 2f)
        denoiseStrength = clamped
        runOnWorker("setDenoiseLevel()") {
            outputRenderer?.denoiseStrength = clamped
        }
    }

    /**
     * Re-tags the current output window. Called whenever the surface or the colour volume of the
     * source changes; a no-op without a live surface, since the next one is tagged on creation.
     * Only valid on the worker thread.
     */
    private fun applyOutputDataSpace() {
        val surface = activeOutputSurface()
        if (surface == null || !surface.isValid) return
        probeCodecDataSpace()
        val want = effectiveOutputDataSpace()
        val rc = NativeEngine.setOutputDataSpace(surface, want)
        when {
            rc == 0 && want != 0 -> {
                val window = NativeEngine.getOutputDataSpace(surface)
                Log.i(TAG, "Output dataspace tagged: $want (window reports $window)")
            }
            rc == 0 -> Unit
            else -> Log.w(TAG, "setOutputDataSpace($want) failed: ${dataSpaceError(rc)}")
        }
        outputRenderer?.mirrorOutputSurface?.takeIf { it.isValid }?.let { NativeEngine.setOutputDataSpace(it, want) }
    }

    /**
     * Reads the dataspace the decoder is using for the current stream, once per input surface.
     * [NativeEngine.getOutputDataSpace] reports 0 for "unspecified" as well as for the decoder
     * simply not having configured itself yet, so an unanswered probe is retried on the next
     * stats window rather than cached forever.
     */
    private fun probeCodecDataSpace() {
        if (codecDataSpaceProbed) return
        val surface = createdInputSurface
        if (surface == null || !surface.isValid) return
        val value = NativeEngine.getOutputDataSpace(surface)
        if (value > 0) {
            codecDataSpace = value
            codecDataSpaceProbed = true
            Log.i(TAG, "Codec output dataspace: $value (colour metadata says $outputDataSpace)")
        } else if (!codecDataSpaceProbeLogged) {
            codecDataSpaceProbeLogged = true
            Log.i(TAG, "Codec output dataspace: not reported ($value); using colour metadata")
        }
    }

    /**
     * The value the output window should carry: the decoder's own whenever it is willing to say,
     * so the processed path is displayed exactly like the bypass path, and the colour-metadata
     * value otherwise. SDR stays at `0` either way.
     */
    private fun effectiveOutputDataSpace(): Int =
        if (outputDataSpace != 0 && codecDataSpace != 0) codecDataSpace else outputDataSpace

    /**
     * Puts the tag back if something took it away. EGL silently resets the dataspace when it
     * recreates the window surface, which any pipeline reset that touches the output can do, so a
     * tag written once does not survive - this reads before it writes and only speaks up when the
     * window had drifted, which is precisely the evidence needed to tell a lost tag from a stable
     * one. Only valid on the worker thread.
     */
    private fun reassertOutputDataSpace(reason: String) {
        if (outputDataSpace == 0) return
        val surface = activeOutputSurface()
        if (surface == null || !surface.isValid) return
        probeCodecDataSpace()
        val want = effectiveOutputDataSpace()
        val before = NativeEngine.getOutputDataSpace(surface)
        if (before != want) {
            val rc = NativeEngine.setOutputDataSpace(surface, want)
            Log.w(
                TAG,
                "Output dataspace drifted ($reason): window=$before want=$want " +
                    "codec=$codecDataSpace rc=${if (rc == 0) "ok" else dataSpaceError(rc)}"
            )
        }
        // The preview is a second window while an encode owns the primary one, and it drifts on
        // its own: EGL re-tags a window whenever it is recreated, and only the primary is read
        // back here. Left alone the mirror keeps rendering the same pixels untagged, which is
        // exactly "the TV box still has HDR, the phone lost it".
        val mirror = outputRenderer?.mirrorOutputSurface
        if (mirror != null && mirror.isValid && NativeEngine.getOutputDataSpace(mirror) != want) {
            NativeEngine.setOutputDataSpace(mirror, want)
            Log.w(TAG, "Preview mirror dataspace drifted ($reason); re-tagged to $want")
        }
    }

    /** Turns the JNI stage's failure code back into something readable in a log line. */
    private fun dataSpaceError(rc: Int): String = when (rc) {
        -1001 -> "rc=-1001 (null surface)"
        -1002 -> "rc=-1002 (null window)"
        -1003 -> "rc=-1003 (ANativeWindow_setBuffersDataSpace not resolvable)"
        else -> "rc=$rc"
    }

    /**
     * The EGL display of the current input bundle, or `null` while no bundle exists. Only valid on
     * the worker thread.
     */
    private fun bundleDisplay(): EGLDisplay? = inputBundle?.display

    override fun setOnInputFrameProcessedListener(listener: OnInputFrameProcessedListener) {
        // Frames are consumed and rendered internally as soon as they are read back, so there is
        // no external handshake to drive. Accepted for API compatibility.
    }

    private var inputSurfaceReadyListener: Runnable? = null

    override fun setOnInputSurfaceReadyListener(listener: Runnable) {
        inputSurfaceReadyListener = listener
    }

    /**
     * Media3 redraw: re-renders the most recent output frame with updated effects. This processor
     * renders every frame immediately upon production, so there is no buffered frame to redraw.
     */
    override fun redraw() {
        // No-op: this processor renders frames immediately and has no buffered output to redraw.
    }

    /** This processor renders every output frame as soon as it becomes available. */
    override fun renderOutputFrame(renderTimeNs: Long) = Unit

    override fun queueInputBitmap(
        inputBitmap: Bitmap,
        timestampIterator: TimestampIterator
    ): Boolean {
        throw UnsupportedOperationException("Interpolation requires an input Surface")
    }

    override fun queueInputTexture(textureId: Int, presentationTimeUs: Long): Boolean {
        throw UnsupportedOperationException("Interpolation requires an input Surface")
    }

    override fun signalEndOfInput() {
        runOnWorker("signalEndOfInput()") {
            endOfInputSignalled = true
            resetPipelineOnWorker("end_of_input")
        }
    }

    /**
     * Media3 flush. All frames registered before the flush stop being considered registered, so the
     * caller has to register the input stream again before feeding new frames.
     */
    override fun flush() {
        runOnWorker("flush()") {
            resetPipelineOnWorker("flush")
            inputStreamRegistered = false
            endOfInputSignalled = false
        }
    }

    override fun release() {
        stop()
    }

    // ---------------------------------------------------------------------------------------
    // Worker-thread helpers
    // ---------------------------------------------------------------------------------------

    private fun runOnWorker(action: String, block: () -> Unit) {
        val handler = workerHandler
        if (handler == null) {
            Log.w(TAG, "$action: worker thread is gone, skipping")
            return
        }
        val posted = handler.post {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "$action failed on the worker thread", t)
                reportError("Pipeline error: ${t.message}")
            }
        }
        if (!posted) {
            Log.w(TAG, "$action: worker thread rejected the task, skipping")
        }
    }

    private fun reportError(message: String) {
        val handler = mainHandler
        if (handler != null) {
            handler.post { onError(message) }
        } else {
            onError(message)
        }
    }

    private fun notifyInputSurfaceCreated(surface: Surface) {
        val handler = mainHandler
        if (handler != null) {
            handler.post { onInputSurfaceCreated(surface) }
        } else {
            onInputSurfaceCreated(surface)
        }
    }

    private fun currentFrameInfo(): FrameInfo {
        val width = if (inputWidth > 0) inputWidth else FALLBACK_FRAME_WIDTH
        val height = if (inputHeight > 0) inputHeight else FALLBACK_FRAME_HEIGHT
        return FrameInfo(
            Format.Builder()
                .setColorInfo(sourceColorInfo ?: ColorInfo.SDR_BT709_LIMITED)
                .setWidth(width)
                .setHeight(height)
                .build(),
            0L
        )
    }

    private fun registerInputStreamOnWorker(frameInfo: FrameInfo) {
        inputStreamRegistered = true
        endOfInputSignalled = false
        try {
            media3Listener.onInputStreamRegistered(
                Media3VideoFrameProcessor.INPUT_TYPE_SURFACE,
                Format.Builder()
                    .setColorInfo(frameInfo.format.colorInfo ?: sourceColorInfo ?: ColorInfo.SDR_BT709_LIMITED)
                    .setWidth(frameInfo.format.width)
                    .setHeight(frameInfo.format.height)
                    .build(),
                ArrayList<Effect>()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "onInputStreamRegistered failed", t)
        }
    }

    /**
     * Clears every piece of per-stream pipeline state. Runs on the worker thread so that no frame
     * buffer is recycled while it could still be in use.
     */
    private fun resetPipelineOnWorker(reason: String) {
        var discarded = 0
        streamGeneration++
        frameGrabber?.discardPendingReadbacks()

        // Clean up pending render queue (motion done, waiting for render)
        while (true) {
            val work = pendingRenderQueue.removeFirstOrNull() ?: break
            releaseFrameBuffer(work.prev.pixels)
            releaseFrameBuffer(work.next.pixels)
            discarded++
        }

        // Motion submitted but not yet rendered now lives only in the motion task's own closure
        // and in the lease table below; there is no separate "in-flight pair" reservation to
        // drop, so the previous frame is released outright.
        pairProcessing = false
        previousFrame?.let {
            releaseFrameBuffer(it.pixels)
            discarded++
        }
        previousFrame = null

        while (true) {
            val frame = frameQueue.poll() ?: break
            releaseFrameBuffer(frame.pixels)
            discarded++
        }

        // Temporal history of the FastDVDnet scaffold must not survive a pipeline boundary either.
        fastDvdNetEngine.reset()
        // ...and neither may the motion-aligned denoiser's: the frame after a seek is not adjacent
        // to anything it has merged, so continuing the recursion would smear across the cut.
        outputRenderer?.resetDenoise()
        denoiseUnavailableLogged = false

        cachedIn0Buf = null
        cachedIn1Buf = null
        cachedDenoised0Buf = null
        cachedDenoised1Buf = null
        cachedOutBuf = null
        cachedMotionBuf = null
        frameBufferPool.clear()
        cachedTargetSize = 0
        rejectedTargetSize = 0
        rejectRetryAfterNs = 0L
        bypassTargetSize = 0
        bypassReportedForSize = 0
        bypassRetryAfterNs = 0L
        lastAllocationLogNs = 0L
        readbackInProgress = false

        frameCountInput = 0
        frameCountOutput = 0
        statsCapturedAtStart = 0
        statsOutputAtStart = 0
        lastStatsResetTime = SystemClock.elapsedRealtime()
        lastProcTimeMs = 0L

        // The capture breadcrumb is cleared so the RES POLICY line is printed again for the new
        // source; resolution itself is never inferred, so there is nothing else to reset.
        lastCaptureLogSrcW = -1
        lastCaptureLogSrcH = -1
        lastCaptureLogW = 0
        lastCaptureLogH = 0
        lastCaptureLogRes = null

        // The decoder may already have moved on to a new stream, so its dataspace answer is stale.
        codecDataSpace = 0
        codecDataSpaceProbed = false
        codecDataSpaceProbeLogged = false

        Log.i(TAG, "resetPipeline: reason=$reason discardedFrames=$discarded")
        // A reset is exactly when the EGL window surface gets torn down and rebuilt, which is
        // where a tag written earlier stops being true.
        reassertOutputDataSpace(reason)
    }

    private fun releaseStateOnWorker() {
        // Motion tasks may still be holding leases on buffers this teardown just discarded; the
        // tables are worthless from here on and must not outlive the pipeline they describe.
        motionLeasedBuffers.clear()
        motionDeferredReleases.clear()
        pendingOutputSurfaceInfo = null
        // The renderer this pointed at is about to go away; the owner stops the encoder itself,
        // so dropping the reference here only stops us drawing into a Surface nobody owns.
        encodeSurface = null
        resetPipelineOnWorker("release")
        hdrRepresentationProbe?.release()
        hdrRepresentationProbe = null
        hdrOutputInvariantReported = false
        outputRenderer?.release()
        outputRenderer = null

        // The bundle's EGL context is still current here, so its GL objects can be deleted safely.
        releaseGlObjectsForBundle(inputBundle, frameGrabber)
        frameGrabber = null
        releaseGlObjectsForBundle(pendingSupersededBundle, null)

        releaseInputSurfaceBundle(inputBundle)
        inputBundle = null
        releaseInputSurfaceBundle(pendingSupersededBundle)
        pendingSupersededBundle = null
        createdInputSurface = null
        inputSurfaceReady = false
        inputSurfaceEverAttached = false
        inputStreamRegistered = false
        endOfInputSignalled = false
    }

    /**
     * Deletes the GL objects that belong to [bundle]'s EGL context. Must run while that context is
     * still current, otherwise the texture names would be meaningless in the new context.
     */
    private fun releaseGlObjectsForBundle(bundle: InputSurfaceBundle?, grabber: OesFrameGrabber?) {
        if (bundle == null || bundle.glObjectsReleased) {
            return
        }
        try {
            grabber?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release the GL readback objects", t)
        }
        try {
            if (bundle.textureId != 0) {
                GlUtil.deleteTexture(bundle.textureId)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete the external texture", t)
        }
        bundle.textureId = 0
        bundle.glObjectsReleased = true
    }

    private fun releaseInputSurfaceBundle(bundle: InputSurfaceBundle?) {
        if (bundle == null) {
            return
        }
        try {
            bundle.surfaceHandle.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release the input Surface", t)
        }
        try {
            bundle.texture.setOnFrameAvailableListener(null)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to detach the SurfaceTexture listener", t)
        }
        try {
            bundle.texture.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release the SurfaceTexture", t)
        }
        try {
            if (bundle.surface != null && bundle.surface != EGL14.EGL_NO_SURFACE) {
                GlUtil.destroyEglSurface(bundle.display, bundle.surface)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to destroy the EGL surface", t)
        }
        try {
            if (bundle.context != null && bundle.context != EGL14.EGL_NO_CONTEXT) {
                GlUtil.destroyEglContext(bundle.display, bundle.context)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to destroy the EGL context", t)
        }
        try {
            EGL14.eglReleaseThread()
        } catch (t: Throwable) {
            Log.w(TAG, "eglReleaseThread failed", t)
        }
    }

    /**
     * Creates the EGL context, the external texture, the SurfaceTexture and the input Surface that
     * the player renders decoded frames into. Runs on the worker thread.
     *
     * The replaced bundle is released only after the owner has attached the new surface to the
     * player (see [onInputSurfaceAttached]), so the player is never left rendering into a Surface
     * that has already been destroyed.
     */
    private fun createInputSurfaceOnWorker(reason: String) {
        if (released) {
            return
        }
        if (workerHandler == null) {
            return
        }

        var newBundle: InputSurfaceBundle? = null
        try {
            // Reuse the surface created by start() if the player never rendered into it.
            val reusable = inputBundle
            if (reusable != null && !inputSurfaceEverAttached) {
                registerInputStreamOnWorker(currentFrameInfo())
                inputSurfaceReady = true
                Log.i(TAG, "Reusing the existing Media3 input surface ($reason)")
                notifyInputSurfaceCreated(reusable.surfaceHandle)
                return
            }

            // The replaced bundle's EGL context is still current right now, so its GL objects have
            // to be deleted before the new context takes over.
            releaseGlObjectsForBundle(inputBundle, frameGrabber)
            frameGrabber = null

            var display = GlUtil.getDefaultEglDisplay()
            val context = try {
                // ES3 pixel-pack buffers allow GPU readback to overlap decoder acquisition.
                GlUtil.createEglContext(
                    EGL14.EGL_NO_CONTEXT,
                    display,
                    3,
                    GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888,
                )
            } catch (t: Throwable) {
                // Keep the established ES2 path for drivers without an ES3 context/config.
                Log.w(TAG, "GLES3 unavailable; retaining synchronous capture fallback", t)
                display = GlUtil.getDefaultEglDisplay()
                GlUtil.createEglContext(display)
            }
            val eglSurface = GlUtil.createFocusedPlaceholderEglSurface(context, display)
            val textureId = GlUtil.createExternalTexture()
            val texture = SurfaceTexture(textureId)
            if (inputWidth > 0 && inputHeight > 0) {
                // Tells the SurfaceTexture how large the producer buffers are, so the sampling
                // transform matrix is built for the real video size instead of an unknown one.
                texture.setDefaultBufferSize(inputWidth, inputHeight)
            }
            val inputSurface = Surface(texture)
            newBundle = InputSurfaceBundle(
                display = display,
                context = context,
                surface = eglSurface,
                textureId = textureId,
                texture = texture,
                surfaceHandle = inputSurface
            )

            // Frames become readable on the worker thread, which is also the thread owning the EGL
            // context, so the readback never has to hop threads. The listener is detached in
            // releaseInputSurfaceBundle().
            texture.setOnFrameAvailableListener(
                { available ->
                    if (available !== texture) {
                        return@setOnFrameAvailableListener
                    }
                    onInputFrameAvailableOnWorker(available)
                },
                workerHandler
            )

            val grabber = OesFrameGrabber()
            grabber.init(textureId)
            frameGrabber = grabber

            // The GL output blitter shares this EGL context, so it is created here on the worker
            // thread while the context is current. A renderer still bound to a superseded
            // context is released first so its program and texture do not leak.
            outputRenderer?.release()
            outputRenderer = null
            hdrRepresentationProbe?.release()
            hdrRepresentationProbe = null
            val renderer = GlOutputRenderer()
            renderer.init(context)
            // A renderer recreated for a new surface has to come up with the level the user
            // picked, not with the shader's own default.
            renderer.denoiseStrength = denoiseStrength
            // An HDR source presented into an 8-bit window surface would look like a fix and be
            // a silent precision loss, so the renderer escalates it instead of keeping it to
            // itself. One report per stream, never per frame.
            renderer.onHdrOutputInvariant = { message ->
                if (!hdrOutputInvariantReported) {
                    hdrOutputInvariantReported = true
                    reportError(message)
                }
            }
            outputRenderer = renderer
            hdrRepresentationProbe = HdrRepresentationProbe()

            // The replaced EGL/SurfaceTexture state has to outlive the handover, so it is retired
            // here and destroyed by onInputSurfaceAttached() once the player has the new surface.
            pendingSupersededBundle = inputBundle
            inputBundle = newBundle
            createdInputSurface = inputSurface
            inputSurfaceReady = true
            inputSurfaceEverAttached = false
            registerInputStreamOnWorker(currentFrameInfo())

            Log.i(
                TAG,
                "Media3 input surface created ($reason): surface=$inputSurface texId=$textureId"
            )

            notifyInputSurfaceCreated(inputSurface)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize the Media3 input surface ($reason)", e)
            if (newBundle === inputBundle) {
                inputBundle = null
                createdInputSurface = null
            }
            releaseGlObjectsForBundle(newBundle, frameGrabber)
            frameGrabber = null
            releaseInputSurfaceBundle(newBundle)
            inputSurfaceReady = false
            reportError("Input surface initialization failed: ${e.message}")
            // The owner has to fall back to normal PlayerView playback, otherwise the processing
            // output surface would sit in front of the user with nothing rendered into it.
            val handler = mainHandler
            if (handler != null) {
                handler.post { onInputSurfaceFailed() }
            } else {
                onInputSurfaceFailed()
            }
        }
    }

    /**
     * Ensures the EGL context that owns the input SurfaceTexture is current on the worker thread.
     * Returns true if successful, false otherwise.
     *
     * Note: The input EGL surface may be EGL_NO_SURFACE when the device supports surfaceless
     * EGL contexts (via GlUtil.createFocusedPlaceholderEglSurface). This is a valid state and
     * eglMakeCurrent should be called with EGL_NO_SURFACE for both draw and read surfaces.
     */
    private fun ensureEglContextCurrent(): Boolean {
        val bundle = inputBundle
        if (bundle == null) {
            Log.e(TAG, "ensureEglContextCurrent: inputBundle is null")
            return false
        }
        val display = bundle.display
        val context = bundle.context
        val surface = bundle.surface
        if (display == null || context == null || surface == null) {
            Log.e(TAG, "ensureEglContextCurrent: EGL display/context/surface is null")
            return false
        }
        if (context == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "ensureEglContextCurrent: EGL context is invalid")
            return false
        }
        val currentContext = EGL14.eglGetCurrentContext()
        val currentDisplay = EGL14.eglGetCurrentDisplay()
        val currentSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val isSurfaceNoSurface = surface == EGL14.EGL_NO_SURFACE
        if (currentContext == context && currentDisplay == display && currentSurface == surface) {
            return true
        }
        val drawSurface = if (isSurfaceNoSurface) EGL14.EGL_NO_SURFACE else surface
        val readSurface = if (isSurfaceNoSurface) EGL14.EGL_NO_SURFACE else surface
        if (!EGL14.eglMakeCurrent(display, drawSurface, readSurface, context)) {
            val error = EGL14.eglGetError()
            Log.e(TAG, "ensureEglContextCurrent: eglMakeCurrent failed: 0x${error.toString(16)} " +
                "display=$display context=$context surface=$surface isNoSurface=$isSurfaceNoSurface " +
                "currentDisplay=$currentDisplay currentContext=$currentContext currentSurface=$currentSurface")
            return false
        }
        return true
    }

    /**
     * Invoked on the worker thread whenever a decoded frame has been queued onto the input
     * SurfaceTexture. This is where real decoded pixels become available.
     */
    private fun onInputFrameAvailableOnWorker(texture: SurfaceTexture) {
        // Bracketing the whole callback is what makes the inter-callback gap measurable: every
        // exit path, including the early returns, has to close the window or the number would
        // drift with whichever branch a frame happened to take. The gap is where anything not
        // covered by a stage timer shows up - handler dispatch, other worker messages, OS
        // scheduling - which is the difference between "the pipeline is slow" and "the worker
        // never got scheduled".
        try {
            handleInputFrameAvailableOnWorker(texture)
        } finally {
            lastCallbackExitNs = System.nanoTime()
        }
    }

    private fun handleInputFrameAvailableOnWorker(texture: SurfaceTexture) {
        // Counted first: this is the decoder-arrival rate the audit asks for, and it must be
        // visible even when every later stage rejects the frame.
        frameCountArrival++
        val entryNs = System.nanoTime()
        if (lastCallbackExitNs != 0L) {
            val gap = entryNs - lastCallbackExitNs
            nsWorkerGap += gap
            workerGapSamples++
            if (gap > maxWorkerGapNs) maxWorkerGapNs = gap
        }
        if (lastArrivalNs != 0L) {
            val interval = entryNs - lastArrivalNs
            nsDecodeInterval += interval
            decodeIntervalCount++
            if (interval > maxDecodeIntervalNs) maxDecodeIntervalNs = interval
        }
        lastArrivalNs = entryNs
        if (released) {
            return
        }
        if (inputBundle?.texture !== texture) {
            // A frame arrived for a SurfaceTexture that has already been replaced.
            droppedFrameCount++
            return
        }
        if (!isProcessingEnabled) {
            // Nothing is intercepting: keep the decoder flowing instead of stalling it on a full
            // BufferQueue, otherwise switching processing off would look like a freeze.
            consumeAndDiscard(texture)
            return
        }
        if (!inputStreamRegistered || endOfInputSignalled) {
            consumeAndDiscard(texture)
            return
        }
        if (readbackInProgress) {
            droppedFrameCount++
            consumeAndDiscard(texture)
            return
        }

        readbackInProgress = true
        try {
            // Diagnostic logging before updateTexImage
            val bundle = inputBundle
            if (VERBOSE_DIAGNOSTICS && bundle != null) {
                val storedDisplay = bundle.display
                val storedContext = bundle.context
                val storedSurface = bundle.surface
                val currentDisplay = EGL14.eglGetCurrentDisplay()
                val currentContext = EGL14.eglGetCurrentContext()
                val currentSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val currentReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                val textureId = bundle.textureId
                val isTextureReleased = texture.isReleased
                val isBundleReleased = bundle.glObjectsReleased
                val isSurfaceNoSurface = storedSurface == EGL14.EGL_NO_SURFACE
                Log.d(TAG, "EGL DIAGNOSTICS: workerThread=${Thread.currentThread().name} " +
                    "storedDisplay=$storedDisplay storedContext=$storedContext storedSurface=$storedSurface " +
                    "surfaceIsNoSurface=$isSurfaceNoSurface " +
                    "currentDisplay=$currentDisplay currentContext=$currentContext " +
                    "currentDrawSurface=$currentSurface currentReadSurface=$currentReadSurface " +
                    "textureId=$textureId textureReleased=$isTextureReleased bundleGLReleased=$isBundleReleased " +
                    "processingEnabled=$isProcessingEnabled " +
                    "contextMatch=${storedContext == currentContext} displayMatch=${storedDisplay == currentDisplay} " +
                    "surfaceMatch=${storedSurface == currentSurface}")
            }

            // Ensure the EGL context that owns the SurfaceTexture is current before updateTexImage
            if (!ensureEglContextCurrent()) {
                Log.e(TAG, "Failed to make EGL context current for updateTexImage")
                droppedFrameCount++
                return
            }

            // Acquires the frame the decoder has just queued. It must be called exactly once per
            // available frame, before the texture is sampled.
            texture.updateTexImage()
            probeSourceTexture(texture)
            captureFrameFromInputSurface(texture)
        } catch (t: Throwable) {
            droppedFrameCount++
            Log.e(TAG, "Frame capture failed", t)
            reportError("Frame capture failed: ${t.message}")
        } finally {
            readbackInProgress = false
        }
    }

    /**
     * One-shot-per-stream evidence for the HDR root cause, taken AFTER `updateTexImage()` so it
     * describes the frame the OES sampler is actually about to read rather than a value observed
     * before the buffer was acquired.
     *
     * Purely observational: nothing here feeds into [isHdrSource], the encoder's colour config,
     * the render path, or the shaders. It exists because "it is HDR" so far rests on Media3's
     * [ColorInfo] and on Dolby Vision branding; the dataspace the producer actually stamped on the
     * buffer is a different, independently checkable fact.
     */
    private fun probeSourceTexture(texture: SurfaceTexture) {
        val color = sourceColorInfo
        val mime = sourceMimeType
        val codecs = sourceCodecs
        val hdr = isHdrSource()
        // SurfaceTexture.getDataSpace() only exists from API 33; below that the producer's answer
        // is simply not reachable through this API and the log says so instead of guessing.
        val surfaceDataSpace = if (Build.VERSION.SDK_INT >= 33) {
            try {
                texture.dataSpace
            } catch (t: Throwable) {
                DATA_SPACE_UNKNOWN
            }
        } else {
            DATA_SPACE_NOT_QUERIED
        }
        val shaderHdr = outputRenderer?.isHdr ?: false
        val retained = hdr && frameGrabber?.isHdrSourceSupported == true
        val key = "$mime|$codecs|$color|$surfaceDataSpace|$outputDataSpace|$hdr|$shaderHdr|$retained"
        val now = System.nanoTime()
        val first = !hdrProbeLogged
        val changed = key != hdrProbeLastKey
        // HDR keeps reporting every ten seconds so a mid-stream metadata change cannot hide;
        // a stable SDR source only ever reports once, which is all a baseline needs.
        val due = hdr && now - hdrProbeLastNs >= HDR_PROBE_REPEAT_NS
        if (!first && !changed && !due) return
        hdrProbeLogged = true
        hdrProbeLastKey = key
        hdrProbeLastNs = now
        Log.i(
            TAG,
            "[HDRPROBE] mime=$mime codecs=$codecs profile=${describeCodecProfile(codecs)} " +
                "colorInfo=${color ?: "unknown"} " +
                "surfaceDataSpace=$surfaceDataSpace(${describeDataSpace(surfaceDataSpace)}) " +
                "outputDataSpace=$outputDataSpace(${describeDataSpace(outputDataSpace)}) " +
                "codecDataSpace=$codecDataSpace(${describeDataSpace(codecDataSpace)}) " +
                "isHdrSource=$hdr shaderIsHdr=$shaderHdr " +
                "captureFormat=${if (retained) "GL_RGBA16F" else "GL_RGBA8"} " +
                "fp16Retained=$retained"
        )
    }

    /**
     * Human-readable decode of an Android dataspace: standard, transfer, range. The layout has
     * been stable since O, and the same decode is used for every dataspace this class quotes so
     * that two lines with the same shape can be compared directly.
     */
    private fun describeDataSpace(value: Int): String = when (value) {
        DATA_SPACE_UNKNOWN -> "unknown"
        DATA_SPACE_NOT_QUERIED -> "not-queried(api<33)"
        0 -> "UNSPECIFIED"
        else -> {
            val standard = (value shr 16) and 0x3F
            val transfer = (value shr 22) and 0x1F
            val range = (value shr 27) and 0x1F
            val s = when (standard) {
                1 -> "BT709"
                2 -> "BT601_625"
                3 -> "BT601_525"
                6 -> "BT2020"
                10 -> "DCI_P3"
                else -> "std$standard"
            }
            val t = when (transfer) {
                1 -> "LINEAR"
                2 -> "SRGB"
                3 -> "SMPTE_170M"
                7 -> "ST2084_PQ"
                8 -> "HLG"
                else -> "tr$transfer"
            }
            val r = when (range) {
                1 -> "FULL"
                2 -> "LIMITED"
                else -> "r$range"
            }
            "$s/$t/$r"
        }
    }

    /**
     * Best-effort profile label from a codec-capabilities string. `hvc1.2.4.L120.90` and
     * `dvhe.08.06` both carry it in the second field, so the raw string is always logged next to
     * this and a decode this class does not know simply falls back to `unknown`.
     */
    private fun describeCodecProfile(codecs: String?): String {
        if (codecs.isNullOrBlank()) return "unknown"
        val fields = codecs.split('.')
        if (fields.size < 2) return "unknown"
        val tag = fields[0].lowercase()
        val profile = fields[1]
        return when {
            tag.startsWith("hvc") || tag.startsWith("hev") -> when (profile) {
                "1" -> "Main"
                "2" -> "Main10"
                "3" -> "MainStill"
                else -> "HEVC-profile-$profile"
            }
            tag.startsWith("dv") -> when (profile) {
                "0" -> "dv-mel"
                "1" -> "dv-bl"
                "4" -> "dv-profile4"
                "5" -> "dv-profile5"
                "7" -> "dv-profile7"
                "8" -> "dv-profile8"
                "9" -> "dv-profile9"
                else -> "dv-profile-$profile"
            }
            tag.startsWith("av01") -> "av1-profile-$profile"
            tag.startsWith("avc") -> when (profile) {
                "64" -> "High"
                "100" -> "High10"
                "244" -> "High444"
                else -> "avc-profile-$profile"
            }
            else -> "profile-$profile"
        }
    }

    /** Keeps the decoder from stalling on a full BufferQueue while a frame is intentionally lost. */
    private fun consumeAndDiscard(texture: SurfaceTexture) {
        // Ensure the EGL context is current before updateTexImage
        if (!ensureEglContextCurrent()) {
            Log.w(TAG, "consumeAndDiscard: failed to make EGL context current")
            return
        }
        try {
            texture.updateTexImage()
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage while discarding a frame failed", t)
        }
    }

    /**
     * Reads the decoded frame the [texture] is currently holding into a direct RGBA buffer and
     * feeds it into the frame pipeline.
     */
    private fun captureFrameFromInputSurface(texture: SurfaceTexture) {
        val sourceWidth = inputWidth
        val sourceHeight = inputHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            droppedFrameCount++
            Log.w(TAG, "Dropping frame: the decoded frame size is not known yet")
            return
        }

        val grabber = frameGrabber
        if (grabber == null || !grabber.isInitialized) {
            droppedFrameCount++
            return
        }

        // The readback size is the source size scaled to the configured resolution; the aspect
        // ratio of the source is preserved, so the frame is never stretched. The capture is also
        // clamped to the output surface - never below it as a performance shortcut.
        val (captureWidth, captureHeight) = resolveCaptureDimensions(sourceWidth, sourceHeight)
        processingWidth = captureWidth
        processingHeight = captureHeight

        if (sourceWidth != lastCaptureLogSrcW ||
            sourceHeight != lastCaptureLogSrcH ||
            captureWidth != lastCaptureLogW ||
            captureHeight != lastCaptureLogH ||
            resolution != lastCaptureLogRes
        ) {
            if (maxOf(sourceWidth, sourceHeight) >= auto4kMinDim) {
                Log.i(TAG, "4K source -> selected processing resolution ${captureWidth}x$captureHeight -> estimated RGBA memory ${captureWidth.toLong() * captureHeight * 4L * 13L} bytes")
            }
            frameBufferPool.clear()
            lastCaptureLogSrcW = sourceWidth
            lastCaptureLogSrcH = sourceHeight
            lastCaptureLogW = captureWidth
            lastCaptureLogH = captureHeight
            lastCaptureLogRes = resolution
            val hdrInfo = if (isHdrSource()) {
                val hdrLeases = frameGrabber?.readbackState() ?: "unknown"
                val retainedW = captureWidth
                val retainedH = captureHeight
                val hdrGrabber = frameGrabber
                val hdrSlots = hdrGrabber?.hdrSourceSlots?.size ?: 0
                val estMem = hdrSlots.toLong() * (retainedW.toLong() * retainedH * 8L)
                " HDR: source=${sourceWidth}x$sourceHeight processing=${retainedW}x$retainedH retained=$hdrSlots leases=$hdrLeases estimatedMemory=${estMem / 1024 / 1024}MB"
            } else ""
            Log.i(
                TAG,
                "RES POLICY: src=${sourceWidth}x$sourceHeight " +
                    "surface=${outputRenderer?.outputSurfaceWidth ?: 0}x" +
                    "${outputRenderer?.outputSurfaceHeight ?: 0} " +
                    "res=$resolution interpolation=$isRifeEnabled denoise=$isDenoiseEnabled " +
                    "-> capture=${captureWidth}x$captureHeight" + hdrInfo
            )
        }

        if (VERBOSE_DIAGNOSTICS) {
            Log.d(
                TAG,
                "ALLOC DIAGNOSTICS: input=${sourceWidth}x$sourceHeight " +
                    "target=${captureWidth}x$captureHeight " +
                    "bytes=${captureWidth.toLong() * captureHeight.toLong() * 4L} " +
                    "pool=${frameBufferPool.size} resolution=$resolution"
            )
        }

        // Cycle start: the window from here to the end of processNextFramePair() is what the
        // decoder's BufferQueue actually experiences, so it is the number that has to reach the
        // frame interval for playback to keep up.
        timingStartNs = System.nanoTime()
        if (timingCycles == 0 && diagnosticWindowStartNs == 0L) {
            diagnosticWindowStartNs = timingStartNs
            diagnosticOutputStartCount = submittedOutputFrameCount
            droppedOutputAtWindowStart = droppedOutputFrameCount
        }
        val tCaptureStart = timingStartNs
        val timestampUs = texture.timestamp / 1000L
        val retainHdrSource = isHdrSource()
        if (retainHdrSource && !grabber.isHdrSourceSupported) {
            if (!hdrCaptureUnavailableLogged) {
                hdrCaptureUnavailableLogged = true
                Log.e(TAG, "HDR capture requires GLES3 RGBA16F render-target support; refusing the RGBA8 HDR path")
                reportError("HDR interpolation unavailable: this GLES driver cannot retain a high-precision source")
            }
            droppedFrameCount++
            return
        }
        if (grabber.isAsyncReadbackSupported) {
            val queued = grabber.enqueueReadbackWithRecovery(
                texture,
                captureWidth,
                captureHeight,
                timestampUs,
                retainHdrSource,
            )
            var completed = 0
            // Only poll ONCE per frame to avoid blocking the worker. If no PBO is ready,
            // we'll try again on the next frame. This prevents the worker from blocking
            // on PBO fences and allows true pipelining.
            val tPollStart = System.nanoTime()
            val info = grabber.nextReadbackInfo()
            if (info != null) {
                val completedPixels = obtainFrameBuffer(info.width, info.height)
                val ready = grabber.pollReadback(completedPixels)
                if (ready != null) {
                    queueCapturedFrame(
                        FrameData(
                            pixels = completedPixels,
                            timestampUs = ready.timestampUs,
                            width = ready.width,
                            height = ready.height,
                        ),
                        ready.sourceTextureId,
                    )
                    completed++
                } else {
                    releaseFrameBuffer(completedPixels)
                    // PBO not ready yet, will retry next frame
                }
            }
            nsPoll += System.nanoTime() - tPollStart
            nsReadback += System.nanoTime() - tCaptureStart
            if (!queued) droppedFrameCount++
            // If we queued but didn't complete any, we'll poll again next frame
            if (queued && completed == 0) return
            return
        }

        val pixels = obtainFrameBuffer(captureWidth, captureHeight)
        val readOk = pixels.capacity() > 0 &&
            grabber.read(texture, captureWidth, captureHeight, pixels)
        nsReadback += System.nanoTime() - tCaptureStart
        if (!readOk) {
            releaseFrameBuffer(pixels)
            droppedFrameCount++
            return
        }

        if (VERBOSE_DIAGNOSTICS) {
            Log.d(
                TAG,
                "FRAME CAPTURE LOG: decoded=${sourceWidth}x$sourceHeight -> " +
                    "preInterp=${captureWidth}x$captureHeight"
            )
        }

        val frame = FrameData(
            pixels = pixels,
            timestampUs = timestampUs,
            width = captureWidth,
            height = captureHeight
        )

        queueCapturedFrame(frame)
    }

    private fun queueCapturedFrame(frame: FrameData, sourceTextureId: Int = 0) {
        frameCountInput++
        if (sourceTextureId != 0) hdrTextureByAnalysisBuffer[frame.pixels] = sourceTextureId

        // Rate-limited content probe: distinguishes "capture produced black pixels" from
        // "presentation shows black". Zero here means the decoder/OES feed is black even though
        // every per-stage counter looks healthy.
        val captureProbeNs = System.nanoTime()
        if (captureProbeNs - lastCaptureContentLogNs >= 1_000_000_000L) {
            lastCaptureContentLogNs = captureProbeNs
            Log.i(
                TAG,
                "[CONTENT] capture ${frame.width}x${frame.height} checksum=" +
                    calculateChecksum(frame.pixels, frame.width, frame.height)
            )
        }

        // Bounded queue with explicit backpressure: drop the oldest frame rather than growing.
        if (!frameQueue.offer(frame)) {
            droppedFrameCount++
            frameQueue.poll()?.let { releaseFrameBuffer(it.pixels) }
            if (!frameQueue.offer(frame)) {
                droppedFrameCount++
                releaseFrameBuffer(frame.pixels)
                return
            }
        }

        processNextFramePair()
    }

    private fun processNextFramePair() {
        if (released) return
        
        // Discard stale frames that have been in queue too long (latency control)
        val nowNs = System.nanoTime()
        val maxQueueNs = MAX_QUEUE_LATENCY_MS * 1_000_000L
        while (true) {
            val head = frameQueue.peek() ?: break
            val ageNs = nowNs - (head.timestampUs * 1000L)
            if (ageNs <= maxQueueNs) break
            frameQueue.poll()?.let { 
                Log.w(TAG, "Discarding stale frame: queueLatencyMs=${ageNs / 1_000_000L} > ${MAX_QUEUE_LATENCY_MS}ms")
                releaseFrameBuffer(it.pixels)
                droppedFrameCount++
            }
        }
        
        val nextFrame = frameQueue.poll() ?: return
        // Track queue latency for this frame
        val queueLatencyMs = (nowNs - (nextFrame.timestampUs * 1000L)) / 1_000_000L
        nsQueueLatency += queueLatencyMs
        queueLatencySamples++
        
        val prev = previousFrame

        val canPrepareMotionOffThread = prev != null &&
            isRifeEnabled &&
            !fastDvdNetEngine.isEnabled &&
            prev.width == nextFrame.width && prev.height == nextFrame.height &&
            outputRenderer?.isWarpInitialized == true
        if (!canPrepareMotionOffThread) {
            processFramePair(prev, nextFrame, null)
            return
        }

        // Motion can run off-thread. Submit motion work; render will happen when motion completes.
        val generation = streamGeneration
        leaseForMotion(prev.pixels)
        leaseForMotion(nextFrame.pixels)
        motionExecutor.execute {
            val prepared = computeMotionFieldOffThread(prev, nextFrame)
            val handler = workerHandler
            if (handler == null) {
                // `workerHandler` only becomes null after `stop()` has already run
                // releaseStateOnWorker() and drained the pipeline, so there is no worker left to
                // race and no pool worth returning to: drop the lease without recycling, and do
                // not touch worker-owned buffers from this thread.
                dropMotionLeases(prev, nextFrame, recycle = false)
                if (generation == streamGeneration) {
                    runOnWorker("async_motion_fallback") { processFramePair(prev, nextFrame, prepared) }
                }
                return@execute
            }
            val posted = handler.post {
                // Motion done. Enqueue for rendering. Leases are kept until after render completes.
                val work = InFlightWork(prev, nextFrame, prepared, generation, generateTimestamps(prev, nextFrame))
                pendingRenderQueue.addLast(work)

                // Try to render from queue (this will also submit next motion if available)
                renderPendingQueue()
            }
            if (!posted) {
                // The looper only stops quitting after releaseStateOnWorker() drained the
                // pipeline, so this is teardown: drop the lease without recycling.
                dropMotionLeases(prev, nextFrame, recycle = false)
            }
        }
    }

    /**
     * Renders one work item from the pending queue, then schedules the next render
     * on the next worker cycle. This yields the worker between frames, allowing
     * capture and other work to proceed.
     * After each render, submits motion for the next queued frame (if any).
     * Keeps motion executor 100% busy by starting next motion before current render finishes.
     */
    private fun renderPendingQueue() {
        if (released) return
        val work = pendingRenderQueue.removeFirstOrNull() ?: return
        if (work.generation != streamGeneration) {
            // Stream changed since the motion task was submitted; clean up this work. Counted
            // here as well as in the reset itself because a reset only counts what it drained -
            // this item was queued after the drain.
            droppedFrameCount++
            Log.d(TAG, "Dropping work computed against an older stream generation")
            dropMotionLeases(work.prev, work.next, recycle = false)
            releaseFrameBuffer(work.prev.pixels)
            releaseFrameBuffer(work.next.pixels)
            // Schedule next render attempt
            workerHandler?.post { renderPendingQueue() }
            return
        }

        val prev = work.prev
        val next = work.next
        val prepared = work.prepared

        // Submit motion for NEXT pair BEFORE rendering current pair.
        // This keeps motion executor busy continuously.
        // Use 'next' (current pair's second frame) as prev for the next pair, not 'previousFrame'.
        trySubmitNextMotion(next)

        // Render current pair
        processFramePair(prev, next, prepared)

        // Drop motion leases AFTER render completes, so HDR textures stay alive until consumed.
        dropMotionLeases(prev, next)

        // Schedule next render on next worker cycle instead of recursing
        if (pendingRenderQueue.isNotEmpty()) {
            workerHandler?.post { renderPendingQueue() }
        }
    }

    /** Tries to submit motion for the next available frame pair. Does nothing if no pair ready. */
    private fun trySubmitNextMotion(prevForNextPair: FrameData) {
        if (released) return
        val nextFrame = frameQueue.peek() ?: return
        val prev = prevForNextPair

        val canPrepareMotionOffThread = isRifeEnabled &&
            !fastDvdNetEngine.isEnabled &&
            prev.width == nextFrame.width && prev.height == nextFrame.height &&
            outputRenderer?.isWarpInitialized == true
        if (!canPrepareMotionOffThread) return

        val generation = streamGeneration
        leaseForMotion(prev.pixels)
        leaseForMotion(nextFrame.pixels)
        motionExecutor.execute {
            val prepared = computeMotionFieldOffThread(prev, nextFrame)
            val handler = workerHandler
            if (handler == null) {
                // See the sibling submit site: teardown, so drop without recycling.
                dropMotionLeases(prev, nextFrame, recycle = false)
                if (generation == streamGeneration) {
                    runOnWorker("async_motion_fallback") { processFramePair(prev, nextFrame, prepared) }
                }
                return@execute
            }
            val posted = handler.post {
                val work = InFlightWork(prev, nextFrame, prepared, generation, generateTimestamps(prev, nextFrame))
                pendingRenderQueue.addLast(work)
                // Note: we do NOT call renderPendingQueue() here to avoid re-entrancy.
                // The current render will call it when it finishes.
            }
            if (!posted) {
                // See the sibling submit site: teardown, so drop without recycling.
                dropMotionLeases(prev, nextFrame, recycle = false)
            }
        }
        // Remove the pair from queue now that motion is committed
        frameQueue.poll()
    }

    private fun computeMotionFieldOffThread(prev: FrameData, nextFrame: FrameData): PreparedMotion {
        val started = System.nanoTime()
        return try {
            val step = NativeEngine.motionFieldStep().coerceAtLeast(1)
            val gridW = (nextFrame.width + step - 1) / step
            val gridH = (nextFrame.height + step - 1) / step
            val bytes = gridW.toLong() * gridH.toLong() * 8L
            if (bytes <= 0L || bytes > Int.MAX_VALUE) {
                PreparedMotion(null, false, System.nanoTime() - started)
            } else {
                val field = ByteBuffer.allocateDirect(bytes.toInt())
                val ready = NativeEngine.computeMotionField(
                    prev.pixels, nextFrame.pixels,
                    nextFrame.width, nextFrame.height,
                    nextFrame.width, nextFrame.height,
                    field,
                    forwardOnly = false,
                )
                PreparedMotion(field.takeIf { ready }, ready, System.nanoTime() - started)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Asynchronous motion estimation failed", t)
            PreparedMotion(null, false, System.nanoTime() - started)
        }
    }

    private fun generateTimestamps(prev: FrameData, next: FrameData): LongArray {
        val pairSpanUs = next.timestampUs - prev.timestampUs
        val exactThreeTimes = memcLevelMultiplier == 3f &&
            pairSpanUs in MIN_PAIR_INTERVAL_US..MAX_PAIR_INTERVAL_US
        val count = (memcLevelMultiplier).toInt()
        return LongArray(count) { i ->
            if (exactThreeTimes && i < 2) {
                prev.timestampUs * 1000L + (pairSpanUs * 1000L * (i + 1)) / 3L
            } else {
                prev.timestampUs * 1000L +
                    ((i + 1).toDouble() / (count + 1).toDouble() * pairSpanUs * 1000.0).toLong()
            }
        }
    }

    private fun processFramePair(
        prev: FrameData?,
        nextFrame: FrameData,
        preparedMotion: PreparedMotion?
    ) {

        if (prev == null) {
            // First frame: log checksum before rendering
            if (VERBOSE_DIAGNOSTICS) {
                val firstFrameChecksum =
                    calculateChecksum(nextFrame.pixels, nextFrame.width, nextFrame.height)
                Log.d(TAG, "PIPELINE CHECKSUM: firstFrame ${nextFrame.width}x${nextFrame.height} checksum=$firstFrameChecksum")
            }
            renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
            frameCountOutput++
            previousFrame = nextFrame
            updateStats()
            return
        }

        if (prev.width != nextFrame.width || prev.height != nextFrame.height) {
            // The resolution changed underneath us: restart the pair instead of feeding the
            // interpolator mismatched buffers.
            Log.i(TAG, "Frame size changed, restarting the interpolation pair")
            releaseFrameBuffer(prev.pixels)
            renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
            frameCountOutput++
            previousFrame = nextFrame
            updateStats()
            return
        }

        // Source cadence, straight from the decoder timestamps of the pair being processed.
        // Rejected outside [1ms, 1s] so a seek cannot set a nonsense one.
        val srcIntervalUs = nextFrame.timestampUs - prev.timestampUs
        if (srcIntervalUs in 1_000L..1_000_000L) {
            lastPairIntervalUs = srcIntervalUs
        }

        val rifeInputW = nextFrame.width
        val rifeInputH = nextFrame.height
        val rifeOutputW = rifeInputW
        val rifeOutputH = rifeInputH

        val requiredInputBytes = rifeInputW.toLong() * rifeInputH.toLong() * 4L

        // The JNI layer never checks the output capacity: interpolateFrameBuffers() writes
        // rifeOutputW * rifeOutputH * 4 bytes straight through the raw pointer that
        // GetDirectBufferAddress() hands it. The Java side therefore has to guarantee that
        // capacity itself.
        val requiredOutputBytes = rifeOutputW.toLong() * rifeOutputH.toLong() * 4L

        if (!ensureCachedBuffers(requiredInputBytes, requiredOutputBytes, rifeInputW, rifeInputH)) {
            releaseFrameBuffer(prev.pixels)
            renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
            previousFrame = nextFrame
            // Still a completed cycle: without these two the bypass blanks [PIPELINE] entirely
            // and freezes the overlay, which is exactly what makes it invisible in a log.
            reportStageTiming()
            updateStats()
            return
        }

        // Present only while the denoiser is on; ensureCachedBuffers() above just allocated or
        // trimmed them for this exact cycle, and the toggles are switched on the worker so they
        // cannot move underneath this cycle. Both consumers below still check for null so a
        // missing scratch buffer degrades to the undenoised frame instead of throwing.
        val den0Buf = cachedDenoised0Buf
        val den1Buf = cachedDenoised1Buf
        val outBuf = cachedOutBuf!!

        prev.pixels.clear()
        nextFrame.pixels.clear()

        // The readback leaves every pooled frame at position 0 with limit = width*height*4, and
        // this cycle's stage reads exactly that many bytes from offset 0, so the pixels can be
        // handed over where they are instead of being memcpy'd into a second buffer. At 1080p that
        // is 16 MB of copying per cycle, roughly 7 ms of it, for data that is already contiguous
        // and already has the right readable range.
        //
        // Only the previous frame can disagree about the shape: the AUTO policy is allowed to step
        // the capture size down between one frame and the next, and a frame on the wrong side of
        // that step is still the old size. When that happens the copy runs as before, but clipped
        // to what both buffers hold - unclipped it would overflow as soon as the source frame was
        // larger than the destination, which is exactly the case the size change produces.
        val tCopyStart = System.nanoTime()
        val frameBytes = (rifeInputW.toLong() * rifeInputH.toLong() * 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
        var in0Buf: ByteBuffer
        val prevPixels = prev.pixels
        val prevBytes = (prev.width.toLong() * prev.height.toLong() * 4L)
            .coerceAtMost(prevPixels.capacity().toLong())
        if (prev.width == rifeInputW && prev.height == rifeInputH &&
            prevBytes >= frameBytes
        ) {
            in0Buf = prevPixels
            in0Buf.position(0)
            in0Buf.limit(frameBytes.toInt())
        } else {
            in0Buf = cachedIn0Buf!!
            in0Buf.clear()
            val savedPrevLimit = prevPixels.limit()
            prevPixels.limit(minOf(prevBytes, in0Buf.remaining().toLong()).toInt())
            in0Buf.put(prevPixels)
            prevPixels.limit(savedPrevLimit)
            in0Buf.flip()
        }
        val in1Buf = nextFrame.pixels
        in1Buf.position(0)
        in1Buf.limit(frameBytes.toInt())
        nsCopy += System.nanoTime() - tCopyStart

        // DIAGNOSTICS: Log checksum of frames before FastDVDnet/interpolation
        var prevChecksum = 0L
        var nextChecksum = 0L
        if (VERBOSE_DIAGNOSTICS) {
            val tChecksumStart = System.nanoTime()
            prevChecksum = calculateChecksum(in0Buf, rifeInputW, rifeInputH)
            nextChecksum = calculateChecksum(in1Buf, rifeInputW, rifeInputH)
            nsChecksum += System.nanoTime() - tChecksumStart
            Log.d(TAG, "PIPELINE CHECKSUM: prevFrame ${rifeInputW}x${rifeInputH} checksum=$prevChecksum nextFrame checksum=$nextChecksum")
        }

        val startTime = SystemClock.elapsedRealtime()

        if (!isRifeEnabled) {
            // State 2: denoise-only. The current frame is cleaned and rendered as-is; no
            // interpolation is attempted and no extra frame is invented.
            if (fastDvdNetEngine.isEnabled && den1Buf != null) {
                // Nothing else on this path asks for the field, but the denoiser's whole premise
                // is that it samples the history where this pair's content moved to, so it computes
                // one here. computeMotionField() reports success for the block-matching search, so
                // the denoiser always gets a real field.
                val motionBuf = cachedMotionBuf
                var presented = false
                if (motionBuf != null && outputRenderer?.isDenoiseInitialized == true) {
                    val tFieldStart = System.nanoTime()
                    val fieldReady = NativeEngine.computeMotionField(
                        in0Buf, in1Buf,
                        rifeInputW, rifeInputH,
                        rifeInputW, rifeInputH,
                        motionBuf,
                        forwardOnly = true,
                    )
                    val fieldNs = System.nanoTime() - tFieldStart
                    nsMotion += fieldNs
                    nsJni += fieldNs
                    if (fieldReady) {
                        val tRenderStart = System.nanoTime()
                        outputRenderer?.isHdr = isHdrSource()
                        presented = outputRenderer?.renderDenoise(in1Buf, motionBuf, rifeInputW, rifeInputH) == true &&
                            outputRenderer?.presentDenoised(
                                rifeInputW, rifeInputH, nextFrame.timestampUs * 1000L
                            ) == true
                        nsRender += System.nanoTime() - tRenderStart
                    }
                }
                if (presented) {
                    lastProcTimeMs = SystemClock.elapsedRealtime() - startTime
                    frameCountOutput++
                    releaseFrameBuffer(prev.pixels)
                    previousFrame = nextFrame
                    updateStats()
                    // The report is reached only through the interpolation branch below; without
                    // a call here this state never emits PIPELINE TIMING and its cost stays invisible.
                    reportStageTiming()
                    return
                }
                noteDenoiseUnavailable()
                // Same refusal as the interpolation path below: the CPU denoiser works in 8-bit
                // and would destroy the precision this capture exists to keep. Reporting false
                // takes the branch that presents the retained high-precision texture instead.
                val denoised = if (isHdrSource()) {
                    false
                } else {
                    // Offload CPU denoise to separate executor
                    val denoiseFuture = denoiseExecutor.submit {
                        fastDvdNetEngine.denoiseFrameBuffer(nextFrame.pixels, rifeInputW, rifeInputH, den1Buf)
                    }
                    denoiseFuture.get() as Boolean
                }
                if (denoised) {
                    // DIAGNOSTICS: Log checksum after FastDVDnet pass-through
                    if (VERBOSE_DIAGNOSTICS) {
                        val fastDvdNetChecksum = calculateChecksum(den1Buf, rifeInputW, rifeInputH)
                        Log.d(TAG, "PIPELINE CHECKSUM: after FastDVDnet ${rifeInputW}x${rifeInputH} checksum=$fastDvdNetChecksum (unchanged=${fastDvdNetChecksum == nextChecksum})")
                    }
                    renderBufferToOutput(den1Buf, rifeInputW, rifeInputH, nextFrame.timestampUs * 1000L)
                } else {
                    renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
                }
            } else {
                // The denoiser was switched off between capture and processing, or its scratch
                // buffer could not be kept: forward the frame instead of leaving a stale picture
                // on the output surface.
                renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
            }
            lastProcTimeMs = SystemClock.elapsedRealtime() - startTime
            frameCountOutput++
            releaseFrameBuffer(prev.pixels)
            previousFrame = nextFrame
            updateStats()
            reportStageTiming()
            return
        }

        // State 3 and 4: the FastDVDnet scaffold is optional pre-processing in front of the interpolator. When
        // it is off the captured buffers are handed to JNI directly, so no extra copy is made.
        //
        // Both implementations are this same stage, so only one runs. The motion-aligned pass is
        // preferred - it needs the field anyway, and its history is what makes it a real denoiser
        // rather than a per-frame filter - and is picked here on shader availability alone. If it
        // turns out below that no field can be had, this path is still there rather than the frame
        // silently going out undenoised.
        var src0Buf = in0Buf
        var src1Buf = in1Buf
        val gpuDenoiseWanted = !isHdrSource() && fastDvdNetEngine.isEnabled &&
            outputRenderer?.isDenoiseInitialized == true
        // Skip CPU denoise for HDR sources: denoise operates in 8-bit and destroys HDR precision.
        if (fastDvdNetEngine.isEnabled && !gpuDenoiseWanted && !isHdrSource() &&
            den0Buf != null && den1Buf != null
        ) {
            // Offload CPU denoise to separate executor to keep worker free for capture/render
            val denoiseFuture = denoiseExecutor.submit {
                val denoisedPrev = fastDvdNetEngine.denoiseFrameBuffer(in0Buf, rifeInputW, rifeInputH, den0Buf)
                val denoisedNext = fastDvdNetEngine.denoiseFrameBuffer(in1Buf, rifeInputW, rifeInputH, den1Buf)
                Pair(denoisedPrev, denoisedNext)
            }
            // Wait for denoise to complete (this is a blocking wait, but on the worker thread
            // we can yield to other tasks. For true async, we'd need callback-based continuation,
            // but this already moves the heavy CPU work off the worker thread during the wait.)
            val result = denoiseFuture.get() as Pair<Boolean, Boolean>
            val (denoisedPrev, denoisedNext) = result
            if (denoisedPrev && denoisedNext) {
                src0Buf = den0Buf
                src1Buf = den1Buf
                // DIAGNOSTICS: Log checksum after FastDVDnet
                if (VERBOSE_DIAGNOSTICS) {
                    val den0Checksum = calculateChecksum(den0Buf, rifeInputW, rifeInputH)
                    val den1Checksum = calculateChecksum(den1Buf, rifeInputW, rifeInputH)
                    Log.d(TAG, "PIPELINE CHECKSUM: after FastDVDnet den0 checksum=$den0Checksum den1 checksum=$den1Checksum")
                }
            } else {
                Log.w(TAG, "FastDVDnet stage failed, interpolating the raw frames")
            }
        }

        // DIAGNOSTICS: Log checksum before the interpolation JNI call
        if (VERBOSE_DIAGNOSTICS) {
            val tChecksumStart = System.nanoTime()
            val src0Checksum = calculateChecksum(src0Buf, rifeInputW, rifeInputH)
            val src1Checksum = calculateChecksum(src1Buf, rifeInputW, rifeInputH)
            nsChecksum += System.nanoTime() - tChecksumStart
            Log.d(TAG, "PIPELINE CHECKSUM: before interpolation src0 checksum=$src0Checksum src1 checksum=$src1Checksum")
        }

        if (rifeInputW != lastDimsLogW || rifeInputH != lastDimsLogH) {
            lastDimsLogW = rifeInputW
            lastDimsLogH = rifeInputH
            Log.i(
                TAG,
                "REAL INTERPOLATION LOG: inputDimensions=${rifeInputW}x$rifeInputH -> " +
                    "outputDimensions=${rifeOutputW}x$rifeOutputH -> " +
                    "renderingSurfaceDimensions=${displaySurfaceWidth}x$displaySurfaceHeight"
            )
        }

        // The output buffer is deliberately NOT flipped here. NativeEngine.interpolateFrameBuffers()
        // reaches the memory through JNI GetDirectBufferAddress() and writes into it directly, so
        // the Java position stays at 0 and a flip() would only publish limit = 0.
        outBuf.clear()

        // GPU warp: motion estimation still runs on the CPU, because that is what the luma pyramid
        // and the SAD search are, but the per-pixel resample moves into the fragment shader. What
        // crosses JNI is then the packed field - ceil(w/step) * ceil(h/step) * 8 bytes, tens of
        // kB - where step is NativeEngine.motionFieldStep(), not necessarily 16: SVPlayer's
        // overlap setting shrinks the grid pitch so its search windows overlap each other.
        // computeMotionField() reports false when the search produces no field at all, in which
        // case the CPU warp further down takes over.
        val motionBuf = preparedMotion?.field ?: cachedMotionBuf

        // The emission schedule has to be known before the engine call, not after it. When the
        // warp cannot draw this pair the per-timestep CPU loop further down is the stage that
        // will, and asking that same stage here as well interpolated the pair a second time -
        // two full CPU interpolations per pair at 2x where one was needed, which is where the
        // fallback path spent about half its time.
        val times = outputTimesFor(prev.timestampUs, nextFrame.timestampUs)
        val ownFrame = times.isNotEmpty() && times[times.lastIndex] >= 1f
        val intermediate = if (ownFrame) {
            times.copyOfRange(0, times.size - 1)
        } else {
            times
        }

        var motionReady = preparedMotion?.ready ?: false
        if (preparedMotion == null && motionBuf != null && outputRenderer?.isWarpInitialized == true) {
            val tMotionStart = System.nanoTime()
            motionReady = NativeEngine.computeMotionField(
                src0Buf,
                src1Buf,
                rifeInputW,
                rifeInputH,
                rifeOutputW,
                rifeOutputH,
                motionBuf,
                forwardOnly = false,
            )
            val motionNs = System.nanoTime() - tMotionStart
            nsMotion += motionNs
            nsJni += motionNs
        }
        // The search may already have run on another thread while this pair waited, so its cost
        // is real even though this thread did not spend it. Folding it into the motion total is
        // what stops an off-thread submission from making the motion stage look free.
        preparedMotion?.elapsedNs?.let { elapsed ->
            nsMotion += elapsed
            nsJni += elapsed
        }
        val tInterpStart = System.nanoTime()
        val success = when {
            // The warp path renders every point itself; nothing else has to run.
            motionReady -> true
            // No point to render, so the CPU loop below never runs and this is the stage's only
            // caller. Its result is what decides whether the pair can be presented at all.
            intermediate.isEmpty() -> NativeEngine.interpolateFrameBuffers(
                src0Buf,
                src1Buf,
                rifeInputW,
                rifeInputH,
                rifeOutputW,
                rifeOutputH,
                0.5f,
                outBuf
            )
            // The loop below runs the stage once per point; a probe here would run the pair
            // twice. It reports its own failure rather than the probe reporting it for it.
            else -> true
        }
        val interpNs = System.nanoTime() - tInterpStart
        nsInterp += interpNs
        nsJni += interpNs

        lastProcTimeMs = SystemClock.elapsedRealtime() - startTime

        if (success) {
            // Temporal order: previous frame was already rendered when it was captured (or as the
            // first frame), so this pair only has to emit the moments that fall inside it. At the
            // default 2x level that is the midpoint and the pair's own frame - A, M(A,B), B,
            // M(B,C), C - which is exactly the cadence the pipeline had before the level became a
            // setting. 3x asks for a third point as well, and the phase carried from one pair to
            // the next keeps a ratio that does not divide the source cadence evenly from drifting
            // or from firing a burst of frames at once.
            val tRenderStart = System.nanoTime()

            // One denoiser pass per pair, before any present. It writes into a framebuffer
            // rather than to the surface, so it costs no swap; the warp or the plain present picks
            // the result up from the texture. It sits inside the render window deliberately: the
            // upload and the draw both land in the renderer's own breakdown, so its cost shows up
            // under render= instead of vanishing between the JNI and render counters. The input is
            // the raw capture, because aligning against already-filtered frames would make the flow
            // describe the filter's output, and running the stage's own pass on top would denoise
            // the same frame twice.
            var denoiseReady = false
            outputRenderer?.isHdr = isHdrSource()
            if (gpuDenoiseWanted) {
                denoiseReady = motionReady && motionBuf != null &&
                    outputRenderer?.renderDenoise(
                        src1Buf, motionBuf, rifeInputW, rifeInputH
                    ) == true
                if (!denoiseReady) {
                    noteDenoiseUnavailable()
                }
            }

            // times / ownFrame / intermediate are computed above, before the engine call: they
            // are the schedule this block executes, and outputTimesFor() carries the cadence
            // phase, so calling it twice here would advance the phase by a whole pair.

            // The presentation time each interpolated frame must carry. Without it the encoder
            // reports zero for every access unit and the muxer invents a timeline at the
            // configured frame rate, which is how a 24 fps source at 3x ends up streamed as 60.
            val pairSpanUs = nextFrame.timestampUs - prev.timestampUs
            val exactThreeTimes = memcLevelMultiplier == 3f &&
                pairSpanUs in MIN_PAIR_INTERVAL_US..MAX_PAIR_INTERVAL_US
            val timestamps = LongArray(intermediate.size) { i ->
                if (exactThreeTimes && i < 2) {
                    prev.timestampUs * 1000L + (pairSpanUs * 1000L * (i + 1)) / 3L
                } else {
                    // Keep the large absolute media timestamp out of Float arithmetic. Float
                    // spacing grows with playback time and eventually quantises output PTS.
                    prev.timestampUs * 1000L +
                        (intermediate[i].toDouble() * pairSpanUs * 1000.0).toLong()
                }
            }

            var presented = intermediate.isEmpty()
            // The warp presents every intermediate point in a single call, so nothing inside the
            // renderer knows how many frames it produced. Counting them here is what keeps
            // generatedOutFps honest: without it only the plain present is ever counted, which at
            // 3x means one frame per pair gets attributed and the reported rate reads as a third
            // of the rate actually reaching the surface.
            var warpPresentedPoints = 0
            val hdrFrame0 = hdrTextureByAnalysisBuffer[prev.pixels] ?: 0
            val hdrFrame1 = hdrTextureByAnalysisBuffer[nextFrame.pixels] ?: 0
            val hdrComposition = hdrFrame0 != 0 && hdrFrame1 != 0
            if (!presented && motionReady && motionBuf != null) {
                // From the denoised pair when there is one. The first cycle after a reset has no
                // denoised history to warp from, so it blends the raw pair, and the present below
                // supplies the cleaned current frame - which for that first cycle is the raw frame
                // anyway, so the two halves of the output still agree.
                presented = if (denoiseReady && outputRenderer?.hasDenoisePair == true) {
                    outputRenderer?.renderWarpFromDen(
                        motionBuf,
                        rifeInputW,
                        rifeInputH,
                        rifeOutputW,
                        rifeOutputH,
                        intermediate,
                        timestamps
                    ) == true
                } else if (hdrComposition) {
                    outputRenderer?.renderWarpTextures(
                        hdrFrame0, hdrFrame1, motionBuf,
                        rifeInputW, rifeInputH, rifeOutputW, rifeOutputH,
                        intermediate, timestamps
                    ) == true
                } else {
                    outputRenderer?.renderWarp(
                        src0Buf, src1Buf, motionBuf,
                        rifeInputW, rifeInputH, rifeOutputW, rifeOutputH,
                        intermediate, timestamps
                    ) == true
                }
                if (presented) warpPresentedPoints = intermediate.size
            }
            submittedOutputFrameCount += warpPresentedPoints
            if (!presented) {
                if (hdrComposition) {
                    // The 8-bit readback is analysis-only. If GPU composition fails, skip these
                    // intermediate frames and present the retained HDR source at the pair time.
                    Log.w(TAG, "HDR GPU composition failed; presenting retained source frame")
                    renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
                    presented = true
                }
            }
            if (!presented) {
                // The shader refused the frame, or the CPU path ran. Redo it on the CPU so the
                // pair still produces a picture: a failure here costs time, never a frame.
                // Every point gets its own CPU interpolation: `success` only ever filled outBuf
                // for the one timestep the GPU path was asked about, so reusing it would put the
                // same midpoint on screen more than once at a level above 2x.
                if (!warnedGpuWarpFallback) {
                    warnedGpuWarpFallback = true
                    Log.w(
                        TAG,
                        "GPU warp refused the pair (motionReady=$motionReady " +
                            "timesteps=${intermediate.size}); falling back to the CPU " +
                            "interpolator, which resamples per block and will show as blocks"
                    )
                }
                var renderedPoints = 0
                for (i in intermediate.indices) {
                    val t = intermediate[i]
                    val tFallbackInterp = System.nanoTime()
                    val ok = NativeEngine.interpolateFrameBuffers(
                        src0Buf,
                        src1Buf,
                        rifeInputW,
                        rifeInputH,
                        rifeOutputW,
                        rifeOutputH,
                        t,
                        outBuf
                    )
                    val fallbackNs = System.nanoTime() - tFallbackInterp
                    nsInterp += fallbackNs
                    nsJni += fallbackNs
                    if (!ok) {
                        continue
                    }
                    // Native wrote requiredOutputBytes of RGBA through the direct address without
                    // touching the Java position, so the readable range is established here rather
                    // than with flip(): position 0, limit = requiredOutputBytes.
                    outBuf.position(0)
                    outBuf.limit(requiredOutputBytes.toInt())

                    if (VERBOSE_DIAGNOSTICS) {
                        val tChecksumStart = System.nanoTime()
                        val rifeOutputChecksum = calculateChecksum(outBuf, rifeOutputW, rifeOutputH)
                        nsChecksum += System.nanoTime() - tChecksumStart
                        Log.d(TAG, "PIPELINE CHECKSUM: after interpolation ${rifeOutputW}x$rifeOutputH checksum=$rifeOutputChecksum")
                    }

                    renderBufferToOutput(outBuf, rifeOutputW, rifeOutputH, timestamps[i])
                    renderedPoints++
                }
                if (renderedPoints == 0) {
                    // On this path the loop is the stage's only caller, so nothing else can
                    // report a refusal: surface it and put the untouched capture on screen
                    // rather than whatever the output buffer happened to hold.
                    reportError("Frame interpolation failed: the native interpolator refused the pair")
                    renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
                }
            }

            // The pair's own frame is the point of the sequence, so it leaves through the plain
            // present - the denoised one when there is a denoised pair, otherwise the raw capture
            // - rather than paying for a warp that would only resample it onto itself.
            if (ownFrame) {
                if (denoiseReady &&
                    outputRenderer?.presentDenoised(
                        rifeInputW, rifeInputH, nextFrame.timestampUs * 1000L
                    ) == true
                ) {
                    // already on the surface
                } else {
                    renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
                }
            }
            nsRender += System.nanoTime() - tRenderStart
            frameCountOutput += times.size
        } else {
            reportError("Frame interpolation failed: the native interpolator refused the pair")
            val tRenderStart = System.nanoTime()
            renderFrameToOutput(nextFrame, nextFrame.timestampUs * 1000L)
            nsRender += System.nanoTime() - tRenderStart
            frameCountOutput++
        }

        releaseFrameBuffer(prev.pixels)
        previousFrame = nextFrame

        reportStageTiming()
        updateStats()
    }

    /**
     * Accumulates one capture-to-render cycle and, every [TIMING_WINDOW_FRAMES] of them, emits a
     * single averaged line. The window covers readback (including any buffer-pool allocation),
     * the input copies, the optional checksum passes, the JNI interpolation and both output
     * uploads/swaps, so `total` is directly comparable to the frame interval the decoder sees.
     */
    /**
     * The moments inside one frame pair that the current interpolation level asks the pipeline to emit,
     * expressed as a timestep in [0, 1] where 0 is the previous frame and 1 is this one.
     *
     * The step is the pair's own timestamp interval divided by the multiplier, so the cadence
     * follows the source rather than a nominal frame rate. Non-integer ratios carry phase in
     * absolute source time so they average out instead of wobbling; exact 3x pins two samples to
     * each pair's thirds. Re-anchoring outside a valid pair stops a seek, stream change or skipped
     * pair burst from firing a backlog of frames in one go.
     *
     * The range is open at the previous frame, because that frame was already presented - either
     * as the first frame of the stream or as the last point of the pair before this one - so the
     * sequence never repeats a frame.
     */
    private fun outputTimesFor(prevUs: Long, nextUs: Long): FloatArray {
        val realGapUs = nextUs - prevUs
        val intervalUs = realGapUs.coerceIn(MIN_PAIR_INTERVAL_US, MAX_PAIR_INTERVAL_US)
        // A gap that had to be clamped is a discontinuity rather than a frame pair: a stall, a
        // backwards timestamp or a jump. Replaying the whole gap would fire a burst of frames in
        // one go, so the cadence restarts from the end of it and the window is measured from
        // there instead of from the frame on the far side of the jump.
        val discontinuity = realGapUs != intervalUs
        val baseUs = if (discontinuity) nextUs - intervalUs else prevUs
        val ratio = memcLevelMultiplier.toDouble().coerceIn(MIN_INTERPOLATION_RATIO, MAX_INTERPOLATION_RATIO)
        val stepUs = maxOf(1L, (intervalUs / ratio).toLong())

        // At exactly 3x, each source pair owns exactly two synthesized moments. Pin them to
        // pair-relative thirds instead of carrying a wall-clock phase across variable frame
        // intervals; otherwise the pair can emit unevenly spaced samples or miss its endpoint.
        if (ratio == 3.0 && !discontinuity) {
            nextOutputUs = Long.MIN_VALUE
            return floatArrayOf(1f / 3f, 2f / 3f, 1f)
        }

        // Re-anchor when the pair is not a pair, when there is no phase yet, or when the phase has
        // run more than the widest supported multiplier away from where it should be.
        if (discontinuity ||
            nextOutputUs == Long.MIN_VALUE ||
            nextOutputUs <= baseUs ||
            nextOutputUs > baseUs + (MAX_INTERPOLATION_RATIO * intervalUs).toLong()
        ) {
            nextOutputUs = baseUs + stepUs
        }

        var count = 0
        var cursor = nextOutputUs
        while (cursor <= nextUs && count < MAX_OUTPUTS_PER_PAIR) {
            count++
            cursor += stepUs
        }
        if (count == 0) {
            // Nothing fits inside the pair, so the phase is past it. Present the pair's own frame
            // rather than dropping the picture for a cycle.
            nextOutputUs = nextUs
            return floatArrayOf(1f)
        }
        val times = FloatArray(count)
        cursor = nextOutputUs
        for (i in 0 until count) {
            var t = ((cursor - baseUs).toDouble() / intervalUs.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
            // The last point of a pair is the pair's own frame whenever it lands close enough to
            // the end of the pair to be indistinguishable from it - which is always the case for
            // a whole-number ratio, up to the rounding of the step. Snapping it to exactly one is
            // what lets the present hand the real frame over instead of paying for a warp that
            // would only resample it onto itself.
            if (i == count - 1 && (nextUs - cursor).toFloat() * OWN_FRAME_SNAP <= intervalUs.toFloat()) {
                t = 1f
            }
            times[i] = t
            cursor += stepUs
        }
        nextOutputUs = cursor
        return times
    }

    private fun reportStageTiming() {
        if (timingStartNs == 0L) {
            return
        }
        nsPair += System.nanoTime() - timingStartNs
        timingStartNs = 0L
        timingCycles++
        if (timingCycles < TIMING_WINDOW_FRAMES) {
            return
        }

        val n = timingCycles.toDouble()
        // Fold in the renderer's own per-phase split so `render` stops being a black box.
        outputRenderer?.takeRenderBreakdown(renderBreakdown)
        nsRenderCurrent += renderBreakdown[0]
        nsRenderSetup += renderBreakdown[1]
        nsRenderUpload += renderBreakdown[2]
        nsRenderDraw += renderBreakdown[3]
        nsRenderSwap += renderBreakdown[4]
        val renderCalls = renderBreakdown[5]
        val dropped = droppedFrameCount - droppedAtWindowStart
        val captured = frameCountInput - capturedAtWindowStart

        Log.i(
            TAG,
            "PIPELINE TIMING: n=$timingCycles " +
                "readback=${fmtMs(nsReadback / n)} " +
                "poll=${fmtMs(nsPoll / n)} " +
                "copy=${fmtMs(nsCopy / n)} " +
                "checksum=${fmtMs(nsChecksum / n)} " +
                "jni=${fmtMs(nsJni / n)} " +
                "motion=${fmtMs(nsMotion / n)} interp=${fmtMs(nsInterp / n)} " +
                "render=${fmtMs(nsRender / n)} " +
                "total=${fmtMs(nsPair / n)} ms/cycle | " +
                "renderSplit calls=$renderCalls cur=${fmtMs(nsRenderCurrent / n)} " +
                "st=${fmtMs(nsRenderSetup / n)} up=${fmtMs(nsRenderUpload / n)} " +
                "dr=${fmtMs(nsRenderDraw / n)} sw=${fmtMs(nsRenderSwap / n)} | " +
                "captured=$captured dropped=$dropped " +
                "in=${frameCountInput - capturedAtWindowStart} " +
                "out=${frameCountOutput - outputAtWindowStart} rife=$isRifeEnabled"
        )
        val avgQueueLatencyMs = if (queueLatencySamples > 0) nsQueueLatency / queueLatencySamples else 0L
        val sourceFps = if (lastPairIntervalUs > 0L) 1_000_000.0 / lastPairIntervalUs else 0.0
        val windowSeconds = ((System.nanoTime() - diagnosticWindowStartNs).coerceAtLeast(1L)) / 1_000_000_000.0
        val generatedFps = (submittedOutputFrameCount - diagnosticOutputStartCount) / windowSeconds
        val droppedOutput = droppedOutputFrameCount - droppedOutputAtWindowStart
        // Swap split: while an encode runs the primary window surface is the encoder's input
        // surface and the mirror is the phone preview; without an encode the primary swap is the
        // phone preview itself. Drained (and reset) once per window so the rates cannot race.
        val swaps = outputRenderer?.takeSwapCounts() ?: longArrayOf(0L, 0L)
        val arrivalRate = (frameCountArrival - arrivalAtWindowStart) / windowSeconds
        val encodeActive = encodeSurface != null
        val previewFps = (if (encodeActive) swaps[1] else swaps[0]) / windowSeconds
        val encoderFps = if (encodeActive) swaps[0] / windowSeconds else 0.0
        val f1 = { v: Double -> String.format(java.util.Locale.US, "%.1f", v) }
        Log.i(TAG, "[PIPELINE] src=${inputWidth}x$inputHeight capture=${previousFrame?.width ?: 0}x${previousFrame?.height ?: 0} srcFps=${String.format(java.util.Locale.US, "%.3f", sourceFps)} requestedOutFps=${String.format(java.util.Locale.US, "%.3f", sourceFps * memcLevelMultiplier)} generatedOutFps=${String.format(java.util.Locale.US, "%.3f", generatedFps)} decoderArrivalFps=${f1(arrivalRate)} previewFps=${f1(previewFps)} encoderFps=${f1(encoderFps)} pairMs=${fmtMs(nsPair / n)} readbackMs=${fmtMs(nsReadback / n)} pollMs=${fmtMs(nsPoll / n)} motionMs=${fmtMs(nsMotion / n)} interpMs=${fmtMs(nsInterp / n)} jniMs=${fmtMs(nsJni / n)} renderMs=${fmtMs(nsRender / n)} encoderMs=${fmtMs(nsRenderSwap / n)} droppedIn=$dropped droppedOut=$droppedOutput queue=${frameQueue.size} queueLatencyMs=$avgQueueLatencyMs burstSubmission=${memcLevelMultiplier > 1f} rendererCalls=${renderCalls.toInt()}${if (bypassTargetSize != 0) " alloc=bypass:$bypassTargetSize" else if (rejectedTargetSize != 0) " alloc=reject:$rejectedTargetSize" else ""}")
        // Scheduling evidence rather than stage cost: how long the worker actually went quiet
        // between callbacks, how regularly the decoder delivered, and how much wall clock each
        // output frame was entitled to. A stage time that fits its budget with room to spare
        // while the gap dominates is a scheduling problem, and a stage time that exceeds the
        // budget on its own is not one.
        val gapSamples = workerGapSamples.coerceAtLeast(1)
        val decodeSamples = decodeIntervalCount.coerceAtLeast(1)
        val decodeIntervalMs = nsDecodeInterval / decodeSamples / 1_000_000.0
        val frameBudgetMs = if (sourceFps > 0.0) 1000.0 / (sourceFps * memcLevelMultiplier) else 0.0
        val pairPerCycleMs = (nsPair / n) / 1_000_000.0
        Log.i(
            TAG,
            "[SCHED] workerGapMs=${fmtMs((nsWorkerGap / gapSamples).toDouble())} " +
                "workerGapMaxMs=${fmtMs(maxWorkerGapNs.toDouble())} " +
                "workerCallbacks=$workerGapSamples " +
                "decoderIntervalMs=${String.format(java.util.Locale.US, "%.2f", decodeIntervalMs)} " +
                "decoderIntervalMaxMs=${fmtMs(maxDecodeIntervalNs.toDouble())} " +
                "decoderSamples=$decodeIntervalCount " +
                "frameBudgetMs=${String.format(java.util.Locale.US, "%.2f", frameBudgetMs)} " +
                "pairMs=${String.format(java.util.Locale.US, "%.2f", pairPerCycleMs)} " +
                "overBudget=${pairPerCycleMs > frameBudgetMs && frameBudgetMs > 0.0} " +
                "queue=${frameQueue.size} readback=${frameGrabber?.readbackState() ?: "none"}"
        )
        
        // HDR diagnostic line
        if (isHdrSource()) {
            val hdrLeases = frameGrabber?.readbackState() ?: "unknown"
            val hdrRetained = hdrTextureByAnalysisBuffer.size
            val hdrGrabber = frameGrabber
            val hdrSlots = hdrGrabber?.hdrSourceSlots?.size ?: 0
            val retainedW = processingWidth
            val retainedH = processingHeight
            val estMem = hdrSlots.toLong() * (retainedW.toLong() * retainedH * 8L)
            Log.i(TAG, "[HDR] source=${inputWidth}x$inputHeight processing=${retainedW}x$retainedH retained=$hdrRetained leases=$hdrLeases estimatedMemory=${estMem / 1024 / 1024}MB status=active")
        }

        timingCycles = 0
        diagnosticWindowStartNs = System.nanoTime()
        diagnosticOutputStartCount = submittedOutputFrameCount
        droppedOutputAtWindowStart = droppedOutputFrameCount
        nsReadback = 0
        nsPoll = 0
        nsCopy = 0
        nsChecksum = 0
        nsJni = 0
        nsMotion = 0
        nsInterp = 0
        nsRender = 0
        nsRenderCurrent = 0
        nsRenderSetup = 0
        nsRenderUpload = 0
        nsRenderDraw = 0
        nsRenderSwap = 0
        nsPair = 0
        nsQueueLatency = 0
        queueLatencySamples = 0
        // The gap counters are per-window but their anchors (lastCallbackExitNs, lastArrivalNs)
        // deliberately are not: resetting an anchor would turn the first sample of the next
        // window into a garbage interval spanning the whole report.
        nsWorkerGap = 0
        workerGapSamples = 0
        maxWorkerGapNs = 0
        nsDecodeInterval = 0
        decodeIntervalCount = 0
        maxDecodeIntervalNs = 0
        capturedAtWindowStart = frameCountInput
        outputAtWindowStart = frameCountOutput
        arrivalAtWindowStart = frameCountArrival
        droppedAtWindowStart = droppedFrameCount

        // Periodic safety net: a wipe between two resets would otherwise go unnoticed until the
        // next surface change, and the decoder's answer is only available once frames are flowing.
        reassertOutputDataSpace("stats")
    }

    private fun fmtMs(nsPerCycle: Double): String =
        String.format(java.util.Locale.US, "%.1f", nsPerCycle / 1_000_000.0)

    /**
     * Allocates the reusable direct buffers used by the JNI stage, keeping one allocation per size
     * change instead of one per frame.
     */
    private fun ensureCachedBuffers(
        requiredInputBytes: Long,
        requiredOutputBytes: Long,
        inputWidth: Int,
        inputHeight: Int
    ): Boolean {
        if (requiredInputBytes <= 0 || requiredOutputBytes <= 0) {
            return false
        }
        val requiredBytes = maxOf(requiredInputBytes, requiredOutputBytes)
        if (requiredBytes > Int.MAX_VALUE) {
            Log.e(TAG, "ensureCachedBuffers: required size $requiredBytes overflows Int")
            return false
        }
        val requiredBytesInt = requiredBytes.toInt()
        // Two distinct refusals, and only one of them may poison capture. `rejectedTargetSize`
        // makes obtainFrameBuffer return a zero-capacity buffer, which stops the read-back
        // entirely; it is set only by a genuine capture allocation failure. `bypassTargetSize`
        // only skips the interpolation call for this pair while video keeps flowing.
        expireAllocationRefusals()
        if (bypassTargetSize == requiredBytesInt || rejectedTargetSize == requiredBytesInt) return false
        val gridStep = NativeEngine.motionFieldStep()
        val gridW = (inputWidth + gridStep - 1) / gridStep
        val gridH = (inputHeight + gridStep - 1) / gridStep
        // Eight bytes per block: four for the vectors, then two cover/uncover masks, the denoiser's
        // blend weight and its noise floor. Mirrors MemcInterpolator::motionFieldBytes(), which the
        // JNI side re-checks against the buffer capacity before it writes anything.
        val motionBytes = gridW.toLong() * gridH.toLong() * 8L
        // Denoiser scratch exists only while the denoiser is on, so it must not be counted while
        // it is off. The previous figure of "5 + 8" counted every capture buffer the queue could
        // ever have held plus five working buffers and still never matched what is actually live;
        // this one is the real peak below.
        val liveFrames = if (fastDvdNetEngine.isEnabled) {
            LIVE_CAPTURE_FRAMES_DENOISED
        } else {
            LIVE_CAPTURE_FRAMES
        }
        val estimatedMemory = requiredBytes * liveFrames + motionBytes
        if (estimatedMemory > FRAME_MEMORY_BUDGET_BYTES) {
            bypassTargetSize = requiredBytesInt
            // A property of the size, not of the moment: no deadline, so it is not retried.
            bypassRetryAfterNs = 0L
            Log.e(
                TAG,
                "Interpolation skipped at ${inputWidth}x${inputHeight}: live frame set needs $estimatedMemory bytes " +
                    "over the $FRAME_MEMORY_BUDGET_BYTES byte budget; frames pass through uninterpolated"
            )
            if (bypassReportedForSize != requiredBytesInt) {
                bypassReportedForSize = requiredBytesInt
                reportError(
                    "Interpolation needs more memory than available at ${inputWidth}x${inputHeight}; " +
                        "playing through without interpolation"
                )
            }
            return false
        }
        if (motionBytes > Int.MAX_VALUE) {
            Log.e(TAG, "ensureCachedBuffers: motion field $motionBytes overflows Int")
            return false
        }
        // Read into a local: a mutable property can never be smart-cast across the null check.
        val motionCapacity = cachedMotionBuf?.capacity() ?: 0
        val denoiseOn = fastDvdNetEngine.isEnabled
        val needsDenoiseScratch = denoiseOn && (cachedDenoised0Buf == null || cachedDenoised1Buf == null)
        if (cachedIn0Buf == null || cachedIn1Buf == null || cachedOutBuf == null ||
            motionCapacity < motionBytes ||
            cachedTargetSize != requiredBytesInt ||
            needsDenoiseScratch
        ) {
            try {
                val in0 = ByteBuffer.allocateDirect(requiredBytesInt)
                val in1 = ByteBuffer.allocateDirect(requiredBytesInt)
                val den0 = if (denoiseOn) ByteBuffer.allocateDirect(requiredBytesInt) else null
                val den1 = if (denoiseOn) ByteBuffer.allocateDirect(requiredBytesInt) else null
                val out = ByteBuffer.allocateDirect(requiredBytesInt)
                val motion = ByteBuffer.allocateDirect(motionBytes.toInt())
                cachedIn0Buf = in0; cachedIn1Buf = in1
                cachedDenoised0Buf = den0; cachedDenoised1Buf = den1
                cachedOutBuf = out; cachedMotionBuf = motion
            } catch (oom: OutOfMemoryError) {
                cachedIn0Buf = null; cachedIn1Buf = null
                cachedDenoised0Buf = null; cachedDenoised1Buf = null
                cachedOutBuf = null; cachedMotionBuf = null; cachedTargetSize = 0
                // Bypass, not reject: capture is unaffected, so the picture keeps moving.
                bypassTargetSize = requiredBytesInt
                // Unlike the budget refusal above, an OutOfMemoryError says nothing about the
                // size itself, so it is retried once the direct buffers of the previous size have
                // had a chance to be returned.
                bypassRetryAfterNs = System.nanoTime() + ALLOCATION_RETRY_COOLDOWN_NS
                Log.e(
                    TAG,
                    "Interpolation buffer allocation failed at ${inputWidth}x${inputHeight}; " +
                        "frames pass through uninterpolated",
                    oom
                )
                if (bypassReportedForSize != requiredBytesInt) {
                    bypassReportedForSize = requiredBytesInt
                    reportError("Not enough memory to interpolate at ${inputWidth}x${inputHeight}; playing through")
                }
                return false
            }
            cachedTargetSize = requiredBytesInt
            Log.i(
                TAG,
                "Allocated interpolation buffers ($requiredBytesInt bytes each, motion field $motionBytes bytes)"
            )
        } else if (!denoiseOn && (cachedDenoised0Buf != null || cachedDenoised1Buf != null)) {
            // Two full-resolution scratch buffers were held while no code path could write to
            // them. They are only touched by the denoise stage, which runs on this worker inside
            // the same cycle as this check, so nothing can be reading them here.
            cachedDenoised0Buf = null
            cachedDenoised1Buf = null
            Log.i(TAG, "Released denoiser scratch (disabled): $requiredBytesInt bytes each")
        }
        return true
    }

    private fun obtainFrameBuffer(width: Int, height: Int): ByteBuffer {
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "obtainFrameBuffer: invalid dimensions ${width}x$height, dropping frame")
            droppedFrameCount++
            return ByteBuffer.allocateDirect(0)
        }
        val requiredBytes = width.toLong() * height.toLong() * 4L
        if (requiredBytes > Int.MAX_VALUE) {
            Log.e(TAG, "obtainFrameBuffer: dimensions ${width}x$height overflow Int, dropping frame")
            droppedFrameCount++
            return ByteBuffer.allocateDirect(0)
        }
        val intBytes = requiredBytes.toInt()
        expireAllocationRefusals()
        if (rejectedTargetSize == intBytes) {
            droppedFrameCount++
            return ByteBuffer.allocateDirect(0)
        }
        while (true) {
            val pooled = frameBufferPool.poll() ?: break
            if (pooled.capacity() >= intBytes) {
                pooled.clear()
                return pooled
            }
        }
        return try {
            ByteBuffer.allocateDirect(intBytes)
        } catch (oom: OutOfMemoryError) {
            droppedFrameCount++
            rejectedTargetSize = intBytes
            rejectRetryAfterNs = System.nanoTime() + ALLOCATION_RETRY_COOLDOWN_NS
            Log.e(TAG, "Capture allocation failed at ${width}x$height; suppressing repeat allocation", oom)
            ByteBuffer.allocateDirect(0)
        }
    }

    /**
     * Clears a refusal whose cool-down has passed, so a stage that failed because a direct
     * allocation threw is offered the size again instead of staying off for the rest of the
     * stream. Worker thread only: both flags it touches are worker-owned.
     */
    private fun expireAllocationRefusals() {
        val nowNs = System.nanoTime()
        if (bypassRetryAfterNs != 0L && nowNs >= bypassRetryAfterNs) {
            bypassRetryAfterNs = 0L
            val size = bypassTargetSize
            if (size != 0) {
                bypassTargetSize = 0
                bypassReportedForSize = 0
                logAllocationRetry(nowNs, "interpolation scratch", size)
            }
        }
        if (rejectRetryAfterNs != 0L && nowNs >= rejectRetryAfterNs) {
            rejectRetryAfterNs = 0L
            val size = rejectedTargetSize
            if (size != 0) {
                rejectedTargetSize = 0
                logAllocationRetry(nowNs, "capture buffer", size)
            }
        }
    }

    /**
     * Rate-limited record that a refused allocation was allowed to be tried again. The refusal
     * itself is logged where it is raised; this is the line that distinguishes a one-second blip
     * from a stream that never recovered, which is what the offline logs cannot show.
     */
    private fun logAllocationRetry(nowNs: Long, stage: String, size: Int) {
        if (nowNs - lastAllocationLogNs < ALLOCATION_LOG_INTERVAL_NS) return
        lastAllocationLogNs = nowNs
        Log.i(
            TAG,
            "[ALLOC] retrying $stage allocation ($size bytes) after cool-down; " +
                "another failure here means this size does not fit on this device"
        )
    }

    /**
     * Returns a frame buffer to the pool. Only called on the worker thread, which is the sole owner
     * of every frame buffer, so nothing can still be reading it.
     */
    private fun releaseFrameBuffer(buffer: ByteBuffer?) {
        if (buffer == null) return
        if (motionLeasedBuffers.containsKey(buffer)) {
            // A motion task is still reading these pixels. Keep the buffer out of the pool -
            // and keep its HDR source texture from being handed to the next capture - until
            // that task drops its lease, then run the release that was held back.
            motionDeferredReleases.add(buffer)
            return
        }
        finishFrameBufferRelease(buffer)
    }

    /** Returns [buffer] to its owner. Called directly only when no motion task can be reading it. */
    private fun finishFrameBufferRelease(buffer: ByteBuffer) {
        hdrTextureByAnalysisBuffer.remove(buffer)?.let { frameGrabber?.releaseSourceTexture(it) }
        // One buffer can be offered twice: the motion task drops its lease on a frame a reset
        // deferred, and the stale-work branch then releases the very same frame a few lines
        // later. ArrayDeque has no duplicate check, so without this the buffer would occupy two
        // slots and `obtainFrameBuffer()` could hand the same memory to two live frames.
        if (frameBufferPool.any { it === buffer }) return
        if (frameBufferPool.size >= minOf(MAX_POOLED_FRAME_BUFFERS, 2)) return
        buffer.clear()
        frameBufferPool.add(buffer)
    }

    /**
     * Marks [buffer] as being read by the motion thread. Worker thread only, and always before
     * the task is submitted so the task can never finish first and drop a lease nobody has taken.
     */
    private fun leaseForMotion(buffer: ByteBuffer?) {
        if (buffer == null) return
        motionLeasedBuffers[buffer] = (motionLeasedBuffers[buffer] ?: 0) + 1
    }

    /**
     * Drops one lease and performs any release that was deferred while it was held. Reached from
     * the worker when the task can be handed back, and from the motion thread only once the worker
     * is unreachable - at that point `stop()` has already run `releaseStateOnWorker()` and quit the
     * looper, so no worker can be inside these tables, and a stale drop is an inert no-op.
     */
    private fun dropMotionLease(buffer: ByteBuffer?, recycle: Boolean) {
        if (buffer == null) return
        val remaining = (motionLeasedBuffers[buffer] ?: 0) - 1
        if (remaining > 0) {
            motionLeasedBuffers[buffer] = remaining
            return
        }
        motionLeasedBuffers.remove(buffer)
        if (motionDeferredReleases.remove(buffer) && recycle) {
            finishFrameBufferRelease(buffer)
        }
    }

    private fun dropMotionLeases(prev: FrameData, next: FrameData, recycle: Boolean = true) {
        dropMotionLease(prev.pixels, recycle)
        dropMotionLease(next.pixels, recycle)
    }

    /**
     * Calculates a cheap pixel checksum (sum of all RGBA values) to verify the buffer is not all-zero.
     * Does not modify the buffer position/limit.
     */
    private fun calculateChecksum(buffer: ByteBuffer, width: Int, height: Int): Long {
        val originalPosition = buffer.position()
        val originalLimit = buffer.limit()
        buffer.position(0)
        val pixelCount = width * height
        var sum: Long = 0
        // Sample every 16th pixel to keep it fast
        val step = 16
        for (i in 0 until pixelCount step step) {
            val offset = i * 4
            if (offset + 3 < buffer.capacity()) {
                sum += (buffer.get(offset).toInt() and 0xFF).toLong()
                sum += (buffer.get(offset + 1).toInt() and 0xFF).toLong()
                sum += (buffer.get(offset + 2).toInt() and 0xFF).toLong()
                sum += (buffer.get(offset + 3).toInt() and 0xFF).toLong()
            }
        }
        buffer.position(originalPosition)
        buffer.limit(originalLimit)
        return sum
    }

    /**
     * Logs, once per stream, that the motion-aligned denoiser could not run and the stage's own
     * implementation is doing the work instead. Called from both branches so the reason is visible
     * whether the search is on (a field is available) or off (no GL denoise program).
     */
    private fun noteDenoiseUnavailable() {
        if (denoiseUnavailableLogged || !fastDvdNetEngine.isEnabled) {
            return
        }
        denoiseUnavailableLogged = true
        Log.w(
            TAG,
            "motion-aligned denoiser not running for this stream " +
                "(renderer=${outputRenderer?.isDenoiseInitialized}); " +
                "the stage's own implementation is used"
        )
    }

    private fun renderFrameToOutput(frame: FrameData, timestampNs: Long = 0L) {
        val hdrTexture = hdrTextureByAnalysisBuffer[frame.pixels] ?: 0
        if (hdrTexture != 0) {
            // Measured here rather than at capture because this is the exact texture the present
            // samples; it is rate-limited inside and writes nothing, so the frame is unaffected.
            hdrRepresentationProbe?.probe(
                hdrTexture,
                frame.width,
                frame.height,
                "tex=$hdrTexture ${frame.width}x${frame.height} out=$outputDataSpace " +
                    "color=${sourceColorInfo ?: "unknown"}"
            )
            outputRenderer?.isHdr = isHdrSource()
            if (outputRenderer?.renderTexture(hdrTexture, frame.width, frame.height, timestampNs) == true) {
                submittedOutputFrameCount++
            } else {
                droppedOutputFrameCount++
                Log.e(TAG, "HDR source texture presentation failed; refusing RGBA8 fallback")
            }
            return
        }
        frame.pixels.clear()
        renderBufferToOutput(frame.pixels, frame.width, frame.height, timestampNs)
    }

    /**
     * Blits already-computed RGBA pixels to the Media3 output surface with GL. The readable range
     * is set explicitly from the destination size instead of being inherited from whatever the
     * producer left behind: the interpolation output buffer is filled through a raw JNI pointer (so its
     * position is never advanced) and the pooled frame buffers may have a capacity larger than
     * this frame.
     *
     * [timestampNs] is the frame's presentation time, handed to `eglPresentationTimeANDROID` so
     * the encoder can stamp the access unit with it.
     */
    private fun renderBufferToOutput(
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        timestampNs: Long = 0L
    ) {
        // An encode can run before, or without, any preview surface being published, so the
        // encoder target counts as a legitimate destination here.
        if (pendingOutputSurfaceInfo == null && encodeSurface == null) {
            droppedOutputFrameCount++
            Log.w(TAG, "renderBufferToOutput: no output surface or encode surface; frame dropped")
            return
        }
        val surface = activeOutputSurface()
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "renderBufferToOutput: no valid output surface; frame dropped")
            droppedOutputFrameCount++
            // Attempt to recover by requesting surface re-creation
            requestSurfaceRecovery()
            return
        }
        val renderer = outputRenderer ?: run {
            droppedOutputFrameCount++
            Log.w(TAG, "renderBufferToOutput: renderer not available; frame dropped")
            return
        }
        if (!renderer.isInitialized) {
            Log.w(TAG, "renderBufferToOutput: renderer not initialized; frame dropped")
            droppedOutputFrameCount++
            return
        }

        val requiredBytes = width.toLong() * height.toLong() * 4L
        if (requiredBytes > Int.MAX_VALUE) {
            Log.e(TAG, "renderBufferToOutput: dimensions ${width}x$height overflow Int")
            droppedOutputFrameCount++
            return
        }
        pixels.position(0)
        pixels.limit(requiredBytes.toInt())

        // Rate-limited content probe at the render boundary: non-zero here with a black screen
        // means the pixels are fine and the fault is presentation-side (EGL/SurfaceView/colour).
        val renderProbeNs = System.nanoTime()
        if (renderProbeNs - lastRenderContentLogNs >= 1_000_000_000L) {
            lastRenderContentLogNs = renderProbeNs
            Log.i(
                TAG,
                "[CONTENT] renderInput ${width}x${height} checksum=" +
                    calculateChecksum(pixels, width, height)
            )
        }

        // DIAGNOSTICS: Log checksum before sending to GlOutputRenderer
        if (VERBOSE_DIAGNOSTICS) {
            val renderChecksum = calculateChecksum(pixels, width, height)
            Log.d(TAG, "PIPELINE CHECKSUM: before GlOutputRenderer ${width}x$height checksum=$renderChecksum")
        }

        try {
            renderer.isHdr = isHdrSource()
            val success = renderer.render(pixels, width, height, timestampNs)
            if (success) {
                submittedOutputFrameCount++
                surfaceRecoveryRequested = false
            } else {
                droppedOutputFrameCount++
                Log.w(TAG, "renderBufferToOutput: renderer.render returned false; frame dropped")
                // Attempt recovery on next frame
            }
        } catch (t: Throwable) {
            droppedOutputFrameCount++
            Log.w(TAG, "Failed to render a frame to the output surface", t)
            // Attempt recovery on next frame
        }
    }

    /**
     * Requests surface recovery by triggering a pipeline reset on the next frame.
     * This helps recover from transient surface/EGL issues without full pipeline restart.
     */
    private fun requestSurfaceRecovery() {
        if (surfaceRecoveryRequested || released) return
        surfaceRecoveryRequested = true
        workerHandler?.post {
            if (!released) {
                Log.w(TAG, "Requesting surface recovery due to invalid output surface")
                resetPipelineOnWorker("surface_recovery")
            }
        }
    }

    /**
     * Longest source edge at or above which a frame counts as 4K for the logging threshold. 3000
     * covers UHD (3840) and DCI 4K (4096) while staying clear of 1440p (2560). Diagnostic only:
     * it never changes the size anything is processed at.
     */
    private val auto4kMinDim = 3000

    /** True when the denoiser stage is on, whichever implementation currently provides it. */
    private val isDenoiseEnabled: Boolean
        get() = fastDvdNetEngine.isEnabled

    /**
     * Capture - and therefore processing - size for a decoded frame: the configured resolution,
     * aspect-preserved, then clamped to the output surface. The clamp matters because the present
     * scales the result to fit that surface anyway, so capturing beyond it only buys pixels that
     * get scaled straight back down.
     *
     * Nothing here may lower the size as a performance or memory shortcut. A source that does not
     * fit is reported by [ensureCachedBuffers] instead of being quietly rendered at fewer pixels,
     * because a silent downgrade is indistinguishable from a broken pipeline when looking at the
     * screen.
     */
    private fun resolveCaptureDimensions(srcW: Int, srcH: Int): Pair<Int, Int> {
        val (targetW, targetH) = calculateTargetDimensions(srcW, srcH, resolution)
        val surfaceW = outputRenderer?.outputSurfaceWidth ?: 0
        val surfaceH = outputRenderer?.outputSurfaceHeight ?: 0
        if (surfaceW <= 0 || surfaceH <= 0) return Pair(targetW, targetH)
        return fitWithin(targetW, targetH, surfaceW, surfaceH)
    }

    /** Largest size with [w]x[h]'s aspect that fits inside maxW x maxH; unchanged if it already does. */
    private fun fitWithin(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (w <= maxW && h <= maxH) return Pair(w, h)
        if (w <= 0 || h <= 0) return Pair(w, h)
        val scale = minOf(maxW.toDouble() / w, maxH.toDouble() / h)
        val outW = ((w * scale).toLong()).coerceIn(1L, maxW.toLong()).toInt()
        val outH = ((h * scale).toLong()).coerceIn(1L, maxH.toLong()).toInt()
        return Pair(outW, outH)
    }

    /**
     * Maps a source size onto the processing size for [res]. Only the longest edge is clamped, so
     * the source aspect ratio is preserved and no fixed 1920x1080 processing size is imposed.
     */
    fun calculateTargetDimensions(
        srcW: Int,
        srcH: Int,
        res: RifeResolution
    ): Pair<Int, Int> {
        val safeSrcW = srcW.coerceAtLeast(1)
        val safeSrcH = srcH.coerceAtLeast(1)

        return when (res) {
            RifeResolution.ORIGINAL ->
                Pair(safeSrcW, safeSrcH)

            RifeResolution.RES_1080P -> {
                val maxDim = 1920

                if (safeSrcW > safeSrcH && safeSrcW > maxDim) {
                    Pair(maxDim, ((safeSrcH * maxDim) / safeSrcW).coerceAtLeast(1))
                } else if (safeSrcH >= safeSrcW && safeSrcH > maxDim) {
                    Pair(((safeSrcW * maxDim) / safeSrcH).coerceAtLeast(1), maxDim)
                } else {
                    Pair(safeSrcW, safeSrcH)
                }
            }

            RifeResolution.RES_720P -> {
                val maxDim = 1280

                if (safeSrcW > safeSrcH && safeSrcW > maxDim) {
                    Pair(maxDim, ((safeSrcH * maxDim) / safeSrcW).coerceAtLeast(1))
                } else if (safeSrcH >= safeSrcW && safeSrcH > maxDim) {
                    Pair(((safeSrcW * maxDim) / safeSrcH).coerceAtLeast(1), maxDim)
                } else {
                    Pair(safeSrcW, safeSrcH)
                }
            }

            RifeResolution.RES_480P -> {
                val maxDim = 854

                if (safeSrcW > safeSrcH && safeSrcW > maxDim) {
                    Pair(maxDim, ((safeSrcH * maxDim) / safeSrcW).coerceAtLeast(1))
                } else if (safeSrcH >= safeSrcW && safeSrcH > maxDim) {
                    Pair(((safeSrcW * maxDim) / safeSrcH).coerceAtLeast(1), maxDim)
                } else {
                    Pair(safeSrcW, safeSrcH)
                }
            }
        }
    }

    private fun updateStats() {
        val now = SystemClock.elapsedRealtime()
        val durationSec = (now - lastStatsResetTime) / 1000.0f

        if (durationSec >= 1.0f) {
            // Baselines, not resets: frameCountInput/frameCountOutput are cumulative so the stage
            // heartbeat and the timing window can derive their own rates without racing this one.
            val inFps = (frameCountInput - statsCapturedAtStart) / durationSec
            val outFps = (frameCountOutput - statsOutputAtStart) / durationSec

            val resStr = when (resolution) {
                RifeResolution.ORIGINAL -> "Original"
                RifeResolution.RES_1080P -> "1080p"
                RifeResolution.RES_720P -> "720p"
                RifeResolution.RES_480P -> "480p"
            }

            val stats = Statistics(
                inputFps = inFps,
                outputFps = outFps,
                // The instantaneous output rate over the last pair, which is what the encoder has
                // to be opened at. The windowed average above is smoothed over a second and would
                // open a 72 fps stream as 60.
                outputFrameRate = if (lastPairIntervalUs > 0) {
                    (1_000_000.0 / lastPairIntervalUs.toDouble() *
                        memcLevelMultiplier.toDouble()).toFloat()
                } else {
                    0f
                },
                processingTimeMs = lastProcTimeMs,
                droppedFrames = droppedFrameCount,
                currentResolution = resStr
            )

            onStatisticsUpdated(stats)

            statsCapturedAtStart = frameCountInput
            statsOutputAtStart = frameCountOutput
            lastStatsResetTime = now
        }
    }
}
