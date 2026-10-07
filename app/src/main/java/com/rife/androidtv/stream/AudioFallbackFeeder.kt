package com.rife.androidtv.stream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fallback audio feeder: when the source audio cannot be passthrough (no E-AC-3/AC3/AAC-LC),
 * decode to PCM and re-encode to E-AC-3 5.1 to preserve surround and Atmos metadata.
 *
 * Pipeline:
 * MediaExtractor (decode) -> MediaCodec (PCM) -> MediaCodec (E-AC-3 encode) -> MpegTsMuxer
 */
class AudioFallbackFeeder(
    private val context: Context,
    private val uri: Uri,
    startPositionMs: Long,
    private val muxer: MpegTsMuxer,
) {
    companion object {
        private const val TAG = "AudioFallbackFeeder"
        private const val TARGET_CHANNELS = 6
        private const val TARGET_SAMPLE_RATE = 48000
        private const val TARGET_BIT_RATE = 640000 // 640 kbps for E-AC-3 5.1
        private const val MAX_INPUT_BYTES = 128 * 1024
        private const val MAX_OUTPUT_BYTES = 64 * 1024
        private const val QUEUE_CAPACITY = 32
    }

    @Volatile private var running = false
    private var extractorThread: Thread? = null
    private var decodeThread: Thread? = null
    private var encodeThread: Thread? = null

    private val decodeQueue = ArrayBlockingQueue<ByteBuffer>(QUEUE_CAPACITY)
    private val encodeQueue = ArrayBlockingQueue<ByteBuffer>(QUEUE_CAPACITY)

    fun start(): Boolean {
        if (running) return true
        val trackInfo = runCatching { probeTrack() }.getOrNull()
        if (trackInfo == null) {
            Log.i(TAG, "no fallback-eligible audio track in $uri")
            return false
        }
        muxer.setAudioFormat(MpegTsMuxer.STREAM_TYPE_EAC3, MpegTsMuxer.AUDIO_STREAM_ID)
        running = true
        extractorThread = Thread({ runExtractor(trackInfo) }, "AudioFallbackExtractor").also { it.start() }
        decodeThread = Thread({ runDecoder(trackInfo.format) }, "AudioFallbackDecoder").also { it.start() }
        encodeThread = Thread({ runEncoder() }, "AudioFallbackEncoder").also { it.start() }
        Log.i(TAG, "audio fallback encoding started (target: E-AC-3 5.1 @ 48 kHz)")
        return true
    }

    private data class TrackInfo(
        val index: Int,
        val format: MediaFormat,
        val mime: String,
    )

    private fun probeTrack(): TrackInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                // Skip already-passthrough codecs
                if (mime == MediaFormat.MIMETYPE_AUDIO_EAC3 ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AC3 ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AAC) continue
                // Accept anything else for fallback: DTS, TrueHD, FLAC, HE-AAC, MP3, etc.
                extractor.selectTrack(i)
                return TrackInfo(i, format, mime)
            }
            throw IllegalStateException("no fallback-eligible audio track")
        } finally {
            extractor.release()
        }
    }

    private fun runExtractor(track: TrackInfo) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(track.index)
            val buffer = ByteBuffer.allocateDirect(MAX_INPUT_BYTES)
            while (running) {
                buffer.clear()
                val size = try {
                    extractor.readSampleData(buffer, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "extractor read error", e)
                    break
                }
                if (size < 0) {
                    // EOS - send flush signal
                    decodeQueue.offer(ByteBuffer.allocateDirect(0))
                    break
                }
                val data = ByteBuffer.allocateDirect(size)
                buffer.flip()
                data.put(buffer)
                data.flip()
                decodeQueue.put(data)
                extractor.advance()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "extractor thread crashed", e)
        } finally {
            running = false
            extractor.release()
        }
    }

    private fun runDecoder(inputFormat: MediaFormat) {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return
        val decoder = MediaCodec.createDecoderByType(mime)
        val decodeFormat = MediaFormat(inputFormat)
        decodeFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, TARGET_CHANNELS)
        decodeFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        decoder.configure(decodeFormat, null, null, 0)
        decoder.start()

        val outputBuffers = decoder.outputBuffers
        val pending = ByteBuffer.allocateDirect(MAX_INPUT_BYTES)

        try {
            while (running) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val data = decodeQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (data != null) {
                        if (data.capacity() == 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            inputBuffer.put(data)
                            inputBuffer.flip()
                            decoder.queueInputBuffer(inputIndex, 0, data.remaining(), 0, 0)
                        }
                    }
                }
                val bufferInfo = MediaCodec.BufferInfo()
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    val outBuf = outputBuffers[outputIndex]
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        decoder.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    if (bufferInfo.size > 0) {
                        pending.clear()
                        if (pending.remaining() < bufferInfo.size) {
                            // Resize not needed for typical audio frames
                        }
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        pending.put(outBuf)
                        pending.flip()
                        encodeQueue.put(pending.duplicate())
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encodeQueue.offer(ByteBuffer.allocateDirect(0))
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "decoder thread crashed", e)
        } finally {
            decoder.stop()
            decoder.release()
        }
    }

    private fun runEncoder() {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_EAC3)
        val format = MediaFormat()
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_EAC3)
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, TARGET_CHANNELS)
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        format.setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BIT_RATE)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectHE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val outputBuffers = encoder.outputBuffers
        val pending = ByteBuffer.allocateDirect(MAX_OUTPUT_BYTES)

        try {
            while (running) {
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val data = encodeQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (data != null) {
                        if (data.capacity() == 0) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            inputBuffer.put(data)
                            inputBuffer.flip()
                            encoder.queueInputBuffer(inputIndex, 0, data.remaining(), 0, 0)
                        }
                    }
                }
                val bufferInfo = MediaCodec.BufferInfo()
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    val outBuf = outputBuffers[outputIndex]
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    if (bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val encoded = ByteArray(bufferInfo.size)
                        outBuf.get(encoded)
                        muxer.onAudioAccessUnit(encoded, bufferInfo.presentationTimeUs * 1000L)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "encoder thread crashed", e)
        } finally {
            encoder.stop()
            encoder.release()
        }
    }

    fun onDiscontinuity(positionMs: Long) {
        // Reset queues and signal discontinuity
        decodeQueue.clear()
        encodeQueue.clear()
    }

    fun stop() {
        running = false
        listOf(extractorThread, decodeThread, encodeThread).forEach { it?.interrupt() }
        listOf(extractorThread, decodeThread, encodeThread).forEach { it?.join(1000) }
    }
}
