package com.rife.androidtv.stream

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer

/**
 * Phase F: taps the source container's Dolby Digital Plus track in parallel with playback and
 * feeds its compressed frames - untouched, never re-encoded - into [MpegTsMuxer.onAudioAccessUnit].
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
 * Only E-AC-3 is muxed: the PMT advertises stream_type 0x87, and advertising a codec the stream
 * does not actually carry would be worse than carrying no audio. When the source has no
 * E-AC-3 track, [start] returns false and the stream runs video-only, which is logged.
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
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    /** >= 0: seek the extractor there. -1: reset the anchor only. MIN_VALUE: nothing pending. */
    @Volatile private var seekRequestMs = Long.MIN_VALUE

    private var startedAtMs = startPositionMs

    /**
     * Opens the extractor, picks the E-AC-3 track and starts the feed thread. Returns false when
     * the container has no E-AC-3 track (video-only is then logged by the caller) - the caller
     * must not treat that as a failed stream start.
     */
    fun start(): Boolean {
        if (running) return true
        val probe = MediaExtractor()
        val mime = try {
            probe.setDataSource(context, uri, null)
            selectEac3Track(probe)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open $uri for audio passthrough: $t")
            null
        } finally {
            probe.release()
        }
        if (mime == null) {
            Log.i(TAG, "no E-AC-3 track in $uri; streaming video-only")
            return false
        }
        running = true
        thread = Thread({ feed(mime) }, "AudioPassthroughFeeder").also { it.start() }
        Log.i(TAG, "audio passthrough started ($mime) from ${startedAtMs}ms")
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

    /** The E-AC-3 mime, or null; leaves [MediaExtractor] positioned on the selected track. */
    private fun selectEac3Track(extractor: MediaExtractor): String? {
        var fallback = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            if (mime == MediaFormat.MIMETYPE_AUDIO_EAC3 || mime == MediaFormat.MIMETYPE_AUDIO_AC3) {
                extractor.selectTrack(i)
                return mime
            }
            if (fallback < 0) fallback = i
        }
        return null
    }

    private fun feed(mime: String) {
        val extractor = MediaExtractor()
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
        try {
            extractor.setDataSource(context, uri, null)
            val track = findTrack(extractor, mime)
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
                muxer.onAudioAccessUnit(sample, outPtsUs)
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
