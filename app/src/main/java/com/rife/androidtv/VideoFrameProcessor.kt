package com.rife.androidtv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
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
    private val textureView: TextureView,
    private val onStatisticsUpdated: (Statistics) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile var isRifeEnabled = false
    @Volatile var resolution = RifeResolution.ORIGINAL

    private val frameQueue = ArrayBlockingQueue<FrameData>(4)
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var previousFrame: FrameData? = null

    private var frameCountInput = 0
    private var frameCountOutput = 0
    private var droppedFrameCount = 0L
    private var lastStatsResetTime = SystemClock.elapsedRealtime()
    private var lastProcTimeMs = 0L

    // Reusable ByteBuffers to avoid memory allocation thrashing per frame
    private var cachedIn0Buf: ByteBuffer? = null
    private var cachedIn1Buf: ByteBuffer? = null
    private var cachedOutBuf: ByteBuffer? = null
    private var cachedSrcSize = 0
    private var cachedTargetSize = 0

    fun start() {
        if (workerThread == null) {
            workerThread = HandlerThread("RifeWorkerThread").apply {
                start()
                workerHandler = Handler(looper)
            }
        }
    }

    fun stop() {
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        frameQueue.clear()
        previousFrame?.bitmap?.recycle()
        previousFrame = null
        cachedIn0Buf = null
        cachedIn1Buf = null
        cachedOutBuf = null
    }

    fun onNewFrameDecoded(bitmap: Bitmap, timestampUs: Long) {
        frameCountInput++

        if (!isRifeEnabled) {
            frameCountOutput++
            updateStats()
            return
        }

        val copyBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val frameData = FrameData(copyBitmap, timestampUs)

        if (!frameQueue.offer(frameData)) {
            droppedFrameCount++
            val dropped = frameQueue.poll()
            dropped?.bitmap?.recycle()
            frameQueue.offer(frameData)
        }

        scheduleWorkerProcessing()
    }

    private fun scheduleWorkerProcessing() {
        workerHandler?.post {
            processNextFramePair()
        }
    }

    private fun processNextFramePair() {
        val nextFrame = frameQueue.poll() ?: return

        val prev = previousFrame
        if (prev == null) {
            renderBitmapToSurface(nextFrame.bitmap)
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

        // Reuse ByteBuffers to eliminate heap allocation & GC thrashing
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
            // 1. Render previous original frame
            renderBitmapToSurface(prev.bitmap)
            frameCountOutput++

            // 2. Render interpolated frame (0.5 timestep)
            outBuf.rewind()
            val interpBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            interpBitmap.copyPixelsFromBuffer(outBuf)
            renderBitmapToSurface(interpBitmap)
            interpBitmap.recycle()
            frameCountOutput++

            // 3. Render next original frame
            renderBitmapToSurface(nextFrame.bitmap)
            frameCountOutput++
        } else {
            val status = NativeEngine.getRifeStatus()
            onError(if (status.lastError.isNotEmpty()) status.lastError else "RIFE frame interpolation failed")
            renderBitmapToSurface(nextFrame.bitmap)
            frameCountOutput++
        }

        prev.bitmap.recycle()
        previousFrame = nextFrame

        updateStats()
    }

    private fun renderBitmapToSurface(bitmap: Bitmap) {
        val surfaceTexture = textureView.surfaceTexture ?: return
        val surface = Surface(surfaceTexture)
        try {
            val canvas: Canvas = surface.lockCanvas(null)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            surface.release()
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
}
