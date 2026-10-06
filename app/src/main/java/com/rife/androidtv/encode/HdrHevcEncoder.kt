package com.rife.androidtv.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * The hardware HEVC Main10 HDR encoder of Phase C: `MediaCodec` in, encoded H.265 access units
 * out, GPU-resident on the input side.
 *
 * ```
 * GlOutputRenderer -> encoder input Surface (EGL window surface) -> MediaCodec
 *                                                              -> EncodedStreamSink
 * ```
 *
 * The surface-input path is deliberate. The alternative - read the finished frame back, convert
 * it to YUV and hand it over as bytes - costs a readback, a colour conversion and a copy per
 * frame at 4K60, which is exactly the traffic this project cannot afford. Nothing in this class
 * ever touches pixel values: it owns the codec, its input surface and the drain loop, and the
 * rendering side belongs to whoever holds the EGL context.
 *
 * The class deliberately knows nothing about interpolation (Phase rule: keep the interpolation
 * engine independent from the encoder) and nothing about transport (Phase E owns muxing).
 */
class HdrHevcEncoder(private val sink: EncodedStreamSink) {

    /** Everything the codec can be asked for, all of it overridable so Phase J can tune it. */
    data class Config(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        /** 0 selects [autoBitrate] for the resolution; anything else is used verbatim. */
        val bitrateBps: Int = 0,
        /**
         * Seconds between key frames. One second, not two: this stream goes over UDP, where a
         * dropped datagram corrupts everything up to the next key frame, and at 4K a two-second GOP
         * is two seconds of frozen picture after a single loss.
         */
        val iFrameIntervalSec: Int = 1,
        /**
         * B-frames are off by default and that is load bearing, not a performance preference:
         * the MPEG-TS muxer writes PTS only, which is correct exactly when decode order equals
         * presentation order. It is also what a streaming pipeline wants - a reference frame
         * ahead of every P-frame is a frame of latency the network does not need.
         */
        val maxBFrames: Int = 0,
        /** HDR10 signalling: BT.2020 primaries, ST2084 transfer, limited range. */
        val hdr10: Boolean = true,
        val colorStandard: Int = MediaFormat.COLOR_STANDARD_BT2020,
        val colorTransfer: Int = MediaFormat.COLOR_TRANSFER_ST2084,
        val colorRange: Int = MediaFormat.COLOR_RANGE_LIMITED,
        val level: Int = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51,
        /** Codec to open when present; otherwise [pickCodec]'s best candidate is used. */
        val preferredCodec: String = PREFERRED_CODEC,
        /**
         * CTA-861.3 static metadata from the source track, forwarded verbatim. Left alone when
         * null: a container without static metadata must not be given invented mastering-display
         * values just to make the stream look more HDR than it is.
         */
        val hdrStaticInfo: ByteBuffer? = null,
    ) {
        init {
            require(width > 0 && height > 0) { "bad frame size ${width}x$height" }
            require(frameRate > 0) { "bad frame rate $frameRate" }
        }

        fun effectiveBitrateBps(): Int =
            if (bitrateBps > 0) bitrateBps else autoBitrate(width, height, frameRate)
    }

    /** One candidate encoder, as reported by [MediaCodecList] before anything is opened. */
    data class EncoderCapability(
        val name: String,
        val hardware: Boolean,
        val vendor: Boolean,
        val hdrEditing: Boolean,
        val main10Hdr10: Boolean,
        val main10: Boolean,
    )

    companion object {
        private const val TAG = "HdrHevcEncoder"

        /**
         * The codec the Phase 9 experiment actually verified: 3840x2160, 24 fps, Main10, HDR10/PQ,
         * BT.2020, Surface input, `FEATURE_HdrEditing`, decoded back as
         * `yuv420p10le bt2020 st2084 Main 10`.
         */
        const val PREFERRED_CODEC = "c2.qti.hevc.encoder.hdr"

        private const val CODEC_TIMEOUT_US = 10_000L

        /**
         * How long the drain loop tolerates silence after [HdrHevcEncoder.signalEndOfStream].
         * A codec that never raises BUFFER_FLAG_END_OF_STREAM would otherwise pin the thread
         * forever; half a second is several missed 10 ms polls, so a merely slow EOS still wins.
         */
        private const val EOS_QUIET_TIMEOUT_NS = 500_000_000L

        /**
         * ~0.1 bit per pixel, which lands near 50 Mbps for 4K60 and near 5 Mbps for 1080p24 -
         * the bottom of the 40-80 Mbps band the plan asks to start testing in, deliberately below
         * the ~180-200 Mbps practical UDP ceiling so the first measurements are network-stable.
         *
         * The ceiling matters more than the target: a 4K60 stream at the uncapped ~500 Mbps would
         * saturate a Wi-Fi link and turn the sink's drop-newest policy into a drop-everything
         * policy. 80 Mbps is visually transparent for HEVC Main10 and leaves the link headroom.
         */
        private fun autoBitrate(width: Int, height: Int, frameRate: Int): Int {
            val bps = width.toLong() * height * frameRate / 10L
            return bps.coerceIn(1_000_000L, 80_000_000L).toInt()
        }

        /** Every encoder in the system that can take `video/hevc`, in preference order. */
        fun capabilities(): List<EncoderCapability> {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            val out = ArrayList<EncoderCapability>()
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }) {
                    continue
                }
                val caps = try {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                } catch (t: Throwable) {
                    Log.w(TAG, "cannot read HEVC capabilities of ${info.name}: $t")
                    continue
                }
                var main10 = false
                var main10Hdr10 = false
                for (pl in caps.profileLevels) {
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) {
                        main10 = true
                    }
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                        main10Hdr10 = true
                    }
                }
                out += EncoderCapability(
                    name = info.name,
                    hardware = info.isHardwareAccelerated,
                    vendor = info.isVendor,
                    hdrEditing = caps.isFeatureSupported(
                        MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing
                    ),
                    main10Hdr10 = main10Hdr10,
                    main10 = main10 || main10Hdr10,
                )
            }
            // Preferred first, then hardware HDR-capable, then plain Main10 hardware, then the rest.
            return out.sortedWith(
                compareByDescending<EncoderCapability> { it.name == PREFERRED_CODEC }
                    .thenByDescending { it.hardware && it.hdrEditing && it.main10Hdr10 }
                    .thenByDescending { it.hardware && it.main10 }
                    .thenByDescending { it.vendor }
            )
        }

        /** Logs the encoder landscape once per process; Phase C's first evidence on a new device. */
        fun logCapabilities() {
            val caps = capabilities()
            if (caps.isEmpty()) {
                Log.e(TAG, "No hardware HEVC encoder on this device")
                return
            }
            for (c in caps) {
                Log.i(
                    TAG,
                    "HEVC encoder: ${c.name} hw=${c.hardware} vendor=${c.vendor} " +
                        "Main10=${c.main10} Main10HDR10=${c.main10Hdr10} hdr-editing=${c.hdrEditing}"
                )
            }
        }

        /**
         * The codec [Config.preferredCodec] when it exists and looks usable, otherwise the best
         * remaining candidate. Returns null only when the device has no HEVC encoder at all.
         */
        fun pickCodec(config: Config): String? {
            val caps = capabilities()
            if (caps.isEmpty()) return null
            if (config.hdr10) {
                val hdrCandidates = caps.filter { it.main10Hdr10 }
                hdrCandidates.firstOrNull { it.name == config.preferredCodec && it.hdrEditing }?.let { return it.name }
                hdrCandidates.firstOrNull { it.hdrEditing }?.let { return it.name }
                hdrCandidates.firstOrNull { it.name == config.preferredCodec }?.let { return it.name }
                return hdrCandidates.firstOrNull()?.name
            }
            caps.firstOrNull { it.name == config.preferredCodec }?.let { return it.name }
            return caps.firstOrNull()?.name
        }

        private fun buildFormat(config: Config): MediaFormat {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                config.width,
                config.height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, config.effectiveBitrateBps())
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    if (config.hdr10) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                )
                setInteger(MediaFormat.KEY_LEVEL, config.level)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, config.maxBFrames)
                // Realtime, zero-latency: without these the codec is free to buffer frames and add
                // a frame of latency the network does not need.
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (config.hdr10) {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, config.colorStandard)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, config.colorTransfer)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, config.colorRange)
                    config.hdrStaticInfo?.let { info ->
                        // Rewound so the codec reads the whole payload; Media3 hands these over at
                        // an arbitrary position.
                        info.rewind()
                        setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, info)
                    }
                } else {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, config.colorStandard)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, config.colorTransfer)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, config.colorRange)
                }
            }
            return format
        }
    }

    // Written under the monitor by open()/close(), read from the drain thread every iteration.
    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var input: Surface? = null

    private var config: Config? = null
    @Volatile var selectedCodecName: String? = null
        private set

    /** The drain thread, read by [signalEndOfStream] outside the monitor, so it must be volatile. */
    @Volatile
    private var drain: Thread? = null

    /** Set by signalEndOfStream() and read by the drain thread, so it must be volatile. */
    @Volatile
    private var eosRequested = false

    /** Nanotime of the EOS request, so a codec that never flags EOS cannot pin the drain loop. */
    @Volatile
    private var eosRequestedAtNs = 0L

    /** The surface rendering must target; null until [open] has succeeded. */
    val inputSurface: Surface?
        get() = input

    val isRunning: Boolean
        get() = codec != null

    fun requestKeyFrame(): Boolean {
        return try {
            val active = codec ?: return false
            active.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not request an IDR after stream discontinuity", t)
            false
        }
    }

    /**
     * Opens the codec and returns the surface frames must be drawn into, or null on failure.
     * Safe to call from any thread, but the returned surface is an ordinary [Surface] and the
     * EGL side of it still belongs to the rendering thread.
     */
    @Synchronized
    fun open(requested: Config): Surface? {
        if (codec != null) {
            Log.w(TAG, "open(): already open as ${config}, ignoring")
            return input
        }
        val name = pickCodec(requested)
        if (name == null) {
            Log.e(TAG, "open(): no HEVC encoder available")
            return null
        }
        val format = buildFormat(requested)
        val hdrEditing = capabilities().firstOrNull { it.name == name }?.hdrEditing == true
        if (requested.hdr10 && hdrEditing) format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true)
        Log.i(
            TAG,
            "opening $name for ${requested.width}x${requested.height}@${requested.frameRate} " +
                "bitrate=${requested.effectiveBitrateBps()} hdr10=${requested.hdr10} " +
                "profile=${format.getInteger(MediaFormat.KEY_PROFILE)} level=${requested.level} " +
                "colorStandard=${requested.colorStandard} transfer=${requested.colorTransfer} " +
                "range=${requested.colorRange} staticHdr=${requested.hdrStaticInfo != null} hdrEditing=$hdrEditing"
        )
        val created = try {
            MediaCodec.createByCodecName(name)
        } catch (t: Throwable) {
            Log.e(TAG, "createByCodecName($name) failed", t)
            return null
        }
        try {
            // Surface input: the surface comes from createInputSurface(), so configure() takes
            // null - the surface argument of configure() is the *decoder's* output target.
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = created.createInputSurface()
            created.start()
            codec = created
            input = surface
            config = requested
            selectedCodecName = name
            startDrain(name)
            return surface
        } catch (t: Throwable) {
            Log.e(TAG, "failed to configure $name", t)
            try {
                created.release()
            } catch (_: Throwable) {
            }
            return null
        }
    }

    private fun startDrain(name: String) {
        eosRequested = false
        eosRequestedAtNs = 0L
        drain = Thread({
            Log.i(TAG, "drain started for $name")
            loop()
            Log.i(TAG, "drain finished for $name")
        }, "HdrEncoderDrain").apply {
            isDaemon = true
            start()
        }
    }

    private fun MediaFormat.intOrUnknown(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(-1) else -1

    private fun logOutputFormat(format: MediaFormat) {
        val profile = format.intOrUnknown(MediaFormat.KEY_PROFILE)
        val transfer = format.intOrUnknown(MediaFormat.KEY_COLOR_TRANSFER)
        val standard = format.intOrUnknown(MediaFormat.KEY_COLOR_STANDARD)
        val range = format.intOrUnknown(MediaFormat.KEY_COLOR_RANGE)
        val level = format.intOrUnknown(MediaFormat.KEY_LEVEL)
        val hdr10Confirmed = profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 &&
            standard == MediaFormat.COLOR_STANDARD_BT2020 && transfer == MediaFormat.COLOR_TRANSFER_ST2084
        Log.i(TAG, "[HDR] output codec=$selectedCodecName profile=$profile level=$level colorStandard=$standard colorTransfer=$transfer colorRange=$range hdr10Confirmed=$hdr10Confirmed staticHdr=${format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)}")
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        var sawFormat = false
        while (true) {
            val c = codec ?: return
            val index = try {
                c.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            } catch (t: Throwable) {
                Log.e(TAG, "dequeueOutputBuffer failed", t)
                sink.onError(t)
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (eosRequested) {
                        val waitedNs = System.nanoTime() - eosRequestedAtNs
                        if (waitedNs > EOS_QUIET_TIMEOUT_NS) {
                            Log.w(
                                TAG,
                                "no EOS ${waitedNs / 1_000_000L} ms after " +
                                    "signalEndOfInputStream, stopping drain"
                            )
                            return
                        }
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    sawFormat = true
                    Log.i(TAG, "output format: $f")
                    logOutputFormat(f)
                    sink.onOutputFormat(f)
                }
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                index >= 0 -> {
                    val buf = try {
                        c.getOutputBuffer(index)
                    } catch (t: Throwable) {
                        Log.e(TAG, "getOutputBuffer($index) failed", t)
                        sink.onError(t)
                        return
                    }
                    if (buf == null) {
                        try {
                            c.releaseOutputBuffer(index, false)
                        } catch (_: Throwable) {
                        }
                        continue
                    }
                    if (!sawFormat && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        // A codec that never announces a format change would leave the sink
                        // without csd-0/csd-1, which Phase D's file and Phase E's muxer both need.
                        val f = c.outputFormat
                        sawFormat = true
                        Log.i(TAG, "output format (late): $f")
                        logOutputFormat(f)
                        sink.onOutputFormat(f)
                    }
                    if (info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        try {
                            sink.onAccessUnit(buf, info)
                        } catch (t: Throwable) {
                            Log.e(TAG, "sink rejected an access unit", t)
                        }
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    try {
                        c.releaseOutputBuffer(index, false)
                    } catch (t: Throwable) {
                        Log.e(TAG, "releaseOutputBuffer($index) failed", t)
                        sink.onError(t)
                        return
                    }
                    if (eos) {
                        Log.i(TAG, "end of stream reached")
                        return
                    }
                }
            }
        }
    }

    /**
     * Asks the codec to finish. Frames still in flight are drained before the loop exits; returns
     * once the drain thread has stopped or [timeoutMs] has elapsed.
     */
    fun signalEndOfStream(timeoutMs: Long = 5_000L): Boolean {
        val c = codec ?: return true
        if (!eosRequested) {
            eosRequestedAtNs = System.nanoTime()
            eosRequested = true
            try {
                c.signalEndOfInputStream()
                Log.i(TAG, "signalEndOfInputStream()")
            } catch (t: Throwable) {
                Log.e(TAG, "signalEndOfInputStream failed", t)
            }
        }
        // Joined outside the monitor: the drain thread can be blocked in a sink callback, and a
        // join that holds the lock would stall every other caller behind a sink that never returns.
        val thread = drain ?: return true
        thread.join(timeoutMs)
        return !thread.isAlive
    }

    @Synchronized
    fun close() {
        signalEndOfStream(2_000L)
        drain?.join(2_000L)
        drain = null
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop() failed: $t")
        }
        try {
            c.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release() failed: $t")
        }
        input?.release()
        input = null
        selectedCodecName = null
        config = null
        Log.i(TAG, "encoder closed")
    }
}
