package dev.anilbeesetti.nextplayer.feature.player.rife

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.util.Log
import android.view.Surface
import androidx.media3.common.VideoFrame
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameProcessor

/**
 * Forked VideoFrameProcessor from NextPlayer with added frame texture callback support.
 * This allows capturing the real GPU frame resource from the decoder.
 */
@UnstableApi
class VideoFrameProcessor(
    private val context: Context,
    private val listener: Listener,
    private val frameTextureListener: FrameTextureListener? = null
) {

    interface Listener {
        fun onInputFrameAvailable(frame: VideoFrame)
        fun onOutputFrameAvailable(outputFrame: VideoFrame)
        fun onQueueInputFrame()
        fun onOutputFrameEnded()
    }

    interface FrameTextureListener {
        fun onFrameTextureAvailable(
            textureId: Int,
            width: Int,
            height: Int,
            transformMatrix: FloatArray,
            presentationTimeUs: Long,
            frameId: Long,
            eglContext: EGLContext,
            eglDisplay: EGLDisplay
        )
    }

    private var videoFrameProcessor: androidx.media3.exoplayer.video.VideoFrameProcessor? = null
    private var inputSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var eglContext: EGLContext? = null
    private var eglDisplay: EGLDisplay? = null
    private var isInitialized = false
    private var frameIdCounter: Long = 0
    private var frameWidth = 0
    private var frameHeight = 0

    fun initialize(): Boolean {
        if (isInitialized) return true

        try {
            // Create EGL context for GPU operations
            eglContext = createEglContext()
            if (eglContext == null) {
                Log.e("VideoFrameProcessor", "Failed to create EGL context")
                return false
            }
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

            // Create SurfaceTexture for frame capture
            surfaceTexture = SurfaceTexture(0)
            surfaceTexture.setDefaultBufferSize(1920, 1080)

            // Create input Surface from SurfaceTexture
            inputSurface = Surface(surfaceTexture)

            // Create Media3 VideoFrameProcessor with frame callbacks
            val factory = VideoFrameProcessor.Factory()
            videoFrameProcessor = factory.createVideoFrameProcessor(
                object : androidx.media3.exoplayer.video.VideoFrameProcessor.Listener {
                    override fun onInputFrameAvailable(frame: VideoFrame) {
                        val frameId = frameIdCounter++
                        val metadata = FrameMetadata(
                            frameId = frameId,
                            presentationTimeUs = frame.presentationTimeUs,
                            width = frame.width,
                            height = frame.height,
                            format = mapFormat(frame.format),
                            isKeyFrame = (frame.bufferInfo.flags and 1) != 0,
                            decoderMetadata = emptyMap()
                        )

                        // If we have a texture listener, also provide the GPU resource
                        frameTextureListener?.let { listener ->
                            val textureId = getTextureId()
                            val transformMatrix = FloatArray(16)
                            surfaceTexture?.getTransformMatrix(transformMatrix)

                            listener.onFrameTextureAvailable(
                                textureId = textureId,
                                width = frame.width,
                                height = frame.height,
                                transformMatrix = transformMatrix,
                                presentationTimeUs = frame.presentationTimeUs,
                                frameId = frameId,
                                eglContext = eglContext!!,
                                eglDisplay = eglDisplay!!
                            )
                        }

                        // Forward to original listener if needed
                        // (handled by the framework's internal callbacks)
                    }

                    override fun onOutputFrameAvailable(outputFrame: VideoFrame) {
                        // Output frame is available after processing
                    }

                    override fun onQueueInputFrame() {
                        // Called when processor needs more input
                    }

                    override fun onOutputFrameEnded() {
                        // Output frame processing ended
                    }
                },
                // Use current thread's looper (UI thread)
                null
            )

            isInitialized = true
            Log.i("VideoFrameProcessor", "VideoFrameProcessor initialized successfully")
            return true

        } catch (e: Exception) {
            Log.e("VideoFrameProcessor", "Initialization failed", e)
            return false
        }
    }

    /** Submit a frame for interpolation processing */
    fun submitFrame(frameId: Long, metadata: FrameMetadata): Boolean {
        return frameCallback?.invoke(frameId, metadata) ?: false
    }

    /** Get the input Surface for MediaCodec decoder to write to */
    fun getInputSurface(): Surface? = inputSurface

    /** Set the output Surface for rendering interpolated frames */
    fun setOutputSurface(surface: Surface?) {
        // TODO: Configure Media3 VideoFrameProcessor output to this surface
    }

    /** Process a frame pair through the interpolation pipeline */
    fun processFramePair(): Boolean {
        // This would trigger the pipeline to process a frame pair
        return true
    }

    /** Get next output frame for display */
    fun getNextOutputFrame(): OutputFrame? {
        // TODO: Implement when output path is connected
        return null
    }

    /** Start the frame processor */
    fun start() {
        // Media3 VideoFrameProcessor starts automatically when frames are queued
    }

    /** Stop the frame processor */
    fun stop() {
        videoFrameProcessor?.release()
        videoFrameProcessor = null
        surfaceTexture?.release()
        surfaceTexture = null
        inputSurface?.release()
        inputSurface = null
        if (eglContext != null) {
            EGL14.eglDestroyContext(eglDisplay!!, eglContext!!)
            eglContext = null
            eglDisplay = null
        }
    }

    /** Reset for discontinuity (seek, stream change) */
    fun reset() {
        videoFrameProcessor?.release()
        videoFrameProcessor = null
        surfaceTexture?.release()
        surfaceTexture = null
        inputSurface?.release()
        inputSurface = null
        isInitialized = false
        frameIdCounter = 0
    }

    /** Check if ready for processing */
    fun isReady(): Boolean = isInitialized && videoFrameProcessor != null

    /** Release all resources */
    fun release() {
        stop()
        eglContext = null
    }

    private fun createEglContext(): EGLContext? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e("VideoFrameProcessor", "EGL initialization failed")
            return null
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            Log.e("VideoFrameProcessor", "EGL config selection failed")
            return null
        }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, configs!![0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            Log.e("VideoFrameProcessor", "EGL context creation failed")
            return null
        }
        return context
    }

    private fun getTextureId(): Int {
        return surfaceTexture?.let { it.textureId } ?: 0
    }

    private fun mapFormat(media3Format: Int): Int {
        return when (media3Format) {
            androidx.media3.common.Format.COLOR_FORMAT_YUV420FLEXIBLE -> androidx.media3.common.Format.COLOR_FORMAT_YUV420FLEXIBLE
            androidx.media3.common.Format.COLOR_FORMAT_RGBA_8888 -> androidx.media3.common.Format.COLOR_FORMAT_RGBA_8888
            androidx.media3.common.Format.COLOR_FORMAT_RGB_888 -> androidx.media3.common.Format.COLOR_FORMAT_RGB_888
            else -> androidx.media3.common.Format.COLOR_FORMAT_YUV420FLEXIBLE
        }
    }

    private var videoFrameProcessor: androidx.media3.exoplayer.video.VideoFrameProcessor? = null
    private var inputSurface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var eglContext: EGLContext? = null
    private var eglDisplay: EGLDisplay? = null
    private var isInitialized = false
    private var frameIdCounter: Long = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameCallback: ((Long, FrameMetadata) -> Boolean)? = null
    private var frameTextureListener: FrameTextureListener? = null

    companion object {
        private const val TAG = "VideoFrameProcessor"
    }
}