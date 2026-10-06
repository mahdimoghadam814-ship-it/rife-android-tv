package com.rife.androidtv.rife

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.util.UnstableApi
import com.rife.androidtv.NativeEngine
import com.rife.androidtv.RifeDiagnosticResult
import com.rife.androidtv.DeviceProfile
import com.rife.androidtv.VulkanCapabilities
import com.rife.androidtv.encode.EncodedStreamSink
import com.rife.androidtv.encode.HdrHevcEncoder
import com.rife.androidtv.stream.FilePacketSink
import com.rife.androidtv.stream.MpegTsMuxer
import com.rife.androidtv.stream.TsPacketSink
import com.rife.androidtv.stream.UdpTsPacketSink
import dev.anilbeesetti.nextplayer.core.model.SvPlayerSettings
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeController
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeResolution as FeatureRifeResolution
import dev.anilbeesetti.nextplayer.feature.player.rife.InterpolationAlgorithm as FeatureInterpolationAlgorithm
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeStats
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide owner of the RIFE / FastDVDnet processing stage.
 *
 * The controller is a process singleton (Koin `@Single`): the [VideoFrameProcessor] it owns keeps
 * one worker thread, one EGL context and one set of pooled frame buffers for the whole app, and
 * both the player screen (surface routing, engine status overlay) and the Video Processing
 * settings entry drive it through this single instance.
 *
 * The native RIFE engine is initialised lazily on the first RIFE enable, off the main thread,
 * exactly like the previous standalone player did: model initialisation loads the RIFE network
 * from assets and can take seconds.
 *
 * FastDVDnet (scaffold) does NOT initialize the RIFE engine. The two stages have independent
 * lifecycles.
 */
@UnstableApi
class RifeEngineController(
    private val context: Context,
) : RifeController {
    companion object {
        private const val TAG = "RifeEngineController"
        private const val TAG_DEVICE = "RIFE-DEVICE"
        private const val TAG_LIFECYCLE = "RIFE-LIFECYCLE"
        private const val TAG_ERROR = "RIFE-ERROR"
    }

    /**
     * The frame processor. Created eagerly (it only starts its worker thread and creates the input
     * surface; no frame is read back while both stages are off).
     */
    val processor = VideoFrameProcessor(
        onStatisticsUpdated = { stats ->
            _stats.value = RifeStats(
                inputFps = stats.inputFps,
                outputFps = stats.outputFps,
                outputFrameRate = stats.outputFrameRate,
                processingTimeMs = stats.processingTimeMs,
                droppedFrames = stats.droppedFrames,
                currentResolution = stats.currentResolution,
            )
        },
        onError = { message -> _error.value = message },
        onInputSurfaceCreated = { surface -> _inputSurface.value = surface },
        onInputSurfaceFailed = {
            Log.e(TAG, "Input surface unavailable; processing stages stay off")
            _inputSurface.value = null
        },
    )

    private val _processingEnabled = MutableStateFlow(false)
    override val processingEnabled: StateFlow<Boolean> = _processingEnabled.asStateFlow()

    private val _stats = MutableStateFlow(
        RifeStats(
            inputFps = 0f,
            outputFps = 0f,
            outputFrameRate = 0f,
            processingTimeMs = 0L,
            droppedFrames = 0L,
            currentResolution = "Original",
        ),
    )
    override val stats: StateFlow<RifeStats> = _stats.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private val _inputSurface = MutableStateFlow<android.view.Surface?>(null)
    override val inputSurface: StateFlow<android.view.Surface?> = _inputSurface.asStateFlow()

    @Volatile
    private var engineReady = false

    @Volatile
    private var engineInitStarted = false

    private var initThread: HandlerThread? = null

    @Volatile
    private var interpolationAlgorithm: FeatureInterpolationAlgorithm =
        FeatureInterpolationAlgorithm.MEMC

    /**
     * Starts the processor's worker thread. Called once when the player screen is created.
     */
    override fun start() {
        processor.start()
    }

    /**
     * Stops the processor and releases every resource it owns. Called when the player screen is
     * destroyed.
     */
    override fun stop() {
        stopEncoding()
        processor.stop()
        _inputSurface.value = null
        _processingEnabled.value = false
    }

    override fun getInputFrameSize(): Pair<Int, Int> {
        return processor.getInputWidth() to processor.getInputHeight()
    }

    /**
     * Enables or disables RIFE interpolation. The enable path resets the pipeline and re-creates
     * the input surface; the new surface is published through [inputSurface] so the player screen
     * can attach it to the player.
     */
    override fun setRifeEnabled(enabled: Boolean) {
        if (enabled && interpolationAlgorithm == FeatureInterpolationAlgorithm.RIFE) {
            // MEMC runs entirely in the native layer and never touches the RIFE model, so
            // loading it (seconds + ~365 MB RSS) would be pure waste in that mode.
            ensureEngineInitialized()
        }
        processor.setRifeEnabled(enabled)
        _processingEnabled.value = processor.isProcessingEnabled
    }

    /**
     * Selects the interpolation backend. The value reaches the native dispatcher immediately, so
     * the next frame pair is already interpolated by the chosen algorithm.
     */
    override fun setInterpolationAlgorithm(algorithm: FeatureInterpolationAlgorithm) {
        interpolationAlgorithm = algorithm
        // The Kotlin ordinals are the native InterpolationAlgorithm values: RIFE 0, MEMC 1,
        // SVPLAYER 2, so the engine can tell the two block-matching backends apart.
        NativeEngine.setInterpolationAlgorithm(algorithm.ordinal)
        if (algorithm != FeatureInterpolationAlgorithm.RIFE) {
            // Measured optimum: the ME/MC loops are memory-bound, so going wider than 4 only
            // adds contention (8 threads was ~2x slower than 4 on an 8-core big.LITTLE device).
            NativeEngine.setMemcThreadCount(4)
        }
        Log.i(TAG_LIFECYCLE, "Interpolation algorithm: $algorithm")
    }

    /**
     * Sets the interpolation ratio: how many output frames are synthesised per source frame. The
     * value is read by the processor on every cycle, so the next pair already emits at the new
     * cadence.
     */
    override fun setMemcLevel(multiplier: Float) {
        processor.setMemcLevel(multiplier)
        Log.i(TAG_LIFECYCLE, "MEMC level: ${multiplier}x")
    }

    /**
     * Sets the denoiser strength. It reaches the renderer as a shader uniform, so it applies to
     * the next frame rather than to the next pipeline reset.
     */
    override fun setDenoiseLevel(strength: Float) {
        processor.setDenoiseLevel(strength)
        Log.i(TAG_LIFECYCLE, "Denoise level: $strength")
    }

    override fun setSvPlayerSettings(settings: SvPlayerSettings) {
        NativeEngine.setSvPlayerSettings(
            settings.performanceQuality,
            settings.artifactMaskLevel,
            settings.blockSize.ordinal,
            settings.searchDistance,
            settings.subpel,
            settings.overlap,
            settings.penaltyLambda,
            settings.blendAlgorithm.ordinal,
            if (settings.sceneAdaptive) 1 else 0,
            settings.meScale,
        )
        Log.i(
            TAG_LIFECYCLE,
            "SVPlayer settings: pq=${settings.performanceQuality} " +
                "mask=${settings.artifactMaskLevel} subpel=${settings.subpel} " +
                "overlap=${settings.overlap} lambda=${settings.penaltyLambda}"
        )
    }

    /**
     * Enables or disables the FastDVDnet pre-processing stage (scaffold: frames pass through).
     * FastDVDnet does NOT initialize the RIFE engine - it runs independently.
     */
    override fun setFastDvdNetEnabled(enabled: Boolean) {
        // FastDVDnet scaffold does NOT require RIFE engine initialization.
        // It only maintains a temporal history buffer and passes frames through unchanged.
        processor.setFastDvdNetEnabled(enabled)
        _processingEnabled.value = processor.isProcessingEnabled
    }

    /**
     * Sets the processing resolution. The value is read by the processor on every captured frame, so
     * the next frame pair is already read back at the new size.
     */
    override fun setResolution(resolution: FeatureRifeResolution) {
        processor.resolution = RifeResolution.valueOf(resolution.name)
    }

    /**
     * Records the decoded frame size reported by `Player.Listener.onVideoSizeChanged`.
     */
    override fun setInputFrameSize(width: Int, height: Int) {
        processor.setInputFrameSize(width, height)
    }

    /**
     * Publishes the output surface the processed frames are rendered to. Passing `null` releases
     * it immediately.
     */
    override fun setOutputSurfaceInfo(outputSurfaceInfo: SurfaceInfo?) {
        processor.setOutputSurfaceInfo(outputSurfaceInfo)
    }

    override fun setOutputDataSpace(dataSpace: Int) {
        processor.setOutputDataSpace(dataSpace)
    }

    override fun onInputSurfaceAttached() {
        processor.onInputSurfaceAttached()
    }

    override fun onInputSurfaceDetached() {
        processor.onInputSurfaceDetached()
    }

    /**
     * Drops every buffered frame: seek, media transition, stream change.
     */
    override fun resetForDiscontinuity(reason: String) {
        processor.resetForNewStream(reason)
        // The block-matching pyramid holds state across frames; a seek/stream change invalidates it.
        NativeEngine.resetMemcState()
    }

    /**
     * The last error reported by the pipeline, if any. The player screen surfaces it to the user.
     */
    override fun consumeError(): String? {
        val current = _error.value
        _error.value = null
        return current
    }

    /**
     * The last native engine status, for the diagnostics dialog.
     */
    fun engineStatus(): RifeDiagnosticResult = NativeEngine.getRifeStatus()

    // --------------------------------------------------------------------------------------
    // Phase E/F: UDP streaming of processed frames to a TV box
    // --------------------------------------------------------------------------------------

    /**
     * Starts UDP streaming of the processed frame pipeline to [host]:[port] as MPEG-TS over UDP.
     * Creates [UdpTsPacketSink] and starts the hardware HEVC encoder with the current video
     * size. The encoder feeds processed frames through [MpegTsMuxer] into the UDP sink.
     * Subtitles are not included in the TS output (they are ExoPlayer overlay-only); the TV box
     * should render subtitles from the original source independently.
     */
    override fun startUdpStream(host: String, port: Int): Boolean {
        val sink = UdpTsPacketSink(host, port)
        try {
            val (width, height) = getInputFrameSize()
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Cannot start UDP stream: input frame size not yet known")
                return false
            }
            val frameRate = 60 // Default; encoder will adapt to actual frame rate
            val config = HdrHevcEncoder.Config(
                width = width,
                height = height,
                frameRate = frameRate,
            )
            if (!startEncoding(config, sink)) {
                sink.close()
                Log.e(TAG, "Failed to start encoding for UDP stream")
                return false
            }
            _udpSink = sink
            _udpRunning = true
            _udpEnabled.value = true
            Log.i(TAG, "UDP streaming started to $host:$port at ${width}x$height@$frameRate")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP streaming", e)
            sink.close()
            return false
        }
    }

    /** Stops UDP streaming and releases the socket and sender thread. */
    override fun stopUdpStream() {
        if (!_udpRunning) return
        _udpRunning = false
        val sink = _udpSink
        _udpSink = null
        _udpEnabled.value = false
        stopEncoding() // Stops the encoder and closes the muxer/sink
        Log.i(TAG, "UDP streaming stopped")
    }

    // --------------------------------------------------------------------------------------
    // Phase C: the hardware Surface-based HEVC Main10 HDR encoder
    // --------------------------------------------------------------------------------------

    private var encoder: HdrHevcEncoder? = null
    private var encodeConfig: HdrHevcEncoder.Config? = null

    /**
     * The transport sink the current encode is writing to, held so [stopEncoding] can close it.
     * Without this the UDP socket, its sender thread and its buffer slots leak on every stop.
     */
    private var packetSink: TsPacketSink? = null
    private val encodedUnits = AtomicLong()
    private val encodedBytes = AtomicLong()
    private var capabilitiesLogged = false

    /**
     * Opens the hardware encoder and redirects processed frames into its input Surface.
     *
     * Off unless called: normal playback never encodes, and [stopEncoding] puts the renderer
     * back on the player's surface. Returns false when the device has no usable HEVC encoder, in
     * which case the preview path is left exactly as it was.
     *
     * [packetSink] is Phase E's hook: hand it something and the encoder's access units go through
     * [MpegTsMuxer] on their way out, so one call is the whole "encoder -> transport stream"
     * connection. Phase F passes a UDP socket; without a sink the output is only counted, which
     * is all Phase C needed.
     */
    fun startEncoding(config: HdrHevcEncoder.Config, packetSink: TsPacketSink? = null): Boolean {
        if (encoder != null) {
            Log.w(TAG, "startEncoding(): already encoding")
            return false
        }
        if (!capabilitiesLogged) {
            capabilitiesLogged = true
            HdrHevcEncoder.logCapabilities()
        }
        val muxer = packetSink?.let { MpegTsMuxer(it) }
        val candidate = HdrHevcEncoder(CountingSink(muxer))
        val surface = candidate.open(config)
        if (surface == null) {
            candidate.close()
            return false
        }
        encoder = candidate
        encodeConfig = config
        this.packetSink = packetSink
        encodedUnits.set(0)
        encodedBytes.set(0)
        processor.setEncodeSurface(surface)
        Log.i(
            TAG,
            "Encoding started: ${config.width}x${config.height}@${config.frameRate} " +
                "bitrate=${config.effectiveBitrateBps()} hdr10=${config.hdr10} " +
                "muxer=${if (muxer != null) "mpeg-ts" else "none"}"
        )
        return true
    }

    /**
     * Ends the encode: the renderer goes back to the preview surface first, so the stream stops
     * cleanly without the player ever drawing into a Surface the codec still owns.
     */
    fun stopEncoding(): Boolean {
        val running = encoder ?: return true
        val cfg = encodeConfig
        encoder = null
        encodeConfig = null
        processor.setEncodeSurface(null)
        val eos = running.signalEndOfStream()
        val units = encodedUnits.get()
        val bytes = encodedBytes.get()
        running.close()
        // The sink owns a socket and a sender thread (UDP) or an open file (Phase D), and the
        // encoder's close does not reach it. Closing it here is what stops a start/stop cycle from
        // leaking both.
        val sink = packetSink
        packetSink = null
        if (sink is Closeable) runCatching { sink.close() }
        val fps = cfg?.frameRate ?: 0
        val seconds = if (fps > 0) units.toDouble() / fps else 0.0
        val avgBitrate = if (seconds > 0.0) (bytes * 8.0 / seconds).toLong() else 0L
        Log.i(
            TAG,
            "Encoding stopped: units=$units bytes=$bytes eos=$eos " +
                "avgBitrate=$avgBitrate target=${cfg?.effectiveBitrateBps()}"
        )
        return eos
    }

    /** True while a Phase C encode owns the output window. */
    val isEncoding: Boolean
        get() = encoder != null

    private var testClipSink: FilePacketSink? = null

    /** The current UDP sink, held so stopUdpStream can close it. */
    private var _udpSink: UdpTsPacketSink? = null

    /** True while UDP streaming is active; stopUdpStream is a no-op if false. */
    @Volatile
    private var _udpRunning = false

    /** StateFlow for observing UDP streaming status from the UI. */
    private val _udpEnabled = MutableStateFlow(false)
    override val udpEnabled: StateFlow<Boolean> = _udpEnabled.asStateFlow()

    /**
     * Phase D: opens a short transport-stream capture at [path] and starts feeding the processed
     * frames into it. The caller owns the duration and calls [stopTestClip] when it is done -
     * the clip is a diagnostic, so a fixed wall-clock stop is the simplest thing that cannot
     * deadlock, and it keeps the timing policy out of the codec path.
     *
     * The frames are whatever the processing stage is currently drawing, so the capture is of the
     * real pipeline rather than of a synthetic source; that is the point of validating it here,
     * before a network can hide a broken bitstream behind a broken receiver.
     */
    override fun startTestClip(path: String, width: Int, height: Int, frameRate: Int): Boolean {
        val sink = FilePacketSink(File(path))
        val config = HdrHevcEncoder.Config(
            width = width,
            height = height,
            frameRate = frameRate,
        )
        if (!startEncoding(config, sink)) {
            sink.close()
            return false
        }
        testClipSink = sink
        return true
    }

    /** Ends a clip opened by [startTestClip], leaving the file flushed and readable. */
    override fun stopTestClip(): Boolean {
        val ok = stopEncoding()
        val sink = testClipSink
        testClipSink = null
        sink?.close()
        return ok
    }

    /**
     * Counts what the codec produced and hands the same bytes on to [delegate] untouched.
     *
     * Deliberately does not read the buffer: the muxer downstream does, and a counter that moved
     * the position would leave it reading an empty frame.
     */
    private inner class CountingSink(
        private val delegate: EncodedStreamSink?,
    ) : EncodedStreamSink {
        override fun onOutputFormat(format: MediaFormat) {
            Log.i(TAG, "Encoder output format: $format")
            delegate?.onOutputFormat(format)
        }

        override fun onAccessUnit(data: ByteBuffer, info: MediaCodec.BufferInfo) {
            val units = encodedUnits.incrementAndGet()
            encodedBytes.addAndGet(info.size.toLong())
            delegate?.onAccessUnit(data, info)
            // Every 120 units rather than every frame: at 60 fps this is a line a second, which
            // is the resolution a bitrate graph needs and nothing like the cost of per-frame logs.
            if (units % 120L == 0L) {
                Log.i(
                    TAG,
                    "Encoded $units units, ${encodedBytes.get()} bytes " +
                        "(${(info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0})"
                )
            }
        }
    }

    private fun ensureEngineInitialized() {
        if (engineReady || engineInitStarted) {
            return
        }
        engineInitStarted = true
        val thread = HandlerThread("RifeEngineInit").apply { start() }
        initThread = thread
        Handler(thread.looper).post {
            try {
                val initSuccess = NativeEngine.initRife(0)
                if (initSuccess) {
                    val baseCacheDir = context.cacheDir.absolutePath
                    val loadSuccess = NativeEngine.loadRifeModel(
                        context.assets,
                        baseCacheDir,
                        "rife-v4.6",
                        isV2 = false,
                        isV4 = true,
                    )
                    engineReady = loadSuccess
                    if (loadSuccess) {
                        Log.i(TAG_LIFECYCLE, "RIFE engine initialised: modelLoaded=$loadSuccess")
                        // Log device profile and capabilities
                        val status = NativeEngine.getRifeStatus()
                        Log.i(TAG_DEVICE, "Device profile: ${status.deviceProfile}, GPU: ${status.gpuName}, " +
                                "Vulkan: ${status.vulkanApiVersion}, Capabilities: ${status.vulkanCapabilities}")
                    } else {
                        Log.e(TAG_LIFECYCLE, "RIFE engine model load failed")
                        _error.value = "RIFE model load failed. Check that model assets are packaged."
                    }
                } else {
                    Log.e(TAG_LIFECYCLE, "RIFE engine init failed")
                    _error.value = "RIFE engine initialization failed."
                }
            } catch (t: Throwable) {
                Log.e(TAG_ERROR, "RIFE engine init crashed", t)
                _error.value = "RIFE engine initialization crashed: ${t.message}"
            } finally {
                // Reset initStarted so a failed initialization can be retried.
                engineInitStarted = false
                initThread?.quitSafely()
                initThread = null
            }
        }
    }
}