package com.rife.androidtv

data class RifeDiagnosticResult(
    val success: Boolean,
    val vulkanAvailable: Boolean,
    val gpuName: String,
    val vulkanApiVersion: String,
    val modelLoaded: Boolean,
    val lastInferenceTimeMs: Long,
    val lastError: String,
    val opDetails: String
)
