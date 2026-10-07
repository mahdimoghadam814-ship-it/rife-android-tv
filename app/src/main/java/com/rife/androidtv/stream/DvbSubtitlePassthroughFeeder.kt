package com.rife.androidtv.stream

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer

/** Carries source DVB bitmap subtitle segments on their own MPEG-TS PID without rendering/burning them in. */
class DvbSubtitlePassthroughFeeder(
    private val context: Context,
    private val uri: Uri,
    private val startPositionMs: Long,
    private val muxer: MpegTsMuxer,
) {
    private data class Track(val index: Int, val language: String, val type: Int, val composition: Int, val ancillary: Int)

    companion object {
        private const val TAG = "DvbSubtitleFeeder"
        private const val MIME_DVB_SUBTITLES = "application/dvbsubs"
        private const val MAX_SAMPLE_BYTES = 256 * 1024
        private const val MAX_VIDEO_LEAD_US = 500_000L
    }

    @Volatile private var running = false
    @Volatile private var seekToMs = Long.MIN_VALUE
    private var thread: Thread? = null

    fun start(): Boolean {
        if (running) return true
        val track = runCatching {
            MediaExtractor().useExtractor { extractor ->
                extractor.setDataSource(context, uri, null)
                findTrack(extractor)
            }
        }.onFailure { Log.w(TAG, "cannot probe DVB subtitle track: $it") }.getOrNull()
        if (track == null) {
            Log.i(TAG, "no supported application/dvbsubs track; remote subtitles remain unavailable")
            return false
        }
        muxer.setDvbSubtitleFormat(track.language, track.type, track.composition, track.ancillary)
        running = true
        thread = Thread({ feed(track) }, "DvbSubtitlePassthrough").also { it.start() }
        Log.i(TAG, "DVB subtitle passthrough started (language=${track.language})")
        return true
    }

    fun onDiscontinuity(positionMs: Long) {
        seekToMs = positionMs
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(500)
        thread = null
    }

    private fun findTrack(extractor: MediaExtractor): Track? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME) != MIME_DVB_SUBTITLES) continue
            val csd = format.getByteBuffer("csd-0")?.duplicate()
            val compositionPageId: Int
            val ancillaryPageId: Int
            if (csd != null && csd.remaining() >= 4) {
                compositionPageId = ((csd.get().toInt() and 0xFF) shl 8) or (csd.get().toInt() and 0xFF)
                ancillaryPageId = ((csd.get().toInt() and 0xFF) shl 8) or (csd.get().toInt() and 0xFF)
            } else {
                Log.w(TAG, "DVB subtitle track lacks the two page IDs in csd-0; cannot signal it safely")
                return null
            }
            val language = format.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.length == 3 } ?: "und"
            val type = if (format.containsKey("subtitling-type")) format.getInteger("subtitling-type") else 0x10
            return Track(index, language, type, compositionPageId, ancillaryPageId)
        }
        return null
    }

    private fun feed(track: Track) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(track.index)
            if (startPositionMs > 0) extractor.seekTo(startPositionMs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
            var generation = muxer.timelineGeneration
            var firstRawPtsUs = Long.MIN_VALUE
            var offsetUs = 0L
            while (running) {
                val seek = seekToMs
                if (seek != Long.MIN_VALUE) {
                    seekToMs = Long.MIN_VALUE
                    if (seek >= 0) extractor.seekTo(seek, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    generation = muxer.timelineGeneration
                    firstRawPtsUs = Long.MIN_VALUE
                }
                if (generation != muxer.timelineGeneration) {
                    generation = muxer.timelineGeneration
                    firstRawPtsUs = Long.MIN_VALUE
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    Thread.sleep(4)
                    continue
                }
                val rawPtsUs = extractor.sampleTime
                val anchorUs = muxer.videoAnchorUs
                if (anchorUs == Long.MIN_VALUE) {
                    Thread.sleep(4)
                    continue
                }
                if (firstRawPtsUs == Long.MIN_VALUE) {
                    firstRawPtsUs = rawPtsUs
                    offsetUs = anchorUs - rawPtsUs
                }
                val ptsUs = rawPtsUs + offsetUs
                val lastVideoUs = muxer.lastVideoPtsUs
                if (lastVideoUs == Long.MIN_VALUE || ptsUs - lastVideoUs > MAX_VIDEO_LEAD_US) {
                    Thread.sleep(4)
                    continue
                }
                if (size <= MAX_SAMPLE_BYTES) {
                    val sample = ByteArray(size)
                    buffer.position(0)
                    buffer.limit(size)
                    buffer.get(sample)
                    muxer.onDvbSubtitleAccessUnit(sample, ptsUs)
                }
                extractor.advance()
            }
        } catch (t: Throwable) {
            if (running) Log.e(TAG, "DVB subtitle feeder stopped", t)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private inline fun <T> MediaExtractor.useExtractor(block: (MediaExtractor) -> T): T =
        try { block(this) } finally { release() }
}
