package com.rife.androidtv.offline

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws top-down RGBA8888 frames into a Surface-input encoder, stamping each with its
 * presentation time.
 *
 * ```
 * RGBA ByteBuffer (interpolator output / captured source frame)
 *      -> glTexSubImage2D (one reused texture)
 *      -> fullscreen quad into the encoder's EGL window surface
 *      -> eglPresentationTimeANDROID + eglSwapBuffers
 *      -> HdrHevcEncoder's input Surface
 * ```
 *
 * The config is chosen against the encoder's input Surface itself, not pre-picked: this
 * encoder is always Main10 and a Main10 input surface rejects an 8-bit config with
 * EGL_BAD_MATCH (the same reason GlOutputRenderer keeps strict/10-bit/relaxed attempts).
 * The offline job has no display surface and no mirror, so this is the smallest possible
 * cousin of [com.rife.androidtv.rife.GlOutputRenderer]'s present path: same quad orientation
 * (texture row 0 lands at the top of the frame) and same timestamp hand-off, minus every
 * realtime feature. Must be called on the thread that owns the EGL context.
 */
class RgbaSurfacePresenter(
    private val display: EGLDisplay,
    private val context: android.opengl.EGLContext,
    width: Int,
    height: Int,
    surface: Surface,
) {

    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var textureHandle = -1
    private val width = width
    private val height = height

    private val vertices: FloatBuffer = directFloat(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
    )
    // V flipped so glReadPixels-style top-down row 0 lands at the top of the presented frame,
    // matching what OesFrameGrabber.read produced and what GlOutputRenderer presents.
    private val texCoords: FloatBuffer = directFloat(
        0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f,
    )

    init {
        windowSurface = createEncoderWindowSurface(surface)
        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            throw IllegalStateException("eglMakeCurrent(encoder surface) failed")
        }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        program = buildProgram()
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        Log.i(TAG, "presenter ready ${width}x$height")
    }

    /**
     * Creates the window surface over the encoder's input Surface, trying strict 8-bit, then
     * the 10-bit RGB10_A2 every Main10 input surface needs, then an unconstrained config that
     * lets the driver match the native window's format - the same three attempts
     * GlOutputRenderer makes, whose failure signal is EGL_BAD_MATCH from this very call.
     */
    private fun createEncoderWindowSurface(surface: Surface): EGLSurface {
        val strict = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val hdr = intArrayOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val relaxed = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val attempts = arrayOf(
            "strict8bit" to strict,
            "hdr10bit" to hdr,
            "relaxed" to relaxed,
        )
        var lastError = 0
        for ((attempt, configAttribs) in attempts) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0) ||
                count[0] == 0
            ) {
                Log.w(TAG, "eglChooseConfig found no config (attempt=$attempt)")
                continue
            }
            val candidate = EGL14.eglCreateWindowSurface(
                display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (candidate != null && candidate != EGL14.EGL_NO_SURFACE) {
                Log.i(TAG, "encoder window surface created (attempt=$attempt)")
                return candidate
            }
            lastError = EGL14.eglGetError()
            Log.w(TAG, "eglCreateWindowSurface failed (attempt=$attempt): 0x$lastError")
        }
        throw IllegalStateException(
            "no EGL config matched the encoder input surface (last error 0x" +
                Integer.toHexString(lastError) + ")",
        )
    }

    /** Uploads [rgba] (width*height*4 bytes, top-down) and presents it at [ptsUs]. */
    fun present(rgba: ByteBuffer, ptsUs: Long) {
        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            throw IllegalStateException("eglMakeCurrent failed before present")
        }
        rgba.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba,
        )
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw IllegalStateException("GL error 0x${Integer.toHexString(error)} during present")
        }
        EGLExt.eglPresentationTimeANDROID(display, windowSurface, ptsUs * 1_000L)
        if (!EGL14.eglSwapBuffers(display, windowSurface)) {
            throw IllegalStateException("eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun buildProgram(): Int {
        val vertex = compile(
            GLES20.GL_VERTEX_SHADER,
            """
                attribute vec2 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vTexCoord = aTexCoord;
                }
            """.trimIndent(),
        )
        val fragment = compile(
            GLES20.GL_FRAGMENT_SHADER,
            """
                #ifdef GL_FRAGMENT_PRECISION_HIGH
                precision highp float;
                #else
                precision mediump float;
                #endif
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """.trimIndent(),
        )
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertex)
        GLES20.glAttachShader(prog, fragment)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw IllegalStateException("presenter program failed to link: $log")
        }
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private fun directFloat(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private const val TAG = "RgbaPresenter"
    }
}
