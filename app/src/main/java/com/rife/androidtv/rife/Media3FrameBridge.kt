package com.rife.androidtv.rife

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameProcessor
import androidx.media3.common.VideoFrame
import android.graphics.SurfaceTexture
import android.opengl.EGLContext
import android.opengl.EGL14
import android.opengl.GLES20
import android.view.Surface
import android.util.Log

/**
 * Minimal frame bridge that wraps Media3's VideoFrameProcessor to expose frame callbacks.
 * This bridges the gap between the decoder output and our RIFE interpolation pipeline.
 * 
 * This is the minimal bridge - it does NOT implement the full RIFE output path.
 * It only connects the decoded frames to the existing RIFE pipeline via callbacks.
 */
@UnstableApi
class Media3FrameBridge(
    private val context: Context,
    private val frameCallback: (Long, FrameMetadata) -> Boolean,
    private val onError: (String) -> Unit,
    private val onSurfaceCreated: (Surface) -> Unit,
    private val onSurfaceFailed: () -> Unit
) {

    private var videoFrameProcessor: VideoFrameProcessor? = null
    private var inputSurface: Surface? = null
    private var outputSurface: Surface? = null
    private var eglContext: EGLContext? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var isInitialized = false
    private var frameIdCounter: Long = 0

    /** Initialize the Media3 VideoFrameProcessor with frame callbacks */
    fun initialize(): Boolean {
        if (isInitialized) return true

        try {
            // Create EGL context for GPU operations
            eglContext = createEglContext()
            if (eglContext == null) {
                onError("Failed to create EGL context")
                return false
            }

            // Create SurfaceTexture for frame capture
            surfaceTexture = SurfaceTexture(0)
            surfaceTexture.setDefaultBufferSize(1920, 1080) // Will be updated with actual size

            // Create input Surface from SurfaceTexture
            inputSurface = Surface(surfaceTexture!!)

            // Create Media3 VideoFrameProcessor with frame callbacks
            val factory = VideoFrameProcessor.Factory()
            videoFrameProcessor = factory.createVideoFrameProcessor(
                object : VideoFrameProcessor.Listener {
                    override fun onInputFrameAvailable(frame: VideoFrame) {
                        // Convert Media3 VideoFrame to our FrameMetadata and submit to pipeline
                        val frameId = frameIdCounter++
                        val metadata = FrameMetadata(
                            frameId = frameId,
                            presentationTimeUs = frame.presentationTimeUs,
                            width = frame.width,
                            height = frame.height,
                            format = mapFormat(frame.format),
                            isKeyFrame = (frame.bufferInfo.flags and 1) != 0, // KEY_FRAME flag
                            decoderMetadata = emptyMap()
                        )
                        frameCallback(frameId, metadata)
                    }

                    override fun onOutputFrameAvailable(outputFrame: VideoFrame) {
                        // Interpolated frame is ready - would be sent to output Surface here
                        // TODO: Submit to output Surface when output path is connected
                        Log.d("Media3FrameBridge", "Output frame ready: ${outputFrame.presentationTimeUs}us")
                    }

                    override fun onQueueInputFrame() {
                        // Called when the processor needs more input frames
                        // Could trigger frame capture here if needed
                    }

                    override fun onOutputFrameEnded() {
                        // Called when output frame processing ends
                    }
                },
                // Use current thread's looper (UI thread)
                null
            )

            isInitialized = true
            Log.i("Media3FrameBridge", "Media3 Frame Bridge initialized successfully")
            return true

        } catch (e: Exception) {
            onError("Failed to initialize Media3FrameBridge: ${e.message}")
            Log.e("Media3FrameBridge", "Initialization failed", e)
            return false
        }
    }

    /** Submit a frame for interpolation processing */
    fun submitFrame(frameId: Long, metadata: FrameMetadata): Boolean {
        return frameCallback(frameId, metadata)
    }

    /** Get the input Surface for MediaCodec decoder to write to */
    fun getInputSurface(): Surface? = inputSurface

    /** Set the output Surface for rendering interpolated frames */
    fun setOutputSurface(surface: Surface?) {
        outputSurface = surface
        // TODO: Configure Media3 VideoFrameProcessor output to this surface
    }

    /** Process a frame pair through the interpolation pipeline */
    fun processFramePair(): Boolean {
        // This would trigger the pipeline to process a frame pair
        // For now, the actual frame processing happens in the Media3 callbacks
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
            EGL14.eglDestroyContext(EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY), eglContext!!)
            eglContext = null
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
            Log.e("Media3FrameBridge", "EGL initialization failed")
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
            Log.e("Media3FrameBridge", "EGL config selection failed")
            return null
        }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, configs!![0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            Log.e("Media3FrameBridge", "EGL context creation failed")
            return null
        }

        return context
    }

    private fun mapFormat(media3Format: Int): FrameFormat {
        return when (media3Format) {
            androidx.media3.common.Format.COLOR_FORMAT_YUV420FLEXIBLE -> FrameFormat.YUV420
            androidx.media3.common.Format.COLOR_FORMAT_RGBA_8888 -> FrameFormat.RGBA
            androidx.media3.common.Format.COLOR_FORMAT_RGB_888 -> FrameFormat.RGB
            else -> FrameFormat.YUV420
        }
    }

    companion object {
        private const val TAG = "Media3FrameBridge"
    }
}