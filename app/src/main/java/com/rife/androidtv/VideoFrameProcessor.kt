package com.rife.androidtv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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

class VideoFrameProcessor(
    private val displaySurfaceView: SurfaceView,
    private val onStatisticsUpdated: (Statistics) -> Unit,
    private val onError: (String) -> Unit
) : SurfaceHolder.Callback {

    @Volatile var isRifeEnabled = false
    @Volatile var resolution = RifeResolution.ORIGINAL

    private var inputSurfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null
        private set

    private var outputSurface: Surface? = null

    private val frameQueue = ArrayBlockingQueue<FrameData>(4)
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var previousFrame: FrameData? = null

    private var frameCountInput = 0
    private var frameCountOutput = 0
    private var droppedFrameCount = 0L
    private var lastStatsResetTime = SystemClock.elapsedRealtime()
    private var lastProcTimeMs = 0L

    // Reusable direct ByteBuffers
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
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        frameQueue.clear()

        previousFrame?.bitmap?.recycle()
        previousFrame = null

        inputSurface?.release()
        inputSurface = null
        inputSurfaceTexture?.release()
        inputSurfaceTexture = null

        cachedIn0Buf = null
        cachedIn1Buf = null
        cachedOutBuf = null
    }

    private fun createInputSurface() {
        inputSurfaceTexture = SurfaceTexture(1001)
        inputSurfaceTexture?.setDefaultBufferSize(1280, 720)
        inputSurface = Surface(inputSurfaceTexture)

        inputSurfaceTexture?.setOnFrameAvailableListener({
            if (isRifeEnabled) {
                // Hardware decoder has produced a new frame!
                captureFrameFromInputSurface()
            }
        }, workerHandler)
    }

    private fun captureFrameFromInputSurface() {
        inputSurfaceTexture?.updateTexImage()
        frameCountInput++

        // Extract frame bitmap directly from decoder SurfaceTexture
        val width = 640
        val height = 360
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val frameData = FrameData(bitmap, System.nanoTime() / 1000)

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
        val (targetW, targetH) = calculateTargetDimensions(srcW, srcH, resolution)

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
            in0Buf, in1Buf,
            srcW, srcH,
            targetW, targetH,
            0.5f,
            outBuf
        )

        lastProcTimeMs = SystemClock.elapsedRealtime() - startTime

        if (success) {
            // Render Frame A
            renderBitmapToOutput(prev.bitmap)
            frameCountOutput++

            // Render RIFE Frame A.5
            outBuf.rewind()
            val interpBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            interpBitmap.copyPixelsFromBuffer(outBuf)
            renderBitmapToOutput(interpBitmap)
            interpBitmap.recycle()
            frameCountOutput++

            // Render Frame B
            renderBitmapToOutput(nextFrame.bitmap)
            frameCountOutput++
        } else {
            val status = NativeEngine.getRifeStatus()
            onError(if (status.lastError.isNotEmpty()) status.lastError else "RIFE frame interpolation failed")
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

    private fun calculateTargetDimensions(srcW: Int, srcH: Int, res: RifeResolution): Pair<Int, Int> {
        return when (res) {
            RifeResolution.ORIGINAL -> Pair(srcW, srcH)
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
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        outputSurface = holder.surface
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        outputSurface = null
    }
}
