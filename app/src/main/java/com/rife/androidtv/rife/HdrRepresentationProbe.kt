package com.rife.androidtv.rife

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Measures what the retained HDR source texture actually contains, so "is this already PQ, or is
 * it linear light waiting for `linearToPQ()`?" is answered by the pixels rather than by the
 * stream's branding.
 *
 * The measurement is a pure readback: it attaches the caller's texture to a private framebuffer,
 * reads a fixed grid of tiles, and restores both framebuffer bindings it found. It never renders
 * and never writes, so it cannot change the picture - which is what makes it safe to run while
 * playback is live.
 *
 * Every value is reported in the texture's own normalisation, and the discrimination the numbers
 * provide is magnitude. PQ and HLG code values for real video sit well up their range (frame means
 * in the tenths), while light linearised over 0..10000 nits puts a 100-nit diffuse white at 0.01
 * and a whole-frame mean near or below that. Those two populations do not overlap, so the measured
 * histogram settles the question without a second inference.
 */
@UnstableApi
class HdrRepresentationProbe {

    /** What the sampled texture holds, decided by magnitude (see class doc). */
    enum class Representation {
        /** No successful measurement yet. */
        UNKNOWN,
        /** PQ/HLG-like code values, frame means in the tenths. */
        ENCODED,
        /** Linear light on the PQ scale, 100-nit white near 0.01. */
        LINEAR,
        /** Distribution between the two populations; treat as [ENCODED] (see [sourceIsEncoded]). */
        INDETERMINATE,
    }

    /**
     * The last measurement's verdict. Read by the present path to pick the shader conversion, so
     * this - not the stream's branding - decides whether the source is decoded before tone-mapping.
     */
    @Volatile
    var representation: Representation = Representation.UNKNOWN
        private set

    /**
     * What the shaders should assume: anything that is not measured LINEAR is treated as encoded.
     * The decoder's YUV-to-RGB conversion copies code values without applying the transfer
     * function, so encoded is the near-universal reality for HDR streams, and guessing encoded on
     * genuinely linear content costs a dark picture that the next measurement corrects - while
     * guessing linear on encoded content is the clip-to-white failure this flag exists to end.
     */
    val sourceIsEncoded: Boolean
        get() = representation != Representation.LINEAR

    private var probeFbo = 0
    private var floatBuffer: FloatBuffer? = null
    private var halfBuffer: ShortBuffer? = null
    private var byteBuffer: ByteBuffer? = null

    private var firstSeenNs = 0L
    private var lastProbeNs = 0L
    private var reported = false
    private var lastSourceKey = ""

    // Aggregates for the sample currently being read.
    private var samples = 0
    private var sumIntensity = 0.0
    private var sumR = 0.0
    private var sumG = 0.0
    private var sumB = 0.0
    private var minIntensity = Float.MAX_VALUE
    private var maxIntensity = -Float.MAX_VALUE
    private var minR = 0f
    private var minG = 0f
    private var minB = 0f
    private var maxR = 0f
    private var maxG = 0f
    private var maxB = 0f
    private val intensity = ArrayList<Float>(SAMPLE_HINT)

    /** Clears the once-per-stream state so a new stream reports fresh evidence. */
    fun reset() {
        reported = false
        lastProbeNs = 0L
        firstSeenNs = 0L
        lastSourceKey = ""
        representation = Representation.UNKNOWN
        intensity.clear()
    }

    fun release() {
        if (probeFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(probeFbo), 0)
            probeFbo = 0
        }
        floatBuffer = null
        halfBuffer = null
        byteBuffer = null
        reported = false
        firstSeenNs = 0L
        lastProbeNs = 0L
        lastSourceKey = ""
        representation = Representation.UNKNOWN
    }

    /**
     * Samples [sourceTexture] (the retained `GL_RGBA16F`, or RGBA8 when HDR could not be
     * retained) and logs the result. Safe to call every frame - the caller's change gate decides
     * when, and this method additionally rate-limits itself. Must run on the thread that owns the
     * current EGL context.
     *
     * [contextKey] identifies the stream and the texture so a new stream always re-reports.
     */
    fun probe(sourceTexture: Int, width: Int, height: Int, contextKey: String) {
        if (sourceTexture == 0 || width <= 0 || height <= 0) return
        val now = System.nanoTime()
        if (firstSeenNs == 0L) firstSeenNs = now
        val first = !reported
        val changed = contextKey != lastSourceKey
        // The very first call only arms the clock: a texture that has not drawn yet reads as all
        // zeros, and zeros reported as evidence would be worse than no measurement at all.
        if (first && now - firstSeenNs < FIRST_DELAY_NS) return
        if (!first && !changed && now - lastProbeNs < REPEAT_NS) return
        lastProbeNs = now

        val readBefore = IntArray(1)
        val drawBefore = IntArray(1)
        GLES20.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, readBefore, 0)
        GLES20.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, drawBefore, 0)
        try {
            if (probeFbo == 0) {
                val ids = IntArray(1)
                GLES20.glGenFramebuffers(1, ids, 0)
                probeFbo = ids[0]
            }
            // A driver that will not hand out a framebuffer would otherwise bind 0 - the window -
            // and the numbers read back would be the presented picture rather than the source
            // texture, which is exactly the kind of plausible-looking evidence this probe exists
            // to avoid producing.
            if (probeFbo == 0) {
                Log.w(TAG, "[HDRREP] skipped: could not create a probe framebuffer")
                return
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, sourceTexture, 0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(
                    TAG,
                    "[HDRREP] skipped: texture $sourceTexture is not readable as a color " +
                        "attachment (status=0x${status.toString(16)})"
                )
                return
            }
            val format = IntArray(1)
            val type = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_FORMAT, format, 0)
            GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_TYPE, type, 0)
            if (readGrid(width, height, type[0])) {
                lastSourceKey = contextKey
                reported = true
                report(width, height, format[0], type[0])
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[HDRREP] probe failed", t)
        } finally {
            // Detach the caller's texture first so this probe cannot leave an attachment behind,
            // then restore the read and draw bindings separately - restoring only one of them
            // would leave the pipeline drawing into this probe's framebuffer.
            if (probeFbo != 0) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFbo)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, 0, 0,
                )
            }
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readBefore[0])
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawBefore[0])
        }
    }

    private fun canDecode(type: Int): Boolean =
        type == GLES20.GL_FLOAT ||
            type == GLES30.GL_HALF_FLOAT ||
            type == GLES20.GL_UNSIGNED_BYTE

    private fun readGrid(width: Int, height: Int, type: Int): Boolean {
        if (!canDecode(type)) {
            Log.w(
                TAG,
                "[HDRREP] skipped: implementation readback type=0x${type.toString(16)} is not " +
                    "one this probe can decode (need FLOAT, HALF_FLOAT or UNSIGNED_BYTE)"
            )
            return false
        }
        val tileW = TILE_SIZE.coerceAtMost(width)
        val tileH = TILE_SIZE.coerceAtMost(height)
        val texels = tileW * tileH
        val quad = texels * 4
        samples = 0
        sumIntensity = 0.0
        sumR = 0.0
        sumG = 0.0
        sumB = 0.0
        minIntensity = Float.MAX_VALUE
        maxIntensity = -Float.MAX_VALUE
        minR = 0f; minG = 0f; minB = 0f
        maxR = 0f; maxG = 0f; maxB = 0f
        intensity.clear()

        val xStep = (width - tileW).coerceAtLeast(0) / (GRID - 1)
        val yStep = (height - tileH).coerceAtLeast(0) / (GRID - 1)
        val values = FloatArray(quad)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                if (!readRegion(gx * xStep, gy * yStep, tileW, tileH, type, values, quad)) {
                    return false
                }
                for (i in 0 until texels) {
                    val o = i * 4
                    record(values[o], values[o + 1], values[o + 2])
                }
            }
        }
        if (samples == 0) return false
        // The geometric centre is quoted on its own: a single texel beside a whole-tile statistic
        // is what separates a bright subtitle or a dark bar from the picture's own distribution.
        val centre = FloatArray(4)
        if (readRegion(width / 2, height / 2, 1, 1, type, centre, centre.size)) {
            centreValues = floatArrayOf(centre[0], centre[1], centre[2])
        }
        return true
    }

    private var centreValues = floatArrayOf(0f, 0f, 0f)

    private fun readRegion(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        type: Int,
        into: FloatArray,
        quad: Int,
    ): Boolean {
        when (type) {
            GLES20.GL_FLOAT -> {
                val buffer = floatTarget(quad)
                buffer.clear()
                GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_FLOAT, buffer)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false
                buffer.rewind()
                buffer.get(into, 0, w * h * 4)
            }
            GLES30.GL_HALF_FLOAT -> {
                val buffer = halfTarget(quad)
                buffer.clear()
                GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false
                buffer.rewind()
                val count = w * h * 4
                for (i in 0 until count) {
                    into[i] = halfToFloat(buffer.get(i).toInt() and 0xFFFF)
                }
            }
            else -> {
                val buffer = byteTarget(quad)
                buffer.clear()
                GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false
                buffer.rewind()
                val count = w * h * 4
                for (i in 0 until count) into[i] = (buffer.get(i).toInt() and 0xFF) / 255f
            }
        }
        return true
    }

    private fun record(r: Float, g: Float, b: Float) {
        val v = if (r >= g && r >= b) r else if (g >= b) g else b
        samples++
        sumIntensity += v.toDouble()
        sumR += r.toDouble()
        sumG += g.toDouble()
        sumB += b.toDouble()
        if (v < minIntensity) {
            minIntensity = v
            minR = r; minG = g; minB = b
        }
        if (v > maxIntensity) {
            maxIntensity = v
            maxR = r; maxG = g; maxB = b
        }
        intensity.add(v)
    }

    private fun report(width: Int, height: Int, format: Int, type: Int) {
        val sorted = intensity.toFloatArray()
        sorted.sort()
        val n = sorted.size
        if (n == 0) return
        val mean = sumIntensity / n
        val p50 = percentile(sorted, 0.50)
        val p90 = percentile(sorted, 0.90)
        val p99 = percentile(sorted, 0.99)
        var overNine = 0
        var overOne = 0
        for (v in sorted) {
            if (v > 0.9f) overNine++
            if (v > 1.0f) overOne++
        }
        val typeName = when (type) {
            GLES20.GL_FLOAT -> "GL_FLOAT"
            GLES30.GL_HALF_FLOAT -> "GL_HALF_FLOAT"
            GLES20.GL_UNSIGNED_BYTE -> "GL_UNSIGNED_BYTE"
            else -> "0x${type.toString(16)}"
        }
        val clamped = type == GLES20.GL_UNSIGNED_BYTE
        Log.i(
            TAG,
            "[HDRREP] src=${width}x$height texels=$n format=0x${format.toString(16)} " +
                "type=$typeName clamped=$clamped over1dot0Possible=${!clamped}"
        )
        Log.i(
            TAG,
            "[HDRREP] intensity min=${f(minIntensity)} max=${f(maxIntensity)} mean=${f(mean)} " +
                "p50=${f(p50)} p90=${f(p90)} p99=${f(p99)} " +
                "fracGt09=${f(overNine.toDouble() / n)} fracGt10=${f(overOne.toDouble() / n)}"
        )
        Log.i(
            TAG,
            "[HDRREP] channelMean r=${f(sumR / n)} g=${f(sumG / n)} b=${f(sumB / n)} " +
                "center=${f(centreValues[0])}/${f(centreValues[1])}/${f(centreValues[2])} " +
                "brightest=${f(maxR)}/${f(maxG)}/${f(maxB)} " +
                "darkest=${f(minR)}/${f(minG)}/${f(minB)}"
        )
        // The magnitude test, stated in the log so the line carries the conclusion and not only
        // the evidence. The verdict is published on [representation], which the present path reads
        // to select the shader conversion - measured, not guessed.
        representation = when {
            mean >= 0.10 || p50 >= 0.10 -> Representation.ENCODED
            mean <= 0.02 && p99 <= 0.25 -> Representation.LINEAR
            else -> Representation.INDETERMINATE
        }
        val verdict = when (representation) {
            Representation.ENCODED ->
                "ENCODED (PQ/HLG-like code values) - decode before tone-mapping, do not re-encode"
            Representation.LINEAR ->
                "LINEAR (0..1 over 10000 nits) - linearToPQ/linearToSdr apply directly"
            Representation.INDETERMINATE ->
                "INDETERMINATE - distribution sits between the two populations; treating as ENCODED"
            Representation.UNKNOWN -> "UNKNOWN"
        }
        Log.i(TAG, "[HDRREP] representation=$verdict | sourceIsEncoded=$sourceIsEncoded")
    }

    private fun percentile(sorted: FloatArray, q: Double): Float {
        val index = (q * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun f(v: Double): String = String.format(java.util.Locale.US, "%.5f", v)
    private fun f(v: Float): String = String.format(java.util.Locale.US, "%.5f", v)

    private fun halfToFloat(bits: Int): Float {
        if (halfPow2.size != 32) {
            val table = FloatArray(32)
            for (e in table.indices) table[e] = Math.pow(2.0, (e - 15).toDouble()).toFloat()
            halfPow2 = table
        }
        val sign = if ((bits and 0x8000) != 0) -1f else 1f
        val exponent = (bits shr 10) and 0x1F
        val mantissa = bits and 0x3FF
        return when (exponent) {
            0 -> sign * mantissa * 5.9604644775390625e-8f
            31 -> if (mantissa == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + mantissa / 1024f) * halfPow2[exponent]
        }
    }

    private var halfPow2 = FloatArray(0)

    private fun floatTarget(quad: Int): FloatBuffer {
        val existing = floatBuffer
        if (existing != null && existing.capacity() >= quad) return existing
        val buffer = ByteBuffer.allocateDirect(quad * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        floatBuffer = buffer
        return buffer
    }

    private fun halfTarget(quad: Int): ShortBuffer {
        val existing = halfBuffer
        if (existing != null && existing.capacity() >= quad) return existing
        val buffer = ByteBuffer.allocateDirect(quad * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        halfBuffer = buffer
        return buffer
    }

    private fun byteTarget(quad: Int): ByteBuffer {
        val existing = byteBuffer
        if (existing != null && existing.capacity() >= quad) return existing
        val buffer = ByteBuffer.allocateDirect(quad).order(ByteOrder.nativeOrder())
        byteBuffer = buffer
        return buffer
    }

    companion object {
        private const val TAG = "HdrRepresentationProbe"

        /** Side of one sampled square; nine of them together cannot stall a frame. */
        private const val TILE_SIZE = 48

        /** 3x3 grid covers the frame well enough that a bright corner is not missed. */
        private const val GRID = 3

        private const val SAMPLE_HINT = 9 * 48 * 48

        private const val REPEAT_NS = 10_000_000_000L

        /**
         * The first report waits so the texture it reads has finished a real draw; probing a
         * just-created, never-drawn texture reports zeros, and zeros read as evidence are worse
         * than no measurement at all.
         */
        private const val FIRST_DELAY_NS = 500_000_000L
    }
}
