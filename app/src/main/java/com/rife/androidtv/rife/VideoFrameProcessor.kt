package com.rife.androidtv.rife

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
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
    val processingTimeMs: Long,
    val droppedFrames: Long,
    val currentResolution: String
)

/**
 * Frame processor for RIFE interpolation and the (scaffold) FastDVDnet pre-processing stage, built
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

        /** Bounded queue: the pipeline must never grow faster than it can interpolate. */
        private const val FRAME_QUEUE_CAPACITY = 4

        /** Pooled capture buffers. Bounded so a 4K stream cannot inflate the heap. */
        private const val MAX_POOLED_FRAME_BUFFERS = 6

        private const val WORKER_TASK_TIMEOUT_MS = 3000L

        /**
         * Per-stage timing is averaged over a window and reported as a single line. Logging every
         * stage of every frame would put thousands of Log.d calls into the profile being measured.
         */
        private const val TIMING_WINDOW_FRAMES = 30

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
    }

    /**
     * Whether RIFE interpolation is active. Input-surface frames are only read back while RIFE or
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
            reportError("RIFE frame processing failed: $message")
        }

        override fun onEnded() {
            Log.i(TAG, "Media3 input stream ended")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Worker thread + EGL / input surface ownership
    // ---------------------------------------------------------------------------------------

    private var workerThread: HandlerThread? = null
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
    // RIFE pipeline state. Only ever mutated on the worker thread.
    // ---------------------------------------------------------------------------------------

    private val frameQueue = ArrayBlockingQueue<FrameData>(FRAME_QUEUE_CAPACITY)
    private val frameBufferPool = java.util.ArrayDeque<ByteBuffer>(MAX_POOLED_FRAME_BUFFERS)

    private var previousFrame: FrameData? = null

    private var frameCountInput = 0
    private var frameCountOutput = 0
    private var droppedFrameCount = 0L
    private var lastStatsResetTime = SystemClock.elapsedRealtime()
    private var lastProcTimeMs = 0L

    private var cachedIn0Buf: ByteBuffer? = null
    private var cachedIn1Buf: ByteBuffer? = null
    private var cachedDenoised0Buf: ByteBuffer? = null
    private var cachedDenoised1Buf: ByteBuffer? = null
    private var cachedOutBuf: ByteBuffer? = null
    private var cachedTargetSize = 0

    /**
     * Packed motion field for the GPU warp: four bytes per 16x16 block, so a few kilobytes even
     * at 1080p. Reallocated alongside the frame buffers because it tracks the processing size.
     */
    private var cachedMotionBuf: ByteBuffer? = null

    /** Dimensions of the last per-frame-dimension breadcrumb, so it is written on change only. */
    private var lastDimsLogW = 0
    private var lastDimsLogH = 0

    /**
     * Adaptive processing resolution (RifeResolution.AUTO).
     *
     * [autoDegradeLevel] indexes [autoDegradeLadder] and only ever moves down, so a source that
     * cannot be held at native resolution settles instead of flapping between two sizes every
     * reporting window. [autoDenoiseBranch] records which AUTO branch picked the current size: only
     * the native-4K-denoiser branch is allowed to degrade, because the other two are fixed by
     * policy and hiding their cost behind a downgrade would mask exactly the work needed to make
     * them fit. [autoCaptureW]/[autoCaptureH] are the size that branch last asked for, so the
     * downgrade is logged with what it cost.
     */
    private var autoDegradeLevel = 0
    private var autoDenoiseBranch = false
    private var autoCaptureW = 0
    private var autoCaptureH = 0

    /**
     * Source frame interval in nanoseconds, taken from the decoder timestamps of the pair being
     * processed. This is the budget the AUTO policy has to fit inside to hold the native frame
     * rate, and it is measured rather than configured because the source is what defines it. Zero
     * until the first pair, and rejected outside [1ms, 1s] so a seek cannot set a nonsense one.
     */
    private var sourceIntervalNs = 0L

    /** Dimensions and mode of the last RES POLICY breadcrumb, so it is written on change only. */
    private var lastCaptureLogSrcW = -1
    private var lastCaptureLogSrcH = -1
    private var lastCaptureLogW = 0
    private var lastCaptureLogH = 0
    private var lastCaptureLogRes: RifeResolution? = null

    /**
     * GL blitter for the output surface. Replaces the previous `lockCanvas()` + `drawBitmap()`
     * path, which rasterised three full-screen bitmaps per interpolated pair on the CPU and was
     * one of the dominant costs on the TV box.
     */
    private var outputRenderer: GlOutputRenderer? = null

    private var pendingOutputSurfaceInfo: SurfaceInfo? = null

    @Volatile
    private var inputWidth = 0

    @Volatile
    private var inputHeight = 0

    @Volatile
    private var inputStreamRegistered = false

    private var endOfInputSignalled = false
    private var readbackInProgress = false
    private var released = false

    // Per-stage timing window (see TIMING_WINDOW_FRAMES). MEMC is only a few ms per frame yet
    // playback lands far below real time on both the TV box and the Poco F7, so the remaining
    // cost has to be located in the GL readback / upload path rather than assumed.
    private var timingStartNs = 0L
    private var timingCycles = 0
    private var nsReadback = 0L
    private var nsCopy = 0L
    private var nsChecksum = 0L
    private var nsJni = 0L
    private var nsRender = 0L
    private var nsPair = 0L
    // Per-phase split of nsRender, drained from GlOutputRenderer once per report window.
    private val renderBreakdown = LongArray(6)
    private var nsRenderCurrent = 0L
    private var nsRenderSetup = 0L
    private var nsRenderUpload = 0L
    private var nsRenderDraw = 0L
    private var nsRenderSwap = 0L
    private var capturedAtWindowStart = 0
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
            createInputSurfaceOnWorker("start")
        }
    }

    /**
     * Enables or disables RIFE interpolation.
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
     * "RIFE OFF + FastDVDnet ON" (state 2) and "RIFE OFF + FastDVDnet OFF" (state 1).
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
            "RIFE only supports INPUT_TYPE_SURFACE"
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
                            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
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
                outputRenderer?.setOutputSurface(display, null)
            } else {
                Log.i(
                    TAG,
                    "Output surface set: ${outputSurfaceInfo.width}x${outputSurfaceInfo.height} " +
                        "(orientationDegrees=${outputSurfaceInfo.orientationDegrees})"
                )
                outputRenderer?.setOutputSurface(display, outputSurfaceInfo.surface)
            }
        }
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
        throw UnsupportedOperationException("RIFE only supports INPUT_TYPE_SURFACE")
    }

    override fun queueInputTexture(textureId: Int, presentationTimeUs: Long): Boolean {
        throw UnsupportedOperationException("RIFE only supports INPUT_TYPE_SURFACE")
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
                reportError("RIFE pipeline error: ${t.message}")
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
                .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
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

        cachedIn0Buf = null
        cachedIn1Buf = null
        cachedDenoised0Buf = null
        cachedDenoised1Buf = null
        cachedOutBuf = null
        cachedMotionBuf = null
        cachedTargetSize = 0
        readbackInProgress = false

        frameCountInput = 0
        frameCountOutput = 0
        lastStatsResetTime = SystemClock.elapsedRealtime()
        lastProcTimeMs = 0L

        // A new stream gets a fresh chance at its native AUTO resolution, and the capture breadcrumb
        // is cleared so the RES POLICY line is printed again for the new source.
        autoDegradeLevel = 0
        autoDenoiseBranch = false
        autoCaptureW = 0
        autoCaptureH = 0
        lastCaptureLogSrcW = -1
        lastCaptureLogSrcH = -1
        lastCaptureLogW = 0
        lastCaptureLogH = 0
        lastCaptureLogRes = null

        Log.i(TAG, "resetPipeline: reason=$reason discardedFrames=$discarded")
    }

    private fun releaseStateOnWorker() {
        pendingOutputSurfaceInfo = null
        resetPipelineOnWorker("release")
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

            val display = GlUtil.getDefaultEglDisplay()
            val context = GlUtil.createEglContext(display)
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
            val renderer = GlOutputRenderer()
            renderer.init(context)
            outputRenderer = renderer

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
            reportError("RIFE input surface initialization failed: ${e.message}")
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
            captureFrameFromInputSurface(texture)
        } catch (t: Throwable) {
            droppedFrameCount++
            Log.e(TAG, "Frame capture failed", t)
            reportError("RIFE frame capture failed: ${t.message}")
        } finally {
            readbackInProgress = false
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
        // ratio of the source is preserved, so the frame is never stretched. This is also where
        // an AUTO source gets downgraded, and where any capture is clamped to the output surface.
        val (captureWidth, captureHeight) = resolveCaptureDimensions(sourceWidth, sourceHeight)
        autoCaptureW = captureWidth
        autoCaptureH = captureHeight

        if (sourceWidth != lastCaptureLogSrcW ||
            sourceHeight != lastCaptureLogSrcH ||
            captureWidth != lastCaptureLogW ||
            captureHeight != lastCaptureLogH ||
            resolution != lastCaptureLogRes
        ) {
            lastCaptureLogSrcW = sourceWidth
            lastCaptureLogSrcH = sourceHeight
            lastCaptureLogW = captureWidth
            lastCaptureLogH = captureHeight
            lastCaptureLogRes = resolution
            Log.i(
                TAG,
                "RES POLICY: src=${sourceWidth}x$sourceHeight " +
                    "surface=${outputRenderer?.outputSurfaceWidth ?: 0}x" +
                    "${outputRenderer?.outputSurfaceHeight ?: 0} " +
                    "res=$resolution memc=$isRifeEnabled denoise=$isDenoiseEnabled " +
                    "-> capture=${captureWidth}x$captureHeight level=$autoDegradeLevel"
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
        val tCaptureStart = timingStartNs
        val pixels = obtainFrameBuffer(captureWidth, captureHeight)
        val readOk = pixels.capacity() > 0 &&
            grabber.read(texture, captureWidth, captureHeight, pixels)
        nsReadback += System.nanoTime() - tCaptureStart
        if (!readOk) {
            releaseFrameBuffer(pixels)
            droppedFrameCount++
            return
        }

        frameCountInput++

        if (VERBOSE_DIAGNOSTICS) {
            Log.d(
                TAG,
                "FRAME CAPTURE LOG: decoded=${sourceWidth}x$sourceHeight -> " +
                    "preRife=${captureWidth}x$captureHeight"
            )
        }

        val frame = FrameData(
            pixels = pixels,
            timestampUs = texture.timestamp / 1000L,
            width = captureWidth,
            height = captureHeight
        )

        // Bounded queue with explicit backpressure: drop the oldest frame rather than growing.
        if (!frameQueue.offer(frame)) {
            droppedFrameCount++
            frameQueue.poll()?.let { releaseFrameBuffer(it.pixels) }
            if (!frameQueue.offer(frame)) {
                releaseFrameBuffer(pixels)
                return
            }
        }

        processNextFramePair()
    }

    private fun processNextFramePair() {
        val nextFrame = frameQueue.poll() ?: return
        val prev = previousFrame

        if (prev == null) {
            // First frame: log checksum before rendering
            if (VERBOSE_DIAGNOSTICS) {
                val firstFrameChecksum =
                    calculateChecksum(nextFrame.pixels, nextFrame.width, nextFrame.height)
                Log.d(TAG, "PIPELINE CHECKSUM: firstFrame ${nextFrame.width}x${nextFrame.height} checksum=$firstFrameChecksum")
            }
            renderFrameToOutput(nextFrame)
            frameCountOutput++
            previousFrame = nextFrame
            updateStats()
            return
        }

        if (prev.width != nextFrame.width || prev.height != nextFrame.height) {
            // The resolution changed underneath us: restart the pair instead of feeding RIFE
            // mismatched buffers.
            Log.i(TAG, "Frame size changed, restarting the RIFE pair")
            releaseFrameBuffer(prev.pixels)
            renderFrameToOutput(nextFrame)
            frameCountOutput++
            previousFrame = nextFrame
            updateStats()
            return
        }

        // Native-rate budget for the AUTO policy, straight from the source timestamps.
        val srcIntervalUs = nextFrame.timestampUs - prev.timestampUs
        if (srcIntervalUs in 1_000L..1_000_000L) {
            sourceIntervalNs = srcIntervalUs * 1_000L
        }

        val rifeInputW = nextFrame.width
        val rifeInputH = nextFrame.height
        val rifeOutputW = rifeInputW
        val rifeOutputH = rifeInputH

        val requiredInputBytes = rifeInputW.toLong() * rifeInputH.toLong() * 4L

        // The JNI layer never checks the output capacity, and RifeEngine::processFrameBuffer() ends
        // with ncnn::Mat::to_pixels_resize(out_ptr, PIXEL_RGB2RGBA, w, h), which writes exactly
        // rifeOutputW * rifeOutputH * 4 bytes through that raw pointer. The Java side therefore has
        // to guarantee that capacity itself.
        val requiredOutputBytes = rifeOutputW.toLong() * rifeOutputH.toLong() * 4L

        if (!ensureCachedBuffers(requiredInputBytes, requiredOutputBytes, rifeInputW, rifeInputH)) {
            releaseFrameBuffer(prev.pixels)
            renderFrameToOutput(nextFrame)
            previousFrame = nextFrame
            return
        }

        val in0Buf = cachedIn0Buf!!
        val in1Buf = cachedIn1Buf!!
        val den0Buf = cachedDenoised0Buf!!
        val den1Buf = cachedDenoised1Buf!!
        val outBuf = cachedOutBuf!!

        prev.pixels.clear()
        nextFrame.pixels.clear()

        // Inputs keep the clear -> put -> flip contract: flip() is what publishes the number of
        // written bytes as the limit, so the readable range matches the frame handed to the stage.
        val tCopyStart = System.nanoTime()
        in0Buf.clear()
        in1Buf.clear()
        in0Buf.put(prev.pixels)
        in1Buf.put(nextFrame.pixels)
        in0Buf.flip()
        in1Buf.flip()
        nsCopy += System.nanoTime() - tCopyStart

        // DIAGNOSTICS: Log checksum of frames before FastDVDnet/RIFE
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
            // State 2: FastDVDnet-only. The current frame is pre-processed and rendered as-is; no
            // interpolation is attempted and no extra frame is invented.
            if (fastDvdNetEngine.isEnabled) {
                val denoised = fastDvdNetEngine.denoiseFrameBuffer(
                    nextFrame.pixels,
                    rifeInputW,
                    rifeInputH,
                    den1Buf
                )
                if (denoised) {
                    // DIAGNOSTICS: Log checksum after FastDVDnet pass-through
                    val fastDvdNetChecksum = calculateChecksum(den1Buf, rifeInputW, rifeInputH)
                    Log.d(TAG, "PIPELINE CHECKSUM: after FastDVDnet ${rifeInputW}x${rifeInputH} checksum=$fastDvdNetChecksum (unchanged=${fastDvdNetChecksum == nextChecksum})")
                    renderBufferToOutput(den1Buf, rifeInputW, rifeInputH)
                } else {
                    renderFrameToOutput(nextFrame)
                }
            } else {
                // Both stages were switched off between capture and processing: forward the frame
                // instead of leaving a stale picture on the output surface.
                renderFrameToOutput(nextFrame)
            }
            lastProcTimeMs = SystemClock.elapsedRealtime() - startTime
            frameCountOutput++
            releaseFrameBuffer(prev.pixels)
            previousFrame = nextFrame
            updateStats()
            return
        }

        // State 3 and 4: the FastDVDnet scaffold is optional pre-processing in front of RIFE. When
        // it is off the captured buffers are handed to JNI directly, so no extra copy is made.
        var src0Buf = in0Buf
        var src1Buf = in1Buf
        if (fastDvdNetEngine.isEnabled) {
            val denoisedPrev = fastDvdNetEngine.denoiseFrameBuffer(
                in0Buf,
                rifeInputW,
                rifeInputH,
                den0Buf
            )
            val denoisedNext = fastDvdNetEngine.denoiseFrameBuffer(
                in1Buf,
                rifeInputW,
                rifeInputH,
                den1Buf
            )
            if (denoisedPrev && denoisedNext) {
                src0Buf = den0Buf
                src1Buf = den1Buf
                // DIAGNOSTICS: Log checksum after FastDVDnet
                val den0Checksum = calculateChecksum(den0Buf, rifeInputW, rifeInputH)
                val den1Checksum = calculateChecksum(den1Buf, rifeInputW, rifeInputH)
                Log.d(TAG, "PIPELINE CHECKSUM: after FastDVDnet den0 checksum=$den0Checksum den1 checksum=$den1Checksum")
            } else {
                Log.w(TAG, "FastDVDnet stage failed, interpolating the raw frames")
            }
        }

        // DIAGNOSTICS: Log checksum before RIFE JNI
        if (VERBOSE_DIAGNOSTICS) {
            val tChecksumStart = System.nanoTime()
            val src0Checksum = calculateChecksum(src0Buf, rifeInputW, rifeInputH)
            val src1Checksum = calculateChecksum(src1Buf, rifeInputW, rifeInputH)
            nsChecksum += System.nanoTime() - tChecksumStart
            Log.d(TAG, "PIPELINE CHECKSUM: before RIFE src0 checksum=$src0Checksum src1 checksum=$src1Checksum")
        }

        if (rifeInputW != lastDimsLogW || rifeInputH != lastDimsLogH) {
            lastDimsLogW = rifeInputW
            lastDimsLogH = rifeInputH
            Log.i(
                TAG,
                "REAL RIFE EXECUTION LOG: preRifeDimensions=${rifeInputW}x$rifeInputH -> " +
                    "rifeInputDimensions=${rifeInputW}x$rifeInputH -> " +
                    "rifeOutputDimensions=${rifeOutputW}x$rifeOutputH -> " +
                    "renderingSurfaceDimensions=${displaySurfaceWidth}x$displaySurfaceHeight"
            )
        }

        // The output buffer is deliberately NOT flipped here. NativeEngine.interpolateFrameBuffers()
        // reaches the memory through JNI GetDirectBufferAddress() and writes into it directly, so
        // the Java position stays at 0 and a flip() would only publish limit = 0.
        outBuf.clear()

        // GPU warp: motion estimation still runs on the CPU, because that is what the luma pyramid
        // and the SAD search are, but the per-pixel resample moves into the fragment shader. What
        // crosses JNI is then the packed field - ceil(w/16) * ceil(h/16) * 8 bytes, tens of kB -
        // instead of a full RGBA frame. computeMotionField() reports false when the algorithm is
        // not MEMC, so the RIFE path keeps working without this layer knowing about the switch.
        val motionBuf = cachedMotionBuf
        val tJniStart = System.nanoTime()
        var motionReady = false
        if (motionBuf != null && outputRenderer?.isWarpInitialized == true) {
            motionReady = NativeEngine.computeMotionField(
                src0Buf,
                src1Buf,
                rifeInputW,
                rifeInputH,
                rifeOutputW,
                rifeOutputH,
                motionBuf
            )
        }
        val success = motionReady || NativeEngine.interpolateFrameBuffers(
            src0Buf,
            src1Buf,
            rifeInputW,
            rifeInputH,
            rifeOutputW,
            rifeOutputH,
            0.5f,
            outBuf
        )
        nsJni += System.nanoTime() - tJniStart

        lastProcTimeMs = SystemClock.elapsedRealtime() - startTime

        if (success) {
            // Temporal order: previous frame was already rendered when it was captured (or as the
            // first frame), so we only render the interpolated frame and the next frame.
            // Sequence: A, M(A,B), B, M(B,C), C — correct 2x interpolation without duplication.
            val tRenderStart = System.nanoTime()
            var presented = false
            if (motionReady && motionBuf != null) {
                presented = outputRenderer?.renderWarp(
                    src0Buf,
                    src1Buf,
                    motionBuf,
                    rifeInputW,
                    rifeInputH,
                    rifeOutputW,
                    rifeOutputH,
                    0.5f
                ) == true
            }
            if (!presented) {
                // The shader refused the frame, or the CPU path ran. Redo it on the CPU so the
                // pair still produces a picture: a failure here costs time, never a frame.
                if (motionReady) {
                    NativeEngine.interpolateFrameBuffers(
                        src0Buf,
                        src1Buf,
                        rifeInputW,
                        rifeInputH,
                        rifeOutputW,
                        rifeOutputH,
                        0.5f,
                        outBuf
                    )
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

                renderBufferToOutput(outBuf, rifeOutputW, rifeOutputH)
            }
            renderFrameToOutput(nextFrame)
            nsRender += System.nanoTime() - tRenderStart
            frameCountOutput += 2
        } else {
            val status = NativeEngine.getRifeStatus()
            reportError(
                if (status.lastError.isNotEmpty()) {
                    status.lastError
                } else {
                    "RIFE frame interpolation failed"
                }
            )
            val tRenderStart = System.nanoTime()
            renderFrameToOutput(nextFrame)
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

        // The AUTO policy's native-4K-denoiser branch only keeps its resolution while the native
        // frame rate holds. One dropped frame in the window is the signal: step down once and log
        // it, so playback recovers instead of stuttering for the rest of the stream.
        if (autoDenoiseBranch && autoDegradeLevel < autoDegradeLadder.lastIndex) {
            val cycleNs = nsPair / n
            val overBudget = sourceIntervalNs > 0L && cycleNs > sourceIntervalNs.toDouble()
            if (dropped > 0 || overBudget) {
                autoDegradeLevel++
                Log.w(
                    TAG,
                    "RES POLICY: ${autoCaptureW}x$autoCaptureH cannot hold the native rate " +
                        "(cycle=${fmtMs(cycleNs)} ms, budget=${fmtMs(sourceIntervalNs.toDouble())} ms, " +
                        "dropped=$dropped), degrading to ${autoDegradeLadder[autoDegradeLevel]}"
                )
            }
        }

        Log.i(
            TAG,
            "PIPELINE TIMING: n=$timingCycles " +
                "readback=${fmtMs(nsReadback / n)} " +
                "copy=${fmtMs(nsCopy / n)} " +
                "checksum=${fmtMs(nsChecksum / n)} " +
                "jni=${fmtMs(nsJni / n)} " +
                "render=${fmtMs(nsRender / n)} " +
                "total=${fmtMs(nsPair / n)} ms/cycle | " +
                "renderSplit calls=$renderCalls cur=${fmtMs(nsRenderCurrent / n)} " +
                "st=${fmtMs(nsRenderSetup / n)} up=${fmtMs(nsRenderUpload / n)} " +
                "dr=${fmtMs(nsRenderDraw / n)} sw=${fmtMs(nsRenderSwap / n)} | " +
                "captured=$captured dropped=$dropped " +
                "in=${frameCountInput} out=$frameCountOutput rife=$isRifeEnabled"
        )

        timingCycles = 0
        nsReadback = 0
        nsCopy = 0
        nsChecksum = 0
        nsJni = 0
        nsRender = 0
        nsRenderCurrent = 0
        nsRenderSetup = 0
        nsRenderUpload = 0
        nsRenderDraw = 0
        nsRenderSwap = 0
        nsPair = 0
        capturedAtWindowStart = frameCountInput
        droppedAtWindowStart = droppedFrameCount
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
        val gridW = (inputWidth + 15) / 16
        val gridH = (inputHeight + 15) / 16
        // Eight bytes per block: four for the vectors, four for the cover/uncover masks. Mirrors
        // MemcInterpolator::motionFieldBytes(), which the JNI side re-checks against the buffer
        // capacity before it writes anything.
        val motionBytes = gridW.toLong() * gridH.toLong() * 8L
        if (motionBytes > Int.MAX_VALUE) {
            Log.e(TAG, "ensureCachedBuffers: motion field $motionBytes overflows Int")
            return false
        }
        // Read into a local: a mutable property can never be smart-cast across the null check.
        val motionCapacity = cachedMotionBuf?.capacity() ?: 0
        if (cachedIn0Buf == null || cachedIn1Buf == null || cachedDenoised0Buf == null ||
            cachedDenoised1Buf == null || cachedOutBuf == null ||
            motionCapacity < motionBytes ||
            cachedTargetSize != requiredBytesInt
        ) {
            cachedIn0Buf = ByteBuffer.allocateDirect(requiredBytesInt)
            cachedIn1Buf = ByteBuffer.allocateDirect(requiredBytesInt)
            cachedDenoised0Buf = ByteBuffer.allocateDirect(requiredBytesInt)
            cachedDenoised1Buf = ByteBuffer.allocateDirect(requiredBytesInt)
            cachedOutBuf = ByteBuffer.allocateDirect(requiredBytesInt)
            cachedMotionBuf = ByteBuffer.allocateDirect(motionBytes.toInt())
            cachedTargetSize = requiredBytesInt
            Log.i(
                TAG,
                "Allocated RIFE buffers ($requiredBytesInt bytes each, motion field $motionBytes bytes)"
            )
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
        while (true) {
            val pooled = frameBufferPool.poll() ?: break
            if (pooled.capacity() >= intBytes) {
                pooled.clear()
                return pooled
            }
        }
        return ByteBuffer.allocateDirect(intBytes)
    }

    /**
     * Returns a frame buffer to the pool. Only called on the worker thread, which is the sole owner
     * of every frame buffer, so nothing can still be reading it.
     */
    private fun releaseFrameBuffer(buffer: ByteBuffer?) {
        if (buffer == null || frameBufferPool.size >= MAX_POOLED_FRAME_BUFFERS) {
            return
        }
        buffer.clear()
        frameBufferPool.add(buffer)
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

    private fun renderFrameToOutput(frame: FrameData) {
        frame.pixels.clear()
        renderBufferToOutput(frame.pixels, frame.width, frame.height)
    }

    /**
     * Blits already-computed RGBA pixels to the Media3 output surface with GL. The readable range
     * is set explicitly from the destination size instead of being inherited from whatever the
     * producer left behind: the RIFE output buffer is filled through a raw JNI pointer (so its
     * position is never advanced) and the pooled frame buffers may have a capacity larger than
     * this frame.
     */
    private fun renderBufferToOutput(pixels: ByteBuffer, width: Int, height: Int) {
        val surfaceInfo = pendingOutputSurfaceInfo ?: return
        if (!surfaceInfo.surface.isValid) {
            return
        }
        val renderer = outputRenderer ?: return
        if (!renderer.isInitialized) {
            return
        }

        val requiredBytes = width.toLong() * height.toLong() * 4L
        if (requiredBytes > Int.MAX_VALUE) {
            Log.e(TAG, "renderBufferToOutput: dimensions ${width}x$height overflow Int")
            return
        }
        pixels.position(0)
        pixels.limit(requiredBytes.toInt())

        // DIAGNOSTICS: Log checksum before sending to GlOutputRenderer. This samples the buffer
        // one byte at a time through ByteBuffer.get(offset), i.e. ~91k JNI calls per 854x427
        // frame and ~182k per interpolated pair, so it must never run on the playback path.
        if (VERBOSE_DIAGNOSTICS) {
            val renderChecksum = calculateChecksum(pixels, width, height)
            Log.d(TAG, "PIPELINE CHECKSUM: before GlOutputRenderer ${width}x$height checksum=$renderChecksum")
        }

        try {
            renderer.render(pixels, width, height)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to render a frame to the output surface", t)
        }
    }

    /**
     * Longest source edge at or above which a frame counts as 4K for the AUTO policy. 3000 covers
     * UHD (3840) and DCI 4K (4096) while staying clear of 1440p (2560).
     */
    private val auto4kMinDim = 3000

    /** Ladder the AUTO policy steps down when its native-resolution branch drops frames. */
    private val autoDegradeLadder = arrayOf(
        RifeResolution.ORIGINAL,
        RifeResolution.RES_1080P,
        RifeResolution.RES_720P,
        RifeResolution.RES_480P,
    )

    /** True when the denoiser stage is on, whichever implementation currently provides it. */
    private val isDenoiseEnabled: Boolean
        get() = fastDvdNetEngine.isEnabled

    /**
     * The processing resolution the engine picks for this source and toggle state (AUTO mode).
     *
     *  * below 4K -> the source resolution, untouched, whatever is enabled;
     *  * 4K with MEMC on -> 1080p. The interpolation cycle has to fit a 41.6 ms budget and 4K is
     *    four times the pixels; the result is scaled back up into the output surface by the
     *    present, which is where that upscaling belongs.
     *  * 4K with only the denoiser -> native 4K. This is the one branch allowed to run at source
     *    resolution, and therefore the one that steps down [autoDegradeLadder] while frames are
     *    dropped (see [reportStageTiming]).
     */
    private fun autoResolution(srcW: Int, srcH: Int): RifeResolution {
        if (maxOf(srcW, srcH) < auto4kMinDim) return RifeResolution.ORIGINAL
        if (isRifeEnabled) return RifeResolution.RES_1080P
        if (!isDenoiseEnabled) return RifeResolution.ORIGINAL
        return autoDegradeLadder[autoDegradeLevel.coerceIn(0, autoDegradeLadder.lastIndex)]
    }

    /**
     * Capture - and therefore processing - size for a decoded frame: the effective resolution
     * (explicit setting, or [autoResolution] under AUTO), aspect-preserved, then clamped to the
     * output surface. The clamp matters because the present scales the result to fit that surface
     * anyway, so capturing beyond it only buys pixels that get scaled straight back down.
     */
    private fun resolveCaptureDimensions(srcW: Int, srcH: Int): Pair<Int, Int> {
        val effective = if (resolution == RifeResolution.AUTO) autoResolution(srcW, srcH) else resolution

        // Whether this call is the AUTO branch that is allowed to degrade. Recomputed every frame
        // from the toggles and the source, not from the size that was picked, so the ladder keeps
        // stepping down after the first downgrade.
        autoDenoiseBranch = resolution == RifeResolution.AUTO &&
            !isRifeEnabled &&
            isDenoiseEnabled &&
            maxOf(srcW, srcH) >= auto4kMinDim

        if (!autoDenoiseBranch && autoDegradeLevel != 0) {
            // Left the native-4K-denoiser branch (toggle changed, source changed, mode set
            // explicitly). The ladder belongs to that branch alone, so it starts over rather than
            // inheriting a downgrade decided under different conditions.
            autoDegradeLevel = 0
        }

        val (targetW, targetH) = calculateTargetDimensions(srcW, srcH, effective)
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
            // AUTO is resolved before this is reached (see resolveCaptureDimensions). Handle it
            // here too so the mapping stays total when called directly; autoResolution() never
            // returns AUTO, so this cannot recurse.
            RifeResolution.AUTO ->
                calculateTargetDimensions(safeSrcW, safeSrcH, autoResolution(safeSrcW, safeSrcH))

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
            val inFps = frameCountInput / durationSec
            val outFps = frameCountOutput / durationSec

            val resStr = when (resolution) {
                RifeResolution.AUTO -> "Auto"
                RifeResolution.ORIGINAL -> "Original"
                RifeResolution.RES_1080P -> "1080p"
                RifeResolution.RES_720P -> "720p"
                RifeResolution.RES_480P -> "480p"
            }

            val stats = Statistics(
                inputFps = inFps,
                outputFps = outFps,
                processingTimeMs = lastProcTimeMs,
                droppedFrames = droppedFrameCount,
                currentResolution = resStr
            )

            onStatisticsUpdated(stats)

            frameCountInput = 0
            frameCountOutput = 0
            lastStatsResetTime = now
        }
    }
}
