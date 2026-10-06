package com.rife.androidtv.stream

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer

/**
 * Phase F: taps the source container's audio track in parallel with playback and feeds its
 * compressed frames - untouched, never re-encoded - into [MpegTsMuxer.onAudioAccessUnit].
 *
 * ```
 * ExoPlayer (video) -> pipeline -> encoder -> muxer -> UDP
 * MediaExtractor (audio, same URI) ------------------> muxer -> UDP
 * ```
 *
 * The extractor is a second reader on the same container: the container is read-only shared
 * state, so this costs no decoding and no copy of video. What it does not know by itself is
 * where the *video* timeline is, because the encoder's PTS after a seek is re-anchored by the
 * muxer. The muxer publishes that anchor ([MpegTsMuxer.videoAnchorUs], first muxed video PTS of
 * the segment) and the current position ([MpegTsMuxer.lastVideoPtsUs]); the feeder maps its own
 * raw sample times onto the anchor with one constant offset per segment and holds back any
 * sample that would run more than [LEAD_US] ahead of the last muxed video frame.
 *
 * A discontinuity (seek, pause/resume, media transition) reaches it through [onDiscontinuity],
 * which re-anchors: the extractor seeks to the position the player resumed at, the offset is
 * recomputed against the segment's new anchor, and no audio leaves the feeder until the first
 * key frame of the new segment has been muxed - so audio and video restart together, or not
 * at all.
 *
 * Two codecs are muxed, and each one is announced to the muxer through
 * [MpegTsMuxer.setAudioFormat] before the first frame leaves:
 *
 *  * **E-AC-3 / AC-3** verbatim, PES `0xBD`, PMT stream_type `0x87`.
 *  * **AAC-LC**, PES `0xC0`, PMT stream_type `0x0F`. MP4 stores these as raw access units, so
 *    each one is wrapped in a 7-byte ADTS header built from the track's AudioSpecificConfig;
 *    `.aac` files already arrive framed and pass through unchanged. The header is the *core*
 *    descriptor - AOT 2 and the core sample rate - because that is what an ADTS frame must
 *    describe when the payload is an HE-AAC core carrying SBR in its fill element.
 *
 * Anything else (HE-AAC without an LC core, DTS, MP3, ...) is rejected by [selectAudioTrack]
 * and the stream runs video-only, which is logged: advertising a codec the stream does not
 * actually carry would be worse than carrying no audio.
 */
class AudioPassthroughFeeder(
    private val context: Context,
    private val uri: Uri,
    startPositionMs: Long,
    private val muxer: MpegTsMuxer,
) {

    companion object {
        private const val TAG = "AudioPassthroughFeeder"

        /** Audio never leads the last muxed video frame by more than this. */
        private const val LEAD_US = 500_000L

        /** Poll cadence while waiting for a video anchor or lead room. */
        private const val IDLE_SLEEP_MS = 4L

        /** One E-AC-3 frame is a few kB; 1 MB is headroom, not an allocation per frame. */
        private const val SAMPLE_BUFFER_BYTES = 1 shl 20

        /** MediaFormat.KEY_CSD_0 is not in the public SDK, so the key name is spelled out. */
        private const val CSD_0 = "csd-0"
    }

    /**
     * A track that can be muxed, together with everything the muxer and the framing need.
     *
     * [adts] is null when no ADTS wrapping applies: for E-AC-3 because that codec is framed by
     * its own sync words, and for AAC because the extractor already hands back ADTS frames.
     */
    private data class AudioFormat(
        val mime: String,
        val streamType: Int,
        val streamId: Int,
        val adts: AdtsParams?,
    )

    /**
     * The three ADTS fields an AudioSpecificConfig is reduced to. [profile] is the ADTS profile,
     * i.e. audioObjectType - 1, so an AAC-LC core is 1.
     */
    private data class AdtsParams(
        val profile: Int,
        val sampleRateIndex: Int,
        val channels: Int,
    )

    @Volatile private var running = false
    private var thread: Thread? = null

    /** >= 0: seek the extractor there. -1: reset the anchor only. MIN_VALUE: nothing pending. */
    @Volatile private var seekRequestMs = Long.MIN_VALUE

    private var startedAtMs = startPositionMs

    /**
     * Opens the extractor, picks a muxable audio track and starts the feed thread. Returns false
     * when the container has none (video-only is then logged by the caller) - the caller must not
     * treat that as a failed stream start.
     */
    fun start(): Boolean {
        if (running) return true
        val probe = MediaExtractor()
        val format = try {
            probe.setDataSource(context, uri, null)
            selectAudioTrack(probe)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open $uri for audio passthrough: $t")
            null
        } finally {
            probe.release()
        }
        if (format == null) {
            Log.i(TAG, "no muxable audio track in $uri; streaming video-only")
            return false
        }
        // Before the thread starts: the PMT and the PES stream_id both read this, and the PMT
        // is rewritten every PSI interval, so a late write would be a mid-stream codec change.
        muxer.setAudioFormat(format.streamType, format.streamId)
        running = true
        thread = Thread({ feed(format) }, "AudioPassthroughFeeder").also { it.start() }
        Log.i(TAG, "audio passthrough started (${format.mime}) from ${startedAtMs}ms")
        return true
    }

    fun stop() {
        running = false
        seekRequestMs = Long.MIN_VALUE
        thread?.interrupt()
        thread?.join(500)
        thread = null
    }

    /**
     * A discontinuity in the video timeline. [positionMs] >= 0 re-seeks the extractor there (and
     * a negative value means "unknown position": keep reading where we are), and in both cases
     * the offset onto the new segment's anchor is recomputed from scratch.
     */
    fun onDiscontinuity(positionMs: Long) {
        seekRequestMs = if (positionMs >= 0L) positionMs else -1L
    }

    /**
     * Picks the track to mux and leaves [MediaExtractor] positioned on it. E-AC-3 wins over AAC
     * because it is passed through byte for byte and so cannot be framed incorrectly; both are
     * returned whenever present, and an AAC track that cannot be framed is caught per sample by
     * [feed] rather than being muxed wrongly here.
     */
    private fun selectAudioTrack(extractor: MediaExtractor): AudioFormat? {
        var eac3Index = -1
        var aacIndex = -1
        var eac3Format: AudioFormat? = null
        var aacFormat: AudioFormat? = null

        for (i in 0 until extractor.trackCount) {
            val track = extractor.getTrackFormat(i)
            val mime = track.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            when {
                mime == MediaFormat.MIMETYPE_AUDIO_EAC3 ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AC3 -> {
                    if (eac3Format == null) {
                        eac3Format = AudioFormat(
                            mime,
                            MpegTsMuxer.STREAM_TYPE_EAC3,
                            MpegTsMuxer.AUDIO_STREAM_ID,
                            null,
                        )
                        eac3Index = i
                    }
                }
                mime == MediaFormat.MIMETYPE_AUDIO_AAC && aacFormat == null -> {
                    aacFormat = aacAudioFormat(track, mime)
                    aacIndex = i
                }
            }
        }

        val chosen = eac3Format ?: aacFormat ?: return null
        extractor.selectTrack(if (eac3Format != null) eac3Index else aacIndex)
        return chosen
    }

    /**
     * Reduces an AAC track to what ADTS framing needs. A track with no usable
     * AudioSpecificConfig is still returned: some containers (.aac) frame the samples themselves,
     * and [feed] decides from the first sample - dropping the audio with an error rather than
     * shipping raw access units under stream_type 0x0F.
     */
    private fun aacAudioFormat(track: MediaFormat, mime: String): AudioFormat {
        // MediaFormat.KEY_CSD_0 is not in the public SDK, so the key name is spelled out.
        val params = parseAudioSpecificConfig(track.getByteBuffer(CSD_0))
        if (params == null) {
            Log.i(TAG, "$mime has no ADTS descriptor; relying on the samples' own framing")
        }
        return AudioFormat(mime, MpegTsMuxer.STREAM_TYPE_AAC_ADTS, MpegTsMuxer.AUDIO_STREAM_ID_MPEG, params)
    }

    /**
     * Reads the AudioSpecificConfig bit field (ISO/IEC 14496-3, 1.6.2.1) down to the three values
     * an ADTS header can carry. Returns null for anything it would have to guess at: an explicit
     * sample rate (ADTS has no code for one), an object type other than AAC-LC, or a channel
     * layout beyond 5.1 - all of which would mean announcing one codec and shipping another.
     */
    private fun parseAudioSpecificConfig(csd: ByteBuffer?): AdtsParams? {
        val source = csd?.duplicate() ?: return null
        if (source.remaining() < 2) return null
        val bytes = ByteArray(source.remaining())
        source.get(bytes)

        var bit = 0
        val available = bytes.size * 8
        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = (bytes[bit shr 3].toInt() and 0xFF) shr (7 - (bit and 7))
                value = (value shl 1) or (byte and 1)
                bit++
            }
            return value
        }
        fun readObjectType(): Int? {
            if (bit + 5 > available) return null
            val base = read(5)
            if (base != 31) return base
            if (bit + 6 > available) return null
            return 32 + read(6)
        }

        val declaredType = readObjectType() ?: return null
        if (bit + 4 > available) return null
        val sampleRateIndex = read(4)
        if (sampleRateIndex == 0x0F) {
            // Explicit samplingFrequency: 24 bits with no ADTS counterpart, so framing it would
            // mean writing a rate the payload does not have.
            return null
        }
        if (bit + 4 > available) return null
        val channels = read(4)

        var objectType = declaredType
        if (declaredType == 5 || declaredType == 29) {
            // SBR / PS: an extension sample rate follows, then the core object type. What an
            // ADTS frame wraps is the core frame - the extension rides in its fill element - so
            // the core's type and the *first* sample rate go in the header.
            //
            // UNMEASURED. The field order above is read off ISO/IEC 14496-3 1.6.2.1, not off a
            // real HE-AAC sample: none was to hand, and this ffmpeg build has no HE-AAC encoder
            // to make one. Everything verified byte for byte so far is AAC-LC (see [adtsHeader]),
            // which is also the only AAC this device's library contains. If HE-AAC audio ever
            // plays at the wrong rate, this branch is where to look first.
            if (bit + 4 > available) return null
            if (read(4) == 0x0F) {
                if (bit + 24 > available) return null
                read(24)
            }
            objectType = readObjectType() ?: return null
        }
        if (objectType != 2) {
            Log.w(TAG, "AAC audioObjectType=$objectType is not AAC-LC; skipping audio")
            return null
        }
        if (channels == 0 || channels > 6) {
            Log.w(TAG, "AAC channelConfiguration=$channels is not expressible in ADTS; skipping audio")
            return null
        }
        return AdtsParams(
            profile = objectType - 1,
            sampleRateIndex = sampleRateIndex,
            channels = channels,
        )
    }

    /** True when the sample already begins with an ADTS sync word. */
    private fun isAdtsFrame(data: ByteArray): Boolean =
        data.size >= 7 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xF0) == 0xF0

    /**
     * The 7-byte ADTS header (no CRC) for a frame of [frameLength] bytes. Verified byte for byte
     * against an ADTS file muxed by ffmpeg from AAC-LC: `ff f1 50 80 2b 3f fc` for 44.1 kHz
     * stereo at LC - profile 1, rate index 4, channel configuration 2, fullness 0x7FF (VBR).
     */
    private fun adtsHeader(params: AdtsParams, frameLength: Int): ByteArray = byteArrayOf(
        0xFF.toByte(),
        0xF1.toByte(), // MPEG-4, layer 0, no CRC
        ((params.profile shl 6) or (params.sampleRateIndex shl 2) or (params.channels shr 2)).toByte(),
        (((params.channels and 0x3) shl 6) or ((frameLength shr 11) and 0x03)).toByte(),
        ((frameLength shr 3) and 0xFF).toByte(),
        (((frameLength and 0x7) shl 5) or 0x1F).toByte(), // buffer fullness 0x7FF
        0xFC.toByte(), // fullness tail, one raw data block
    )

    private fun feed(format: AudioFormat) {
        val extractor = MediaExtractor()
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
        try {
            extractor.setDataSource(context, uri, null)
            val track = findTrack(extractor, format.mime)
            if (track < 0) return
            extractor.selectTrack(track)
            if (startedAtMs > 0L) extractor.seekTo(startedAtMs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            // Per-segment anchor state.
            var firstRawPtsUs = Long.MIN_VALUE
            var offsetUs = 0L
            var offsetReady = false

            while (running) {
                val pendingSeek = seekRequestMs
                if (pendingSeek != Long.MIN_VALUE) {
                    seekRequestMs = Long.MIN_VALUE
                    if (pendingSeek >= 0L) {
                        extractor.seekTo(pendingSeek, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    }
                    firstRawPtsUs = Long.MIN_VALUE
                    offsetReady = false
                }

                buffer.clear()
                val size = try {
                    extractor.readSampleData(buffer, 0)
                } catch (t: Throwable) {
                    Log.w(TAG, "readSampleData failed; stopping audio feed: $t")
                    return
                }
                if (size < 0) {
                    // End of container. The player will either stop or be re-seeked, and the
                    // seek path restarts us from there; just idle instead of spinning.
                    Thread.sleep(IDLE_SLEEP_MS)
                    continue
                }
                val rawPtsUs = extractor.sampleTime

                if (!offsetReady) {
                    val anchorUs = muxer.videoAnchorUs
                    if (anchorUs == Long.MIN_VALUE) {
                        // No key frame of this segment has been muxed yet (or the stream is
                        // between segments): hold the sample until the video timeline exists.
                        Thread.sleep(IDLE_SLEEP_MS)
                        continue
                    }
                    if (firstRawPtsUs == Long.MIN_VALUE) firstRawPtsUs = rawPtsUs
                    offsetUs = anchorUs - firstRawPtsUs
                    offsetReady = true
                }

                val outPtsUs = rawPtsUs + offsetUs
                val lastVideoUs = muxer.lastVideoPtsUs
                if (lastVideoUs == Long.MIN_VALUE || outPtsUs - lastVideoUs > LEAD_US) {
                    Thread.sleep(IDLE_SLEEP_MS)
                    continue
                }

                val sample = ByteArray(size)
                buffer.position(0)
                buffer.limit(size)
                buffer.get(sample)

                // Frame the payload for the codec the PMT announced. E-AC-3 carries its own sync
                // words and a raw AAC access unit can never begin with 0xFF (id_syn_ele is at
                // most 6), so the two directions of this test cannot be confused.
                val payload = when {
                    format.adts != null && !isAdtsFrame(sample) -> {
                        val header = adtsHeader(format.adts, 7 + size)
                        val framed = ByteArray(7 + size)
                        System.arraycopy(header, 0, framed, 0, 7)
                        System.arraycopy(sample, 0, framed, 7, size)
                        framed
                    }
                    format.mime == MediaFormat.MIMETYPE_AUDIO_AAC && !isAdtsFrame(sample) -> {
                        // No ADTS descriptor and no sync word: this container's samples are raw
                        // access units we cannot frame. Sending them unframed under stream_type
                        // 0x0F would be silent, undetectable corruption - stop the feed and let
                        // the stream run video-only instead.
                        Log.e(TAG, "AAC samples are neither ADTS-framed nor frameable; dropping audio")
                        return
                    }
                    else -> sample
                }
                muxer.onAudioAccessUnit(payload, outPtsUs)
                extractor.advance()
            }
        } catch (t: Throwable) {
            if (running) Log.e(TAG, "audio feed crashed", t)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mime: String): Int {
        for (i in 0 until extractor.trackCount) {
            val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m == mime) return i
        }
        return -1
    }
}
