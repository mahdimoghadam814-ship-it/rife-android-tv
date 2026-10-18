package com.rife.androidtv.offline

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Translates source [MediaFormat] descriptions into the offline muxer's track descriptors and
 * the encoder's HDR/colour configuration, and ports the realtime pipeline's auto-meScale
 * policy so the offline search runs on the same domain as the smooth 480p processing mode.
 *
 * Everything here is best-effort metadata translation: the encoder's own SEI (from
 * [MediaFormat.KEY_HDR_STATIC_INFO]) is authoritative for HDR signalling, and the Matroska
 * Colour/MasteringDisplay elements are the container-side mirror of the same source values.
 * A failed parse skips the container element rather than failing the job.
 */
object TrackMapper {

    private const val TAG = "OfflineTrackMapper"

    /** What the runner needs to know about the source video track. */
    data class SourceVideo(
        val mime: String,
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val durationUs: Long,
        val bitrateBps: Int,
        val hdr10: Boolean,
        val hlg: Boolean,
        val colorStandard: Int,
        val colorTransfer: Int,
        val colorRange: Int,
        val hdrStaticInfo: ByteBuffer?,
        val colour: MatroskaMuxer.Colour?,
    )

    /** One passthrough track (audio or subtitle) with its muxer descriptor. */
    data class Passthrough(
        val extractorIndex: Int,
        val format: MediaFormat,
        val type: Int,
        val descriptor: MatroskaMuxer.Track,
        /** True when the codec cannot be represented in Matroska - the job must fail fast. */
        val unsupportedReason: String? = null,
    )

    fun parseSourceVideo(format: MediaFormat, trackIndex: Int): SourceVideo {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        val frameRate = when {
            format.containsKey(MediaFormat.KEY_FRAME_RATE) ->
                runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f)
                    .takeIf { it > 0f }
                    ?: runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrDefault(0f)
            else -> 0f
        }.takeIf { it > 0f } ?: estimateFrameRate(durationUs, format)
        val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0)
        } else 0
        val standard = intOr(format, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        val transfer = intOr(format, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        val range = intOr(format, MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        val staticInfo = format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.also { it.rewind() }
        val hdr10 = transfer == MediaFormat.COLOR_TRANSFER_ST2084
        val hlg = transfer == MediaFormat.COLOR_TRANSFER_HLG
        val colour = if (hdr10 || hlg || standard == MediaFormat.COLOR_STANDARD_BT2020) {
            MatroskaMuxer.Colour(
                primaries = if (standard == MediaFormat.COLOR_STANDARD_BT2020) 9 else 1,
                transfer = when {
                    hdr10 -> 16
                    hlg -> 18
                    else -> 1
                },
                matrix = if (standard == MediaFormat.COLOR_STANDARD_BT2020) 9 else 1,
                range = if (range == MediaFormat.COLOR_RANGE_FULL) 1 else 2,
                mastering = staticInfo?.let { parseMasteringDisplay(it) },
                maxClL = staticInfo?.let { parseU16(it, MASTERING_BYTES) } ?: 0,
                maxFall = staticInfo?.let { parseU16(it, MASTERING_BYTES + 2) } ?: 0,
            )
        } else {
            MatroskaMuxer.Colour(
                primaries = 1,
                transfer = 1,
                matrix = 1,
                range = if (range == MediaFormat.COLOR_RANGE_FULL) 1 else 2,
            )
        }
        Log.i(
            TAG,
            "source video #$trackIndex ${width}x$height @${frameRate}fps mime=$mime " +
                "hdr10=$hdr10 hlg=$hlg bitrate=$bitrate durationUs=$durationUs",
        )
        return SourceVideo(
            mime = mime,
            width = width,
            height = height,
            frameRate = frameRate,
            durationUs = durationUs,
            bitrateBps = bitrate,
            hdr10 = hdr10,
            hlg = hlg,
            colorStandard = standard,
            colorTransfer = transfer,
            colorRange = range,
            hdrStaticInfo = staticInfo,
            colour = colour,
        )
    }

    /** Builds passthrough descriptors; tracks with [unsupportedReason] must abort the job. */
    fun describePassthrough(
        extractorFormats: List<Pair<Int, MediaFormat>>,
        firstTrackNumber: Int,
    ): List<Passthrough> {
        var number = firstTrackNumber
        return extractorFormats.map { (trackIndex, format) ->
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val type = when {
                mime.startsWith("audio/") -> MatroskaMuxer.TRACK_TYPE_AUDIO
                mime.startsWith("text/") ||
                    mime.startsWith("subtitle") ||
                    mime.startsWith("application/x-subrip") ||
                    mime.startsWith("application/x-hdmv") ||
                    mime.startsWith("application/ttml") ||
                    mime.contains("subtitle") ||
                    mime.contains("vobsub") ||
                    mime.contains("pgs") -> MatroskaMuxer.TRACK_TYPE_SUBTITLE
                else -> MatroskaMuxer.TRACK_TYPE_AUDIO
            }
            val codecId = codecIdFor(mime, type)
            val unsupported = when {
                codecId != null -> null
                type == MatroskaMuxer.TRACK_TYPE_SUBTITLE ->
                    "subtitle codec '$mime' is not supported by the offline muxer"
                else -> "audio codec '$mime' is not supported by the offline muxer"
            }
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
            } else 0
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
            } else 0
            val language = format.getString(MediaFormat.KEY_LANGUAGE) ?: "und"
            val muxerNumber = number++
            val descriptor = MatroskaMuxer.Track(
                number = muxerNumber,
                type = type,
                codecId = codecId ?: "A_INVALID",
                codecPrivate = codecPrivateFor(format, mime),
                language = language,
                sampleRate = sampleRate,
                channels = channels,
            )
            Log.i(TAG, "passthrough #$trackIndex mime=$mime -> ${codecId ?: "UNSUPPORTED"} type=$type lang=$language")
            Passthrough(trackIndex, format, type, descriptor, unsupported)
        }
    }

    /**
     * The realtime policy: raise meScale (search downscale) until the search domain is at most
     * 900 px tall, keeping the domain at or above 240 px. 1080p lands on 2 (540p domain) and
     * 4K on 4 (540p domain) - the same domain the smooth 480p/720p processing modes search,
     * which is the math the user asked to keep.
     */
    fun autoMeScale(height: Int): Int {
        var ms = 1
        while (ms < 8 && height > 0 && height / ms > 900 && height / (ms * 2) >= 240) {
            ms *= 2
        }
        return ms
    }

    /** Encoder bitrate: source average when known (slightly raised), else a resolution ladder. */
    fun outputBitrateBps(sourceBps: Int, width: Int, height: Int, outFrameRate: Int): Int {
        if (sourceBps > 0) {
            return (sourceBps.toLong() * 115 / 100).coerceIn(8_000_000L, 80_000_000L).toInt()
        }
        val bps = width.toLong() * height * outFrameRate / 10L
        return bps.coerceIn(8_000_000L, 80_000_000L).toInt()
    }

    // ---------------------------------------------------------------- mappings

    private fun codecIdFor(mime: String, type: Int): String? {
        val normalized = mime.lowercase()
        if (type == MatroskaMuxer.TRACK_TYPE_SUBTITLE) {
            return when {
                normalized.contains("subrip") || normalized == "application/x-subrip" -> "S_TEXT/UTF8"
                normalized.contains("ssa") || normalized.contains("ass") -> "S_TEXT/ASS"
                normalized.contains("vtt") -> "S_TEXT/WEBVTT"
                normalized.contains("ttml") -> "S_TEXT/TMML"
                normalized.contains("pgs") || normalized.contains("hdmv") -> "S_HDMV/PGS"
                normalized.contains("vobsub") || normalized.contains("dvdsub") -> "S_VOBSUB"
                else -> null
            }
        }
        return when {
            normalized == MediaFormat.MIMETYPE_VIDEO_AVC -> "V_MPEG4/ISO/AVC"
            normalized == MediaFormat.MIMETYPE_VIDEO_HEVC -> "V_MPEGH/ISO/HEVC"
            normalized == "video/x-vnd.on2.vp8" -> "V_VP8"
            normalized == "video/x-vnd.on2.vp9" -> "V_VP9"
            normalized == "video/av01" -> "V_AV1/AV1"
            normalized == MediaFormat.MIMETYPE_AUDIO_AAC -> "A_AAC"
            normalized == "audio/mpeg" -> "A_MPEG/L3"
            normalized == "audio/ac3" -> "A_AC3"
            normalized == "audio/eac3" -> "A_EAC3"
            normalized == "audio/flac" -> "A_FLAC"
            normalized == "audio/opus" -> "A_OPUS"
            normalized == "audio/vorbis" -> "A_VORBIS"
            normalized == "audio/vnd.dts" -> "A_DTS"
            normalized == "audio/vnd.dts.hd" -> "A_DTS/HD"
            normalized == "audio/truehd" -> "A_TRUEHD"
            normalized == "audio/mp3" -> "A_MPEG/L3"
            else -> null
        }
    }

    /** Codec private data: the source csd buffers, byte-for-byte (avcC/hvcC/ASC/STREAMINFO). */
    private fun codecPrivateFor(format: MediaFormat, mime: String): ByteArray? {
        val parts = ArrayList<ByteArray>(2)
        var index = 0
        while (format.containsKey("csd-$index")) {
            val csd = format.getByteBuffer("csd-$index") ?: break
            csd.rewind()
            val bytes = ByteArray(csd.remaining())
            csd.get(bytes)
            if (bytes.isNotEmpty()) parts.add(bytes)
            index++
            if (index > 4) break
        }
        if (parts.isEmpty()) return null
        // Vorbis needs identification + comment concatenated; everything else uses csd-0 only
        // (AAC AudioSpecificConfig, avcC, hvcC, FLAC STREAMINFO, AC3/EAC3 headers).
        if (mime.equals("audio/vorbis", true) && parts.size >= 2) {
            val joined = ByteArray(parts[0].size + parts[1].size)
            System.arraycopy(parts[0], 0, joined, 0, parts[0].size)
            System.arraycopy(parts[1], 0, joined, parts[0].size, parts[1].size)
            return joined
        }
        return parts[0]
    }

    private fun intOr(format: MediaFormat, key: String, fallback: Int): Int =
        if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrDefault(fallback) else fallback

    private fun estimateFrameRate(durationUs: Long, format: MediaFormat): Float {
        // MediaFormat carries no frame-count key the extractor is required to set; when the
        // track omits KEY_FRAME_RATE the default of 24 fps matches typical movie content and
        // the muxer's FrameRate element is informational only.
        return 24f
    }

    // CTA-861.3 static info layout inside KEY_HDR_STATIC_INFO: one leading byte, then
    // display_primaries_x/y pairs (3 x u16), white point x/y (u16), max/min display
    // luminance (u16), then MaxCLL and MaxFALL (u16 each) - 25 bytes total.
    private const val MASTERING_BYTES = 1 + 12 + 4 + 4

    private fun parseU16(buffer: ByteBuffer, offset: Int): Int {
        if (buffer.capacity() < offset + 2) return 0
        return ((buffer.get(offset).toInt() and 0xFF) shl 8) or
            (buffer.get(offset + 1).toInt() and 0xFF)
    }

    private fun parseMasteringDisplay(buffer: ByteBuffer): MatroskaMuxer.MasteringDisplay? {
        return try {
            // u16 pairs at offset 1, scaled x50_000 for chromaticity and x10_000 for cd/m^2
            // are already the Matroska units when the source stored them that way (Android
            // passes the CTA payload through verbatim, and mkvmerge reads the same layout).
            val redX = parseU16(buffer, 1)
            val redY = parseU16(buffer, 3)
            val greenX = parseU16(buffer, 5)
            val greenY = parseU16(buffer, 7)
            val blueX = parseU16(buffer, 9)
            val blueY = parseU16(buffer, 11)
            val whiteX = parseU16(buffer, 13)
            val whiteY = parseU16(buffer, 15)
            val maxLum = parseU16(buffer, 17) // max_display luminance * 10000? AOSP: cd/m^2 * 10000? no: u16 cd/m2
            val minLum = parseU16(buffer, 19)
            if (redX == 0 && redY == 0 && maxLum == 0) return null
            MatroskaMuxer.MasteringDisplay(
                redX = redX, redY = redY,
                greenX = greenX, greenY = greenY,
                blueX = blueX, blueY = blueY,
                whiteX = whiteX, whiteY = whiteY,
                // MKV wants luminance x10_000; the CTA payload in Android stores max as a
                // plain u16 cd/m^2 and min x10_000. Scale max up, keep min as-is.
                minLuminance = minLum,
                maxLuminance = maxLum * 10_000,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "HDR static info parse failed; container mastering metadata skipped", t)
            null
        }
    }

    /** Profile the encoder should ask for, mirroring the source when it is HEVC. */
    fun preferredHevcProfile(hdr10: Boolean): Int =
        if (hdr10) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
        } else {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        }
}
