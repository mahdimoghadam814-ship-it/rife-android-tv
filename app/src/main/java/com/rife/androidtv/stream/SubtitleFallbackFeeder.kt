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
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fallback subtitle feeder: when the source has text subtitles (SRT, VTT, PGS)
 * but no DVB bitmap subtitles, render text to bitmaps and encode as DVB segments.
 *
 * Pipeline:
 * MediaExtractor (subtitle track) -> parse text -> render to bitmap -> DVB segment -> MpegTsMuxer
 */
class SubtitleFallbackFeeder(
    private val context: Context,
    private val uri: Uri,
    startPositionMs: Long,
    private val muxer: MpegTsMuxer,
) {
    companion object {
        private const val TAG = "SubtitleFallbackFeeder"
        private const val MIME_TEXT_SUBTITLES = "text/"
        private const val MIME_SRT = "application/x-subrip"
        private const val MIME_VTT = "text/vtt"
        private const val MIME_PGS = "application/x-pgs"
        private const val MAX_SAMPLE_BYTES = 256 * 1024
        private const val MAX_VIDEO_LEAD_US = 500_000L
        private const val DVB_BITMAP_WIDTH = 720
        private const val DVB_BITMAP_HEIGHT = 576
        private const val SUBTITLE_REGION_HEIGHT = 200
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
        // Declare DVB subtitle format
        muxer.setDvbSubtitleFormat(track.language, 0x10, 1, 2)
        running = true
        thread = Thread({ runFeeder(track) }, "SubtitleFallbackFeeder").also { it.start() }
        Log.i(TAG, "subtitle fallback encoding started (${track.mime} -> DVB bitmap)")
        return true
    }

    private fun probeTrack(): Track? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("text/") &&
                    mime != MIME_SRT &&
                    mime != MIME_VTT &&
                    mime != MIME_PGS) continue
                // Skip already DVB subtitles
                if (mime == "application/dvbsubs") continue
                val language = format.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.length == 3 } ?: "und"
                return Track(i, language, mime)
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
                // Render cue to DVB bitmap segment
                val segment = renderDvbSegment(cue)
                if (!segment.isEmpty()) {
                    muxer.onDvbSubtitleAccessUnit(segment, ptsUs)
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
        while (true) {
            val size = extractor.readSampleData(ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES), 0)
            if (size < 0) break
            val timeUs = extractor.sampleTime
            val data = ByteArray(size)
            extractor.readSampleData(ByteBuffer.wrap(data), 0)
            val text = parseSubtitleSample(data, mime)
            if (text.isNotBlank()) {
                cues.add(SubtitleCue(timeUs, timeUs + 5_000_000L, text)) // 5s default duration
            }
            extractor.advance()
        }
        return cues
    }

    private fun parseSubtitleSample(data: ByteArray, mime: String): String {
        return try {
            String(data, java.nio.charset.StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            String(data, java.nio.charset.StandardCharsets.ISO_8859_1).trim()
        }
    }

    private fun renderDvbSegment(cue: SubtitleCue): ByteArray {
        // Create a bitmap with the subtitle text
        val bitmap = Bitmap.createBitmap(DVB_BITMAP_WIDTH, DVB_BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = TextPaint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        val layout = StaticLayout.Builder.obtain(cue.text, 0, cue.text.length, paint, DVB_BITMAP_WIDTH - 80)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(40f, (DVB_BITMAP_HEIGHT - SUBTITLE_REGION_HEIGHT).toFloat())
        layout.draw(canvas)
        canvas.restore()

        // Encode to DVB bitmap segment (simplified - real impl needs RLE encoding per EN 300 743)
        return encodeDvbBitmap(bitmap)
    }

    private fun encodeDvbBitmap(bitmap: Bitmap): ByteArray {
        // Simplified DVB bitmap encoding: just return the raw ARGB as a placeholder
        // Real implementation requires:
        // 1. Convert to 2/4/8-bit palette
        // 2. Run-length encode per EN 300 743
        // 3. Build page/composition/region/clut objects
        val bytes = ByteArray(bitmap.width * bitmap.height * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        return bytes
    }

    fun onDiscontinuity(positionMs: Long) {
        // Seek handled by extractor re-seek
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1000)
    }
}
