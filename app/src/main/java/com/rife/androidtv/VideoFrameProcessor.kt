package com.rife.androidtv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.util.EGLSurfaceTexture
import java.nio.ByteBuffer
import java.util.LinkedList
import java.util.concurrent.ArrayBlockingQueue

enum class RifeResolution {
    ORIGINAL,
    RES_720P,
    RES_480P
}

data class FrameData(
    val bitmap: Bitmap,
    val timestampUs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int
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

    companion object {
        private const val TAG = "VideoFrameProcessor"
    }

    @Volatile
    var isRifeEnabled = false

    @Volatile
    var isFastDvdNetEnabled = false

    @Volatile
    var resolution = RifeResolution.ORIGINAL

    private var inputSurfaceTexture: android.graphics.SurfaceTexture? = null

    var inputSurface: Surface? = null
        private set

    private var outputSurface: Surface? = null
    private var displaySurfaceWidth = 0
    private var displaySurfaceHeight = 0

    private val frameQueue = ArrayBlockingQueue<FrameData>(8)
    private val fastDvdNetWindow = LinkedList<FrameData>()

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

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
    private var cachedTargetSize = 0
    private var cachedSrcSize = 0

    private var fastDvdNetBuffers = Array(5) { ByteBuffer.allocateDirect(1280 * 720 * 4) }
    private var fastDvdNetOutBuffer = ByteBuffer.allocateDirect(1280 * 720 * 4)

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
        isFastDvdNetEnabled = false

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

        clearTemporalBuffers()

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    fun clearTemporalBuffers() {
        frameQueue.clear()
        fastDvdNetWindow.forEach { it.bitmap.recycle() }
        fastDvdNetWindow.clear()

        previousFrame?.bitmap?.recycle()
        previousFrame = null
    }

    private fun createInputSurface() {
        val handler = workerHandler ?: return

        handler.post {
            try {
                val egl = EGLSurfaceTexture(
                    handler,
                    EGLSurfaceTexture.TextureImageListener {
                        handler.post {
                            if (isRifeEnabled || isFastDvdNetEnabled) {
                                captureFrameFromInputSurface()
                            }
                        }
                    }
                )

                egl.init(EGLSurfaceTexture.SECURE_MODE_NONE)

                eglSurfaceTexture = egl
                inputSurfaceTexture = egl.surfaceTexture
                inputSurfaceTexture?.setDefaultBufferSize(1920, 1080)
                inputSurface = Surface(inputSurfaceTexture)

                Log.i(TAG, "Input SurfaceTexture initialized with Media3 EGLSurfaceTexture")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize EGL SurfaceTexture", e)
                onError("EGL initialization failed: ${e.message}")
            }
        }
    }

    private fun captureFrameFromInputSurface() {
        if (!isRifeEnabled && !isFastDvdNetEnabled) {
            return
        }

        frameCountInput++

        val sourceWidth = 1920
        val sourceHeight = 1080

        val (targetW, targetH) = calculateTargetDimensions(sourceWidth, sourceHeight, resolution)

        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)

        val frameData = FrameData(
            bitmap = bitmap,
            timestampUs = System.nanoTime() / 1000,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )

        if (!frameQueue.offer(frameData)) {
            droppedFrameCount++

            val dropped = frameQueue.poll()
            dropped?.bitmap?.recycle()

            frameQueue.offer(frameData)
        }

        processNextFramePipeline()
    }

    private fun processNextFramePipeline() {
        val nextFrame = frameQueue.poll() ?: return

        /*
         * Pipeline Stage 1: FastDVDnet Denoising (if enabled)
         */
        val processedFrame = if (isFastDvdNetEnabled) {
            fastDvdNetWindow.addLast(nextFrame)

            /* Fill startup window with duplicate initial frames if less than 5 */
            while (fastDvdNetWindow.size < 5) {
                fastDvdNetWindow.addFirst(nextFrame)
            }

            if (fastDvdNetWindow.size > 5) {
                val popped = fastDvdNetWindow.removeFirst()
                popped.bitmap.recycle()
            }

            denoiseTemporalWindow()
        } else {
            nextFrame
        }

        /*
         * Pipeline Stage 2: RIFE Frame Interpolation (if enabled)
         */
        if (isRifeEnabled) {
            processRifeInterpolation(processedFrame)
        } else {
            renderBitmapToOutput(processedFrame.bitmap)
            frameCountOutput++
            updateStats()
        }
    }

    private fun denoiseTemporalWindow(): FrameData {
        if (fastDvdNetWindow.size < 5) return fastDvdNetWindow.last

        val width = fastDvdNetWindow[2].bitmap.width
        val height = fastDvdNetWindow[2].bitmap.height
        val bufSize = width * height * 4

        for (i in 0 until 5) {
            if (fastDvdNetBuffers[i].capacity() < bufSize) {
                fastDvdNetBuffers[i] = ByteBuffer.allocateDirect(bufSize)
            }
            fastDvdNetBuffers[i].rewind()
            fastDvdNetWindow[i].bitmap.copyPixelsToBuffer(fastDvdNetBuffers[i])
            fastDvdNetBuffers[i].rewind()
        }

        if (fastDvdNetOutBuffer.capacity() < bufSize) {
            fastDvdNetOutBuffer = ByteBuffer.allocateDirect(bufSize)
        }
        fastDvdNetOutBuffer.rewind()

        val success = NativeEngine.denoiseFrameBuffer(
            fastDvdNetBuffers,
            width,
            height,
            fastDvdNetOutBuffer
        )

        if (success) {
            fastDvdNetOutBuffer.rewind()
            val denoisedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            denoisedBitmap.copyPixelsFromBuffer(fastDvdNetOutBuffer)

            return FrameData(
                bitmap = denoisedBitmap,
                timestampUs = fastDvdNetWindow[2].timestampUs,
                sourceWidth = fastDvdNetWindow[2].sourceWidth,
                sourceHeight = fastDvdNetWindow[2].sourceHeight
            )
        }

        return fastDvdNetWindow[2]
    }

    private fun processRifeInterpolation(nextFrame: FrameData) {
        val prev = previousFrame

        if (prev == null) {
            renderBitmapToOutput(nextFrame.bitmap)
            previousFrame = nextFrame
            frameCountOutput++
            updateStats()
            return
        }

        val rifeInputW = nextFrame.bitmap.width
        val rifeInputH = nextFrame.bitmap.height
        val rifeOutputW = rifeInputW
        val rifeOutputH = rifeInputH

        val bufferSizeTarget = rifeInputW * rifeInputH * 4

        if (cachedIn0Buf == null || cachedIn1Buf == null || cachedTargetSize != bufferSizeTarget) {
            cachedIn0Buf = ByteBuffer.allocateDirect(bufferSizeTarget)
            cachedIn1Buf = ByteBuffer.allocateDirect(bufferSizeTarget)
            cachedSrcSize = bufferSizeTarget
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
            rifeInputW,
            rifeInputH,
            rifeOutputW,
            rifeOutputH,
            0.5f,
            outBuf
        )

        lastProcTimeMs = SystemClock.elapsedRealtime() - startTime

        if (success) {
            renderBitmapToOutput(prev.bitmap)
            frameCountOutput++

            outBuf.rewind()

            val interpBitmap = Bitmap.createBitmap(
                rifeOutputW,
                rifeOutputH,
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
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val destRect = Rect(0, 0, canvas.width, canvas.height)
            canvas.drawBitmap(bitmap, srcRect, destRect, null)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun calculateTargetDimensions(
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
        val durationSec = (now - lastStatsResetTime) / 1000.0f

        if (durationSec >= 1.0f) {
            val inFps = frameCountInput / durationSec
            val outFps = frameCountOutput / durationSec

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
        displaySurfaceWidth = holder.surfaceFrame.width()
        displaySurfaceHeight = holder.surfaceFrame.height()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        outputSurface = holder.surface
        displaySurfaceWidth = width
        displaySurfaceHeight = height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        outputSurface = null
        displaySurfaceWidth = 0
        displaySurfaceHeight = 0
    }
}
