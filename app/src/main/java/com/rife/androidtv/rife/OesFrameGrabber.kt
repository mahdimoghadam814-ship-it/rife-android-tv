package com.rife.androidtv.rife

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.GlUtil
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

    data class ReadbackFrameInfo(
        val timestampUs: Long,
        val width: Int,
        val height: Int,
        val sourceTextureId: Int = 0,
    )

    private data class PboSlot(
        var id: Int = 0,
        var fence: Long = 0L,
        var info: ReadbackFrameInfo? = null,
    )

    private val pboSlots = Array(5) { PboSlot() }
    private val pendingPbos = java.util.ArrayDeque<PboSlot>()
    private var pboBytes = 0
    private var pboSupported = false
    internal data class HdrSourceSlot(var textureId: Int = 0, var inUse: Boolean = false)
    // Pipeline depth = previousFrame(1) + pair in flight(2) + pendingRenderQueue(2) + capturing(1) = 6.
    // But we only need to retain HDR sources for frames that are still in the pipeline.
    // Max simultaneous: previousFrame (1) + current pair being processed (2) + 1 pending pair (2) = 5.
    // Use 5 to allow one spare while keeping GPU memory bounded.
    internal val hdrSourceSlots = Array(5) { HdrSourceSlot() }
    private var hdrFramebuffer = 0
    private var hdrWidth = 0
    private var hdrHeight = 0
    private var hdrSourceSupported = false
    private var copyProgram = 0
    private var copyPosition = -1
    private var copyTexCoord = -1
    private var copyTexture = -1
    private var lastRefusalLogNs = 0L
    val isAsyncReadbackSupported: Boolean get() = pboSupported
    val isHdrSourceSupported: Boolean get() = hdrSourceSupported

    /** Compact ring/lease summary so a silent capture stall is readable from a single log line. */
    fun readbackState(): String =
        "pbo=$pboSupported pending=${pendingPbos.size}/${pboSlots.size} " +
            "hdrLeases=${hdrSourceSlots.count { it.inUse }}/${hdrSourceSlots.size} " +
            "hdrTex=${hdrSourceSlots.count { it.textureId != 0 }} copy=$copyProgram"

    /**
     * Every `false` from [enqueueReadback] discards a decoded frame, so a persistent failure would
     * blank playback with no other symptom. Rate-limited to one line per second so the reason stays
     * visible without contributing to the per-frame log flood.
     */
    private fun refuse(reason: String): Boolean {
        val nowNs = System.nanoTime()
        if (nowNs - lastRefusalLogNs >= 1_000_000_000L) {
            lastRefusalLogNs = nowNs
            Log.w(TAG, "Readback refused: $reason; ${readbackState()}")
        }
        return false
    }

    companion object {
        private const val TAG = "OesFrameGrabber"

        /**
         * Per-frame capture diagnostics. Costs a whole-buffer pass inside the timed readback, so
         * it is off by default.
         */
        private const val VERBOSE_DIAGNOSTICS = false

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
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
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
        pboSupported = (GLES20.glGetString(GLES20.GL_VERSION) ?: "").contains("OpenGL ES 3")
        hdrSourceSupported = pboSupported && (
            extensions.contains("GL_EXT_color_buffer_half_float") ||
                extensions.contains("GL_EXT_color_buffer_float")
            )
        if (hdrSourceSupported) {
            try {
                initializeCopyProgram()
            } catch (t: Throwable) {
                hdrSourceSupported = false
                Log.w(TAG, "RGBA16F analysis-copy shader unavailable", t)
            }
        }

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

    /** Queues an asynchronous RGBA readback. The returned analysis frame is polled later. */
    fun enqueueReadback(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        timestampUs: Long,
        retainHdrSource: Boolean = false,
    ): Boolean {
        if (!pboSupported || width <= 0 || height <= 0) {
            return refuse("unsupported pbo=$pboSupported size=${width}x$height")
        }
        val bytesLong = width.toLong() * height.toLong() * 4L
        if (bytesLong <= 0L || bytesLong > Int.MAX_VALUE) {
            return refuse("size overflow ${width}x$height")
        }
        val bytes = bytesLong.toInt()
        if (pboBytes != bytes) {
            releasePbos()
            try {
                pboSlots.forEach { it.id = GlUtil.createPixelBufferObject(bytes) }
                pboBytes = bytes
            } catch (t: Throwable) {
                Log.w(TAG, "Pixel-buffer readback unavailable; using synchronous readback", t)
                releasePbos()
                pboSupported = false
                return false
            }
        }
        val slot = pboSlots.firstOrNull { it.info == null }
            ?: return refuse("pbo ring full")
        var sourceTextureId = 0
        if (retainHdrSource) {
            if (!hdrSourceSupported || !ensureHdrSources(width, height)) {
                return refuse("hdr sources unsupported=$hdrSourceSupported allocated=${hdrWidth}x$hdrHeight")
            }
            val source = hdrSourceSlots.firstOrNull { !it.inUse }
                ?: return refuse("hdr source leases exhausted")
            source.inUse = true
            sourceTextureId = source.textureId
            if (!drawOesToFramebuffer(surfaceTexture, width, height, hdrFramebuffer, sourceTextureId) ||
                !copyHdrToAnalysis(sourceTextureId, width, height)
            ) {
                releaseSourceTexture(sourceTextureId)
                return refuse("hdr oes draw/copy")
            }
        } else if (!drawOesToFbo(surfaceTexture, width, height)) {
            return refuse("oes draw")
        }
        return try {
            GlUtil.schedulePixelBufferRead(fbo, width, height, slot.id)
            slot.info = ReadbackFrameInfo(timestampUs, width, height, sourceTextureId)
            slot.fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(slot.fence != 0L) { "glFenceSync returned no fence" }
            GLES30.glFlush()
            pendingPbos.addLast(slot)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not queue pixel-buffer readback", t)
            releaseSourceTexture(sourceTextureId)
            false
        }
    }

    /**
     * Attempts to enqueue a readback. If the readback ring is stuck (all PBOs pending or HDR
     * leases exhausted), clears the stuck state and retries once. This prevents a permanent
     * capture blackout when the pipeline hits a transient resource exhaustion.
     */
    fun enqueueReadbackWithRecovery(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        timestampUs: Long,
        retainHdrSource: Boolean = false,
    ): Boolean {
        val queued = enqueueReadback(surfaceTexture, width, height, timestampUs, retainHdrSource)
        if (!queued) {
            // The ring/leases are stuck. Discard everything and try once more.
            discardPendingReadbacks()
            return enqueueReadback(surfaceTexture, width, height, timestampUs, retainHdrSource)
        }
        return true
    }

    fun nextReadbackInfo(): ReadbackFrameInfo? = pendingPbos.peekFirst()?.info

    fun releaseSourceTexture(textureId: Int) {
        if (textureId == 0) return
        hdrSourceSlots.firstOrNull { it.textureId == textureId }?.inUse = false
    }

    /** Called only at stream discontinuities; makes queued GPU writes safe to discard. */
    fun discardPendingReadbacks() {
        if (pendingPbos.isEmpty()) return
        GLES30.glFinish()
        pendingPbos.forEach { slot ->
            slot.info?.let { releaseSourceTexture(it.sourceTextureId) }
            if (slot.fence != 0L) GLES30.glDeleteSync(slot.fence)
            slot.fence = 0L
            slot.info = null
        }
        pendingPbos.clear()
    }

    /** Maps only a signaled PBO, copies into the reusable CPU analysis buffer, then releases it. */
    fun pollReadback(out: ByteBuffer): ReadbackFrameInfo? {
        val slot = pendingPbos.peekFirst() ?: return null
        val info = slot.info ?: return null
        val sync = slot.fence
        if (sync != 0L) {
            val wait = GLES30.glClientWaitSync(sync, 0, 0L)
            if (wait != GLES30.GL_CONDITION_SATISFIED &&
                wait != GLES30.GL_ALREADY_SIGNALED
            ) return null
        }
        val byteCount = info.width.toLong() * info.height.toLong() * 4L
        if (byteCount > out.capacity()) {
            discardHeadPbo(slot)
            return null
        }
        try {
            val mapped = GlUtil.mapPixelBufferObject(slot.id, byteCount.toInt())
                ?: return null
            out.clear()
            out.limit(byteCount.toInt())
            mapped.position(0)
            mapped.limit(byteCount.toInt())
            out.put(mapped)
            out.position(0)
            GlUtil.unmapPixelBufferObject(slot.id)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not map completed pixel-buffer readback", t)
            discardHeadPbo(slot)
            return null
        }
        discardHeadPbo(slot, releaseSource = false)
        return info
    }

    private fun discardHeadPbo(slot: PboSlot, releaseSource: Boolean = true) {
        if (pendingPbos.peekFirst() === slot) pendingPbos.removeFirst()
        if (releaseSource) slot.info?.let { releaseSourceTexture(it.sourceTextureId) }
        if (slot.fence != 0L) GLES30.glDeleteSync(slot.fence)
        slot.fence = 0L
        slot.info = null
    }

    private fun drawOesToFbo(surfaceTexture: SurfaceTexture, width: Int, height: Int): Boolean {
        ensureFbo(width, height)
        return drawOesToFramebuffer(surfaceTexture, width, height, fbo, fboTex)
    }

    private fun drawOesToFramebuffer(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        framebuffer: Int,
        targetTexture: Int,
    ): Boolean {
        if (!isInitialized || width <= 0 || height <= 0) return false
        surfaceTexture.getTransformMatrix(stMatrix)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, targetTexture, 0,
        )
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + TEXTURE_UNIT)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniform1i(uTextureHandle, TEXTURE_UNIT)
        GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val error = GLES20.glGetError()
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return error == GLES20.GL_NO_ERROR
    }

    private fun ensureHdrSources(width: Int, height: Int): Boolean {
        if (hdrWidth == width && hdrHeight == height && hdrSourceSlots.all { it.textureId != 0 }) return true
        if (hdrSourceSlots.any { it.inUse }) return false
        releaseHdrSources()
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        hdrFramebuffer = fboIds[0]
        val textureIds = IntArray(hdrSourceSlots.size)
        GLES20.glGenTextures(textureIds.size, textureIds, 0)
        try {
            hdrSourceSlots.forEachIndexed { index, slot ->
                slot.textureId = textureIds[index]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.textureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES30.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
                    GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null,
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hdrFramebuffer)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, slot.textureId, 0,
                )
                check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    "RGBA16F source framebuffer is incomplete"
                }
            }
            hdrWidth = width
            hdrHeight = height
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "RGBA16F source capture unavailable", t)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            releaseHdrSources()
            hdrSourceSupported = false
            return false
        }
    }

    private fun initializeCopyProgram() {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() { gl_Position = aPosition; vTextureCoord = aTextureCoord.xy; }
        """.trimIndent())
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTextureCoord;
            uniform sampler2D uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vTextureCoord); }
        """.trimIndent())
        copyProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(copyProgram, vertex)
        GLES20.glAttachShader(copyProgram, fragment)
        GLES20.glLinkProgram(copyProgram)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(copyProgram, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(copyProgram)
            GLES20.glDeleteProgram(copyProgram)
            copyProgram = 0
            throw IllegalStateException("RGBA16F analysis-copy shader failed: $log")
        }
        copyPosition = GLES20.glGetAttribLocation(copyProgram, "aPosition")
        copyTexCoord = GLES20.glGetAttribLocation(copyProgram, "aTextureCoord")
        copyTexture = GLES20.glGetUniformLocation(copyProgram, "uTexture")
    }

    private fun copyHdrToAnalysis(sourceTextureId: Int, width: Int, height: Int): Boolean {
        if (copyProgram == 0) return false
        ensureFbo(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(copyProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTextureId)
        GLES20.glUniform1i(copyTexture, 0)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(copyPosition)
        GLES20.glVertexAttribPointer(copyPosition, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(copyTexCoord)
        GLES20.glVertexAttribPointer(copyTexCoord, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val error = GLES20.glGetError()
        GLES20.glDisableVertexAttribArray(copyPosition)
        GLES20.glDisableVertexAttribArray(copyTexCoord)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return error == GLES20.GL_NO_ERROR
    }

    private fun releaseHdrSources() {
        val ids = hdrSourceSlots.map { it.textureId }.filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        hdrSourceSlots.forEach { it.textureId = 0; it.inUse = false }
        if (hdrFramebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(hdrFramebuffer), 0)
        hdrFramebuffer = 0
        hdrWidth = 0
        hdrHeight = 0
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
        val requiredBytes = targetWidth.toLong() * targetHeight.toLong() * 4L
        if (!isInitialized || targetWidth <= 0 || targetHeight <= 0) {
            return false
        }
        if (requiredBytes > Int.MAX_VALUE) {
            Log.w(TAG, "read(${targetWidth}x$targetHeight) overflows Int")
            return false
        }
        val requiredBytesInt = requiredBytes.toInt()
        if (!out.isDirect || out.capacity() < requiredBytesInt) {
            Log.w(
                TAG,
                "read(${targetWidth}x$targetHeight) needs a direct buffer of $requiredBytesInt bytes, " +
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
            out.limit(requiredBytesInt)
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
            out.limit(requiredBytesInt)

            // DIAGNOSTICS: Calculate cheap pixel checksum to verify capture is non-black
            if (VERBOSE_DIAGNOSTICS) {
                val checksum = calculateChecksum(out, targetWidth, targetHeight)
                Log.d(TAG, "CAPTURE CHECKSUM: ${targetWidth}x$targetHeight checksum=$checksum")
            }

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
     * Bitmap sink for the same FBO path. It is only used for diagnostics; the frame pipeline itself
     * reads straight into a direct buffer through [read] so that no per-frame Bitmap is needed.
     */
    fun grabFrame(
        surfaceTexture: SurfaceTexture,
        targetWidth: Int,
        targetHeight: Int,
        outBitmap: Bitmap
    ): Boolean {
        val requiredBytes = targetWidth.toLong() * targetHeight.toLong() * 4L
        if (requiredBytes <= 0 || requiredBytes > Int.MAX_VALUE) {
            return false
        }
        val requiredBytesInt = requiredBytes.toInt()
        // Select the reuse buffer as a single non-null value: a `var` reassigned inside the null
        // check above is not smart-cast to non-null by the Kotlin compiler, so the nullable state
        // would leak into read()/copyPixelsFromBuffer(). The observable behaviour is identical:
        // reuse pixelBuffer while its capacity suffices, otherwise allocate and publish the new one.
        val currentBuffer = pixelBuffer
        val staging = if (currentBuffer == null || currentBuffer.capacity() < requiredBytesInt) {
            ByteBuffer.allocateDirect(requiredBytesInt).order(ByteOrder.nativeOrder())
        } else {
            currentBuffer
        }
        pixelBuffer = staging
        if (!read(surfaceTexture, targetWidth, targetHeight, staging)) {
            return false
        }
        staging.position(0)
        staging.limit(requiredBytesInt)
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

    private fun releasePbos() {
        pendingPbos.forEach { slot ->
            slot.info?.let { releaseSourceTexture(it.sourceTextureId) }
            if (slot.fence != 0L) GLES30.glDeleteSync(slot.fence)
            slot.fence = 0L
            slot.info = null
        }
        pendingPbos.clear()
        val ids = pboSlots.map { it.id }.filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteBuffers(ids.size, ids, 0)
        pboSlots.forEach { it.id = 0; it.info = null; it.fence = 0L }
        pboBytes = 0
    }

    /**
     * Releases every GL object this grabber created. The external texture itself is owned by
     * [VideoFrameProcessor] and is deleted there while its EGL context is still current.
     */
    fun release() {
        releasePbos()
        releaseHdrSources()
        if (copyProgram != 0) GLES20.glDeleteProgram(copyProgram)
        copyProgram = 0
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
