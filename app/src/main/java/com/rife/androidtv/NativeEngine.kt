package com.rife.androidtv

import android.content.res.AssetManager
import android.view.Surface
import java.nio.ByteBuffer

object NativeEngine {
    init {
        System.loadLibrary("rife_native")
    }

    @JvmStatic
    external fun runDiagnostics(): NativeDiagnosticResult

    @JvmStatic
    external fun initRife(gpuId: Int): Boolean

    @JvmStatic
    external fun loadRifeModel(assetManager: AssetManager, baseCacheDir: String, modelDir: String, isV2: Boolean, isV4: Boolean): Boolean

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

    /**
     * Runs motion estimation only and packs the result instead of warping: eight bytes per 16x16
     * block, row-major. [mvBuffer] must hold at least
     * `ceil(targetWidth/16) * ceil(targetHeight/16) * 8` bytes.
     *
     * The first four bytes of each block are forward x, forward y, backward x, backward y - each
     * a whole-pixel vector biased by +128. The next four are the forward and backward
     * cover/uncover masks (0 = fully trusted, 255 = the content behind that warp is being
     * covered up), then two reserved zero bytes.
     *
     * The caller uploads the two halves as separate RGBA textures and lets a fragment shader do
     * the bilinear resample, which is what keeps the per-pixel warp off the CPU.
     */
    @JvmStatic
    external fun computeMotionField(
        in0Buffer: ByteBuffer,
        in1Buffer: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        mvBuffer: ByteBuffer
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

    @JvmStatic
    external fun runRifeTest(width: Int, height: Int): Boolean

    @JvmStatic
    external fun setInterpolationAlgorithm(algorithm: Int)

    @JvmStatic
    external fun setMemcThreadCount(threads: Int)

    @JvmStatic
    external fun resetMemcState()

    @JvmStatic
    external fun getMemcLastDurationMs(): Double

    @JvmStatic
    external fun getRifeStatus(): RifeDiagnosticResult
}
