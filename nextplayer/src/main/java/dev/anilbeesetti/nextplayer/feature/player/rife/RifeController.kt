package dev.anilbeesetti.nextplayer.feature.player.rife

import androidx.media3.common.util.UnstableApi

@UnstableApi
interface RifeController {
    fun start()
    fun stop()
    fun setRifeEnabled(enabled: Boolean)
    fun setFastDvdNetEnabled(enabled: Boolean)
    fun setResolution(resolution: RifeResolution)
    fun setInputFrameSize(width: Int, height: Int)
    fun setOutputSurfaceInfo(outputSurfaceInfo: androidx.media3.common.SurfaceInfo?)
    fun onInputSurfaceAttached()
    fun onInputSurfaceDetached()
    fun resetForDiscontinuity(reason: String)
    fun consumeError(): String?
    fun engineStatus(): RifeDiagnosticResult
    fun getTemporalFrameStore(): TemporalFrameStore
    fun getPipelineCoordinator(): PipelineCoordinator
    fun submitFrameToTemporalStore(metadata: FrameMetadata): Boolean
    fun processFrameThroughPipeline(): Boolean
    fun onFrameAvailable(frameId: Long, metadata: FrameMetadata): Boolean
    fun getFrameBridgeInputSurface(): android.view.Surface?
}

@UnstableApi
enum class RifeResolution {
    ORIGINAL,
    HD,
    FHD,
    FOUR_K
}

@UnstableApi
data class RifeStats(
    val inputFps: Float,
    val outputFps: Float,
    val processingTimeMs: Long,
    val droppedFrames: Long,
    val currentResolution: String
)

@UnstableApi
data class FrameMetadata(
    val frameId: Long,
    val presentationTimeUs: Long,
    val width: Int,
    val height: Int,
    val format: Int,
    val isKeyFrame: Boolean = false,
    val decoderMetadata: Map<String, String> = emptyMap()
) {
    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()
}

@UnstableApi
enum class FrameFormat {
    RGBA,
    RGB,
    YUV420,
    YUV422,
    YUV444,
    P010,
    NV12,
    NV21
}

@UnstableApi
data class RifeDiagnosticResult(
    val success: Boolean,
    val vulkanAvailable: Boolean,
    val gpuName: String,
    val vulkanApiVersion: String,
    val modelLoaded: Boolean,
    val lastInferenceTimeMs: Long,
    val lastError: String,
    val opDetails: String,
    val vulkanCapabilities: VulkanCapabilities,
    val deviceProfile: DeviceProfile,
    val deviceModel: String
)

@UnstableApi
data class VulkanCapabilities(
    val fp16Storage: Boolean,
    val fp16Packed: Boolean,
    val fp16Arithmetic: Boolean,
    val int8Storage: Boolean,
    val int8Packed: Boolean,
    val int8Arithmetic: Boolean,
    val int16Storage: Boolean,
    val int16Arithmetic: Boolean,
    val shaderInt16: Boolean,
    val shaderInt64: Boolean,
    val cooperativeMatrix: Boolean,
    val subgroupSizeControl: Boolean,
    val storageBuffer16Bit: Boolean,
    val uniformStorageBuffer16Bit: Boolean
) {
    override fun toString(): String {
        return "VulkanCapabilities(fp16Storage=$fp16Storage, fp16Packed=$fp16Packed, " +
                "fp16Arithmetic=$fp16Arithmetic, int8Storage=$int8Storage, int8Packed=$int8Packed, " +
                "int8Arithmetic=$int8Arithmetic, int16Storage=$int16Storage, int16Arithmetic=$int16Arithmetic, " +
                "shaderInt16=$shaderInt16, shaderInt64=$shaderInt64, cooperativeMatrix=$cooperativeMatrix, " +
                "subgroupSizeControl=$subgroupSizeControl, storageBuffer16Bit=$storageBuffer16Bit, " +
                "uniformStorageBuffer16Bit=$uniformStorageBuffer16Bit)"
    }
}

@UnstableApi
enum class DeviceProfile {
    UNKNOWN,
    POCO_F7,
    XIAOMI_TV_BOX_S_3RD_GEN,
    GENERIC_HIGH_END,
    GENERIC_MID_RANGE,
    GENERIC_LOW_END,
    CPU_FALLBACK
}

@UnstableApi
data class FrameMetadata(
    val frameId: Long,
    val presentationTimeUs: Long,
    val width: Int,
    val height: Int,
    val format: Int,
    val isKeyFrame: Boolean = false,
    val decoderMetadata: Map<String, String> = emptyMap()
) {
    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()
}

@UnstableApi
enum class FrameFormat {
    RGBA,
    RGB,
    YUV420,
    YUV422,
    YUV444,
    P010,
    NV12,
    NV21
}

@UnstableApi
interface TemporalFrameStore {
    fun addFrame(metadata: FrameMetadata): Boolean
    fun getLatestFramePair(): FramePair?
    fun getFrame(frameId: Long): FrameMetadata?
    fun evictOlderThan(timestampUs: Long): Int
    fun clear()
    fun size(): Int
    fun hasValidFramePair(): Boolean
    fun getConfig(): TemporalFrameStoreConfig
}

@UnstableApi
data class TemporalFrameStoreConfig(
    val maxHistorySize: Int = 3,
    val maxFrameAgeUs: Long = 500_000, // 500ms
    val retainKeyFrames: Boolean = true
)

@UnstableApi
data class FramePair(
    val previous: FrameMetadata,
    val current: FrameMetadata,
    val frameIntervalUs: Long
) {
    val isValid: Boolean
        get() = previous.frameId < current.frameId && frameIntervalUs > 0
}