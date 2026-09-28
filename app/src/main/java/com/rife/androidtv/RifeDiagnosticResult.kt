package com.rife.androidtv

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

enum class DeviceProfile {
    UNKNOWN,
    POCO_F7,
    XIAOMI_TV_BOX_S_3RD_GEN,
    GENERIC_HIGH_END,
    GENERIC_MID_RANGE,
    GENERIC_LOW_END,
    CPU_FALLBACK
}

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
