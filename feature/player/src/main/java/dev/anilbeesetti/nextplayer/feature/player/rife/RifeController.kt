package dev.anilbeesetti.nextplayer.feature.player.rife

import android.view.Surface
import androidx.media3.common.SurfaceInfo
import dev.anilbeesetti.nextplayer.core.model.SvPlayerSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Processing resolutions offered by the Video Processing settings entry.
 */
enum class RifeResolution {
    AUTO,
    ORIGINAL,
    RES_1080P,
    RES_720P,
    RES_480P,
}

/**
 * Interpolation backends offered by the Video Processing settings entry.
 */
enum class InterpolationAlgorithm {
    RIFE,
    MEMC,
    SVPLAYER,
}

/**
 * Snapshot of the processing pipeline statistics, refreshed about once per second.
 */
data class RifeStats(
    val inputFps: Float,
    val outputFps: Float,
    /**
     * The instantaneous output frame rate over the last pair. The encoder is opened at this rate;
     * [outputFps] is a one-second average and would open a 72 fps stream as 60.
     */
    val outputFrameRate: Float,
    val processingTimeMs: Long,
    val droppedFrames: Long,
    val currentResolution: String,
)

/**
 * The app-wide RIFE / FastDVDnet processing control surface, consumed by the real Next Player
 * player screen. The implementation lives in the `app` module (it owns the native engine and the
 * Media3 frame pipeline); this interface is what the feature modules see.
 *
 * The controller owns:
 *  * the worker thread, EGL context and pooled frame buffers of the processing pipeline,
 *  * the native RIFE engine (initialised lazily, off the main thread),
 *  * the input surface the player must render decoded frames into while a stage is enabled
 *    (published through [inputSurface]),
 *  * the output surface the processed frames are rendered to (published by the player screen
 *    through [setOutputSurfaceInfo]).
 */
interface RifeController {

    /** Whether any processing stage (RIFE or FastDVDnet) currently intercepts frames. */
    val processingEnabled: StateFlow<Boolean>

    /** Pipeline statistics for the engine status overlay. */
    val stats: StateFlow<RifeStats>

    /**
     * The input surface the player must render decoded frames into, or `null` while no processing
     * stage is enabled or the surface is being recreated. A new value means the previous surface
     * has been superseded and must be replaced on the player.
     */
    val inputSurface: StateFlow<Surface?>

    /** The last pipeline error, if any. */
    val error: StateFlow<String?>

    fun start()

    fun stop()

    fun setRifeEnabled(enabled: Boolean)

    fun setFastDvdNetEnabled(enabled: Boolean)

    fun setResolution(resolution: RifeResolution)

    /**
     * Selects the interpolation backend. Switching to [InterpolationAlgorithm.MEMC] does not
     * require the RIFE model; switching to [InterpolationAlgorithm.RIFE] is handled lazily by
     * [setRifeEnabled].
     */
    fun setInterpolationAlgorithm(algorithm: InterpolationAlgorithm)

    /**
     * How many output frames are synthesised per source frame. 2f is one interpolated frame
     * between each pair, 3f is two, and so on. Only the interpolation ratio changes: the source
     * cadence, the resolution policy and the denoiser are untouched.
     */
    fun setMemcLevel(multiplier: Float)

    /**
     * Strength of the motion-aligned denoiser, from [strength] on the settings' own scale (1f is
     * the balanced default). It scales the history blend in the shader; the per-pixel similarity
     * gate still rejects any sample that disagrees with its own motion-compensated counterpart by
     * more than the frame's own noise does, so a higher level removes more noise without turning
     * into a blur.
     */
    fun setDenoiseLevel(strength: Float)

    /**
     * Publishes the SVPlayer tuning surface: the performance versus quality bar, the artifact
     * masking level and the expert overrides beneath them. Ignored unless the selected
     * algorithm is [InterpolationAlgorithm.SVPLAYER]; taking effect on the next frame pair.
     */
    fun setSvPlayerSettings(settings: SvPlayerSettings)

    /** Records the decoded frame size reported by `Player.Listener.onVideoSizeChanged`. */
    fun setInputFrameSize(width: Int, height: Int)

    /**
     * Phase D: starts a local transport-stream capture at [path] fed by the Surface-based
     * encoder, at the source frame size and [frameRate]. Returns false if the codec could not
     * open or an encode is already running. Paired with [stopTestClip].
     */
    fun startTestClip(path: String, width: Int, height: Int, frameRate: Int): Boolean

    /** Ends a capture opened by [startTestClip], leaving the file flushed and readable. */
    fun stopTestClip(): Boolean

    /** Drops every buffered frame: seek, media transition, stream change. */
    fun resetForDiscontinuity(reason: String)

    /**
     * Publishes the output surface (the SurfaceView the processed result is rendered to). Passing
     * `null` releases it immediately.
     */
    fun setOutputSurfaceInfo(outputSurfaceInfo: SurfaceInfo?)

    /**
     * Tags the output buffers with [dataSpace] (`0` for the platform default) so HDR sources are
     * shown through the right transfer curve. The processing path blits into a plain 8-bit window
     * and therefore gets no dataspace from the platform, unlike the bypass path.
     */
    fun setOutputDataSpace(dataSpace: Int)

    /** Returns and clears the last pipeline error, if any. */
    fun consumeError(): String?

    /**
     * Called once the player has actually taken over the current input surface, so the replaced
     * EGL/SurfaceTexture state can be released.
     */
    fun onInputSurfaceAttached()

    /**
     * Called when the player no longer renders into the input surface (processing switched off,
     * surface taken away).
     */
    fun onInputSurfaceDetached()
}
