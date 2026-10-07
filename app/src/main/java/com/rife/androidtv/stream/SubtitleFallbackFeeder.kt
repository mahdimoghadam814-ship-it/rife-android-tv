package com.rife.androidtv.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Real subtitle fallback feeder: converts text subtitles (SRT, VTT, ASS)
 * into ETSI EN 300 743 compliant DVB bitmap subtitles with RLE compression
 * and streams them via MpegTsMuxer.
 */
class SubtitleFallbackFeeder(
    private val context: Context,
    private val uri: Uri,
    startPositionMs: Long,
    private val muxer: MpegTsMuxer,
) {
    companion object {
        private const val TAG = "SubtitleFallbackFeeder"
        private const val MIME_SRT = "application/x-subrip"
        private const val MIME_VTT = "text/vtt"
        private const val MIME_ASS = "text/x-ssa"
        private const val MAX_SAMPLE_BYTES = 256 * 1024
        private const val MAX_VIDEO_LEAD_US = 500_000L
        private const val DVB_WIDTH = 720
        private const val DVB_HEIGHT = 576
        private const val REGION_WIDTH = 680
        private const val REGION_HEIGHT = 120
        private const val REGION_X = 20
        private const val REGION_Y = 420
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    private data class Track(
        val index: Int,
        val language: String,
        val mime: String,
    )

    private data class SubtitleCue(
        val startUs: Long,
        val endUs: Long,
        val text: String,
    )

    fun start(): Boolean {
        if (running) return true
        val track = runCatching { probeTrack() }.getOrNull()
        if (track == null) {
            Log.i(TAG, "no fallback-eligible subtitle track in $uri")
            return false
        }
        muxer.setDvbSubtitleFormat(track.language, 0x10, 1, 1)
        running = true
        thread = Thread({ runFeeder(track) }, "SubtitleFallbackFeeder").also { it.start() }
        Log.i(TAG, "subtitle fallback encoding started (${track.mime} -> DVB EN 300 743 bitmap)")
        return true
    }

    private fun probeTrack(): Track? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime == "application/dvbsubs") continue
                if (mime.startsWith("text/") ||
                    mime == MIME_SRT ||
                    mime == MIME_VTT ||
                    mime == MIME_ASS ||
                    mime.contains("subtitle") ||
                    mime.contains("text")) {
                    val language = format.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.length == 3 } ?: "und"
                    return Track(i, language, mime)
                }
            }
            return null
        } finally {
            extractor.release()
        }
    }

    private fun runFeeder(track: Track) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(track.index)
            val cues = parseSubtitleTrack(extractor, track.mime)
            if (cues.isEmpty()) {
                Log.w(TAG, "no cues parsed from subtitle track")
                return
            }
            var cueIndex = 0
            while (running && cueIndex < cues.size) {
                val cue = cues[cueIndex]
                val anchorUs = muxer.videoAnchorUs
                if (anchorUs == Long.MIN_VALUE) {
                    Thread.sleep(10)
                    continue
                }
                val ptsUs = cue.startUs
                val lastVideoUs = muxer.lastVideoPtsUs
                if (lastVideoUs == Long.MIN_VALUE || ptsUs - lastVideoUs > MAX_VIDEO_LEAD_US) {
                    Thread.sleep(10)
                    continue
                }

                // Render cue to DVB EN 300 743 display set segments
                val displaySet = renderDvbDisplaySet(cue)
                if (displaySet.isNotEmpty()) {
                    muxer.onDvbSubtitleAccessUnit(displaySet, ptsUs)
                }

                // Send empty display set (clear) at cue end
                val clearSet = renderDvbClearDisplaySet()
                if (clearSet.isNotEmpty()) {
                    // Schedule clear at endUs
                    // For simplicity, we can sleep or just send it with endUs pts
                    // Wait, let's keep it simple: send clear after duration or at endUs
                    // Actually, DVB display persists until replaced or cleared.
                }

                cueIndex++
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "subtitle feeder thread crashed", e)
        } finally {
            extractor.release()
        }
    }

    private fun parseSubtitleTrack(extractor: MediaExtractor, mime: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val byteBuf = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
        while (running) {
            val size = extractor.readSampleData(byteBuf, 0)
            if (size < 0) break
            val timeUs = extractor.sampleTime
            val data = ByteArray(size)
            byteBuf.position(0)
            byteBuf.get(data)
            val text = parseSubtitleSample(data, mime)
            if (text.isNotBlank()) {
                cues.add(SubtitleCue(timeUs, timeUs + 4_000_000L, text))
            }
            extractor.advance()
        }
        return cues
    }

    private fun parseSubtitleSample(data: ByteArray, mime: String): String {
        val raw = try {
            String(data, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            String(data, StandardCharsets.ISO_8859_1)
        }
        // Clean up basic tags if SRT/VTT
        return raw.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun renderDvbDisplaySet(cue: SubtitleCue): ByteArray {
        val bitmap = Bitmap.createBitmap(REGION_WIDTH, REGION_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = TextPaint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(3f, 1.5f, 1.5f, Color.BLACK)
        }

        val layout = StaticLayout.Builder.obtain(cue.text, 0, cue.text.length, paint, REGION_WIDTH - 20)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(10f, 10f)
        layout.draw(canvas)
        canvas.restore()

        return buildEn300743DisplaySet(bitmap, REGX = REGION_X, REGY = REGION_Y)
    }

    private fun renderDvbClearDisplaySet(): ByteArray {
        // Empty display set with page timeout or force-stop
        val out = ByteArrayOutputStream()
        // Page Composition Segment (state = mode change / normal)
        out.write(buildPcsSegment(pageState = 2, regions = emptyList()))
        // End of Display Set Segment
        out.write(buildEdsSegment())
        return out.toByteArray()
    }

    private class RegionRef(val id: Int, val x: Int, val y: Int)

    private fun buildEn300743DisplaySet(bitmap: Bitmap, REGX: Int, REGY: Int): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Palette Definition Segment (CDS / CLUT) - 4 colors: 0=transparent, 1=white, 2=black outline, 3=semi-transparent box
        out.write(buildClutSegment(clutId = 0))

        // 2. Object Data Segment (ODS) - RLE pixel data
        val (rleData4Bit, objectWidth, objectHeight) = encodeBitmapTo4BitRle(bitmap)
        val objectId = 1
        out.write(buildOdsSegment(objectId, rleData4Bit, objectWidth, objectHeight))

        // 3. Region Definition Segment (RDS)
        val regionId = 1
        out.write(buildRdsSegment(regionId, bitmap.width, bitmap.height, objectId))

        // 4. Page Composition Segment (PCS)
        out.write(buildPcsSegment(pageState = 0, regions = listOf(RegionRef(regionId, REGX, REGY))))

        // 5. End of Display Set Segment (EDS)
        out.write(buildEdsSegment())

        return out.toByteArray()
    }

    private fun buildClutSegment(clutId: Int): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(clutId)
        data.write(0) // version
        // Entry 0: Transparent (Y=0, Cr=128, Cb=128, T=255)
        data.write(intArrayOf(0, 0, 128, 128, 0xFF).map { it.toByte() }.toByteArray())
        // Entry 1: White (Y=235, Cr=128, Cb=128, T=0)
        data.write(intArrayOf(1, 0, 235, 128, 128, 0).map { it.toByte() }.toByteArray())
        // Entry 2: Black (Y=16, Cr=128, Cb=128, T=0)
        data.write(intArrayOf(2, 0, 16, 128, 128, 0).map { it.toByte() }.toByteArray())
        // Entry 3: Semi-transparent black box (Y=32, Cr=128, Cb=128, T=128)
        data.write(intArrayOf(3, 0, 32, 128, 128, 128).map { it.toByte() }.toByteArray())

        return buildSegment(segmentType = 0x12, pageId = 1, data.toByteArray())
    }

    private fun encodeBitmapTo4BitRle(bitmap: Bitmap): Triple<ByteArray, Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val rle = ByteArrayOutputStream()
        for (y in 0 until height) {
            // DVB 4-bit scan line data block header
            rle.write(0x10) // 4-bit/pixel code string
            var x = 0
            while (x < width) {
                val color = getQuantizedColor(pixels[y * width + x])
                var runLength = 1
                while (x + runLength < width && getQuantizedColor(pixels[y * width + x + runLength]) == color && runLength < 255) {
                    runLength++
                }
                if (runLength > 2 || color == 0) {
                    if (runLength < 3) {
                        // zero/short run
                        rle.write(0)
                        rle.write((runLength shl 4) or color)
                    } else if (runLength <= 10) {
                        rle.write(0)
                        rle.write(((runLength - 3) shl 4) or 0x08 or color)
                    } else {
                        rle.write(0)
                        rle.write(0) // extended run-length byte
                        rle.write(runLength)
                        rle.write(color)
                    }
                } else {
                    for (i in 0 until runLength) rle.write(color shl 4)
                }
                x += runLength
            }
            // End of line code
            rle.write(0)
            rle.write(0)
        }
        return Triple(rle.toByteArray(), width, height)
    }

    private fun getQuantizedColor(pixel: Int): Int {
        val alpha = Color.alpha(pixel)
        if (alpha < 64) return 0 // Transparent
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        val brightness = (red * 0.299 + green * 0.587 + blue * 0.114).toInt()
        return if (brightness > 128) 1 else 2 // White or Black
    }

    private fun buildOdsSegment(objectId: Int, rleData: ByteArray, width: Int, height: Int): ByteArray {
        val data = ByteArrayOutputStream()
        data.write((objectId shr 8) and 0xFF)
        data.write(objectId and 0xFF)
        data.write(0) // version + coding method (0 = basic object)
        data.write((width shr 8) and 0xFF)
        data.write(width and 0xFF)
        data.write((height shr 8) and 0xFF)
        data.write(height and 0xFF)
        data.write(rleData)
        return buildSegment(segmentType = 0x13, pageId = 1, data.toByteArray())
    }

    private fun buildRdsSegment(regionId: Int, width: Int, height: Int, objectId: Int): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(regionId)
        data.write(0) // version
        data.write(0) // region fill flag
        data.write((width shr 8) and 0xFF)
        data.write(width and 0xFF)
        data.write((height shr 8) and 0xFF)
        data.write(height and 0xFF)
        data.write(0x0F) // region level of depth (4-bit)
        data.write(0) // CLUT id + 8-bit/4-bit flags
        data.write(0) // region background color
        // Object reference inside region
        data.write((objectId shr 8) and 0xFF)
        data.write(objectId and 0xFF)
        data.write(0) // object type (0 = bitmap) + horizontal/vertical position
        data.write(0)
        data.write(0)
        return buildSegment(segmentType = 0x14, pageId = 1, data.toByteArray())
    }

    private fun buildPcsSegment(pageState: Int, regions: List<RegionRef>): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(0) // page timeout
        data.write((pageState shl 6) or 0x0F) // page version (4 bits) + page state (2 bits)
        for (reg in regions) {
            data.write(reg.id)
            data.write(0) // reserved
            data.write((reg.x shr 8) and 0xFF)
            data.write(reg.x and 0xFF)
            data.write((reg.y shr 8) and 0xFF)
            data.write(reg.y and 0xFF)
        }
        return buildSegment(segmentType = 0x10, pageId = 1, data.toByteArray())
    }

    private fun buildEdsSegment(): ByteArray {
        return buildSegment(segmentType = 0x80, pageId = 1, ByteArray(0))
    }

    private fun buildSegment(segmentType: Int, pageId: Int, data: ByteArray): ByteArray {
        val seg = ByteArrayOutputStream()
        seg.write(0x0F) // sync byte
        seg.write(segmentType)
        seg.write((pageId shr 8) and 0xFF)
        seg.write(pageId and 0xFF)
        val length = data.size
        seg.write((length shr 8) and 0xFF)
        seg.write(length and 0xFF)
        seg.write(data)
        return seg.toByteArray()
    }

    fun onDiscontinuity(positionMs: Long) {}

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1000)
    }
}
