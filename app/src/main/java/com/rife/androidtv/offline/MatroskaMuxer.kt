package com.rife.androidtv.offline

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal Matroska (EBML) muxer for the offline 3x interpolation job.
 *
 * Why this exists instead of [android.media.MediaMuxer]: MediaMuxer writes MP4/WebM only and
 * cannot carry a subtitle track at all, nor audio codecs like DTS/TrueHD that movie files
 * actually ship. The job's contract is that audio and subtitle streams are copied bit-exactly
 * and keep their original timestamps, so the container has to accept every codec the source
 * extractor can read. Matroska does, and writing the subset the job needs is smaller than
 * working around its absence.
 *
 * Output shape:
 *  * EBML header + Segment of unknown size (players scan the tail for Cues);
 *  * Info with TimestampScale 10_000 (10 microsecond ticks): the block-relative timestamp is
 *    int16, so at 100 ns ticks a block could only reach 3.2 ms past its cluster - at 10 us
 *    ticks it reaches 327 ms, which lets clusters hold a sensible span while keeping
 *    microsecond precision on the 3x presentation times;
 *  * Tracks written once up front (video from the encoder's output format, audio/subtitle
 *    descriptors from the source);
 *  * Clusters of up to ~250 ms or [MAX_CLUSTER_BYTES], opened by the video's own progression:
 *    SimpleBlocks for video and audio, BlockGroups with a Duration element for subtitles
 *    (their samples carry a start time only);
 *  * Cues after the last cluster, one CuePoint per video keyframe, so players can seek.
 *
 * Subtitles are pre-buffered through [addSubtitleEvents] before the first video sample: a
 * subtitle's display duration is the gap to the next event of the same track, which cannot be
 * known while streaming, and a wrong duration cuts dialogue short. Events are a few hundred kB
 * for a whole movie, so holding them is free.
 *
 * Everything is written through one lock: the encoder's drain thread appends video samples
 * while the pipeline thread enqueues audio, and both must agree on cluster order. This class
 * is free of Android types and unit-testable on the JVM.
 */
class MatroskaMuxer(
    private val out: OutputStream,
    private val durationUs: Long,
    private val title: String? = null,
) : Closeable {

    /** One track, fully described before the first sample. */
    data class Track(
        val number: Int,
        val type: Int,
        val codecId: String,
        val codecPrivate: ByteArray? = null,
        val language: String = "und",
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Float = 0f,
        val colour: Colour? = null,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val bitDepth: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = number
    }

    /**
     * Colour signalling in Matroska values: primaries 1 = BT.709, 9 = BT.2020; transfer
     * 1 = BT.709, 16 = ST2084 (PQ), 18 = HLG; matrix 1 = BT.709, 9 = BT.2020 non-constant;
     * range 1 = full, 2 = limited.
     */
    data class Colour(
        val primaries: Int,
        val transfer: Int,
        val matrix: Int,
        val range: Int,
        val mastering: MasteringDisplay? = null,
        val maxClL: Int = 0,
        val maxFall: Int = 0,
    )

    /** ST 2086 mastering display in Matroska units: chromaticities x50_000, luminance x10_000. */
    data class MasteringDisplay(
        val redX: Int, val redY: Int,
        val greenX: Int, val greenY: Int,
        val blueX: Int, val blueY: Int,
        val whiteX: Int, val whiteY: Int,
        val minLuminance: Int, val maxLuminance: Int,
    )

    /** One subtitle event with its payload exactly as the source extractor produced it. */
    data class SubtitleEvent(val ptsUs: Long, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = ptsUs.hashCode()
    }

    private val buffered: OutputStream =
        if (out is BufferedOutputStream) out else BufferedOutputStream(out, 1 shl 16)

    private val tracks = ArrayList<Track>()
    private var headerWritten = false
    private var segmentDataStart = 0L
    private var bytesWritten = 0L
    private var closed = false

    // Cluster assembly.
    private var clusterOpen = false
    private var clusterStartTicks = 0L
    private var clusterBytes = 0
    private val clusterBuf = ByteArrayOutputStream(1 shl 16)

    // CuePoints for video keyframes: [timeTicks, clusterOffsetFromSegmentData].
    private val cuePoints = ArrayList<LongArray>()

    // Per-track subtitle event queues, sorted by pts, with display durations precomputed.
    private class SubQueue(val trackNumber: Int) {
        var pts = LongArray(0)
        var payloads = Array(0) { ByteArray(0) }
        var durations = LongArray(0) // ticks
        var next = 0
        fun remaining() = next < pts.size
    }

    private val subQueues = ArrayList<SubQueue>()

    /** Tracks must be declared before the first sample; the header is written on the first add. */
    fun addTrack(track: Track) {
        check(!closed) { "muxer closed" }
        check(!headerWritten) { "tracks must be declared before the first sample" }
        tracks += track
    }

    /**
     * Declares a subtitle track's full event list (any order; sorted here) and precomputes each
     * event's display duration as the gap to the next event of the same track. The last event
     * of a track gets [DEFAULT_SUBTITLE_DURATION_US].
     */
    @Synchronized
    fun addSubtitleEvents(trackNumber: Int, events: List<SubtitleEvent>) {
        check(!closed) { "muxer closed" }
        val sorted = events.sortedBy { it.ptsUs }
        val queue = SubQueue(trackNumber)
        val n = sorted.size
        queue.pts = LongArray(n) { sorted[it].ptsUs }
        queue.payloads = Array(n) { sorted[it].payload }
        queue.durations = LongArray(n) {
                val nextPts = if (it + 1 < n) sorted[it + 1].ptsUs else sorted[it].ptsUs + DEFAULT_SUBTITLE_DURATION_US
                (usToTicks(nextPts - sorted[it].ptsUs)).coerceAtLeast(1L)
        }
        subQueues += queue
    }

    /**
     * One encoded video access unit. [keyframe] drives both the SimpleBlock flag and the Cues
     * entries players seek with. Called on the encoder's drain thread.
     */
    @Synchronized
    fun addVideoSample(data: ByteArray, ptsUs: Long, keyframe: Boolean) {
        writeHeaderOnce()
        val ticks = usToTicks(ptsUs)
        if (clusterOpen && (ticks - clusterStartTicks >= CLUSTER_SPAN_TICKS || clusterBytes >= MAX_CLUSTER_BYTES)) {
            closeCluster()
        }
        if (!clusterOpen) {
            openCluster(ticks)
            // Nothing else is written between open and close, so the cluster element will
            // land exactly at the current top-level position when it is flushed.
            if (keyframe) cuePoints.add(longArrayOf(ticks, bytesWritten - segmentDataStart))
        }
        val block = simpleBlock(videoTrackNumber, ticks - clusterStartTicks, keyframe, data)
        clusterBuf.write(block)
        clusterBytes += block.size
    }

    /** One audio sample, copied verbatim with its original timestamp. */
    @Synchronized
    fun addAudioSample(trackNumber: Int, data: ByteArray, ptsUs: Long) {
        writeHeaderOnce()
        check(tracks.any { it.number == trackNumber }) { "unknown track $trackNumber" }
        val ticks = usToTicks(ptsUs)
        if (!clusterOpen) openCluster(ticks)
        val block = simpleBlock(trackNumber, ticks - clusterStartTicks, true, data)
        clusterBuf.write(block)
        clusterBytes += block.size
    }

    /** Flushes remaining subtitle events, closes the last cluster and writes the Cues. */
    @Synchronized
    fun finish() {
        if (closed) return
        writeHeaderOnce()
        // Drain the open window, then give any events past the last video frame clusters of
        // their own: cluster timestamps may only move forward, so a late event cannot be
        // back-dated into a closed window without corrupting its block offset.
        closeCluster()
        while (subQueues.any { it.remaining() }) {
            val next = subQueues.filter { it.remaining() }.minOf { it.pts[it.next] }
            openCluster(next)
            drainSubtitlesUpTo(next + CLUSTER_SPAN_TICKS)
            clusterOpen = false
            val payload = clusterBuf.toByteArray()
            writeTop(ID_CLUSTER, payload)
            clusterBytes = 0
        }
        writeCues()
        buffered.flush()
        closed = true
    }

    override fun close() {
        if (!closed) finish()
        buffered.close()
    }

    // ---------------------------------------------------------------- internals

    private val videoTrackNumber: Int
        get() = tracks.firstOrNull { it.type == TRACK_TYPE_VIDEO }?.number
            ?: throw IllegalStateException("no video track declared")

    private fun writeHeaderOnce() {
        if (headerWritten) return
        check(tracks.isNotEmpty()) { "no tracks declared" }
        // EBML header.
        val header = Scope().apply {
            uint(ID_EBML_VERSION, 1L)
            uint(ID_EBML_READ_VERSION, 1L)
            uint(ID_EBML_MAX_ID_LENGTH, 4L)
            uint(ID_EBML_MAX_SIZE_LENGTH, 8L)
            str(ID_DOCTYPE, "matroska")
            uint(ID_DOCTYPE_VERSION, 4L)
            uint(ID_DOCTYPE_READ_VERSION, 2L)
        }
        writeTop(ID_EBML, header.take())
        // Segment with unknown size: the size vint 0x01FFFFFFFFFFFFFF.
        emit(ID_BYTES_SEGMENT)
        emit(UNKNOWN_SIZE_VINT)
        segmentDataStart = bytesWritten
        // Info.
        val info = Scope().apply {
            uint(ID_TIMESTAMP_SCALE, TIMESTAMP_SCALE)
            flt(ID_DURATION, usToTicks(durationUs).toDouble())
            str(ID_MUXING_APP, "NextPlayer")
            str(ID_WRITING_APP, "NextPlayer offline 3x interpolation")
            title?.takeIf { it.isNotBlank() }?.let { str(ID_TITLE, it) }
        }
        writeTop(ID_INFO, info.take())
        // Tracks.
        val tracksScope = Scope()
        for (track in tracks) {
            val entry = Scope()
            entry.uint(ID_TRACK_NUMBER, track.number.toLong())
            entry.uint(ID_TRACK_UID, track.number.toLong())
            entry.uint(ID_TRACK_TYPE, track.type.toLong())
            entry.uint(ID_FLAG_LACING, 0L)
            entry.uint(ID_CODEC_DECODE_ALL, 1L)
            if (track.language != "und") entry.str(ID_LANGUAGE, track.language)
            entry.str(ID_CODEC_ID, track.codecId)
            track.codecPrivate?.takeIf { it.isNotEmpty() }?.let { entry.bin(ID_CODEC_PRIVATE, it) }
            when (track.type) {
                TRACK_TYPE_VIDEO -> entry.nested(ID_VIDEO) {
                    uint(ID_PIXEL_WIDTH, track.width.toLong())
                    uint(ID_PIXEL_HEIGHT, track.height.toLong())
                    if (track.frameRate > 0f) flt(ID_FRAME_RATE, track.frameRate.toDouble())
                    track.colour?.let { writeColour(it) }
                }
                TRACK_TYPE_AUDIO -> entry.nested(ID_AUDIO) {
                    if (track.sampleRate > 0) flt(ID_SAMPLING_FREQUENCY, track.sampleRate.toDouble())
                    if (track.channels > 0) uint(ID_CHANNELS, track.channels.toLong())
                    if (track.bitDepth > 0) uint(ID_BIT_DEPTH, track.bitDepth.toLong())
                }
            }
            tracksScope.nestedRaw(ID_TRACK_ENTRY, entry.take())
        }
        writeTop(ID_TRACKS, tracksScope.take())
        headerWritten = true
    }

    private fun Scope.writeColour(c: Colour) {
        nested(ID_COLOUR) {
            uint(ID_COLOUR_PRIMARIES, c.primaries.toLong())
            uint(ID_TRANSFER_CHARACTERISTICS, c.transfer.toLong())
            uint(ID_MATRIX_COEFFICIENTS, c.matrix.toLong())
            uint(ID_COLOUR_RANGE, c.range.toLong())
            if (c.maxClL > 0) uint(ID_MAX_CLL, c.maxClL.toLong())
            if (c.maxFall > 0) uint(ID_MAX_FALL, c.maxFall.toLong())
            c.mastering?.let { m ->
                nested(ID_MASTERING_METADATA) {
                    uint(ID_PRIMARY_RED_X, m.redX.toLong())
                    uint(ID_PRIMARY_RED_Y, m.redY.toLong())
                    uint(ID_PRIMARY_GREEN_X, m.greenX.toLong())
                    uint(ID_PRIMARY_GREEN_Y, m.greenY.toLong())
                    uint(ID_PRIMARY_BLUE_X, m.blueX.toLong())
                    uint(ID_PRIMARY_BLUE_Y, m.blueY.toLong())
                    uint(ID_WHITE_POINT_X, m.whiteX.toLong())
                    uint(ID_WHITE_POINT_Y, m.whiteY.toLong())
                    uint(ID_LUMINANCE_MIN, m.minLuminance.toLong())
                    uint(ID_LUMINANCE_MAX, m.maxLuminance.toLong())
                }
            }
        }
    }

    /**
     * Writes every queued subtitle event whose start time falls before [endTicks] (or all of
     * them at [Long.MAX_VALUE]) as BlockGroups, draining each track's queue in order. Called
     * with a cluster open whose window the events fall inside, so block offsets stay small.
     */
    private fun drainSubtitlesUpTo(endTicks: Long) {
        if (subQueues.isEmpty()) return
        // Events are placed into the cluster that is open when they drain; the caller keeps
        // cluster windows aligned with the video, and passthrough events track video time, so
        // the offset is one interleave window at worst.
        for (queue in subQueues) {
            while (queue.remaining() && queue.pts[queue.next] < endTicks) {
                val i = queue.next
                val ticks = queue.pts[i]
                if (!clusterOpen || ticks - clusterStartTicks >= CLUSTER_SPAN_TICKS) {
                    // Past the open window: open the cluster that owns this event. Cluster
                    // timestamps may only move forward, so a late event clamps into the
                    // current window rather than rewinding the file.
                    if (!clusterOpen) openCluster(ticks)
                }
                val rel = (ticks - clusterStartTicks).coerceIn(-MAX_BLOCK_OFFSET_TICKS, MAX_BLOCK_OFFSET_TICKS)
                val trackVint = vintBytes(queue.trackNumber.toLong())
                val blockBody = ByteArrayOutputStream(queue.payloads[i].size + trackVint.size + 3)
                blockBody.write(trackVint)
                blockBody.write(((rel.toInt() shr 8) and 0xFF))
                blockBody.write(rel.toInt() and 0xFF)
                blockBody.write(0x00)
                blockBody.write(queue.payloads[i])
                val inner = ByteArrayOutputStream(blockBody.size() + 16)
                writeElementTo(inner, ID_BLOCK, blockBody.toByteArray())
                writeUintElementTo(inner, ID_BLOCK_DURATION, queue.durations[i])
                val group = elementBytes(ID_BLOCK_GROUP, inner.toByteArray())
                clusterBuf.write(group)
                clusterBytes += group.size
                queue.next++
            }
        }
    }

    private fun openCluster(ticks: Long) {
        // Cluster Timecode is absolute in TimestampScale ticks.
        clusterStartTicks = ticks
        clusterBytes = 0
        clusterOpen = true
        clusterBuf.reset()
        val tc = elementBytes(ID_TIMECODE, uintBytes(ticks))
        clusterBuf.write(tc)
        clusterBytes += tc.size
    }

    private fun closeCluster() {
        if (!clusterOpen) return
        // Late subtitle events land here before the window closes.
        drainSubtitlesUpTo(clusterStartTicks + CLUSTER_SPAN_TICKS)
        val payload = clusterBuf.toByteArray()
        writeTop(ID_CLUSTER, payload)
        clusterOpen = false
        clusterBytes = 0
    }

    private fun writeCues() {
        if (cuePoints.isEmpty()) return
        val body = ByteArrayOutputStream(cuePoints.size * 32 + 16)
        for (cue in cuePoints) {
            val tps = ByteArrayOutputStream(24)
            writeUintElementTo(tps, ID_CUE_TRACK, videoTrackNumber.toLong())
            writeUintElementTo(tps, ID_CUE_CLUSTER_POSITION, cue[1])
            val inner = ByteArrayOutputStream(48)
            writeUintElementTo(inner, ID_CUE_TIME, cue[0])
            writeElementTo(inner, ID_CUE_TRACK_POSITIONS, tps.toByteArray())
            writeElementTo(body, ID_CUE_POINT, inner.toByteArray())
        }
        writeTop(ID_CUES, body.toByteArray())
    }

    private fun simpleBlock(trackNumber: Int, relTicks: Long, keyframe: Boolean, data: ByteArray): ByteArray {
        val rel = relTicks.coerceIn(-MAX_BLOCK_OFFSET_TICKS, MAX_BLOCK_OFFSET_TICKS).toInt()
        val track = vintBytes(trackNumber.toLong())
        val body = ByteArray(track.size + 3 + data.size)
        var o = 0
        for (b in track) body[o++] = b
        body[o++] = ((rel shr 8) and 0xFF).toByte()
        body[o++] = (rel and 0xFF).toByte()
        body[o++] = if (keyframe) 0x80.toByte() else 0x00
        System.arraycopy(data, 0, body, o, data.size)
        return elementBytes(ID_SIMPLE_BLOCK, body)
    }

    // ---------------------------------------------------------------- IO + EBML primitives

    private fun emit(bytes: ByteArray) {
        buffered.write(bytes)
        bytesWritten += bytes.size
    }

    private fun writeTop(id: Int, payload: ByteArray) {
        emit(idBytes(id))
        emit(vintBytes(payload.size.toLong()))
        emit(payload)
    }

    /** Builds one element's id+size+payload bytes. */
    private fun elementBytes(id: Int, payload: ByteArray): ByteArray {
        val size = vintBytes(payload.size.toLong())
        val idb = idBytes(id)
        val outBytes = ByteArray(idb.size + size.size + payload.size)
        System.arraycopy(idb, 0, outBytes, 0, idb.size)
        System.arraycopy(size, 0, outBytes, idb.size, size.size)
        System.arraycopy(payload, 0, outBytes, idb.size + size.size, payload.size)
        return outBytes
    }

    private fun writeElementTo(sink: ByteArrayOutputStream, id: Int, payload: ByteArray) {
        sink.write(idBytes(id))
        sink.write(vintBytes(payload.size.toLong()))
        sink.write(payload)
    }

    private fun writeUintElementTo(sink: ByteArrayOutputStream, id: Int, value: Long) {
        writeElementTo(sink, id, uintBytes(value))
    }

    /** EBML size vint: minimal length, marker bit set on the first byte. */
    private fun vintBytes(value: Long): ByteArray {
        var length = 1
        while (length < 8 && value >= (1L shl (7 * length))) length++
        val bytes = ByteArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            bytes[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        bytes[0] = (bytes[0].toInt() or (0x80 shr (length - 1))).toByte()
        return bytes
    }

    /** Big-endian, minimal length (at least one byte). */
    private fun uintBytes(value: Long): ByteArray {
        var length = 1
        var probe = value
        while (probe > 0xFF) {
            probe = probe shr 8
            length++
        }
        val bytes = ByteArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            bytes[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return bytes
    }

    private fun idBytes(id: Int): ByteArray = when {
        id <= 0xFF -> byteArrayOf(id.toByte())
        id <= 0xFFFF -> byteArrayOf((id shr 8).toByte(), id.toByte())
        id <= 0xFFFFFF -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
        else -> byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
    }

    /**
     * Microseconds to TimestampScale ticks. [TIMESTAMP_SCALE] is nanoseconds per tick, so the
     * conversion is `us * 1_000 / scale`; the parenthesised form the code used before divided
     * `10_000 / 1_000_000` to zero first and silently zeroed every timestamp in the file.
     */
    private fun usToTicks(us: Long): Long = us * 1_000L / TIMESTAMP_SCALE

    /** Accumulates one master element's payload. */
    private inner class Scope {
        private val buf = ByteArrayOutputStream(256)

        fun take(): ByteArray = buf.toByteArray()

        fun uint(id: Int, value: Long) = writeUintElementTo(buf, id, value)

        fun flt(id: Int, value: Double) {
            val payload = ByteArray(8)
            val bits = java.lang.Double.doubleToRawLongBits(value)
            for (i in 0 until 8) payload[i] = (bits shr (56 - 8 * i)).toByte()
            writeElementTo(buf, id, payload)
        }

        fun str(id: Int, value: String) = writeElementTo(buf, id, value.toByteArray(StandardCharsets.UTF_8))

        fun bin(id: Int, value: ByteArray) = writeElementTo(buf, id, value)

        fun nested(id: Int, block: Scope.() -> Unit) {
            val inner = Scope()
            inner.block()
            writeElementTo(buf, id, inner.take())
        }

        fun nestedRaw(id: Int, payload: ByteArray) = writeElementTo(buf, id, payload)
    }

    companion object {
        const val TIMESTAMP_SCALE = 10_000L // 10 us per tick

        const val TRACK_TYPE_VIDEO = 1
        const val TRACK_TYPE_AUDIO = 2
        const val TRACK_TYPE_SUBTITLE = 17

        // int16 block offsets reach +/-32767 ticks = +/-327 ms at 10 us ticks; 250 ms keeps
        // every in-cluster offset comfortably inside while bounding interleave distance.
        private const val CLUSTER_SPAN_TICKS = 25_000L
        private const val MAX_CLUSTER_BYTES = 4 shl 20
        private const val MAX_BLOCK_OFFSET_TICKS = 32_767L
        private const val DEFAULT_SUBTITLE_DURATION_US = 500_000L

        private val UNKNOWN_SIZE_VINT = byteArrayOf(
            0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        private val ID_BYTES_SEGMENT = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)

        // Element IDs as their literal big-endian bytes (see ID_BYTES).
        private const val ID_EBML = 0x1A45DFA3
        private const val ID_EBML_VERSION = 0x4286
        private const val ID_EBML_READ_VERSION = 0x42F7
        private const val ID_EBML_MAX_ID_LENGTH = 0x42F3
        private const val ID_EBML_MAX_SIZE_LENGTH = 0x42F0
        private const val ID_DOCTYPE = 0x4282
        private const val ID_DOCTYPE_VERSION = 0x4287
        private const val ID_DOCTYPE_READ_VERSION = 0x4285
        private const val ID_INFO = 0x1549A966
        private const val ID_TIMESTAMP_SCALE = 0x2AD7B1
        private const val ID_DURATION = 0x4489
        private const val ID_MUXING_APP = 0x4D80
        private const val ID_WRITING_APP = 0x5741
        private const val ID_TITLE = 0x7BA9
        private const val ID_TRACKS = 0x1654AE6B
        private const val ID_TRACK_ENTRY = 0xAE
        private const val ID_TRACK_NUMBER = 0xD7
        private const val ID_TRACK_UID = 0x73C5
        private const val ID_TRACK_TYPE = 0x83
        private const val ID_FLAG_LACING = 0x9C
        private const val ID_CODEC_DECODE_ALL = 0xAA
        private const val ID_LANGUAGE = 0x22B59C
        private const val ID_CODEC_ID = 0x86
        private const val ID_CODEC_PRIVATE = 0x63A2
        private const val ID_VIDEO = 0xE0
        private const val ID_PIXEL_WIDTH = 0xB0
        private const val ID_PIXEL_HEIGHT = 0xBA
        private const val ID_FRAME_RATE = 0x2383E3
        private const val ID_AUDIO = 0xE1
        private const val ID_SAMPLING_FREQUENCY = 0xB5
        private const val ID_CHANNELS = 0x9F
        private const val ID_BIT_DEPTH = 0x6246
        private const val ID_COLOUR = 0x55B0
        private const val ID_COLOUR_PRIMARIES = 0x55B7
        private const val ID_TRANSFER_CHARACTERISTICS = 0x55B6
        private const val ID_MATRIX_COEFFICIENTS = 0x55B1
        private const val ID_COLOUR_RANGE = 0x55B9
        private const val ID_MAX_CLL = 0x55AA
        private const val ID_MAX_FALL = 0x55AB
        private const val ID_MASTERING_METADATA = 0x55DB
        private const val ID_PRIMARY_RED_X = 0x55D0
        private const val ID_PRIMARY_RED_Y = 0x55D1
        private const val ID_PRIMARY_GREEN_X = 0x55D2
        private const val ID_PRIMARY_GREEN_Y = 0x55D3
        private const val ID_PRIMARY_BLUE_X = 0x55D4
        private const val ID_PRIMARY_BLUE_Y = 0x55D5
        private const val ID_WHITE_POINT_X = 0x55D6
        private const val ID_WHITE_POINT_Y = 0x55D7
        private const val ID_LUMINANCE_MAX = 0x55D9
        private const val ID_LUMINANCE_MIN = 0x55DA
        private const val ID_CLUSTER = 0x1F43B675
        private const val ID_TIMECODE = 0xE7
        private const val ID_SIMPLE_BLOCK = 0xA3
        private const val ID_BLOCK_GROUP = 0xA0
        private const val ID_BLOCK = 0xA1
        private const val ID_BLOCK_DURATION = 0x9B
        private const val ID_CUES = 0x1C53BB6B
        private const val ID_CUE_POINT = 0xBB
        private const val ID_CUE_TIME = 0xB3
        private const val ID_CUE_TRACK_POSITIONS = 0xB7
        private const val ID_CUE_TRACK = 0xF7
        private const val ID_CUE_CLUSTER_POSITION = 0xF1
    }
}
