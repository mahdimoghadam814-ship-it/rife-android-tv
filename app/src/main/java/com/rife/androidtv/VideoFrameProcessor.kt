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
    private var isProcessing = false

    private var frameCountInput = 0
    private var frameCountOutput = 0
    private var droppedFrameCount = 0L
    private var lastStatsResetTime = SystemClock.elapsedRealtime()
    private var lastProcTimeMs = 0L

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
    }

    fun onNewFrameDecoded(bitmap: Bitmap, timestampUs: Long) {
        frameCountInput++

        if (!isRifeEnabled) {
            // RIFE OFF: direct surface copy
            renderBitmapToSurface(bitmap)
            frameCountOutput++
            updateStats()
            return
        }

        // RIFE ON: Queue frame for RIFE processing thread
        val copyBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val frameData = FrameData(copyBitmap, timestampUs)

        if (!frameQueue.offer(frameData)) {
            // Queue full: drop stale frame to prevent latency growth
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
            // First frame: render directly & store as previous
            renderBitmapToSurface(nextFrame.bitmap)
            previousFrame = nextFrame
            frameCountOutput++
            updateStats()
            return
        }

        // Calculate target dimensions according to selected RIFE resolution setting
        val srcW = nextFrame.bitmap.width
        val srcH = nextFrame.bitmap.height
        val (targetW, targetH) = calculateTargetDimensions(srcW, srcH, resolution)

        // Allocate direct ByteBuffers for RGBA frame data
        val bufferSizeSrc = srcW * srcH * 4
        val bufferSizeTarget = targetW * targetH * 4

        val in0Buf = ByteBuffer.allocateDirect(bufferSizeSrc)
        val in1Buf = ByteBuffer.allocateDirect(bufferSizeSrc)
        val outBuf = ByteBuffer.allocateDirect(bufferSizeTarget)

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
            // Fallback to normal frame rendering
            renderBitmapToSurface(nextFrame.bitmap)
            frameCountOutput++
        }

        prev.bitmap.recycle()
        previousFrame = nextFrame

        updateStats()
    }

    private fun renderBitmapToSurface(bitmap: Bitmap) {
        val surfaceHolder = textureView.surfaceTexture ?: return
        val surface = Surface(surfaceHolder)
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
