package com.rife.androidtv.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.rife.androidtv.encode.EncodedStreamSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

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
 * Phase E: MPEG-TS muxing of the encoder's H.265 access units.
 *
 * ```
 * HdrHevcEncoder -> MpegTsMuxer -> TsPacketSink (file now, UDP in Phase F)
 * ```
 *
 * Video only. Audio is deliberately absent until Phase H, which owns the presentation timeline -
 * muxing an audio track whose PTS we have not yet derived from the source would produce a stream
 * that looks wrong for the wrong reason.
 *
 * What is emitted, per 188-byte packet:
 *
 *  * a PAT on PID 0 and a PMT on [pmtPid], every 50 ms and before the first access unit, so a
 *    player that tunes in late still finds the program;
 *  * one PES packet per access unit, PTS only. PTS-only is correct exactly when there are no
 *    B-frames - which is why [com.rife.androidtv.encode.HdrHevcEncoder] asks for `max-bframes=0` -
 *    and it is what a low-latency streaming pipeline wants anyway;
 *  * a PCR every 30 ms in the adaptation field, derived from the same clock as the PES PTS so a
 *    player can lock its clock to ours instead of guessing.
 *
 * Everything is derived from the access unit's own presentation timestamp. When that timestamp
 * arrives as zero or stops increasing - a surface-input encoder that nobody called
 * `eglPresentationTimeANDROID` on - the muxer falls back to a generated timeline at the negotiated
 * frame rate and says so once, loudly, rather than emitting a stream of zero-length PTS deltas.
 * Replacing that fallback with the real media timeline is Phase H's job.
 */
class MpegTsMuxer(
    private val output: TsPacketSink,
    private val videoPid: Int = 0x0100,
    private val pmtPid: Int = 0x1000,
    private val programNumber: Int = 1,
) : EncodedStreamSink {

    companion object {
        private const val TAG = "MpegTsMuxer"

        /** ISO 13818-1 packet length. Nothing else is legal in a transport stream. */
        const val TS_PACKET_SIZE = 188

        /** ISO 13818-1 stream_type for H.265 / HEVC. */
        private const val STREAM_TYPE_HEVC = 0x24

        private const val VIDEO_STREAM_ID = 0xE0

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

    override fun onAccessUnit(data: ByteBuffer, info: MediaCodec.BufferInfo) {
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

        val ptsUs = nextPtsUs(info.presentationTimeUs)
        maybeWritePsi(ptsUs)

        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
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
        writePes(pesBuffer, cursor, pcrDue(pts90), pts90)
        unitsMuxed++

        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.i(TAG, "end of stream after $unitsMuxed access units")
        }
    }

    /**
     * The presentation timestamp to mux with: the codec's own when it is usable, a generated one
     * otherwise. Monotonicity is not optional - a transport stream whose PTS goes backwards makes
     * players drop frames or exit, and reporting that once is more useful than a silent repair.
     */
    private fun nextPtsUs(rawUs: Long): Long {
        val usable = rawUs > 0 && (lastPtsUs == Long.MIN_VALUE || rawUs > lastPtsUs)
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
        if (lastPcr90 == Long.MIN_VALUE) return true
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

    /** The 9-byte PES header plus the 5-byte PTS, which is all a video packet needs. */
    private fun buildPesHeader(ptsUs: Long): ByteArray {
        val pts = toTicks(ptsUs)
        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = VIDEO_STREAM_ID.toByte()
        // PES_packet_length 0: video packets are unbounded by the spec.
        header[4] = 0x00
        header[5] = 0x00
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
        section[n++] = 0xF0.toByte() // reserved | program_info_length = 0
        section[n++] = 0x00.toByte()
        section[n++] = STREAM_TYPE_HEVC.toByte()
        section[n++] = (0xE0 or ((videoPid shr 8) and 0x1F)).toByte() // reserved | elementary_PID
        section[n++] = (videoPid and 0xFF).toByte()
        section[n++] = 0xF0.toByte() // reserved | ES_info_length = 0
        section[n++] = 0x00.toByte()
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
     * Splits one PES packet ([length] bytes of [payload]) into transport packets on [videoPid].
     *
     * The adaptation field does double duty: it carries the PCR when [withPcr] is set, and it
     * absorbs the padding that brings the final packet of a PES up to exactly 188 bytes. A
     * payload-only packet gets `adaptation_field_control=01`; anything else gets `11`, and a
     * one-byte adaptation is written as `adaptation_field_length = 0`, which is the spec's way of
     * stuffing a single byte.
     */
    private fun writePes(payload: ByteArray, length: Int, withPcr: Boolean, pts90: Long) {
        var offset = 0
        var first = true
        while (offset < length) {
            val remaining = length - offset
            val pcrBytes = if (first && withPcr) 8 else 0
            val maxPayload = TS_PACKET_SIZE - 4 - pcrBytes
            val take = minOf(remaining, maxPayload)
            val adaptation = TS_PACKET_SIZE - 4 - take

            packet[0] = 0x47.toByte()
            packet[1] = (
                (if (first) 0x40 else 0x00) or ((videoPid shr 8) and 0x1F)
                ).toByte() // payload_unit_start on the packet that begins the PES
            packet[2] = (videoPid and 0xFF).toByte()
            packet[3] = (
                ((if (adaptation == 0) 0x01 else 0x03) shl 4) or takeContinuity(videoPid)
                ).toByte()

            if (adaptation > 0) {
                writeAdaptation(adaptation, if (first) pts90 else null, withPcr && first)
            }
            System.arraycopy(payload, offset, packet, 4 + adaptation, take)
            output.onTsPacket(packet, TS_PACKET_SIZE)

            offset += take
            first = false
        }
    }

    private fun writeAdaptation(totalBytes: Int, pcr: Long?, writePcr: Boolean) {
        if (totalBytes == 1) {
            packet[4] = 0x00.toByte() // adaptation_field_length 0: one byte of pure stuffing
            return
        }
        packet[4] = (totalBytes - 1).toByte()
        val flags = if (writePcr) 0x10 else 0x00 // PCR_flag
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
