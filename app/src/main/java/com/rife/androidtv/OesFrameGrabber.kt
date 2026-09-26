package com.rife.androidtv

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The single OES -> FBO -> RGBA readback path of this app.
 *
 * A MediaCodec video renderer (ExoPlayer) renders the decoded frame into a `Surface` that wraps a
 * [SurfaceTexture]. The frame itself stays on the GPU as a `GL_TEXTURE_EXTERNAL_OES` texture, so it
 * has to be sampled explicitly before any CPU/JNI stage can see it:
 *
 * ```
 * MediaCodec -> Surface(SurfaceTexture) -> GL_TEXTURE_EXTERNAL_OES
 *            -> OesFrameGrabber (FBO of the pre-RIFE size)
 *            -> glReadPixels -> direct RGBA ByteBuffer
 *            -> FastDVDnet scaffold (optional) -> RIFE JNI -> output Surface
 * ```
 *
 * Three details are load bearing and must not be "simplified":
 *  * the external texture is explicitly bound to texture unit 0 for every draw. Without the bind the
 *    sampler reads whatever happens to live on unit 0, which is what produced the black screen that
 *    this path was written to fix;
 *  * the 4x4 (16 float, column major) SurfaceTexture transform matrix is uploaded verbatim with
 *    `glUniformMatrix4fv`. There is deliberately no 3x3 -> 4x4 conversion, because a conversion
 *    silently drops the perspective/offset terms the decoder's crop matrix uses;
 *  * the quad's V coordinate is flipped so that row 0 of the `glReadPixels` result is the first image
 *    line. `glReadPixels` reads bottom-up, and RIFE plus `Bitmap.copyPixelsFromBuffer` both expect
 *    top-down rows.
 *
 * Every method must be called on the thread that owns the EGL context used to create the program and
 * the FBO (the RIFE worker thread). The class holds no per-frame allocations: the shaders, the quad
 * buffers, the FBO and the FBO texture are created once and reused, and readback targets a
 * caller-owned direct buffer.
 */
@UnstableApi
class OesFrameGrabber {

    companion object {
        private const val TAG = "OesFrameGrabber"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
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

        /** Clip-space positions of a full-screen triangle strip. */
        private val FULL_QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
        )

        /**
         * Texture coordinates of the same strip. V is flipped relative to the clip-space Y so that
         * `glReadPixels` returns the image top row first.
         */
        private val FULL_QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f
        )

        private const val TEXTURE_UNIT = 0
    }

    private var program = 0
    private var aPositionHandle = -1
    private var aTextureCoordHandle = -1
    private var uStMatrixHandle = -1
    private var uTextureHandle = -1

    private var externalTextureId = 0

    private var fbo = 0
    private var fboTex = 0
    private var fboWidth = 0
    private var fboHeight = 0

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

    /**
     * Backing array for [SurfaceTexture.getTransformMatrix]: a 4x4 column-major matrix, exactly the
     * layout `glUniformMatrix4fv` expects. Refilled from the SurfaceTexture on every grab.
     */
    private val stMatrix = FloatArray(16)

    /** Reused staging buffer for the [grabFrame] Bitmap sink only. */
    private var pixelBuffer: ByteBuffer? = null

    /** Whether the program exists and the external texture has been bound to it. */
    val isInitialized: Boolean
        get() = program != 0 && externalTextureId != 0

    /**
     * Compiles the external-texture program. Must be called with the owning EGL context current.
     *
     * @param externalTextureId The `GL_TEXTURE_EXTERNAL_OES` name the [SurfaceTexture] was created
     *     with. The texture itself is owned by [VideoFrameProcessor] and is never deleted here.
     */
    fun init(externalTextureId: Int) {
        if (program != 0 && this.externalTextureId == externalTextureId) {
            return
        }
        release()

        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        if (!extensions.contains("GL_OES_EGL_image_external")) {
            throw IllegalStateException("GL_OES_EGL_image_external is not supported")
        }

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
            throw IllegalStateException("Failed to link external texture program: $programLog")
        }

        program = newProgram
        this.externalTextureId = externalTextureId

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uStMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        if (aPositionHandle < 0 || aTextureCoordHandle < 0 ||
            uStMatrixHandle < 0 || uTextureHandle < 0
        ) {
            release()
            throw IllegalStateException("External texture program is missing expected attributes")
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        Log.i(TAG, "External texture program ready (externalTexId=$externalTextureId)")
    }

    /**
     * Samples the OES texture currently held by [surfaceTexture] and reads `targetWidth` x
     * `targetHeight` RGBA pixels into [out].
     *
     * The caller must have called [SurfaceTexture.updateTexImage] first. [out] must be a direct
     * buffer with capacity for at least `targetWidth * targetHeight * 4` bytes; on success its
     * readable range is left at `position = 0`, `limit = targetWidth * targetHeight * 4`.
     */
    fun read(
        surfaceTexture: SurfaceTexture,
        targetWidth: Int,
        targetHeight: Int,
        out: ByteBuffer
    ): Boolean {
        val requiredBytes = targetWidth * targetHeight * 4
        if (!isInitialized || targetWidth <= 0 || targetHeight <= 0) {
            return false
        }
        if (!out.isDirect || out.capacity() < requiredBytes) {
            Log.w(
                TAG,
                "read(${targetWidth}x$targetHeight) needs a direct buffer of $requiredBytes bytes, " +
                    "got direct=${out.isDirect} capacity=${out.capacity()}"
            )
            return false
        }

        return try {
            surfaceTexture.getTransformMatrix(stMatrix)
            ensureFbo(targetWidth, targetHeight)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, targetWidth, targetHeight)
            GLES20.glUseProgram(program)

            // Bind the decoded-frame texture explicitly on every draw: an unbound sampler reads
            // whatever texture unit 0 happens to contain and produces a black/garbage frame.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + TEXTURE_UNIT)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
            GLES20.glUniform1i(uTextureHandle, TEXTURE_UNIT)
            GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(
                aPositionHandle,
                3,
                GLES20.GL_FLOAT,
                false,
                12,
                vertexBuffer
            )

            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
            GLES20.glVertexAttribPointer(
                aTextureCoordHandle,
                4,
                GLES20.GL_FLOAT,
                false,
                16,
                texCoordBuffer
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "glDrawArrays failed with GL error 0x${error.toString(16)}")
                return false
            }

            out.position(0)
            out.limit(requiredBytes)
            GLES20.glReadPixels(
                0,
                0,
                targetWidth,
                targetHeight,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                out
            )

            val readError = GLES20.glGetError()
            if (readError != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "glReadPixels failed with GL error 0x${readError.toString(16)}")
                return false
            }

            // glReadPixels writes straight into the buffer memory and does not move the Java
            // position, so the readable range is established explicitly here.
            out.position(0)
            out.limit(requiredBytes)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "External texture readback failed", t)
            false
        } finally {
            if (program != 0) {
                GLES20.glDisableVertexAttribArray(aPositionHandle)
                GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
        }
    }

    /**
     * Bitmap sink for the same FBO path. It is only used for diagnostics; the frame pipeline itself
     * reads straight into a direct buffer through [read] so that no per-frame Bitmap is needed.
     */
    fun grabFrame(
        surfaceTexture: SurfaceTexture,
        targetWidth: Int,
        targetHeight: Int,
        outBitmap: Bitmap
    ): Boolean {
        val requiredBytes = targetWidth * targetHeight * 4
        if (requiredBytes <= 0) {
            return false
        }
        // Select the reuse buffer as a single non-null value: a `var` reassigned inside the null
        // check above is not smart-cast to non-null by the Kotlin compiler, so the nullable state
        // would leak into read()/copyPixelsFromBuffer(). The observable behaviour is identical:
        // reuse pixelBuffer while its capacity suffices, otherwise allocate and publish the new one.
        val currentBuffer = pixelBuffer
        val staging = if (currentBuffer == null || currentBuffer.capacity() < requiredBytes) {
            ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
        } else {
            currentBuffer
        }
        pixelBuffer = staging
        if (!read(surfaceTexture, targetWidth, targetHeight, staging)) {
            return false
        }
        staging.position(0)
        staging.limit(requiredBytes)
        outBitmap.copyPixelsFromBuffer(staging)
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

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        fboTex = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            fboTex,
            0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Readback framebuffer is incomplete: 0x${status.toString(16)}")
            releaseFbo()
            throw IllegalStateException("Readback framebuffer is incomplete: 0x${status.toString(16)}")
        }

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

    /**
     * Releases every GL object this grabber created. The external texture itself is owned by
     * [VideoFrameProcessor] and is deleted there while its EGL context is still current.
     */
    fun release() {
        releaseFbo()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        aPositionHandle = -1
        aTextureCoordHandle = -1
        uStMatrixHandle = -1
        uTextureHandle = -1
        externalTextureId = 0
        pixelBuffer = null
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
