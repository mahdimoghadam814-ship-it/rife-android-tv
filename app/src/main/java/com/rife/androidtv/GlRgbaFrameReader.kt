package com.rife.androidtv

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Reads the *real* decoded pixels out of the [SurfaceTexture] that a MediaCodec video renderer has
 * rendered into, so the RIFE pipeline receives actual frame data instead of a placeholder bitmap.
 *
 * This mirrors the sampling stage that Media3's own `ExternalTextureManager` /
 * `ExternalShaderProgram` perform:
 *  * the [SurfaceTexture] is sampled through a `GL_TEXTURE_EXTERNAL_OES` shader,
 *  * the sample is scaled into an RGBA8888 framebuffer whose size is the requested target size
 *    (this is where the pre-RIFE down-scaling happens, so no extra CPU copy is needed), and
 *  * [GLES20.glReadPixels] writes the result straight into a direct [ByteBuffer].
 *
 * The V coordinate of the quad is deliberately flipped so that `glReadPixels` returns rows
 * top-down (row 0 == first line of the image), which is the layout RIFE and
 * `Bitmap.copyPixelsFromBuffer` both expect.
 *
 * Every method must be called on the thread that owns the EGL context used to create the program
 * and the framebuffer (the RIFE worker thread).
 */
@UnstableApi
internal class GlRgbaFrameReader {

    companion object {
        private const val TAG = "GlRgbaFrameReader"

        /** `x, y` position and `x, y` texture coordinate are 4 bytes each. */
        private const val QUAD_STRIDE_BYTES = 4 * 4

        private const val EXTERNAL_TEXTURE_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n"

        private const val VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                "attribute vec4 aTexCoord;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = aPosition;\n" +
                "  vTexCoord = (uTexMatrix * vec4(aTexCoord.xy, 0.0, 1.0)).xy;\n" +
                "}\n"

        /**
         * Interleaved `x, y` clip-space position / texture coordinate pairs for a triangle strip.
         * The texture V coordinate is flipped relative to the clip-space Y coordinate so that the
         * first image line ends up in the first `glReadPixels` row.
         */
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )
    }

    /** Thrown when the external-texture readback pipeline cannot be created. */
    class GlReadbackException(message: String) : RuntimeException(message)

    private var program = 0
    private var positionAttrib = -1
    private var texCoordAttrib = -1
    private var texMatrixUniform = -1
    private var externalTextureUniform = -1

    private var externalTextureId = 0
    private var fboId = 0
    private var targetTextureId = 0
    private var targetWidth = 0
    private var targetHeight = 0

    private var vertexBuffer: FloatBuffer? = null

    /**
     * Backing array for [SurfaceTexture.getTransformMatrix], which requires a 4x4 (16 float)
     * column-major matrix, exactly the layout `glUniformMatrix4fv` expects. It is filled with the
     * SurfaceTexture's own matrix on every [read] and uploaded without any conversion.
     */
    private val surfaceTextureMatrix = FloatArray(16)

    /** Whether GL objects have been created and are usable. */
    val isInitialized: Boolean
        get() = program != 0 && externalTextureId != 0 && fboId != 0

    /**
     * Creates the external-texture shader program and the framebuffer used for readback. Must be
     * called with the owning EGL context current.
     *
     * @param externalTextureId The `GL_TEXTURE_EXTERNAL_OES` identifier the [surfaceTexture] was
     *     created with. The reader does not own it and never deletes it.
     * @param surfaceTexture The [SurfaceTexture] the external GL texture belongs to.
     */
    fun create(externalTextureId: Int, surfaceTexture: SurfaceTexture) {
        release()

        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        if (!extensions.contains("GL_OES_EGL_image_external")) {
            throw GlReadbackException("GL_OES_EGL_image_external is not supported")
        }

        this.externalTextureId = externalTextureId

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, EXTERNAL_TEXTURE_SHADER)

        val newProgram = GLES20.glCreateProgram()
        if (newProgram == 0) {
            throw GlReadbackException("glCreateProgram failed")
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
            throw GlReadbackException("Failed to link external texture program: $programLog")
        }
        program = newProgram

        positionAttrib = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixUniform = GLES20.glGetUniformLocation(program, "uTexMatrix")
        externalTextureUniform = GLES20.glGetUniformLocation(program, "uTexture")

        if (positionAttrib < 0 || texCoordAttrib < 0 || texMatrixUniform < 0 || externalTextureUniform < 0) {
            release()
            throw GlReadbackException("External texture program is missing expected attributes")
        }

        try {
            fboId = GlUtil.createFboForTexture(0)
        } catch (e: GlUtil.GlException) {
            release()
            throw GlReadbackException("Failed to create GL objects: ${e.message}")
        }

        vertexBuffer = ByteBuffer
            .allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD)
            .apply { position(0) }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GlUtil.checkGlError()

        Log.i(
            TAG,
            "Created external texture readback (surfaceTexture=${surfaceTexture.hashCode()}, " +
                "externalTexId=$externalTextureId, fboId=$fboId)"
        )
    }

    /**
     * Reads the current [SurfaceTexture] image into [out].
     *
     * [out] must be a direct buffer with at least `width * height * 4` bytes of capacity. Its
     * position/limit are reset to `0`/`capacity` on return. The caller is responsible for calling
     * [SurfaceTexture.updateTexImage] beforehand (see [VideoFrameProcessor]).
     */
    fun read(surfaceTexture: SurfaceTexture, width: Int, height: Int, out: ByteBuffer): Boolean {
        if (!isInitialized || width <= 0 || height <= 0) {
            return false
        }
        val requiredBytes = width * height * 4
        if (out.capacity() < requiredBytes) {
            return false
        }

        return try {
            ensureTargetTexture(width, height)
            surfaceTexture.getTransformMatrix(surfaceTextureMatrix)

            GLES20.glUseProgram(program)
            GlUtil.checkGlError()

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GlUtil.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
            GLES20.glUniform1i(externalTextureUniform, 0)
            GlUtil.checkGlError()

            val quad = vertexBuffer ?: return false
            quad.position(0)
            GLES20.glEnableVertexAttribArray(positionAttrib)
            GLES20.glVertexAttribPointer(
                positionAttrib,
                2,
                GLES20.GL_FLOAT,
                /* normalized= */ false,
                /* stride= */ QUAD_STRIDE_BYTES,
                quad
            )
            GLES20.glEnableVertexAttribArray(texCoordAttrib)
            quad.position(2)
            GLES20.glVertexAttribPointer(
                texCoordAttrib,
                2,
                GLES20.GL_FLOAT,
                /* normalized= */ false,
                /* stride= */ QUAD_STRIDE_BYTES,
                quad
            )
            quad.position(0)

            GLES20.glUniformMatrix4fv(
                texMatrixUniform,
                1,
                /* transpose= */ false,
                surfaceTextureMatrix,
                0
            )
            GlUtil.checkGlError()

            GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()

            out.clear()
            GLES20.glReadPixels(
                0,
                0,
                width,
                height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                out
            )
            GlUtil.checkGlError()
            out.clear()
            true
        } catch (e: GlUtil.GlException) {
            Log.e(TAG, "External texture readback failed", e)
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "External texture readback failed", e)
            false
        } finally {
            if (isInitialized) {
                GLES20.glDisableVertexAttribArray(positionAttrib)
                GLES20.glDisableVertexAttribArray(texCoordAttrib)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
        }
    }

    /** Releases all GL objects. Must be called with the owning EGL context current. */
    fun release() {
        try {
            if (targetTextureId != 0) {
                GlUtil.deleteTexture(targetTextureId)
            }
        } catch (e: GlUtil.GlException) {
            Log.w(TAG, "Failed to delete readback texture", e)
        }
        targetTextureId = 0

        try {
            if (fboId != 0) {
                GlUtil.deleteFbo(fboId)
            }
        } catch (e: GlUtil.GlException) {
            Log.w(TAG, "Failed to delete readback framebuffer", e)
        }
        fboId = 0

        // The external texture is owned by VideoFrameProcessor and is deleted there, while its EGL
        // context is still current.
        externalTextureId = 0

        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }

        positionAttrib = -1
        texCoordAttrib = -1
        texMatrixUniform = -1
        externalTextureUniform = -1
        targetWidth = 0
        targetHeight = 0
        vertexBuffer = null
    }

    private fun ensureTargetTexture(width: Int, height: Int) {
        if (targetTextureId != 0 && targetWidth == width && targetHeight == height) {
            return
        }

        if (targetTextureId != 0) {
            GlUtil.deleteTexture(targetTextureId)
            targetTextureId = 0
        }

        targetTextureId = GlUtil.createTexture(width, height, /* useHighPrecisionColorComponents= */ false)
        GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, targetTextureId)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            targetTextureId,
            0
        )
        GlUtil.checkGlError()

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw GlReadbackException("Readback framebuffer is incomplete: 0x${status.toString(16)}")
        }

        targetWidth = width
        targetHeight = height
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw GlReadbackException("glCreateShader failed for type $type")
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw GlReadbackException("Failed to compile shader: $log")
        }
        return shader
    }
}
