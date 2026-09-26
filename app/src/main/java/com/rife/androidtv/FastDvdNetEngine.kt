package com.rife.androidtv

import android.util.Log
import java.nio.ByteBuffer

/**
 * FastDVDnet temporal-denoising stage — SCAFFOLD ONLY, NOT A NEURAL NETWORK.
 *
 * This class exists to hold the *plumbing* of a FastDVDnet-style pre-processing stage in front of
 * RIFE (a bounded temporal window, a preprocessing entry point, a history that has to be flushed on
 * seek / new media / toggle). It is **not** a denoiser:
 *
 *  * no model is loaded, no weights are shipped, and nothing is inferred;
 *  * [denoiseFrameBuffer] currently copies the current frame through unchanged, so enabling this
 *    stage does not remove any noise and must not be presented as if it does.
 *
 * The only genuinely implemented behaviour is the window bookkeeping: the last
 * [HISTORY_SIZE] frames are kept in pre-allocated direct buffers and dropped by [reset]. A real
 * implementation will replace the body of [denoiseFrameBuffer] with model inference over that same
 * window; nothing else in the pipeline has to change.
 *
 * Buffers are pooled and reused, so a steady-state frame loop performs no allocation, and the
 * window can never grow: it holds at most [HISTORY_SIZE] buffers of the size of the last processed
 * frame.
 */
class FastDvdNetEngine {

    companion object {
        private const val TAG = "FastDvdNetEngine"

        /** Number of consecutive frames the real implementation is meant to consume: t-2..t+2. */
        private const val HISTORY_SIZE = 5

        private const val MAX_POOLED_BUFFERS = HISTORY_SIZE
    }

    /** When false the pipeline skips this stage entirely. */
    @Volatile
    var isEnabled = false

    /**
     * Intended noise sigma (0..100) of the real implementation. It is only logged today, because no
     * inference runs.
     */
    @Volatile
    var noiseSigma = 20.0f

    private val lock = Any()

    private val history = arrayOfNulls<ByteBuffer>(HISTORY_SIZE)
    private var historySize = 0
    private var writeIndex = 0

    private val pool = ArrayDeque<ByteBuffer>(MAX_POOLED_BUFFERS)

    /** How many frames of the temporal window are currently filled. Diagnostics only. */
    val temporalWindowSize: Int
        get() = synchronized(lock) { historySize }

    /**
     * Drops the temporal window. Called whenever frames from before a discontinuity must not be
     * mixed with frames after it: seek, media transition, video-size change, processing toggle and
     * processor stop.
     */
    fun reset() {
        synchronized(lock) {
            for (index in 0 until HISTORY_SIZE) {
                history[index]?.let { buffer ->
                    buffer.clear()
                    if (pool.size < MAX_POOLED_BUFFERS) {
                        pool.addLast(buffer)
                    }
                }
                history[index] = null
            }
            historySize = 0
            writeIndex = 0
        }
        Log.i(TAG, "Temporal history reset (scaffold, no model inference)")
    }

    /**
     * Pre-processing entry point for the frame at the current pipeline position.
     *
     * [input] and [output] must be direct buffers with capacity for at least
     * `width * height * 4` bytes. On success [output] holds `width * height * 4` bytes starting at
     * position 0, which is the state [VideoFrameProcessor] renders from.
     *
     * @return `true` when [output] was filled, `false` when the buffers are too small.
     */
    fun denoiseFrameBuffer(
        input: ByteBuffer,
        width: Int,
        height: Int,
        output: ByteBuffer
    ): Boolean {
        val requiredBytes = width * height * 4
        if (requiredBytes <= 0) {
            return false
        }
        if (!input.isDirect || !output.isDirect ||
            input.capacity() < requiredBytes || output.capacity() < requiredBytes
        ) {
            Log.w(
                TAG,
                "denoiseFrameBuffer($width x $height) needs direct buffers of $requiredBytes bytes, " +
                    "got in=${input.capacity()} out=${output.capacity()}"
            )
            return false
        }

        // duplicate() keeps the caller's position/limit untouched, so the pipeline's buffer state
        // stays under the processor's control.
        val source = input.duplicate()
        source.position(0)
        source.limit(requiredBytes)

        val target = output.duplicate()
        target.clear()
        target.limit(requiredBytes)
        target.put(source)
        target.position(0)
        target.limit(requiredBytes)

        synchronized(lock) {
            val slot = writeIndex
            var buffer = history[slot]
            if (buffer == null || buffer.capacity() < requiredBytes) {
                buffer = pool.pollFirst()?.takeIf { it.capacity() >= requiredBytes }
                    ?: ByteBuffer.allocateDirect(requiredBytes)
            }
            buffer.clear()
            val windowCopy = buffer.duplicate()
            windowCopy.put(source)
            history[slot] = buffer
            writeIndex = (writeIndex + 1) % HISTORY_SIZE
            if (historySize < HISTORY_SIZE) {
                historySize++
            }
        }

        Log.d(
            TAG,
            "SCAFFOLD pass-through (windowSize=${temporalWindowSize}, sigma=$noiseSigma, " +
                "frame=${width}x$height) - no model is loaded, frame is copied unchanged"
        )
        return true
    }
}
