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
