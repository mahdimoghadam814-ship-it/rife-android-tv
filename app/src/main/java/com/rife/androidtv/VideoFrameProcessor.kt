package com.rife.androidtv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
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
    var resolution = RifeResolution.ORIGINAL

    @Volatile
    private var sourceWidth = 1920

    @Volatile
    private var sourceHeight = 1080

    private var inputSurfaceTexture: SurfaceTexture? = null

    var inputSurface: Surface? = null
        private set

    private var outputSurface: Surface? = null
    private var displaySurfaceWidth = 0
    private var displaySurfaceHeight = 0

    private val frameQueue = ArrayBlockingQueue<FrameData>(4)

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var inferenceThread: HandlerThread? = null
    private var inferenceHandler: Handler? = null

    private var eglSurfaceTexture: EGLSurfaceTexture? = null
    private var frameGrabber: OesFrameGrabber? = null

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

    private var cachedCaptureBitmap: Bitmap? = null
    private var cachedInterpBitmap: Bitmap? = null

    init {
        displaySurfaceView.holder.addCallback(this)
    }

    fun setSourceVideoDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            sourceWidth = width
            sourceHeight = height
            Log.i(TAG, "Source video dimensions updated: ${width}x${height}")
        }
    }

    fun start() {
        if (workerThread == null) {
            workerThread = HandlerThread("RifeCaptureThread").apply {
                start()
                workerHandler = Handler(looper)
            }
        }

        if (inferenceThread == null) {
            inferenceThread = HandlerThread("RifeInferenceThread").apply {
                start()
                inferenceHandler = Handler(looper)
            }
        }

        createInputSurface()
    }

    fun stop() {
        isRifeEnabled = false

        val handler = workerHandler
        val egl = eglSurfaceTexture

        if (handler != null) {
            handler.post {
                try {
                    frameGrabber?.release()
                    frameGrabber = null
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (egl != null) {
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
            }
        } else {
            frameGrabber = null
            eglSurfaceTexture = null
            inputSurfaceTexture = null
        }

        inputSurface?.release()
        inputSurface = null

        frameQueue.clear()

        previousFrame?.bitmap?.recycle()
        previousFrame = null

        cachedCaptureBitmap?.recycle()
        cachedCaptureBitmap = null

        cachedInterpBitmap?.recycle()
        cachedInterpBitmap = null

        cachedIn0Buf = null
        cachedIn1Buf = null
        cachedOutBuf = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        inferenceThread?.quitSafely()
        inferenceThread = null
        inferenceHandler = null
    }

    private fun createInputSurface() {
        val handler = workerHandler ?: return

        handler.post {
            try {
                val egl = EGLSurfaceTexture(
                    handler,
                    EGLSurfaceTexture.TextureImageListener {
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
                inputSurfaceTexture?.setDefaultBufferSize(sourceWidth, sourceHeight)
                inputSurface = Surface(inputSurfaceTexture)

                Log.i(TAG, "Input SurfaceTexture initialized with Media3 EGLSurfaceTexture")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize EGL SurfaceTexture", e)
                onError("RIFE EGL initialization failed: ${e.message}")
            }
        }
    }

    private fun captureFrameFromInputSurface() {
        if (!isRifeEnabled) {
            return
        }

        val egl = eglSurfaceTexture ?: return
        val surfaceTex = egl.surfaceTexture

        frameCountInput++

        val srcW = sourceWidth
        val srcH = sourceHeight

        val (preRifeW, preRifeH) = calculateTargetDimensions(srcW, srcH, resolution)

        Log.d(
            TAG,
            "FRAME CAPTURE LOG: sourceDimensions=${srcW}x${srcH} -> preRifeDimensions=${preRifeW}x${preRifeH}"
        )

        var captureBitmap = cachedCaptureBitmap
        if (captureBitmap == null || captureBitmap.width != preRifeW || captureBitmap.height != preRifeH) {
            captureBitmap?.recycle()
            captureBitmap = Bitmap.createBitmap(preRifeW, preRifeH, Bitmap.Config.ARGB_8888)
            cachedCaptureBitmap = captureBitmap
        }

        if (frameGrabber == null) {
            frameGrabber = OesFrameGrabber()
        }

        val grabbed = try {
            frameGrabber?.grabFrame(
                surfaceTexture = surfaceTex,
                targetWidth = preRifeW,
                targetHeight = preRifeH,
                outBitmap = captureBitmap
            ) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error grabbing frame from OES texture", e)
            false
        }

        if (!grabbed) {
            return
        }

        verifyBitmapPixels(captureBitmap, "CAPTURED_FRAME")

        val frameBitmapCopy = captureBitmap.copy(Bitmap.Config.ARGB_8888, false)

        val frameData = FrameData(
            bitmap = frameBitmapCopy,
            timestampUs = System.nanoTime() / 1000,
            sourceWidth = srcW,
            sourceHeight = srcH
        )

        if (!frameQueue.offer(frameData)) {
            droppedFrameCount++

            val dropped = frameQueue.poll()
            dropped?.bitmap?.recycle()

            frameQueue.offer(frameData)
        }

        scheduleInference()
    }

    private fun scheduleInference() {
        inferenceHandler?.post {
            if (isRifeEnabled) {
                processNextFramePair()
            }
        }
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

        val rifeInputW = nextFrame.bitmap.width
        val rifeInputH = nextFrame.bitmap.height
        val rifeOutputW = rifeInputW
        val rifeOutputH = rifeInputH

        Log.i(
            TAG,
            "REAL RIFE EXECUTION LOG: sourceDimensions=${nextFrame.sourceWidth}x${nextFrame.sourceHeight} -> preRifeDimensions=${rifeInputW}x${rifeInputH} -> rifeInputDimensions=${rifeInputW}x${rifeInputH} -> rifeOutputDimensions=${rifeOutputW}x${rifeOutputH} -> renderingSurfaceDimensions=${displaySurfaceWidth}x${displaySurfaceHeight}"
        )

        val bufferSizeTarget = rifeInputW * rifeInputH * 4

        if (cachedIn0Buf == null || cachedIn1Buf == null || cachedTargetSize != bufferSizeTarget) {
            cachedIn0Buf = ByteBuffer.allocateDirect(bufferSizeTarget)
            cachedIn1Buf = ByteBuffer.allocateDirect(bufferSizeTarget)
            cachedTargetSize = bufferSizeTarget
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

            var interpBitmap = cachedInterpBitmap
            if (interpBitmap == null || interpBitmap.width != rifeOutputW || interpBitmap.height != rifeOutputH) {
                interpBitmap?.recycle()
                interpBitmap = Bitmap.createBitmap(rifeOutputW, rifeOutputH, Bitmap.Config.ARGB_8888)
                cachedInterpBitmap = interpBitmap
            }

            interpBitmap.copyPixelsFromBuffer(outBuf)
            verifyBitmapPixels(interpBitmap, "RIFE_OUTPUT_INTERP_FRAME")

            renderBitmapToOutput(interpBitmap)
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

    private fun verifyBitmapPixels(bitmap: Bitmap, label: String) {
        val w = bitmap.width
        val h = bitmap.height
        val centerPixel = bitmap.getPixel(w / 2, h / 2)
        val isCenterBlack = (centerPixel and 0x00FFFFFF) == 0

        Log.d(
            TAG,
            "DIAGNOSTIC PIXEL CHECK [$label]: size=${w}x${h}, centerPixel=0x${Integer.toHexString(centerPixel)}, isBlack=$isCenterBlack"
        )
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
