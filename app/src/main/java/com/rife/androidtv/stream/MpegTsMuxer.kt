package com.rife.androidtv.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.rife.androidtv.encode.EncodedStreamSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

/**
 * Where the muxer's finished 188-byte transport packets go.
 *
 * Phase F implements this over a UDP socket; writing the same bytes to a file produces a `.ts`
 * that ffprobe and VLC both accept, which is how the muxer gets validated on its own.
 */
fun interface TsPacketSink {
    fun onTsPacket(packet: ByteArray, length: Int)
}

/**
 * Phase E/F: MPEG-TS muxing of the encoder's H.265 access units, plus the source's compressed
 * E-AC-3 audio and supported DVB subtitle tracks once their passthrough feeders supply them.
 *
 * ```
 * HdrHevcEncoder -> MpegTsMuxer -> TsPacketSink (file or UDP)
 * AudioPassthroughFeeder ------>/
 * DvbSubtitlePassthroughFeeder ->/
 * ```
 *
 * What is emitted, per 188-byte packet:
 *
 *  * a PAT on PID 0 and a PMT on [pmtPid], every 50 ms and before the first access unit, so a
 *    player that tunes in late still finds the program; the PMT advertises the audio stream
 *    type 0x87 only once an audio frame has actually been muxed;
 *  * one PES packet per video access unit, PTS only. PTS-only is correct exactly when there are
 *    no B-frames - which is why [com.rife.androidtv.encode.HdrHevcEncoder] asks for
 *    `max-bframes=0` - and it is what a low-latency streaming pipeline wants anyway;
 *  * one PES packet per audio frame on [audioPid], PTS only, anchored onto the segment's first
 *    muxed video PTS and kept monotonic by the muxer;
 *  * a PCR every 30 ms in the adaptation field, derived from the same clock as the PES PTS so a
 *    player can lock its clock to ours instead of guessing.
 *
 * Everything is derived from the access unit's own presentation timestamp. When that timestamp
 * arrives as zero or stops increasing - a surface-input encoder that nobody called
 * `eglPresentationTimeANDROID` on - the muxer falls back to a generated timeline at the negotiated
 * frame rate and says so once, loudly, rather than emitting a stream of zero-length PTS deltas.
 *
 * A discontinuity ([resetForDiscontinuity]) closes the stream until the next key frame so the
 * encoder's queued pre-seek frames cannot reach the receiver, re-arms the audio anchor and marks
 * the first packet after it with the adaptation-field discontinuity_indicator.
 */
class MpegTsMuxer(
    private val output: TsPacketSink,
    private val videoPid: Int = 0x0100,
    private val audioPid: Int = 0x0101,
    private val pmtPid: Int = 0x1000,
    private val programNumber: Int = 1,
) : EncodedStreamSink {

    companion object {
        private const val TAG = "MpegTsMuxer"

        /** ISO 13818-1 packet length. Nothing else is legal in a transport stream. */
        const val TS_PACKET_SIZE = 188

        /** ISO 13818-1 stream_type for H.265 / HEVC. */
        private const val STREAM_TYPE_HEVC = 0x24

        /**
         * SMPTE ST 302 / TS 102 366: AC-3 / E-AC-3 (Dolby Digital Plus) audio. Public because
         * [AudioPassthroughFeeder] declares it to the muxer rather than the muxer guessing.
         */
        const val STREAM_TYPE_EAC3 = 0x87

        /**
         * ISO 13818-1 stream_type for MPEG-2 Audio (AAC) carried as ADTS. The feeder wraps every
         * frame in ADTS whether or not the container already had it, so one stream_type covers
         * both cases.
         */
        const val STREAM_TYPE_AAC_ADTS = 0x0F

        /** DVB subtitling carried as private PES with stream_type 0x06. */
        const val STREAM_TYPE_DVB_SUBTITLE = 0x06

        private const val VIDEO_STREAM_ID = 0xE0

        /**
         * E-AC-3 rides PES private_stream_1, not the audio stream_id range: verified against a
         * reference stream muxed by ffmpeg (PES bytes `00 00 01 BD` on the E-AC-3 PID).
         */
        const val AUDIO_STREAM_ID = 0xBD

        /** PES stream_id for stream 0 of the MPEG audio stream_id range (0xC0-0xDF). */
        const val AUDIO_STREAM_ID_MPEG = 0xC0

        /**
         * PSI repetition interval.
         *
         * Checked before each access unit, so the realised gap is this plus one frame - at 50 ms
         * and 60 fps that is at most ~67 ms, which keeps PAT and PMT inside the 100 ms window a
         * DVB-style receiver assumes when it tunes in mid-stream. Two 188-byte packets a second
         * of overhead is not worth thinking about next to a 40-80 Mbps video payload.
         */
        private const val PSI_INTERVAL_US = 50_000L

        /** PCR interval, in 90 kHz ticks. 30 ms is well inside every player's tolerance. */
        private const val PCR_INTERVAL_90KHZ = 2_700L

        /** PTS is a 33-bit value in 90 kHz ticks; it wraps every ~26.5 hours. */
        private const val PTS_MASK = 0x1FFFFFFFFL

        private const val FALLBACK_FRAME_RATE = 60

        /**
         * CRC-32 as ISO 13818-1 defines it: polynomial 0x04C11DB7, init all ones, no reflection,
         * no final xor. Not the CRC-32 of zip/png, and using the wrong one makes every PSI
         * section unparseable, which looks exactly like "VLC sees nothing".
         */
        private val CRC_TABLE = IntArray(256) { seed ->
            var crc = seed shl 24
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            crc
        }

        private fun crc32(data: ByteArray, length: Int): Int {
            var crc = -1
            for (i in 0 until length) {
                val index = ((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF
                crc = (crc shl 8) xor CRC_TABLE[index]
            }
            return crc
        }
    }

    private val packet = ByteArray(TS_PACKET_SIZE)
    private val sectionBuffer = ByteArray(64)
    private val subtitlePid = 0x0102

    /** One PES packet, header and payload, reused across frames so 4K does not churn the heap. */
    private var pesBuffer = ByteArray(64 * 1024)

    private val continuity = HashMap<Int, Int>()

    private var config: ByteArray? = null
    private var frameRate = FALLBACK_FRAME_RATE

    private var lastPsiUs = Long.MIN_VALUE
    private var lastPcr90 = Long.MIN_VALUE
    private var lastPtsUs = Long.MIN_VALUE
    private var fallbackActive = false
    private var unitsMuxed = 0L
    private var paceAnchorPtsUs = Long.MIN_VALUE
    private var paceAnchorNs = 0L

    // ---- Phase H/F: seek-safe video and the audio passthrough timeline ----

    /** Set by [resetForDiscontinuity], cleared at the first key frame after it. */
    private var dropUntilKeyFrame = false
    private var droppedAfterReset = 0L

    /** Emits the adaptation-field discontinuity_indicator on the first packet after a reset. */
    private var pendingDiscontinuityFlag = false

    /** Bumped on every discontinuity; the audio feeder compares it to detect a new segment. */
    @Volatile var timelineGeneration = 0
        private set

    /** First muxed video PTS of the current segment; audio extracts offset themselves onto it. */
    @Volatile var videoAnchorUs = Long.MIN_VALUE
        private set

    /** Latest muxed video PTS; the audio feeder holds a bounded lead over it. */
    @Volatile var lastVideoPtsUs = Long.MIN_VALUE
        private set
    private var anchorGeneration = -1

    /** True once the PMT must advertise the audio elementary stream. */
    @Volatile private var hasAudio = false
    private var lastAudioPtsUs = Long.MIN_VALUE
    private var hasSubtitle = false
    private var subtitleLanguage = "und"
    private var subtitleType = 0x10
    private var subtitleCompositionPageId = 0
    private var subtitleAncillaryPageId = 0
    private var lastSubtitlePtsUs = Long.MIN_VALUE

    /**
     * Codec of the stream that will be put on [audioPid], as declared by the feeder. Both the
     * PMT entry and the PES stream_id come from here: the muxer cannot infer them, and a PMT
     * that names a codec the stream does not carry makes receivers withhold audio entirely.
     */
    @Volatile private var audioStreamType: Int = STREAM_TYPE_EAC3
    @Volatile private var audioStreamId: Int = AUDIO_STREAM_ID

    /**
     * Declares what [AudioPassthroughFeeder] is about to write, before the first frame. E-AC-3
     * stays the default so a stream that never calls this still muxes as it did before.
     */
    fun setAudioFormat(streamType: Int, streamId: Int) {
        audioStreamType = streamType
        audioStreamId = streamId
        Log.i(TAG, "audio format: stream_type=0x${streamType.toString(16)} pes_stream_id=0x${streamId.toString(16)}")
    }

    /** Declares only a source DVB subtitle stream; unsupported text codecs are never advertised. */
    fun setDvbSubtitleFormat(language: String, type: Int, compositionPageId: Int, ancillaryPageId: Int) {
        subtitleLanguage = language.takeIf {
            it.length == 3 && it.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' }
        } ?: "und"
        subtitleType = type and 0xFF
        subtitleCompositionPageId = compositionPageId and 0xFFFF
        subtitleAncillaryPageId = ancillaryPageId and 0xFFFF
        Log.i(TAG, "DVB subtitle declared: language=$subtitleLanguage type=0x${subtitleType.toString(16)}")
    }

    override fun onOutputFormat(format: MediaFormat) {
        Log.i(TAG, "encoder format: $format")
        frameRate = runCatching {
            when {
                format.containsKey(MediaFormat.KEY_FRAME_RATE) ->
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                format.containsKey("capture-rate") -> format.getInteger("capture-rate")
                else -> FALLBACK_FRAME_RATE
            }
        }.getOrDefault(FALLBACK_FRAME_RATE).coerceAtLeast(1)

        // MediaFormat.KEY_CSD_* is not in the public SDK, so the key names are spelled out.
        // HEVC puts VPS, SPS and PPS in separate slots; a codec that emits fewer simply leaves
        // the later ones null. Never clear a config already captured from a CODEC_CONFIG buffer
        // because this format happened to carry no csd at all.
        val csd = ByteArrayOutputStream().apply {
            appendCsd(format, "csd-0")
            appendCsd(format, "csd-1")
            appendCsd(format, "csd-2")
        }.toByteArray()
        if (csd.isNotEmpty()) config = csd
        Log.i(TAG, "stream parameters: ${config?.size ?: 0} bytes, frameRate=$frameRate")
    }

    private fun ByteArrayOutputStream.appendCsd(format: MediaFormat, key: String) {
        val buffer = format.getByteBuffer(key) ?: return
        val bytes = ByteArray(buffer.remaining())
        buffer.duplicate().get(bytes)
        if (bytes.isEmpty()) return
        // An encoder's csd is Annex-B, but a config blob without a start code would be silently
        // appended to the PES payload as undecodable noise, so prefix one rather than assume.
        val hasStartCode = bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0 &&
            (bytes[1].toInt() and 0xFF) == 0 &&
            (bytes[2].toInt() and 0xFF) == 1
        if (!hasStartCode) write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        write(bytes)
    }

    @Synchronized override fun onAccessUnit(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return

        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // Held back rather than muxed: the parameters are folded into the next key frame,
            // which is where a player doing random access looks for them.
            val bytes = ByteArray(data.remaining())
            data.duplicate().get(bytes)
            config = bytes
            Log.i(TAG, "codec config captured: ${bytes.size} bytes")
            return
        }

        val accessUnit = ByteArray(data.remaining())
        data.duplicate().get(accessUnit)

        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

        // Phase H: after a seek the encoder's queued pre-seek frames still drain through with old
        // timestamps; muxing them corrupts the receiver's timeline up to the next IDR. Hold the
        // stream closed until the first key frame of the new segment, then resume from it. The
        // requestKeyFrame() issued alongside the reset makes that arrive within a frame or two.
        if (dropUntilKeyFrame) {
            if (isKeyFrame) {
                dropUntilKeyFrame = false
                droppedAfterReset = 0L
                Log.i(TAG, "video resuming after discontinuity at pts=${info.presentationTimeUs}us")
            } else {
                if (++droppedAfterReset in setOf(1L, 30L, 120L)) {
                    Log.i(TAG, "dropping $droppedAfterReset pre-keyframe access units after discontinuity")
                }
                return
            }
        }

        val ptsUs = nextPtsUs(info.presentationTimeUs)
        // Non-blocking pace calculation for PTS generation only; we do NOT block here.
        // The UDP sink's sender thread handles actual transmission pacing.
        calculatePaceDelayNs(ptsUs)
        maybeWritePsi(ptsUs)
        if (anchorGeneration != timelineGeneration) {
            anchorGeneration = timelineGeneration
            videoAnchorUs = ptsUs
        }
        lastVideoPtsUs = ptsUs
        val header = buildPesHeader(ptsUs)
        val params = if (isKeyFrame) config else null

        val needed = header.size + (params?.size ?: 0) + accessUnit.size
        if (pesBuffer.size < needed) pesBuffer = ByteArray(needed)

        var cursor = 0
        System.arraycopy(header, 0, pesBuffer, cursor, header.size)
        cursor += header.size
        if (params != null) {
            System.arraycopy(params, 0, pesBuffer, cursor, params.size)
            cursor += params.size
        }
        System.arraycopy(accessUnit, 0, pesBuffer, cursor, accessUnit.size)
        cursor += accessUnit.size

        val pts90 = toTicks(ptsUs)
        val discontinuity = pendingDiscontinuityFlag
        pendingDiscontinuityFlag = false
        writePes(pesBuffer, cursor, pcrDue(pts90), pts90, videoPid, discontinuity)
        unitsMuxed++

        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.i(TAG, "end of stream after $unitsMuxed access units")
        }
    }

    /**
     * Phase F: one compressed audio frame (E-AC-3 or ADTS-framed AAC) from the source container,
     * already on the source media timeline. The muxer anchors it to the first video PTS of the
     * current segment ([videoAnchorUs]) and keeps it monotonic; the feeder owns the actual offset
     * and gating, and declared the codec through [setAudioFormat].
     */
    @Synchronized fun onAudioAccessUnit(data: ByteArray, rawPtsUs: Long) {
        if (data.isEmpty()) return
        if (data.size + 8 > 0xFFFF) {
            // PES_packet_length is 16-bit and audio streams must carry a real value. A single
            // E-AC-3 or AAC frame is a few kB, so this is a guard, not a path.
            Log.e(TAG, "audio frame of ${data.size} bytes does not fit one PES packet; dropping")
            return
        }
        val ptsUs = if (lastAudioPtsUs == Long.MIN_VALUE || rawPtsUs > lastAudioPtsUs) {
            rawPtsUs
        } else {
            // A backwards PTS makes receivers drop audio until the next resync, which is exactly
            // what the discontinuity path is trying to avoid. Repair it by one tick instead.
            lastAudioPtsUs + 1L
        }
        lastAudioPtsUs = ptsUs
        hasAudio = true
        maybeWritePsi(ptsUs)

        val header = buildPesHeader(ptsUs, audioStreamId, data.size + 8)
        val needed = header.size + data.size
        if (pesBuffer.size < needed) pesBuffer = ByteArray(needed)
        System.arraycopy(header, 0, pesBuffer, 0, header.size)
        System.arraycopy(data, 0, pesBuffer, header.size, data.size)
        writePes(pesBuffer, header.size + data.size, withPcr = false, toTicks(ptsUs), audioPid, discontinuity = false)
    }

    /** Muxes one DVB subtitle segment sample, preserving its original timing and segment bytes. */
    @Synchronized fun onDvbSubtitleAccessUnit(data: ByteArray, rawPtsUs: Long) {
        if (data.isEmpty() || data.size + 10 > 0xFFFF) return
        val ptsUs = if (lastSubtitlePtsUs == Long.MIN_VALUE || rawPtsUs > lastSubtitlePtsUs) rawPtsUs
        else lastSubtitlePtsUs + 1L
        lastSubtitlePtsUs = ptsUs
        hasSubtitle = true
        maybeWritePsi(ptsUs)
        // DVB EN 300 743 PES prefix: data_identifier in the DVB subtitling range, stream id 0.
        val payload = ByteArray(data.size + 2)
        payload[0] = 0x20
        payload[1] = 0x00
        System.arraycopy(data, 0, payload, 2, data.size)
        val header = buildPesHeader(ptsUs, AUDIO_STREAM_ID, payload.size + 8)
        val needed = header.size + payload.size
        if (pesBuffer.size < needed) pesBuffer = ByteArray(needed)
        System.arraycopy(header, 0, pesBuffer, 0, header.size)
        System.arraycopy(payload, 0, pesBuffer, header.size, payload.size)
        writePes(pesBuffer, needed, withPcr = false, toTicks(ptsUs), subtitlePid, discontinuity = false)
    }

    /**
     * Re-anchor PTS and sender pacing after a seek; raw UDP cannot signal a remote seek command.
     * Also closes the stream until the next key frame ([dropUntilKeyFrame]) so the encoder's
     * queued pre-seek frames cannot reach the receiver, and re-arms the audio anchor.
     */
    @Synchronized fun resetForDiscontinuity() {
        lastPtsUs = Long.MIN_VALUE
        fallbackActive = false
        lastPcr90 = Long.MIN_VALUE
        lastPsiUs = Long.MIN_VALUE
        paceAnchorPtsUs = Long.MIN_VALUE
        paceAnchorNs = 0L
        dropUntilKeyFrame = true
        droppedAfterReset = 0L
        pendingDiscontinuityFlag = true
        timelineGeneration++
        videoAnchorUs = Long.MIN_VALUE
        lastVideoPtsUs = Long.MIN_VALUE
        anchorGeneration = -1
        lastAudioPtsUs = Long.MIN_VALUE
        lastSubtitlePtsUs = Long.MIN_VALUE
        (output as? UdpTsPacketSink)?.discardPending()
        Log.i(TAG, "stream timeline reset after discontinuity (generation=$timelineGeneration)")
    }

    /**
     * Non-blocking pace helper: returns the nanoseconds until the next frame should be sent
     * based on the frame rate, or 0 if no pacing is needed. This does NOT block - the caller
     * decides whether to wait. Used only for PTS generation when codec timestamps are unusable.
     */
    private fun calculatePaceDelayNs(ptsUs: Long): Long {
        if (paceAnchorPtsUs == Long.MIN_VALUE) {
            paceAnchorPtsUs = ptsUs
            paceAnchorNs = System.nanoTime()
            return 0L
        }
        val dueNs = paceAnchorNs + (ptsUs - paceAnchorPtsUs).coerceAtLeast(0L) * 1_000L
        val remaining = dueNs - System.nanoTime()
        return remaining.coerceAtLeast(0L)
    }

    /**
     * The presentation timestamp to mux with: the codec's own when it is usable, a generated one
     * otherwise. Monotonicity is not optional - a transport stream whose PTS goes backwards makes
     * players drop frames or exit, and reporting that once is more useful than a silent repair.
     */
    private fun nextPtsUs(rawUs: Long): Long {
        // Zero is a perfectly good timestamp for the first frame, so the test is not "greater
        // than zero" but "non-negative and increasing". Once the timeline has been taken over it
        // stays taken over: a stream that switches back to codec timestamps mid-play is the
        // discontinuity this fallback exists to avoid.
        val usable = !fallbackActive && rawUs >= 0 &&
            (lastPtsUs == Long.MIN_VALUE || rawUs > lastPtsUs)
        val pts = if (usable) {
            rawUs
        } else {
            if (!fallbackActive) {
                fallbackActive = true
                Log.w(
                    TAG,
                    "encoder PTS is unusable (last=$lastPtsUs raw=$rawUs); " +
                        "generating a ${frameRate} fps timeline instead - Phase H owns fixing this"
                )
            }
            if (lastPtsUs == Long.MIN_VALUE) 0L else lastPtsUs + 1_000_000L / frameRate
        }
        lastPtsUs = pts
        return pts
    }

    private fun toTicks(ptsUs: Long): Long = (ptsUs * 90L / 1000L) and PTS_MASK

    private fun pcrDue(pts90: Long): Boolean {
        if (lastPcr90 == Long.MIN_VALUE) {
            // Record it. Returning without recording leaves the sentinel in place forever, which
            // puts a PCR on every single access unit instead of every PCR_INTERVAL_90KHZ.
            lastPcr90 = pts90
            return true
        }
        val since = (pts90 - lastPcr90) and PTS_MASK
        if (since >= PCR_INTERVAL_90KHZ) {
            lastPcr90 = pts90
            return true
        }
        return false
    }

    private fun maybeWritePsi(ptsUs: Long) {
        if (lastPsiUs != Long.MIN_VALUE && ptsUs - lastPsiUs < PSI_INTERVAL_US) return
        lastPsiUs = ptsUs
        writePat()
        writePmt()
    }

    /**
     * The PES header: the 9 fixed bytes plus the 5-byte PTS. [packetLength] follows the spec's
     * accounting - bytes after the length field - where 0 marks the unbounded video form and the
     * audio form must carry its real size.
     */
    private fun buildPesHeader(ptsUs: Long, streamId: Int = VIDEO_STREAM_ID, packetLength: Int = 0): ByteArray {
        val pts = toTicks(ptsUs)
        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = streamId.toByte()
        header[4] = ((packetLength shr 8) and 0xFF).toByte()
        header[5] = (packetLength and 0xFF).toByte()
        // '10' + scrambling 00 + priority 0 + alignment 0 + copyright 0 + original 0
        header[6] = 0x80.toByte()
        // PTS_DTS_flags '10' (PTS only) + six clear flags
        header[7] = 0x80.toByte()
        header[8] = 5
        writePts(header, 9, pts)
        return header
    }

    private fun writePts(target: ByteArray, offset: Int, pts: Long) {
        target[offset] = (0x20 or (((pts shr 30) and 0x7).toInt() shl 1) or 0x01).toByte()
        target[offset + 1] = ((pts shr 22) and 0xFF).toByte()
        target[offset + 2] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 0x01).toByte()
        target[offset + 3] = ((pts shr 7) and 0xFF).toByte()
        target[offset + 4] = (((pts and 0x7F).toInt() shl 1) or 0x01).toByte()
    }

    // ------------------------------------------------------------------------------------
    // PAT / PMT
    // ------------------------------------------------------------------------------------

    private fun writePat() {
        val section = sectionBuffer
        var n = 0
        section[n++] = 0x00.toByte() // table_id
        section[n++] = 0x00.toByte() // section_syntax + length high, patched below
        section[n++] = 0x00.toByte()
        section[n++] = ((programNumber shr 8) and 0xFF).toByte()
        section[n++] = (programNumber and 0xFF).toByte()
        section[n++] = 0xC1.toByte() // reserved 11 | version 0 | current_next 1
        section[n++] = 0x00.toByte() // section_number
        section[n++] = 0x00.toByte() // last_section_number
        section[n++] = ((programNumber shr 8) and 0xFF).toByte()
        section[n++] = (programNumber and 0xFF).toByte()
        section[n++] = (0xE0 or ((pmtPid shr 8) and 0x1F)).toByte()
        section[n++] = (pmtPid and 0xFF).toByte()
        patchSectionLength(section, n)
        n = appendCrc(section, n)
        writePsi(0x0000, section, n)
    }

    private fun writePmt() {
        val section = sectionBuffer
        var n = 0
        section[n++] = 0x02.toByte() // table_id
        section[n++] = 0x00.toByte()
        section[n++] = 0x00.toByte()
        section[n++] = ((programNumber shr 8) and 0xFF).toByte()
        section[n++] = (programNumber and 0xFF).toByte()
        section[n++] = 0xC1.toByte()
        section[n++] = 0x00.toByte()
        section[n++] = 0x00.toByte()
        section[n++] = (0xE0 or ((videoPid shr 8) and 0x1F)).toByte() // reserved | PCR_PID
        section[n++] = (videoPid and 0xFF).toByte()
        // A registration_descriptor naming the codec. Several TV-box firmwares will not identify an
        // HEVC elementary stream without it, and the four bytes cost nothing.
        section[n++] = 0xF0.toByte() // reserved | program_info_length, patched below
        section[n++] = 0x00.toByte()
        section[n++] = 0x05.toByte() // descriptor_tag: registration_descriptor
        section[n++] = 0x04.toByte() // descriptor_length
        section[n++] = 0x48.toByte() // 'H'
        section[n++] = 0x45.toByte() // 'E'
        section[n++] = 0x56.toByte() // 'V'
        section[n++] = 0x43.toByte() // 'C'
        // program_info_length is the 12-bit field two bytes above the descriptor tag; it was
        // written as a placeholder and is only knowable once the descriptor exists.
        // It counts the descriptor itself: tag + length + 4 payload bytes = 6. Writing 5 here
        // made every ES entry after the descriptor parse one byte early, which is how ffprobe
        // ended up reporting codec_tag 0x0043 instead of HEVC.
        section[10] = (0xF0 or ((6 shr 8) and 0x0F)).toByte()
        section[11] = 0x06.toByte()
        section[n++] = STREAM_TYPE_HEVC.toByte()
        section[n++] = (0xE0 or ((videoPid shr 8) and 0x1F)).toByte() // reserved | elementary_PID
        section[n++] = (videoPid and 0xFF).toByte()
        section[n++] = 0xF0.toByte() // reserved | ES_info_length = 0
        section[n++] = 0x00.toByte()
        // The audio entry appears only once a frame has actually been muxed: advertising a PID
        // that never carries packets makes some receivers wait for audio before showing video.
        if (hasAudio) {
            section[n++] = audioStreamType.toByte()
            section[n++] = (0xE0 or ((audioPid shr 8) and 0x1F)).toByte()
            section[n++] = (audioPid and 0xFF).toByte()
            if (audioStreamType == STREAM_TYPE_EAC3) {
                // DVB E-AC-3 descriptor (ETSI EN 300 468, tag 0x7A). The flags byte is zero:
                // optional component type, bsid, main id and service type fields are absent.
                // The original E-AC-3 access units remain untouched, including any Atmos/JOC
                // signaling carried in-band.
                section[n++] = 0xF0.toByte()
                section[n++] = 0x03.toByte() // ES_info_length
                section[n++] = 0x7A.toByte() // EAC3_descriptor
                section[n++] = 0x01.toByte()
                section[n++] = 0x00.toByte()
            } else {
                section[n++] = 0xF0.toByte() // ES_info_length = 0 (AAC ADTS)
                section[n++] = 0x00.toByte()
            }
        }
        if (hasSubtitle) {
            section[n++] = STREAM_TYPE_DVB_SUBTITLE.toByte()
            section[n++] = (0xE0 or ((subtitlePid shr 8) and 0x1F)).toByte()
            section[n++] = (subtitlePid and 0xFF).toByte()
            section[n++] = 0xF0.toByte()
            section[n++] = 0x0A.toByte() // descriptor total: tag + len + eight payload bytes
            section[n++] = 0x59.toByte() // subtitling_descriptor
            section[n++] = 0x08.toByte()
            subtitleLanguage.forEach { section[n++] = it.code.toByte() }
            section[n++] = subtitleType.toByte()
            section[n++] = (subtitleCompositionPageId shr 8).toByte()
            section[n++] = subtitleCompositionPageId.toByte()
            section[n++] = (subtitleAncillaryPageId shr 8).toByte()
            section[n++] = subtitleAncillaryPageId.toByte()
        }
        patchSectionLength(section, n)
        n = appendCrc(section, n)
        writePsi(pmtPid, section, n)
    }

    /**
     * Fills in section_syntax_indicator, the reserved bits and the 12-bit section_length.
     *
     * [end] is where the CRC will be written, so the bytes after the length field are everything
     * from index 3 to the end of the CRC: `end + 4 - 3`. For a one-program PAT that is 13, which
     * is the number every parser expects - getting it wrong by the size of the CRC makes the
     * section unparseable and looks exactly like "the player sees no stream".
     */
    private fun patchSectionLength(section: ByteArray, end: Int) {
        val length = end + 1
        section[1] = (0xB0 or ((length shr 8) and 0x0F)).toByte()
        section[2] = (length and 0xFF).toByte()
    }

    private fun appendCrc(section: ByteArray, end: Int): Int {
        val crc = crc32(section, end)
        section[end] = (crc ushr 24).toByte()
        section[end + 1] = (crc ushr 16).toByte()
        section[end + 2] = (crc ushr 8).toByte()
        section[end + 3] = crc.toByte()
        return end + 4
    }

    private fun writePsi(pid: Int, section: ByteArray, length: Int) {
        packet[0] = 0x47.toByte()
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte() // payload_unit_start
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = (0x10 or takeContinuity(pid)).toByte() // adaptation 01 = payload only
        packet[4] = 0x00.toByte() // pointer_field
        System.arraycopy(section, 0, packet, 5, length)
        var i = 5 + length
        while (i < TS_PACKET_SIZE) {
            // PSI sections are padded with 0xFF up to the packet end; this is the padding the
            // spec asks for, not leftover buffer.
            packet[i++] = 0xFF.toByte()
        }
        output.onTsPacket(packet, TS_PACKET_SIZE)
    }

    private fun takeContinuity(pid: Int): Int {
        val current = continuity[pid] ?: 0
        continuity[pid] = (current + 1) and 0x0F
        return current
    }

    // ------------------------------------------------------------------------------------
    // Payload packetisation
    // ------------------------------------------------------------------------------------

    /**
     * Splits one PES packet ([length] bytes of [payload]) into transport packets on [pid].
     *
     * The adaptation field does double duty: it carries the PCR when [withPcr] is set, sets the
     * discontinuity_indicator when [discontinuity] is set, and absorbs the padding that brings
     * the final packet of a PES up to exactly 188 bytes. A payload-only packet gets
     * `adaptation_field_control=01`; anything else gets `11`, and a one-byte adaptation is written
     * as `adaptation_field_length = 0`, which is the spec's way of stuffing a single byte.
     */
    private fun writePes(
        payload: ByteArray,
        length: Int,
        withPcr: Boolean,
        pts90: Long,
        pid: Int,
        discontinuity: Boolean = false,
    ) {
        var offset = 0
        var first = true
        while (offset < length) {
            val remaining = length - offset
            val pcrBytes = if (first && withPcr) 8 else 0
            // A discontinuity_indicator on the first packet needs its own flags byte, so reserve
            // two adaptation bytes when no PCR already guarantees a flags-bearing adaptation. A
            // one-byte adaptation is length-only and cannot carry the flag.
            val flagBytes = if (first && discontinuity && pcrBytes == 0) 2 else 0
            val maxPayload = TS_PACKET_SIZE - 4 - pcrBytes - flagBytes
            val take = minOf(remaining, maxPayload)
            val adaptation = TS_PACKET_SIZE - 4 - take

            packet[0] = 0x47.toByte()
            packet[1] = (
                (if (first) 0x40 else 0x00) or ((pid shr 8) and 0x1F)
                ).toByte() // payload_unit_start on the packet that begins the PES
            packet[2] = (pid and 0xFF).toByte()
            packet[3] = (
                ((if (adaptation == 0) 0x01 else 0x03) shl 4) or takeContinuity(pid)
                ).toByte()

            if (adaptation > 0) {
                writeAdaptation(adaptation, if (first) pts90 else null, withPcr && first, discontinuity && first)
            }
            System.arraycopy(payload, offset, packet, 4 + adaptation, take)
            output.onTsPacket(packet, TS_PACKET_SIZE)

            offset += take
            first = false
        }
    }

    private fun writeAdaptation(totalBytes: Int, pcr: Long?, writePcr: Boolean, discontinuity: Boolean = false) {
        if (totalBytes == 1) {
            packet[4] = 0x00.toByte() // adaptation_field_length 0: one byte of pure stuffing
            return
        }
        packet[4] = (totalBytes - 1).toByte()
        val flags = (if (writePcr) 0x10 else 0x00) or (if (discontinuity) 0x80 else 0x00)
        packet[5] = flags.toByte()
        var cursor = 6
        if (writePcr && pcr != null) {
            val base = pcr and PTS_MASK
            packet[cursor++] = ((base shr 25) and 0xFF).toByte()
            packet[cursor++] = ((base shr 17) and 0xFF).toByte()
            packet[cursor++] = ((base shr 9) and 0xFF).toByte()
            packet[cursor++] = ((base shr 1) and 0xFF).toByte()
            // low bit of the base, then six reserved one-bits, then the 9-bit extension (0).
            packet[cursor++] = ((((base and 0x01).toInt()) shl 7) or 0x7E).toByte()
            packet[cursor++] = 0x00.toByte()
        }
        val end = 4 + totalBytes
        while (cursor < end) {
            packet[cursor++] = 0xFF.toByte() // stuffing
        }
    }
}
