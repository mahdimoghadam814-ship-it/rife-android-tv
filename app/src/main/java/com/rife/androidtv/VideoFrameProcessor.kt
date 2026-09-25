package com.rife.androidtv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.util.EGLSurfaceTexture
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

enum class RifeResolution {
    ORIGINAL,
    RES_720P,
    RES_480P
}

data class FrameData(
    val bitmap: Bitmap,
    val timestampUs: Long
)

data class Statistics(
    val inputFps: Float,
    val outputFps: Float,
    val processingTimeMs: Long,
    val droppedFrames: Long,
    val currentResolution: String
)

@androidx.media3.common.util.UnstableApi
class VideoFrameProcessor(
    private val displaySurfaceView: SurfaceView,
    private val onStatisticsUpdated: (Statistics) -> Unit,
    private val onError: (String) -> Unit
) : SurfaceHolder.Callback {

    @Volatile
    var isRifeEnabled = false

    @Volatile
    var resolution = RifeResolution.ORIGINAL

    private var inputSurfaceTexture: android.graphics.SurfaceTexture? = null

    var inputSurface: Surface? = null
        private set

    private var outputSurface: Surface? = null

    private val frameQueue = ArrayBlockingQueue<FrameData>(4)

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    /*
     * Media3's EGLSurfaceTexture owns the EGL/GLES context and the
     * SurfaceTexture lifecycle. Its callback is invoked after the
     * SurfaceTexture image has been updated.
     */
    private var eglSurfaceTexture: EGLSurfaceTexture? = null

    private var previousFrame: FrameData? = null

    private var frameCountInput = 0
    private var frameCountOutput = 0
    private var droppedFrameCount = 0L
    private var lastStatsResetTime = SystemClock.elapsedRealtime()
    private var lastProcTimeMs = 0L

    private var cachedIn0Buf: ByteBuffer? = null
    private var cachedIn1Buf: ByteBuffer? = null
    private var cachedOutBuf: ByteBuffer? = null
    private var cachedSrcSize = 0
    private var cachedTargetSize = 0

    init {
        displaySurfaceView.holder.addCallback(this)
    }

    fun start() {
        if (workerThread == null) {
            workerThread = HandlerThread("RifeWorkerThread").apply {
                start()
                workerHandler = Handler(looper)
            }
        }

        createInputSurface()
    }

    fun stop() {
        isRifeEnabled = false

        /*
         * Release EGL/SurfaceTexture resources on the same thread on
         * which they were created.
         */
        val handler = workerHandler
        val egl = eglSurfaceTexture

        if (handler != null && egl != null) {
            handler.post {
                try {
                    egl.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (eglSurfaceTexture === egl) {
                    eglSurfaceTexture = null
                    inputSurfaceTexture = null
                }
            }
        } else {
            eglSurfaceTexture = null
            inputSurfaceTexture = null
        }

        inputSurface?.release()
        inputSurface = null

        frameQueue.clear()

        previousFrame?.bitmap?.recycle()
        previousFrame = null

        cachedIn0Buf = null
        cachedIn1Buf = null
        cachedOutBuf = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    private fun createInputSurface() {
        val handler = workerHandler ?: return

        handler.post {
            try {
                /*
                 * EGLSurfaceTexture creates the required EGL/GLES context
                 * and a SurfaceTexture associated with that context.
                 */
                val egl = EGLSurfaceTexture(
                    handler,
                    EGLSurfaceTexture.TextureImageListener {
                        /*
                         * Media3 1.3.1 invokes this callback BEFORE it calls
                         * SurfaceTexture.updateTexImage().
                         *
                         * Post the capture work so it runs after
                         * EGLSurfaceTexture finishes updateTexImage().
                         */
                        handler.post {
                            if (isRifeEnabled) {
                                captureFrameFromInputSurface()
                            }
                        }
                    }
                )

                egl.init(EGLSurfaceTexture.SECURE_MODE_NONE)

                eglSurfaceTexture = egl
                inputSurfaceTexture = egl.surfaceTexture
                inputSurfaceTexture?.setDefaultBufferSize(1280, 720)
                inputSurface = Surface(inputSurfaceTexture)

                android.util.Log.i(
                    "RifeFrameProcessor",
                    "Input SurfaceTexture initialized with Media3 EGLSurfaceTexture"
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "RifeFrameProcessor",
                    "Failed to initialize EGL SurfaceTexture",
                    e
                )

                onError("RIFE EGL initialization failed: ${e.message}")
            }
        }
    }

    /*
     * IMPORTANT:
     *
     * We intentionally DO NOT call updateTexImage() here.
     *
     * Media3 1.3.1 performs updateTexImage() inside its own EGLSurfaceTexture
     * runnable. The TextureImageListener callback happens BEFORE that call,
     * so the callback above posts this method to the Handler. That guarantees
     * this method runs after updateTexImage() has completed.
     *
     * This first milestone only verifies the SurfaceTexture/EGL lifecycle.
     * Pixel extraction from the GL texture will be implemented separately.
     */
    private fun captureFrameFromInputSurface() {
        if (!isRifeEnabled) {
            return
        }

        frameCountInput++

        /*
         * The existing pipeline expects a Bitmap here.
         *
         * For this milestone we keep the existing placeholder behavior
         * so that we can first verify that enabling RIFE no longer crashes.
         *
         * The next milestone will replace this with actual GPU texture
         * -> CPU Bitmap readback.
         */
        val width = 640
        val height = 360

        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        val frameData = FrameData(
            bitmap,
            System.nanoTime() / 1000
        )

        if (!frameQueue.offer(frameData)) {
            droppedFrameCount++

            val dropped = frameQueue.poll()
            dropped?.bitmap?.recycle()

            frameQueue.offer(frameData)
        }

        processNextFramePair()
    }

    private fun processNextFramePair() {
        val nextFrame = frameQueue.poll() ?: return

        val prev = previousFrame

        if (prev == null) {
            renderBitmapToOutput(nextFrame.bitmap)
            previousFrame = nextFrame
            frameCountOutput++
            updateStats()
            return
        }

        val srcW = nextFrame.bitmap.width
        val srcH = nextFrame.bitmap.height

        val (targetW, targetH) =
            calculateTargetDimensions(srcW, srcH, resolution)

        val bufferSizeSrc = srcW * srcH * 4
        val bufferSizeTarget = targetW * targetH * 4

        if (cachedIn0Buf == null || cachedSrcSize != bufferSizeSrc) {
            cachedIn0Buf = ByteBuffer.allocateDirect(bufferSizeSrc)
            cachedIn1Buf = ByteBuffer.allocateDirect(bufferSizeSrc)
            cachedSrcSize = bufferSizeSrc
        }

        if (cachedOutBuf == null || cachedTargetSize != bufferSizeTarget) {
            cachedOutBuf = ByteBuffer.allocateDirect(bufferSizeTarget)
            cachedTargetSize = bufferSizeTarget
        }

        val in0Buf = cachedIn0Buf!!
        val in1Buf = cachedIn1Buf!!
        val outBuf = cachedOutBuf!!

        in0Buf.rewind()
        in1Buf.rewind()
        outBuf.rewind()

        prev.bitmap.copyPixelsToBuffer(in0Buf)
        nextFrame.bitmap.copyPixelsToBuffer(in1Buf)

        in0Buf.rewind()
        in1Buf.rewind()

        val startTime = SystemClock.elapsedRealtime()

        val success = NativeEngine.interpolateFrameBuffers(
            in0Buf,
            in1Buf,
            srcW,
            srcH,
            targetW,
            targetH,
            0.5f,
            outBuf
        )

        lastProcTimeMs =
            SystemClock.elapsedRealtime() - startTime

        if (success) {
            renderBitmapToOutput(prev.bitmap)
            frameCountOutput++

            outBuf.rewind()

            val interpBitmap = Bitmap.createBitmap(
                targetW,
                targetH,
                Bitmap.Config.ARGB_8888
            )

            interpBitmap.copyPixelsFromBuffer(outBuf)
            renderBitmapToOutput(interpBitmap)
            interpBitmap.recycle()
            frameCountOutput++

            renderBitmapToOutput(nextFrame.bitmap)
            frameCountOutput++
        } else {
            val status = NativeEngine.getRifeStatus()

            onError(
                if (status.lastError.isNotEmpty()) {
                    status.lastError
                } else {
                    "RIFE frame interpolation failed"
                }
            )

            renderBitmapToOutput(nextFrame.bitmap)
            frameCountOutput++
        }

        prev.bitmap.recycle()
        previousFrame = nextFrame

        updateStats()
    }

    private fun renderBitmapToOutput(bitmap: Bitmap) {
        val surface = outputSurface ?: return

        try {
            val canvas: Canvas = surface.lockCanvas(null)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateTargetDimensions(
        srcW: Int,
        srcH: Int,
        res: RifeResolution
    ): Pair<Int, Int> {
        return when (res) {
            RifeResolution.ORIGINAL ->
                Pair(srcW, srcH)

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
        val durationSec =
            (now - lastStatsResetTime) / 1000.0f

        if (durationSec >= 1.0f) {
            val inFps =
                frameCountInput / durationSec

            val outFps =
                frameCountOutput / durationSec

            val resStr = when (resolution) {
                RifeResolution.ORIGINAL -> "Original"
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        outputSurface = holder.surface
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        outputSurface = holder.surface
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        outputSurface = null
    }
}
