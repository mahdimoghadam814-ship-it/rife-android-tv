package com.rife.androidtv.offline

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.util.GlUtil
import com.rife.androidtv.NativeEngine
import com.rife.androidtv.encode.EncodedStreamSink
import com.rife.androidtv.encode.HdrHevcEncoder
import com.rife.androidtv.rife.OesFrameGrabber
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sink adapter: the encoder's drain thread lands encoded access units straight into the
 * Matroska muxer. The video track descriptor is built from the codec's real output format
 * (csd-0 is the avcC/hvcC bytes Matroska wants as CodecPrivate) the moment the codec reports
 * it, which is always before the first access unit.
 */
private class MkvEncodedSink(
    private val muxer: MatroskaMuxer,
    private val colour: MatroskaMuxer.Colour?,
    private val width: Int,
    private val height: Int,
    private val frameRate: Float,
) : EncodedStreamSink {

    @Volatile
    var error: Throwable? = null
        private set

    override fun onOutputFormat(format: MediaFormat) {
        val csd = format.getByteBuffer("csd-0")?.let { buffer ->
            buffer.rewind()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        val codecId = if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) {
            "V_MPEG4/ISO/AVC"
        } else {
            "V_MPEGH/ISO/HEVC"
        }
        muxer.addTrack(
            MatroskaMuxer.Track(
                number = 1,
                type = MatroskaMuxer.TRACK_TYPE_VIDEO,
                codecId = codecId,
                codecPrivate = csd,
                width = width,
                height = height,
                frameRate = frameRate,
                colour = colour,
            ),
        )
    }

    override fun onAccessUnit(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val payload = ByteArray(info.size)
        data.position(info.offset)
        data.limit(info.offset + info.size)
        data.get(payload)
        val keyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        muxer.addVideoSample(payload, info.presentationTimeUs, keyframe)
    }

    override fun onError(cause: Throwable) {
        error = cause
        Log.e(TAG, "encoder sink error", cause)
    }

    companion object {
        private const val TAG = "OfflineInterp"
    }
}

/**
 * The offline 3x interpolation pipeline. Not realtime: decode, interpolate and encode run at
 * whatever speed the CPU/GPU/codec can sustain, so 1080p and 4K sources get the same math the
 * realtime pipeline can only afford at 480p - the SVPlayer search on the auto-meScale domain
 * (1080p -> ms2, 4K -> ms4) with the dense warp at full native resolution.
 *
 * ```
 * MediaExtractor(video) -> MediaCodec decoder -> Surface(SurfaceTexture)
 *      -> OesFrameGrabber RGBA readback (native resolution, HDR code values kept)
 *      -> NativeEngine.interpolateFrameBuffers(t = 1/3, 2/3)   [same CPU warp as realtime]
 *      -> RgbaSurfacePresenter -> HdrHevcEncoder (surface input, Main10 HDR10 or SDR)
 *      -> MatroskaMuxer  <- audio samples verbatim from a passthrough extractor
 *                        <- subtitle events verbatim (pre-buffered, durations precomputed)
 * ```
 *
 * Output timeline: source frames stay anchors at their original PTS, two synthesized frames
 * fill each gap, and audio/subtitle samples keep their source timestamps - so the movie's
 * duration and every stream's sync are unchanged. Only the video track is re-encoded.
 */
class OfflineInterpolationRunner(
    private val context: Context,
    private val inputUri: android.net.Uri,
    private val output: OutputStream,
    private val sourceDisplayName: String,
    private val onProgress: (fraction: Float, etaMs: Long) -> Unit,
    private val cancelled: () -> Boolean,
) {

    private class Cancelled : Exception("interpolation cancelled")

    private var frameWidth = 0
    private var frameHeight = 0

    fun run() {
        var passthroughExtractor: MediaExtractor? = null
        var decodeExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: HdrHevcEncoder? = null
        var muxer: MatroskaMuxer? = null
        var decoderOutputSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        var eglDisplay: EGLDisplay? = null
        var eglContext: android.opengl.EGLContext? = null
        var eglSurface: EGLSurface? = null
        var handlerThread: HandlerThread? = null
        var grabber: OesFrameGrabber? = null
        var presenter: RgbaSurfacePresenter? = null
        val pfdList = ArrayList<android.os.ParcelFileDescriptor>()
        try {
            // ---- Passthrough inventory: audio + subtitle tracks, fail fast on codecs the
            // container cannot carry, and pre-buffer subtitle events for exact durations.
            val passthrough = openExtractor(pfdList)
            passthroughExtractor = passthrough
            val passthroughFormats = ArrayList<Pair<Int, MediaFormat>>()
            for (i in 0 until passthrough.trackCount) {
                val format = passthrough.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/") || isSubtitleMime(mime)) {
                    passthroughFormats.add(i to format)
                }
            }
            val descriptors = TrackMapper.describePassthrough(
                passthroughFormats, firstTrackNumber = 2,
            )
            descriptors.firstOrNull { it.unsupportedReason != null }?.let {
                throw IllegalStateException(it.unsupportedReason!!)
            }
            val subtitleTracks = descriptors.filter { it.type == MatroskaMuxer.TRACK_TYPE_SUBTITLE }
            val audioTracks = descriptors.filter { it.type == MatroskaMuxer.TRACK_TYPE_AUDIO }

            // ---- Decode inventory: the video track, native size, colour and HDR metadata.
            val videoExtractor = openExtractor(pfdList)
            decodeExtractor = videoExtractor
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            val sourceVideoFormat = videoFormat
                ?: throw IllegalStateException("no video track in $sourceDisplayName")
            videoExtractor.selectTrack(videoTrackIndex)
            val source = TrackMapper.parseSourceVideo(sourceVideoFormat, videoTrackIndex)
            if (source.width <= 0 || source.height <= 0) {
                throw IllegalStateException("source reports no frame size")
            }
            frameWidth = source.width
            frameHeight = source.height
            val outFrameRate = (source.frameRate * OfflinePts.MULTIPLIER).toInt().coerceAtLeast(2)
            val meScale = TrackMapper.autoMeScale(source.height)

            // ---- Muxer + passthrough tracks + pre-buffered subtitle events.
            val title = sourceDisplayName.substringBeforeLast('.')
            val matroska = MatroskaMuxer(output, durationUs = source.durationUs, title = title)
            muxer = matroska
            for (descriptor in descriptors) {
                matroska.addTrack(descriptor.descriptor)
            }
            if (subtitleTracks.isNotEmpty()) {
                val events = scanSubtitleEvents(passthrough, subtitleTracks)
                for (track in subtitleTracks) {
                    matroska.addSubtitleEvents(
                        track.descriptor.number, events[track.extractorIndex] ?: emptyList(),
                    )
                }
            }

            // ---- GL: one context for the decoder's SurfaceTexture, the RGBA readback and the
            // encoder's input surface.
            val display = GlUtil.getDefaultEglDisplay()
            eglDisplay = display
            val context3 = try {
                GlUtil.createEglContext(
                    EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "GLES3 context unavailable; falling back to ES2", t)
                GlUtil.createEglContext(display)
            }
            eglContext = context3
            val placeholder = GlUtil.createFocusedPlaceholderEglSurface(context3, display)
            eglSurface = placeholder
            val textureId = GlUtil.createExternalTexture()
            val texture = SurfaceTexture(textureId)
            surfaceTexture = texture
            texture.setDefaultBufferSize(source.width, source.height)
            val outputSurface = Surface(texture)
            decoderOutputSurface = outputSurface
            val frameLock = Object()
            var frameReady = false
            val thread = HandlerThread("OfflineDecode").apply { start() }
            handlerThread = thread
            texture.setOnFrameAvailableListener(
                {
                    synchronized(frameLock) {
                        frameReady = true
                        frameLock.notifyAll()
                    }
                },
                Handler(thread.looper),
            )
            val oesGrabber = OesFrameGrabber()
            grabber = oesGrabber
            oesGrabber.init(textureId)

            // ---- Native interpolation: same SVPlayer surface as realtime, auto meScale so the
            // search domain matches the smooth 480p processing mode.
            NativeEngine.setInterpolationThreadCount(4)
            NativeEngine.setSvPlayerSettings(
                /* performanceQuality = */ 1.0f,
                /* artifactMaskLevel = */ 1.0f,
                /* blockSize = */ 2,
                /* searchDistance = */ 60,
                /* subpel = */ 2,
                /* overlap = */ 2,
                /* penaltyLambda = */ 30.0f,
                /* blendAlgorithm = */ 2,
                /* sceneAdaptive = */ 1,
                /* meScale = */ meScale,
            )
            NativeEngine.resetInterpolationState()
            Log.i(
                TAG,
                "offline job: ${source.width}x${source.height}@${source.frameRate}fps " +
                    "hdr10=${source.hdr10} meScale=$meScale outFps=$outFrameRate " +
                    "audio=${audioTracks.size} subs=${subtitleTracks.size}",
            )

            // ---- Decoder.
            val decoderCodec = MediaCodec.createDecoderByType(source.mime)
            decoder = decoderCodec
            decoderCodec.configure(sourceVideoFormat, outputSurface, null, 0)
            decoderCodec.start()

            // ---- Encoder + muxer sink.
            val sink = MkvEncodedSink(
                muxer = matroska,
                colour = source.colour,
                width = source.width,
                height = source.height,
                frameRate = outFrameRate.toFloat(),
            )
            val encoderInstance = HdrHevcEncoder(sink)
            encoder = encoderInstance
            val encoderSurface = encoderInstance.open(
                HdrHevcEncoder.Config(
                    width = source.width,
                    height = source.height,
                    frameRate = outFrameRate,
                    bitrateBps = TrackMapper.outputBitrateBps(
                        source.bitrateBps, source.width, source.height, outFrameRate,
                    ),
                    iFrameIntervalSec = 2,
                    maxBFrames = 0,
                    hdr10 = source.hdr10,
                    colorStandard = source.colorStandard,
                    colorTransfer = source.colorTransfer,
                    colorRange = source.colorRange,
                    hdrStaticInfo = source.hdrStaticInfo,
                ),
            ) ?: throw IllegalStateException("no HEVC encoder available on this device")
            val presenterInstance = RgbaSurfacePresenter(
                display = display,
                config = currentConfig(display),
                context = context3,
                width = source.width,
                height = source.height,
                surface = encoderSurface,
            )
            presenter = presenterInstance

            // ---- Audio streaming extractor (kept separate from the subtitle scan).
            val streamingExtractor = if (audioTracks.isNotEmpty()) {
                openExtractor(pfdList).also { ex ->
                    audioTracks.forEach { ex.selectTrack(it.extractorIndex) }
                }
            } else null
            audioExtractor = streamingExtractor
            val audioByIndex = audioTracks.associateBy { it.extractorIndex }
            var audioCarry: ByteBuffer? = null

            // ---- Frame buffers.
            val frameBytes = source.width.toLong() * source.height * 4L
            require(frameBytes <= Int.MAX_VALUE) { "frame too large for offline buffers" }
            val bytes = frameBytes.toInt()
            val prevBuf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            val curBuf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            val midBuf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

            val bufferInfo = MediaCodec.BufferInfo()
            val durationUs = source.durationUs.coerceAtLeast(1L)
            var prevPtsUs = -1L
            var havePrev = false
            var presentedFrames = 0L
            var lastProgressAtMs = 0L
            val startedAtMs = System.currentTimeMillis()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (cancelled()) throw Cancelled()

                // Feed the decoder.
                if (!inputDone) {
                    val inIndex = decoderCodec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = decoderCodec.getInputBuffer(inIndex)!!
                        val sampleSize = videoExtractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoderCodec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoderCodec.queueInputBuffer(
                                inIndex, 0, sampleSize, videoExtractor.sampleTime, 0,
                            )
                            videoExtractor.advance()
                        }
                    }
                }

                // Drain the decoder. Render order matters: the buffer must be released to the
                // SurfaceTexture BEFORE updateTexImage or the GL consumer sees a stale frame.
                val outIndex = decoderCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex >= 0 -> {
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val hasFrame = bufferInfo.size > 0 && !eos
                        if (hasFrame) {
                            val ptsUs = bufferInfo.presentationTimeUs
                            decoderCodec.releaseOutputBuffer(outIndex, true)
                            val deadline = SystemClock.uptimeMillis() + FRAME_WAIT_MS
                            synchronized(frameLock) {
                                while (!frameReady) {
                                    val remaining = deadline - SystemClock.uptimeMillis()
                                    if (remaining <= 0) {
                                        throw IllegalStateException(
                                            "decoder frame never arrived at pts=$ptsUs",
                                        )
                                    }
                                    frameLock.wait(remaining)
                                }
                                frameReady = false
                            }
                            texture.updateTexImage()
                            curBuf.clear()
                            if (!oesGrabber.read(texture, source.width, source.height, curBuf)) {
                                throw IllegalStateException("frame readback failed at pts=$ptsUs")
                            }
                            when {
                                !havePrev -> {
                                    // First frame: emit its anchor immediately.
                                    presenterInstance.present(curBuf, ptsUs)
                                    presentedFrames++
                                    copyCurrentIntoPrev(curBuf, prevBuf)
                                    prevPtsUs = ptsUs
                                    havePrev = true
                                }
                                else -> {
                                    // Synthesized fillers first, then the previous frame's
                                    // anchor keeps the A,M,M,A,M,M... timeline order.
                                    interpolateAndPresent(
                                        presenterInstance, prevBuf, curBuf, midBuf, prevPtsUs, ptsUs, 1,
                                    )
                                    interpolateAndPresent(
                                        presenterInstance, prevBuf, curBuf, midBuf, prevPtsUs, ptsUs, 2,
                                    )
                                    copyCurrentIntoPrev(curBuf, prevBuf)
                                    prevPtsUs = ptsUs
                                }
                            }
                            lastProgressAtMs = reportProgress(
                                ptsUs, durationUs, presentedFrames, startedAtMs, lastProgressAtMs,
                            )
                            if (streamingExtractor != null) {
                                audioCarry = drainAudio(
                                    streamingExtractor, audioByIndex, matroska,
                                    ptsUs + AUDIO_SKEW_US, audioCarry,
                                )
                            }
                        } else {
                            decoderCodec.releaseOutputBuffer(outIndex, false)
                        }
                        if (eos) outputDone = true
                    }
                }
            }

            if (cancelled()) throw Cancelled()

            // Last source frame's anchor, then any remaining audio.
            if (havePrev) {
                presenterInstance.present(prevBuf, prevPtsUs)
                presentedFrames++
            }
            if (streamingExtractor != null) {
                drainAudio(streamingExtractor, audioByIndex, matroska, Long.MAX_VALUE, audioCarry)
            }

            encoderInstance.signalEndOfStream(15_000L)
            encoderInstance.close()
            if (sink.error != null) throw IllegalStateException("encoder failed", sink.error)
            matroska.finish()
            onProgress(1f, 0L)
            Log.i(
                TAG,
                "offline job finished: $presentedFrames output frames " +
                    "(${System.currentTimeMillis() - startedAtMs} ms wall), meScale=$meScale",
            )
        } catch (c: Cancelled) {
            Log.i(TAG, "offline job cancelled")
            throw c
        } finally {
            runCatching { encoder?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { passthroughExtractor?.release() }
            runCatching { decodeExtractor?.release() }
            runCatching { audioExtractor?.release() }
            runCatching { presenter?.release() }
            runCatching { grabber?.release() }
            runCatching { decoderOutputSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching {
                eglSurface?.let { eglDisplay?.let { d -> EGL14.eglDestroySurface(d, it) } }
                eglContext?.let { eglDisplay?.let { d -> EGL14.eglDestroyContext(d, it) } }
            }
            runCatching { handlerThread?.quitSafely() }
            pfdList.forEach { pfd -> runCatching { pfd.close() } }
        }
    }

    /**
     * This compile SDK's MediaExtractor stubs only expose the String data-source overloads, so
     * content URIs go through a ParcelFileDescriptor kept open for the extractor's lifetime.
     */
    private fun openExtractor(pfds: MutableList<android.os.ParcelFileDescriptor>): MediaExtractor {
        val pfd = context.contentResolver.openFileDescriptor(inputUri, "r")
            ?: throw IllegalStateException("cannot open $sourceDisplayName for reading")
        pfds.add(pfd)
        return MediaExtractor().apply { setDataSource(pfd.fileDescriptor) }
    }

    private fun interpolateAndPresent(
        presenter: RgbaSurfacePresenter,
        prevBuf: ByteBuffer,
        curBuf: ByteBuffer,
        midBuf: ByteBuffer,
        prevPtsUs: Long,
        curPtsUs: Long,
        index: Int,
    ) {
        val timestep = index.toFloat() / OfflinePts.MULTIPLIER
        midBuf.clear()
        val ok = NativeEngine.interpolateFrameBuffers(
            prevBuf, curBuf, frameWidth, frameHeight, frameWidth, frameHeight, timestep, midBuf,
        )
        if (!ok) {
            throw IllegalStateException("interpolation failed at t=$timestep pts=$prevPtsUs")
        }
        midBuf.position(0)
        presenter.present(midBuf, OfflinePts.midPtsUs(prevPtsUs, curPtsUs, index))
    }

    private fun reportProgress(
        ptsUs: Long,
        durationUs: Long,
        frames: Long,
        startedAtMs: Long,
        lastProgressAtMs: Long,
    ): Long {
        val now = System.currentTimeMillis()
        if (now - lastProgressAtMs < PROGRESS_INTERVAL_MS) return lastProgressAtMs
        val fraction = (ptsUs.toDouble() / durationUs).coerceIn(0.0, 1.0).toFloat()
        val elapsed = now - startedAtMs
        val eta = if (fraction > 0.02f) {
            (elapsed / fraction.toDouble() * (1.0 - fraction)).toLong()
        } else {
            -1L
        }
        onProgress(fraction, eta)
        return now
    }

    /**
     * Copies every audio sample whose timestamp is below [limitUs] into the muxer with its
     * original PTS. The extractor interleaves selected tracks by time, so one forward scan is
     * enough; [carry] reuses the sample buffer across calls.
     */
    private fun drainAudio(
        extractor: MediaExtractor,
        byIndex: Map<Int, TrackMapper.Passthrough>,
        muxer: MatroskaMuxer,
        limitUs: Long,
        carry: ByteBuffer?,
    ): ByteBuffer? {
        var buffer = carry ?: ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.nativeOrder())
        while (true) {
            val pts = extractor.sampleTime
            if (pts < 0 || pts > limitUs) return buffer
            val track = byIndex[extractor.sampleTrackIndex] ?: return buffer
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return buffer
            if (size > buffer.capacity()) {
                buffer = ByteBuffer.allocateDirect(size * 2).order(ByteOrder.nativeOrder())
                buffer.clear()
                val retry = extractor.readSampleData(buffer, 0)
                if (retry < 0) return buffer
                copyAudioSample(muxer, track, buffer, retry, pts)
            } else {
                copyAudioSample(muxer, track, buffer, size, pts)
            }
            if (!extractor.advance()) return buffer
        }
    }

    private fun copyAudioSample(
        muxer: MatroskaMuxer,
        track: TrackMapper.Passthrough,
        buffer: ByteBuffer,
        size: Int,
        pts: Long,
    ) {
        val payload = ByteArray(size)
        buffer.position(0)
        buffer.get(payload)
        muxer.addAudioSample(track.descriptor.number, payload, pts)
    }

    /** One pass over the subtitle tracks, buffering every event with its exact payload. */
    private fun scanSubtitleEvents(
        extractor: MediaExtractor,
        subtitleTracks: List<TrackMapper.Passthrough>,
    ): Map<Int, List<MatroskaMuxer.SubtitleEvent>> {
        subtitleTracks.forEach { extractor.selectTrack(it.extractorIndex) }
        val events = HashMap<Int, ArrayList<MatroskaMuxer.SubtitleEvent>>()
        var buffer = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.nativeOrder())
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            if (size > buffer.capacity()) {
                buffer = ByteBuffer.allocateDirect(size * 2).order(ByteOrder.nativeOrder())
                buffer.clear()
                val retry = extractor.readSampleData(buffer, 0)
                if (retry < 0) break
                collectSubtitleSample(extractor, buffer, retry, events)
            } else {
                collectSubtitleSample(extractor, buffer, size, events)
            }
            if (!extractor.advance()) break
        }
        subtitleTracks.forEach { extractor.unselectTrack(it.extractorIndex) }
        return events
    }

    private fun collectSubtitleSample(
        extractor: MediaExtractor,
        buffer: ByteBuffer,
        size: Int,
        events: HashMap<Int, ArrayList<MatroskaMuxer.SubtitleEvent>>,
    ) {
        val pts = extractor.sampleTime
        if (pts < 0) return
        val payload = ByteArray(size)
        buffer.position(0)
        buffer.get(payload)
        events.getOrPut(extractor.sampleTrackIndex) { ArrayList() }
            .add(MatroskaMuxer.SubtitleEvent(pts, payload))
    }

    private fun isSubtitleMime(mime: String): Boolean {
        val normalized = mime.lowercase()
        return normalized.startsWith("text/") ||
            normalized.startsWith("subtitle") ||
            normalized.contains("subrip") ||
            normalized.contains("ssa") ||
            normalized.contains("vtt") ||
            normalized.contains("ttml") ||
            normalized.contains("pgs") ||
            normalized.contains("hdmv") ||
            normalized.contains("vobsub") ||
            normalized.contains("subtitle")
    }

    private fun currentConfig(display: EGLDisplay): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x0030,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw IllegalStateException("eglChooseConfig returned no config")
        }
        return configs[0]!!
    }

    private fun copyCurrentIntoPrev(curBuf: ByteBuffer, prevBuf: ByteBuffer) {
        curBuf.position(0)
        curBuf.limit(frameWidth * frameHeight * 4)
        prevBuf.clear()
        prevBuf.put(curBuf)
        prevBuf.flip()
    }

    companion object {
        private const val TAG = "OfflineInterp"

        /** How far audio may lead the just-presented video PTS when it is muxed. */
        private const val AUDIO_SKEW_US = 250_000L

        private const val PROGRESS_INTERVAL_MS = 1_000L

        private const val FRAME_WAIT_MS = 2_000L
    }
}
