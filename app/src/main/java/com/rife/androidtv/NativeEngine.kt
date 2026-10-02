package com.rife.androidtv

import android.content.res.AssetManager
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
     * Runs motion estimation only and packs the result instead of warping: four bytes per 16x16
     * block (forward x, forward y, backward x, backward y), each a whole-pixel vector biased by
     * +128, row-major. [mvBuffer] must hold at least
     * `ceil(targetWidth/16) * ceil(targetHeight/16) * 4` bytes.
     *
     * The caller uploads the packed field as an RGBA texture and lets a fragment shader do the
     * bilinear resample, which is what keeps the per-pixel warp off the CPU.
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
