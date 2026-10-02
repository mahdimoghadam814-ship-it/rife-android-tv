package com.rife.androidtv.rife

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders already-computed RGBA frames to the Media3 output surface with OpenGL ES.
 *
 * This replaces the previous `Surface.lockCanvas()` + `Canvas.drawBitmap()` path, which rasterised
 * every output frame on the CPU (three full-screen bitmaps per interpolated pair) and was one of
 * the dominant costs on the TV box. Here the frame is uploaded to a texture and blitted to the
 * output EGL window surface entirely on the GPU:
 *
 * ```
 * RGBA direct buffer -> glTexImage2D -> full-screen quad -> eglSwapBuffers
 * ```
 *
 * All methods must be called on the thread that owns [context] (the RIFE worker thread), which is
 * also the thread that owns the EGL context used by [OesFrameGrabber], so the two stages share one
 * GL context and no texture or buffer has to cross threads.
 */
@UnstableApi
class GlOutputRenderer {

    companion object {
        private const val TAG = "GlOutputRenderer"

        /**
         * Per-frame render diagnostics. Runs twice per interpolated pair and includes whole-buffer
         * checksums plus three EGL queries, so it is off while profiling the real cost.
         */
        private const val VERBOSE_DIAGNOSTICS = false

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform vec2 uContentScale;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * uContentScale, aPosition.z, aPosition.w);
                vTextureCoord = aTextureCoord.xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoord);
            }
        """

        /** Clip-space positions of a full-screen triangle strip. */
        private val FULL_QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
        )

        /**
         * Texture coordinates of the same strip. The readback buffer holds the image top row first
         * (see [OesFrameGrabber]), which becomes texture row 0 after `glTexImage2D`; GL texture row
         * 0 is the BOTTOM of the sampled image, so V is flipped to keep the picture upright.
         */
        private val FULL_QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f
        )
    }

    private var program = 0
    private var aPositionHandle = -1
    private var aTextureCoordHandle = -1
    private var uTextureHandle = -1
    private var uContentScaleHandle = -1
    private var textureId = 0

    /** Dimensions currently described by [textureId]; 0 until the first upload. */
    private var textureWidth = 0
    private var textureHeight = 0

    /**
     * Clip-space scale that letterboxes the frame into the surface. Seeded to 0 so the very first
     * [updateContentScale] always reports a change and the uniform (defaulting to 0) gets set.
     */
    private var contentScaleX = 0.0f
    private var contentScaleY = 0.0f

    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var windowSurface: EGLSurface? = null
    private var outputSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /**
     * Accumulated nanoseconds per phase of [render], in order: eglMakeCurrent (+ surface resize),
     * state setup (viewport/uniforms/clear), glTexImage2D upload, attribute setup + glDrawArrays,
     * eglSwapBuffers. [VideoFrameProcessor] drains them into the PIPELINE TIMING line and resets
     * them, so a single number like "render=28.0" can be attributed to the right call.
     */
    private var nsCurrent = 0L
    private var nsSetup = 0L
    private var nsUpload = 0L
    private var nsDraw = 0L
    private var nsSwap = 0L
    private var renderCalls = 0L

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_VERTICES)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_TEX_COORDS)
            position(0)
        }

    val isInitialized: Boolean
        get() = program != 0 && textureId != 0

    /**
     * Creates the blit program and the output texture. Must be called with [context] current.
     */
    fun init(context: EGLContext) {
        if (program != 0) {
            return
        }
        this.context = context

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        val newProgram = GLES20.glCreateProgram()
        if (newProgram == 0) {
            throw IllegalStateException("glCreateProgram failed")
        }
        GLES20.glAttachShader(newProgram, vertexShader)
        GLES20.glAttachShader(newProgram, fragmentShader)
        GLES20.glLinkProgram(newProgram)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        val programLog = GLES20.glGetProgramInfoLog(newProgram)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(newProgram)
            throw IllegalStateException("Failed to link output blit program: $programLog")
        }

        program = newProgram
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        uContentScaleHandle = GLES20.glGetUniformLocation(program, "uContentScale")

        if (aPositionHandle < 0 || aTextureCoordHandle < 0 || uTextureHandle < 0 ||
            uContentScaleHandle < 0
        ) {
            release()
            throw IllegalStateException("Output blit program is missing expected attributes")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        textureWidth = 0
        textureHeight = 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        Log.i(TAG, "Output blit program ready (textureId=$textureId)")
    }

    /**
     * (Re)creates the EGL window surface that processed frames are rendered to. Must be called with
     * the worker's EGL context current. Passing `null` releases the window surface.
     */
    fun setOutputSurface(display: EGLDisplay, surface: Surface?) {
        this.display = display
        releaseWindowSurface()
        outputSurface = surface
        surfaceWidth = 0
        surfaceHeight = 0
        if (surface == null || !surface.isValid) {
            return
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            Log.e(TAG, "eglChooseConfig failed for the output surface")
            return
        }

        val newWindowSurface = EGL14.eglCreateWindowSurface(
            display,
            configs[0],
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (newWindowSurface == null || newWindowSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}")
            return
        }

        windowSurface = newWindowSurface
        updateSurfaceSize()
        Log.i(TAG, "Output window surface created (${surfaceWidth}x$surfaceHeight)")
    }

    /**
     * Moves the accumulated per-phase render costs into [into] (which must hold at least 6 longs,
     * filled as makeCurrent, setup, upload, draw, swap, call count) and resets them.
     */
    fun takeRenderBreakdown(into: LongArray) {
        into[0] = nsCurrent
        into[1] = nsSetup
        into[2] = nsUpload
        into[3] = nsDraw
        into[4] = nsSwap
        into[5] = renderCalls
        nsCurrent = 0L
        nsSetup = 0L
        nsUpload = 0L
        nsDraw = 0L
        nsSwap = 0L
        renderCalls = 0L
    }

    /**
     * Uploads [buffer] (exactly [width] x [height] RGBA bytes, top row first) and presents it.
     * Must be called with the worker's EGL context current.
     */
    fun render(buffer: ByteBuffer, width: Int, height: Int) {
        val eglDisplay = display
        val eglContext = context
        val eglSurface = windowSurface
        if (program == 0 || textureId == 0 || eglDisplay == null ||
            eglContext == null || eglSurface == null
        ) {
            return
        }
        if (width <= 0 || height <= 0) {
            return
        }

        var tPhase = System.nanoTime()
        // Two renders run back to back per interpolated pair, and eglMakeCurrent is the first
        // thing each of them does. The second call re-binds a context/surface pair that the
        // first one just established, so ask EGL rather than paying for the redundant rebind.
        // ensureEglContextCurrent() in VideoFrameProcessor uses the same query idiom.
        val alreadyCurrent = EGL14.eglGetCurrentContext() == eglContext &&
            EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == eglSurface
        if (!alreadyCurrent &&
            !EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        ) {
            Log.e(TAG, "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}")
            return
        }

        // DIAGNOSTICS: Log EGL state before rendering
        if (VERBOSE_DIAGNOSTICS) {
            val currentDisplay = EGL14.eglGetCurrentDisplay()
            val currentContext = EGL14.eglGetCurrentContext()
            val currentSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            Log.d(TAG, "RENDER EGL STATE: display=$currentDisplay context=$currentContext surface=$currentSurface")
        }

        if (surfaceWidth != width || surfaceHeight != height) {
            // The window surface keeps the size of the SurfaceView; the viewport is set from the
            // actual surface size so the frame is never stretched by a stale viewport.
            updateSurfaceSize()
        }
        nsCurrent += System.nanoTime() - tPhase

        if (VERBOSE_DIAGNOSTICS) {
            // DIAGNOSTICS: Calculate output buffer checksum
            val checksum = calculateChecksum(buffer, width, height)
            Log.d(TAG, "RENDER INPUT CHECKSUM: ${width}x$height checksum=$checksum")

            // DIAGNOSTICS: Query framebuffer binding and viewport before drawing
            val boundFbo = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFbo, 0)
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val currentProgram = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, currentProgram, 0)
            Log.d(TAG, "RENDER PRE-DRAW: boundFbo=${boundFbo[0]} viewport=${viewport.contentToString()} currentProgram=${currentProgram[0]} surfaceSize=${surfaceWidth}x$surfaceHeight")
        }

        tPhase = System.nanoTime()
        GLES20.glViewport(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureHandle, 0)

        if (updateContentScale(width, height, surfaceWidth, surfaceHeight)) {
            GLES20.glUniform2f(uContentScaleHandle, contentScaleX, contentScaleY)
        }

        // The quad no longer covers the whole surface whenever the aspect ratios differ, so the
        // bars are painted black instead of leaving the previous frame's contents on screen.
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        nsSetup += System.nanoTime() - tPhase

        val requiredBytes = width.toLong() * height.toLong() * 4L
        if (requiredBytes > Int.MAX_VALUE) {
            Log.e(TAG, "render: dimensions ${width}x$height overflow Int")
            return
        }
        buffer.position(0)
        buffer.limit(requiredBytes.toInt())
        tPhase = System.nanoTime()
        if (textureWidth != width || textureHeight != height) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
            textureWidth = width
            textureHeight = height
        } else {
            // Same shape as last frame: the storage already exists, so this is a plain
            // memcpy into it instead of letting the driver re-specify the texture.
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
        }
        nsUpload += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "Output blit failed with GL error 0x${error.toString(16)}")
        }

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        nsDraw += System.nanoTime() - tPhase

        // DIAGNOSTICS: Check eglSwapBuffers result and error. Successful swaps are silent: this
        // line fires twice per interpolated pair and is pure overhead at the frame rate we need.
        tPhase = System.nanoTime()
        val swapResult = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        val swapError = EGL14.eglGetError()
        nsSwap += System.nanoTime() - tPhase
        renderCalls++
        if (!swapResult || swapError != EGL14.EGL_SUCCESS || VERBOSE_DIAGNOSTICS) {
            Log.d(TAG, "eglSwapBuffers: result=$swapResult error=0x${swapError.toString(16)}")
        }
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

    /**
     * Recomputes [contentScaleX]/[contentScaleY] so a [srcWidth] x [srcHeight] frame fits a
     * [surfWidth] x [surfHeight] surface undistorted: the tighter of the two aspect ratios
     * governs, the surplus axis is shrunk and the freed margin is pillar- or letterboxed.
     *
     * Returns true only when the value changed, so the uniform upload can be skipped.
     */
    private fun updateContentScale(
        srcWidth: Int,
        srcHeight: Int,
        surfWidth: Int,
        surfHeight: Int
    ): Boolean {
        if (srcWidth <= 0 || srcHeight <= 0 || surfWidth <= 0 || surfHeight <= 0) {
            return false
        }
        val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()
        val surfAspect = surfWidth.toFloat() / surfHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (srcAspect > surfAspect) {
            scaleX = 1.0f
            scaleY = surfAspect / srcAspect
        } else {
            scaleX = srcAspect / surfAspect
            scaleY = 1.0f
        }
        if (scaleX == contentScaleX && scaleY == contentScaleY) {
            return false
        }
        contentScaleX = scaleX
        contentScaleY = scaleY
        return true
    }

    private fun updateSurfaceSize() {
        val eglDisplay = display ?: return
        val eglSurface = windowSurface ?: return
        val width = IntArray(1)
        val height = IntArray(1)
        if (EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, width, 0) &&
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, height, 0)
        ) {
            surfaceWidth = width[0]
            surfaceHeight = height[0]
        }
    }

    private fun releaseWindowSurface() {
        val eglDisplay = display
        val eglSurface = windowSurface
        if (eglDisplay != null && eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        windowSurface = null
        outputSurface = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    /**
     * Releases every GL object this renderer created. The EGL context itself is owned by
     * [VideoFrameProcessor] and is deleted there while it is still current.
     */
    fun release() {
        releaseWindowSurface()
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        aPositionHandle = -1
        aTextureCoordHandle = -1
        uTextureHandle = -1
        display = null
        context = null
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw IllegalStateException("glCreateShader failed for type $type")
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Failed to compile shader: $log")
        }
        return shader
    }
}
