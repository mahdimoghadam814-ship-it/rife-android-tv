package com.rife.androidtv

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Custom AudioProcessor that introduces positive or negative audio delay in milliseconds.
 *
 * - Positive delay (+ms): Delays audio output relative to video (buffers audio samples).
 * - Negative delay (-ms): Advances audio output relative to video by discarding leading audio samples.
 */
@UnstableApi
class AudioDelayAudioProcessor : AudioProcessor {

    var delayMs: Long = 0L
        set(value) {
            val clamped = value.coerceIn(-5000L, 5000L)
            if (field != clamped) {
                field = clamped
                recalculateDelayBytes()
            }
        }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var delayBytesCount: Int = 0
    private var pendingDelayBytes: Int = 0
    private var pendingDiscardBytes: Int = 0

    private var delayBuffer: ByteArray = ByteArray(0)
    private var bufferWriteIndex: Int = 0
    private var bufferReadIndex: Int = 0
    private var bufferedByteCount: Int = 0

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded: Boolean = false

    private fun recalculateDelayBytes() {
        if (inputAudioFormat == AudioFormat.NOT_SET || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.bytesPerFrame <= 0) {
            delayBytesCount = 0
            pendingDiscardBytes = 0
            pendingDelayBytes = 0
            return
        }

        val bytesPerMs = (inputAudioFormat.sampleRate * inputAudioFormat.bytesPerFrame) / 1000
        if (delayMs > 0) {
            val targetBytes = (delayMs * bytesPerMs).toInt()
            delayBytesCount = targetBytes
            pendingDelayBytes = targetBytes
            pendingDiscardBytes = 0

            if (delayBuffer.size < targetBytes) {
                delayBuffer = ByteArray(targetBytes)
            }
            bufferWriteIndex = 0
            bufferReadIndex = 0
            bufferedByteCount = 0
        } else if (delayMs < 0) {
            val discardBytes = ((-delayMs) * bytesPerMs).toInt()
            pendingDiscardBytes = discardBytes
            pendingDelayBytes = 0
            delayBytesCount = 0
        } else {
            delayBytesCount = 0
            pendingDiscardBytes = 0
            pendingDelayBytes = 0
        }
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_8BIT ||
            inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_FLOAT) {
            this.inputAudioFormat = inputAudioFormat
            recalculateDelayBytes()
            return inputAudioFormat
        }
        throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    override fun isActive(): Boolean {
        return inputAudioFormat != AudioFormat.NOT_SET && delayMs != 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        var remaining = inputBuffer.remaining()

        // Handle Negative Delay (Discarding leading bytes)
        if (pendingDiscardBytes > 0) {
            val bytesToDiscard = minOf(remaining, pendingDiscardBytes)
            inputBuffer.position(inputBuffer.position() + bytesToDiscard)
            pendingDiscardBytes -= bytesToDiscard
            remaining = inputBuffer.remaining()
            if (remaining == 0) return
        }

        // Handle Positive Delay (Ring Buffering audio bytes)
        if (delayMs > 0 && delayBytesCount > 0) {
            if (outputBuffer.capacity() < remaining) {
                outputBuffer = ByteBuffer.allocateDirect(remaining)
                    .order(ByteOrder.nativeOrder())
            } else {
                outputBuffer.clear()
            }

            while (inputBuffer.hasRemaining()) {
                val bIn = inputBuffer.get()
                if (bufferedByteCount < delayBytesCount) {
                    delayBuffer[bufferWriteIndex] = bIn
                    bufferWriteIndex = (bufferWriteIndex + 1) % delayBuffer.size
                    bufferedByteCount++
                } else {
                    val bOut = delayBuffer[bufferReadIndex]
                    outputBuffer.put(bOut)

                    delayBuffer[bufferReadIndex] = bIn
                    bufferReadIndex = (bufferReadIndex + 1) % delayBuffer.size
                    bufferWriteIndex = bufferReadIndex
                }
            }
            outputBuffer.flip()
        } else {
            // Pass through remaining buffer
            if (outputBuffer.capacity() < remaining) {
                outputBuffer = ByteBuffer.allocateDirect(remaining)
                    .order(ByteOrder.nativeOrder())
            } else {
                outputBuffer.clear()
            }
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean {
        return inputEnded && !outputBuffer.hasRemaining()
    }

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        recalculateDelayBytes()
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        delayMs = 0L
    }
}
