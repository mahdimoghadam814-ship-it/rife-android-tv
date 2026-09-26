package com.rife.androidtv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.FrameInfo
import androidx.media3.common.OnInputFrameProcessedListener
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.media3.common.VideoFrameProcessor as Media3VideoFrameProcessor

enum class RifeResolution {
    ORIGINAL,
    RES_1080P,
    RES_720P,
    RES_480P
}

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
 *  * the display surface of [displaySurfaceView] is published through `setOutputSurfaceInfo()`, and
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
    private val displaySurfaceView: SurfaceView,
    private val onStatisticsUpdated: (Statistics) -> Unit,
    private val onError: (String) -> Unit,
    private val onInputSurfaceCreated: (Surface) -> Unit = {},
    private val onInputSurfaceFailed: () -> Unit = {}
) : SurfaceHolder.Callback, Media3VideoFrameProcessor {

    companion object {
        private const val TAG = "VideoFrameProcessor"

        /** Bounded queue: the pipeline must never grow faster than it can interpolate. */
        private const val FRAME_QUEUE_CAPACITY = 4

        /** Pooled capture buffers. Bounded so a 4K stream cannot inflate the heap. */
        private const val MAX_POOLED_FRAME_BUFFERS = 6

        private const val WORKER_TASK_TIMEOUT_MS = 3000L

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
            effects: MutableList<Effect>,
            frameInfo: FrameInfo
        ) {
            Log.i(
                TAG,
                "Media3 input stream registered: type=$inputType ${frameInfo.width}x${frameInfo.height}"
            )
        }

        override fun onOutputSizeChanged(width: Int, height: Int) {
            Log.i(TAG, "Media3 output size changed: ${width}x$height")
        }

        override fun onOutputFrameAvailableForRendering(presentationTimeUs: Long) {
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

    /** Reusable bitmap used only to blit already-computed pixels to the output Surface. */
    private var outputBitmap: Bitmap? = null

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

    private val mainHandler: Handler? = try {
        Handler(Looper.getMainLooper())
    } catch (t: Throwable) {
        null
    }

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

    init {
        displaySurfaceView.holder.addCallback(this)
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
        effects: MutableList<Effect>?,
        frameInfo: FrameInfo
    ) {
        check(inputType == Media3VideoFrameProcessor.INPUT_TYPE_SURFACE) {
            "RIFE only supports INPUT_TYPE_SURFACE"
        }
        if (inputWidth <= 0 || inputHeight <= 0) {
            inputWidth = frameInfo.width
            inputHeight = frameInfo.height
        }
        val handler = workerHandler ?: run {
            Log.w(TAG, "registerInputStream(): worker thread is gone, skipping")
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                registerInputStreamOnWorker(FrameInfo.Builder(frameInfo).build())
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
            if (outputSurfaceInfo == null) {
                Log.i(TAG, "Output surface released")
            } else {
                Log.i(
                    TAG,
                    "Output surface set: ${outputSurfaceInfo.width}x${outputSurfaceInfo.height} " +
                        "(orientationDegrees=${outputSurfaceInfo.orientationDegrees})"
                )
            }
        }
    }

    override fun setOnInputFrameProcessedListener(listener: OnInputFrameProcessedListener?) {
        // Frames are consumed and rendered internally as soon as they are read back, so there is
        // no external handshake to drive. Accepted for API compatibility.
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
        return FrameInfo.Builder(ColorInfo.SDR_BT709_LIMITED, width, height).build()
    }

    private fun registerInputStreamOnWorker(frameInfo: FrameInfo) {
        inputStreamRegistered = true
        endOfInputSignalled = false
        try {
            media3Listener.onInputStreamRegistered(
                Media3VideoFrameProcessor.INPUT_TYPE_SURFACE,
                ArrayList<Effect>(),
                frameInfo
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
        cachedTargetSize = 0
        readbackInProgress = false

        frameCountInput = 0
        frameCountOutput = 0
        lastStatsResetTime = SystemClock.elapsedRealtime()
        lastProcTimeMs = 0L

        Log.i(TAG, "resetPipeline: reason=$reason discardedFrames=$discarded")
    }

    private fun releaseStateOnWorker() {
        pendingOutputSurfaceInfo = null
        resetPipelineOnWorker("release")
        releaseOutputBitmap()

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

    private fun releaseOutputBitmap() {
        val bitmap = outputBitmap
        outputBitmap = null
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
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
        // ratio of the source is preserved, so the frame is never stretched.
        val (captureWidth, captureHeight) =
            calculateTargetDimensions(sourceWidth, sourceHeight, resolution)

        val pixels = obtainFrameBuffer(captureWidth, captureHeight)
        if (!grabber.read(texture, captureWidth, captureHeight, pixels)) {
            releaseFrameBuffer(pixels)
            droppedFrameCount++
            return
        }

        frameCountInput++

        Log.d(
            TAG,
            "FRAME CAPTURE LOG: decoded=${sourceWidth}x$sourceHeight -> " +
                "preRife=${captureWidth}x$captureHeight"
        )

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

        val rifeInputW = nextFrame.width
        val rifeInputH = nextFrame.height
        val rifeOutputW = rifeInputW
        val rifeOutputH = rifeInputH

        val requiredInputBytes = rifeInputW * rifeInputH * 4

        // The JNI layer never checks the output capacity, and RifeEngine::processFrameBuffer() ends
        // with ncnn::Mat::to_pixels_resize(out_ptr, PIXEL_RGB2RGBA, w, h), which writes exactly
        // rifeOutputW * rifeOutputH * 4 bytes through that raw pointer. The Java side therefore has
        // to guarantee that capacity itself.
        val requiredOutputBytes = rifeOutputW * rifeOutputH * 4

        if (!ensureCachedBuffers(requiredInputBytes, requiredOutputBytes)) {
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
        in0Buf.clear()
        in1Buf.clear()
        in0Buf.put(prev.pixels)
        in1Buf.put(nextFrame.pixels)
        in0Buf.flip()
        in1Buf.flip()

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
            } else {
                Log.w(TAG, "FastDVDnet stage failed, interpolating the raw frames")
            }
        }

        Log.i(
            TAG,
            "REAL RIFE EXECUTION LOG: preRifeDimensions=${rifeInputW}x$rifeInputH -> " +
                "rifeInputDimensions=${rifeInputW}x$rifeInputH -> " +
                "rifeOutputDimensions=${rifeOutputW}x$rifeOutputH -> " +
                "renderingSurfaceDimensions=${displaySurfaceWidth}x$displaySurfaceHeight"
        )

        // The output buffer is deliberately NOT flipped here. NativeEngine.interpolateFrameBuffers()
        // reaches the memory through JNI GetDirectBufferAddress() and writes into it directly, so
        // the Java position stays at 0 and a flip() would only publish limit = 0.
        outBuf.clear()

        val success = NativeEngine.interpolateFrameBuffers(
            src0Buf,
            src1Buf,
            rifeInputW,
            rifeInputH,
            rifeOutputW,
            rifeOutputH,
            0.5f,
            outBuf
        )

        lastProcTimeMs = SystemClock.elapsedRealtime() - startTime

        if (success) {
            // The native code wrote requiredOutputBytes of RGBA through the direct address and never
            // touched the Java ByteBuffer position, so the readable range is established here rather
            // than with flip(): position 0, limit = requiredOutputBytes. That is exactly what
            // renderBufferToOutput() -> Bitmap.copyPixelsFromBuffer() needs.
            outBuf.position(0)
            outBuf.limit(requiredOutputBytes)

            // Temporal order has to stay previous -> interpolated -> next, otherwise the output
            // playback runs backwards or repeats.
            renderFrameToOutput(prev)
            frameCountOutput++

            renderBufferToOutput(outBuf, rifeOutputW, rifeOutputH)
            frameCountOutput++

            renderFrameToOutput(nextFrame)
            frameCountOutput++
        } else {
            val status = NativeEngine.getRifeStatus()
            reportError(
                if (status.lastError.isNotEmpty()) {
                    status.lastError
                } else {
                    "RIFE frame interpolation failed"
                }
            )
            renderFrameToOutput(nextFrame)
            frameCountOutput++
        }

        releaseFrameBuffer(prev.pixels)
        previousFrame = nextFrame

        updateStats()
    }

    /**
     * Allocates the reusable direct buffers used by the JNI stage, keeping one allocation per size
     * change instead of one per frame.
     */
    private fun ensureCachedBuffers(requiredInputBytes: Int, requiredOutputBytes: Int): Boolean {
        if (requiredInputBytes <= 0 || requiredOutputBytes <= 0) {
            return false
        }
        val requiredBytes = maxOf(requiredInputBytes, requiredOutputBytes)
        if (cachedIn0Buf == null || cachedIn1Buf == null || cachedDenoised0Buf == null ||
            cachedDenoised1Buf == null || cachedOutBuf == null || cachedTargetSize != requiredBytes
        ) {
            cachedIn0Buf = ByteBuffer.allocateDirect(requiredBytes)
            cachedIn1Buf = ByteBuffer.allocateDirect(requiredBytes)
            cachedDenoised0Buf = ByteBuffer.allocateDirect(requiredBytes)
            cachedDenoised1Buf = ByteBuffer.allocateDirect(requiredBytes)
            cachedOutBuf = ByteBuffer.allocateDirect(requiredBytes)
            cachedTargetSize = requiredBytes
            Log.i(TAG, "Allocated RIFE buffers ($requiredBytes bytes each)")
        }
        return true
    }

    private fun obtainFrameBuffer(width: Int, height: Int): ByteBuffer {
        val requiredBytes = width * height * 4
        while (true) {
            val pooled = frameBufferPool.poll() ?: break
            if (pooled.capacity() >= requiredBytes) {
                pooled.clear()
                return pooled
            }
        }
        return ByteBuffer.allocateDirect(requiredBytes)
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

    private fun renderFrameToOutput(frame: FrameData) {
        frame.pixels.clear()
        renderBufferToOutput(frame.pixels, frame.width, frame.height)
    }

    /** Blits already-computed RGBA pixels to the Media3 output surface. */
    private fun renderBufferToOutput(pixels: ByteBuffer, width: Int, height: Int) {
        val surfaceInfo = pendingOutputSurfaceInfo ?: return
        val surface = surfaceInfo.surface
        if (!surface.isValid) {
            return
        }

        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            val bitmap = obtainOutputBitmap(width, height)

            // copyPixelsFromBuffer() consumes exactly width * height * 4 bytes starting at
            // position 0, so the readable range is set explicitly from the destination size
            // instead of being inherited from whatever the producer left behind: the RIFE output
            // buffer is filled through a raw JNI pointer (so its position is never advanced) and
            // the pooled frame buffers may have a capacity larger than this frame.
            val requiredBytes = width * height * 4
            pixels.position(0)
            pixels.limit(requiredBytes)
            bitmap.copyPixelsFromBuffer(pixels)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, canvas.width, canvas.height),
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to render a frame to the output surface", e)
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to post a rendered frame", e)
                }
            }
        }
    }

    private fun obtainOutputBitmap(width: Int, height: Int): Bitmap {
        val existing = outputBitmap
        if (existing != null && !existing.isRecycled &&
            existing.width == width && existing.height == height
        ) {
            return existing
        }
        releaseOutputBitmap()
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outputBitmap = created
        return created
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
        return when (res) {
            RifeResolution.ORIGINAL ->
                Pair(srcW, srcH)

            RifeResolution.RES_1080P -> {
                val maxDim = 1920

                if (srcW > srcH && srcW > maxDim) {
                    Pair(maxDim, (srcH * maxDim) / srcW)
                } else if (srcH >= srcW && srcH > maxDim) {
                    Pair((srcW * maxDim) / srcH, maxDim)
                } else {
                    Pair(srcW, srcH)
                }
            }

            RifeResolution.RES_720P -> {
                val maxDim = 1280

                if (srcW > srcH && srcW > maxDim) {
                    Pair(maxDim, (srcH * maxDim) / srcW)
                } else if (srcH >= srcW && srcH > maxDim) {
                    Pair((srcW * maxDim) / srcH, maxDim)
                } else {
                    Pair(srcW, srcH)
                }
            }

            RifeResolution.RES_480P -> {
                val maxDim = 854

                if (srcW > srcH && srcW > maxDim) {
                    Pair(maxDim, (srcH * maxDim) / srcW)
                } else if (srcH >= srcW && srcH > maxDim) {
                    Pair((srcW * maxDim) / srcH, maxDim)
                } else {
                    Pair(srcW, srcH)
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

    // ---------------------------------------------------------------------------------------
    // Output SurfaceHolder.Callback: the SurfaceView the processed result is drawn to
    // ---------------------------------------------------------------------------------------

    private var displaySurfaceWidth = 0
    private var displaySurfaceHeight = 0

    override fun surfaceCreated(holder: SurfaceHolder) {
        displaySurfaceWidth = holder.surfaceFrame.width()
        displaySurfaceHeight = holder.surfaceFrame.height()
        setOutputSurfaceInfo(buildSurfaceInfo(holder))
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        displaySurfaceWidth = width
        displaySurfaceHeight = height
        setOutputSurfaceInfo(buildSurfaceInfo(holder))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        displaySurfaceWidth = 0
        displaySurfaceHeight = 0
        // Hand the surface back so the processor stops rendering to it immediately.
        setOutputSurfaceInfo(null)
    }

    private fun buildSurfaceInfo(holder: SurfaceHolder): SurfaceInfo? {
        val surface = holder.surface
        if (!surface.isValid) {
            return null
        }
        val width = holder.surfaceFrame.width()
        val height = holder.surfaceFrame.height()
        return SurfaceInfo(
            surface,
            if (width > 0) width else FALLBACK_FRAME_WIDTH,
            if (height > 0) height else FALLBACK_FRAME_HEIGHT
        )
    }
}
