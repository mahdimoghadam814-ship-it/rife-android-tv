package com.rife.androidtv.rife

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.util.UnstableApi
import com.rife.androidtv.NativeEngine
import com.rife.androidtv.RifeDiagnosticResult
import com.rife.androidtv.DeviceProfile
import com.rife.androidtv.VulkanCapabilities
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeController
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeResolution as FeatureRifeResolution
import dev.anilbeesetti.nextplayer.feature.player.rife.InterpolationAlgorithm as FeatureInterpolationAlgorithm
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeStats
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
        FeatureInterpolationAlgorithm.RIFE

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
        processor.stop()
        _inputSurface.value = null
        _processingEnabled.value = false
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
        NativeEngine.setInterpolationAlgorithm(
            if (algorithm == FeatureInterpolationAlgorithm.MEMC) 1 else 0,
        )
        if (algorithm == FeatureInterpolationAlgorithm.MEMC) {
            // Measured optimum: the ME/MC loops are memory-bound, so going wider than 4 only
            // adds contention (8 threads was ~2x slower than 4 on an 8-core big.LITTLE device).
            NativeEngine.setMemcThreadCount(4)
        }
        Log.i(TAG_LIFECYCLE, "Interpolation algorithm: $algorithm")
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