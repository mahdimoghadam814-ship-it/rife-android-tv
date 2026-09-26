package com.rife.androidtv

import android.graphics.Bitmap
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the active GL_TEXTURE_EXTERNAL_OES texture from EGLSurfaceTexture into an FBO
 * at target (pre-RIFE) resolution and reads RGBA pixels into a Bitmap.
 */
class OesFrameGrabber {

    companion object {
        private const val TAG = "OesFrameGrabber"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = aTextureCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoord);
            }
        """

        private val FULL_QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
             1.0f, -1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f,
             1.0f,  1.0f, 0.0f
        )

        private val FULL_QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )
    }

    private var program = 0
    private var aPositionHandle = 0
    private var aTextureCoordHandle = 0
    private var uTextureHandle = 0

    private var fbo = 0
    private var fboTex = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(FULL_QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_VERTICES)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(FULL_QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_TEX_COORDS)
            position(0)
        }

    private var pixelBuffer: ByteBuffer? = null

    fun init() {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")
    }

    fun grabFrame(
        targetWidth: Int,
        targetHeight: Int,
        outBitmap: Bitmap
    ): Boolean {
        if (program == 0) {
            init()
        }

        ensureFbo(targetWidth, targetHeight)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, targetWidth, targetHeight)

        GLES20.glUseProgram(program)

        GLES20.glUniform1i(uTextureHandle, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val bufferSize = targetWidth * targetHeight * 4
        if (pixelBuffer == null || pixelBuffer!!.capacity() < bufferSize) {
            pixelBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
        }
        val buf = pixelBuffer!!
        buf.rewind()

        GLES20.glReadPixels(
            0, 0, targetWidth, targetHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        buf.rewind()
        outBitmap.copyPixelsFromBuffer(buf)

        return true
    }

    private fun ensureFbo(width: Int, height: Int) {
        if (fbo != 0 && fboWidth == width && fboHeight == height) {
            return
        }

        releaseFbo()

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]

        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        fboTex = texs[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTex, 0
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        fboWidth = width
        fboHeight = height
    }

    private fun releaseFbo() {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (fboTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            fboTex = 0
        }
        fboWidth = 0
        fboHeight = 0
    }

    fun release() {
        releaseFbo()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
