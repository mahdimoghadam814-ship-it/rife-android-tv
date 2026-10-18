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
 * The offline job has no display surface and no mirror, so this is the smallest possible
 * cousin of [com.rife.androidtv.rife.GlOutputRenderer]'s present path: same quad orientation
 * (texture row 0 lands at the top of the frame) and same timestamp hand-off, minus every
 * realtime feature. Must be called on the thread that owns the EGL context.
 */
class RgbaSurfacePresenter(
    private val display: EGLDisplay,
    private val config: EGLConfig,
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
        val attribs = intArrayOf(EGL14.EGL_NONE)
        windowSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
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
