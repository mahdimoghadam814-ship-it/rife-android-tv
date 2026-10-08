package com.rife.androidtv

import android.view.Surface
import java.nio.ByteBuffer

/**
 * JNI surface of the SVP-only processing pipeline.
 *
 * The library carries exactly two pieces of native code: this bridge and the CPU
 * block-matching interpolator that implements the SVPlayer search. The RIFE neural network
 * and the plain MEMC baseline were removed, so there is no model, no Vulkan runtime and no
 * algorithm selector left - every entry point below is unconditional.
 */
object NativeEngine {
    init {
        System.loadLibrary("rife_native")
    }

    /**
     * Runs motion estimation only and packs the result instead of warping: eight bytes per
     * block, row-major, on the grid pitch reported by [motionFieldStep].
     *
     * The first four bytes of each block are forward x, forward y, backward x, backward y - each
     * a whole-pixel vector biased by +128. The next four are the forward and backward
     * cover/uncover masks (0 = fully trusted, 255 = the content behind that warp is being
     * covered up), then two reserved zero bytes.
     *
     * The caller uploads the two halves as separate RGBA textures and lets a fragment shader do
     * the bilinear resample, which is what keeps the per-pixel warp off the CPU.
     *
     * [forwardOnly] drops the backward search when nothing will read it - the denoiser samples
     * history along the forward vector alone, so with interpolation off the second estimate is
     * the largest single cost in the frame and pure overhead.
     */
    @JvmStatic
    external fun computeMotionField(
        in0Buffer: ByteBuffer,
        in1Buffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        mvBuffer: ByteBuffer,
        forwardOnly: Boolean,
    ): Boolean

    /**
     * Tags the buffers queued for [surface] with an `android.hardware.DataSpace` so the display
     * stack decodes them as HDR instead of as plain sRGB. The processed frames are blitted into a
     * plain RGBA8888 window, so unlike the bypass path - where MediaCodec writes the dataspace
     * itself - nothing carries that information for us.
     *
     * Returns the platform result: 0 on success, negative when the surface is unusable or the
     * device predates the API.
     */
    @JvmStatic
    external fun setOutputDataSpace(surface: Surface, dataSpace: Int): Int

    /**
     * Reads the `android.hardware.DataSpace` the buffers of [surface] currently carry.
     *
     * Used for two things: confirming that a tag written by [setOutputDataSpace] is still there
     * (EGL quietly resets it when it recreates the window surface), and observing the dataspace
     * MediaCodec stamped on its own output window - the exact value the bypass path displays.
     *
     * Returns the dataspace on success, or a negative failure code far enough from any platform
     * `status_t` that it cannot be mistaken for one.
     */
    @JvmStatic
    external fun getOutputDataSpace(surface: Surface): Int

    @JvmStatic
    external fun interpolateFrameBuffers(
        in0Buffer: ByteBuffer,
        in1Buffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        timestep: Float,
        outBuffer: ByteBuffer
    ): Boolean

    @JvmStatic
    external fun setInterpolationThreadCount(threads: Int)

    /**
     * The pitch, in pixels, that the packed motion grid is laid out on - `ceil(width / step)` by
     * `ceil(height / step)` blocks of eight bytes each.
     *
     * It shrinks when SVP's `overlap` is on, because the overlap shrinks the grid pitch rather
     * than widening the search window. Everything that sizes the field's buffer or indexes it in
     * a shader has to ask for this rather than assume 16; a mismatch smears the field instead of
     * failing.
     */
    @JvmStatic
    external fun motionFieldStep(): Int

    /**
     * Which of SVP's renderers the warp blends with: 0 is the plain forward/backward time blend
     * (`algo 11`), 1 adds the dynamic median (`algo 13`, SVP's own default), 2 adds cover/uncover
     * (`algo 21`). The three are alternatives rather than cumulative rungs.
     *
     * Read from the interpolator rather than from the settings so that the CPU fallback and the
     * GL shader cannot disagree.
     */
    @JvmStatic
    external fun motionFieldBlendMode(): Int

    @JvmStatic
    external fun setSvPlayerSettings(
        performanceQuality: Float,
        artifactMaskLevel: Float,
        blockSize: Int,
        searchDistance: Int,
        subpel: Int,
        overlap: Int,
        penaltyLambda: Float,
        blendAlgorithm: Int,
        sceneAdaptive: Int,
        meScale: Int,
    )

    /** Drops the pyramid and field history held across frames: seek, stream change, discontinuity. */
    @JvmStatic
    external fun resetInterpolationState()

    /** Wall-clock duration of the most recent native interpolate()/motionField() call, in ms. */
    @JvmStatic
    external fun getInterpolationLastDurationMs(): Double
}
