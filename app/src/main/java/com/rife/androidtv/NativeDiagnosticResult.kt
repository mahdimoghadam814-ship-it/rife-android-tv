package com.rife.androidtv

data class NativeDiagnosticResult(
    val vulkanSupported: Boolean,
    val vulkanApiVersion: String,
    val gpuName: String,
    val vendorId: Int,
    val deviceId: Int,
    val driverInfo: String,
    val relevantFeatures: String,
    val ncnnVersion: String,
    val ncnnVulkanOpSuccess: Boolean,
    val ncnnOpDetails: String,
    val errorMessage: String
)
