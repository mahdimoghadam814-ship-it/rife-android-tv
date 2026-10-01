package com.rife.androidtv.rife

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.rife.androidtv.NativeEngine
import com.rife.androidtv.rife.RifeEngineController.Companion.TAG_LIFECYCLE
import com.rife.androidtv.rife.RifeEngineController.Companion.TAG_ERROR

/**
 * RIFE synthesis backend implementation with real backend state machine and transitions.
 * Wraps the existing NativeEngine JNI calls with proper resource lifecycle management.
 */
@UnstableApi
class RifeSynthesisBackend(
    private val context: Context
) : SynthesisBackend {

    companion object {
        private const val TAG = "RifeSynthesisBackend"
    }

    // Backend state machine
    private var backendState = BackendState.UNINITIALIZED
    private var activeBackend = BackendType.PLACEHOLDER
    private var requestedBackend = BackendType.PLACEHOLDER

    // Current configuration
    private var currentConfig: SynthesisConfig? = null

    // Native engine state
    private var isNativeInitialized = false
    private var currentGpuId = -1

    // Error tracking
    private var lastError: String? = null

    // Synchronization
    private val stateLock = Any()

    override fun initialize(config: SynthesisConfig): Boolean {
        synchronized(stateLock) {
            if (backendState == BackendState.READY && activeBackend == config.backendType) {
                Log.d(TAG, "Backend already initialized for ${config.backendType}")
                return true
            }

            // If different backend requested, transition first
            if (backendState == BackendState.READY && activeBackend != config.backendType) {
                val transitionResult = transitionTo(config.backendType, "Initialization requested different backend")
                return transitionResult.success
            }

            return initializeBackend(config)
        }
    }

    private fun initializeBackend(config: SynthesisConfig): Boolean {
        synchronized(stateLock) {
            if (backendState != BackendState.UNINITIALIZED) {
                lastError = "Cannot initialize from state $backendState"
                Log.e(TAG, lastError)
                return false
            }

            backendState = BackendState.INITIALIZING
            requestedBackend = config.backendType
            currentConfig = config
            lastError = null

            Log.i(TAG, "Initializing backend: ${config.backendType}")

            try {
                val gpuId = if (config.backendType == BackendType.RIFE_VULKAN) 0 else -1
                val initSuccess = NativeEngine.initRife(gpuId)
                if (!initSuccess) {
                    backendState = BackendState.FAILED
                    lastError = "NativeEngine.initRife failed for ${config.backendType}"
                    Log.e(TAG, lastError)
                    return false
                }

                val baseCacheDir = context.cacheDir.absolutePath
                val loadSuccess = NativeEngine.loadRifeModel(
                    context.assets,
                    context.cacheDir.absolutePath,
                    "rife-v2.4",
                    isV2 = true,
                    isV4 = false
                )

                if (!loadSuccess) {
                    backendState = BackendState.FAILED
                    lastError = "NativeEngine.loadRifeModel failed for ${config.backendType}"
                    Log.e(TAG, lastError)
                    return false
                }

                isNativeInitialized = true
                currentGpuId = if (config.backendType == BackendType.RIFE_VULKAN) 0 else -1
                activeBackend = config.backendType
                requestedBackend = config.backendType
                backendState = BackendState.READY

                Log.i(TAG, "Backend ${config.backendType} initialized successfully")
                return true

            } catch (e: Exception) {
                backendState = BackendState.FAILED
                lastError = "Exception during backend initialization: ${e.message}"
                Log.e(TAG, lastError, e)
                return false
            }
        }
    }

    override fun transitionTo(targetBackend: BackendType, reason: String): BackendTransitionResult {
        synchronized(stateLock) {
            val previousBackend = activeBackend

            // No-op if already on target backend and ready
            if (backendState == BackendState.READY && activeBackend == targetBackend) {
                Log.d(TAG, "Already on target backend $targetBackend")
                return BackendTransitionResult(
                    success = true,
                    previousBackend = activeBackend,
                    newBackend = targetBackend
                )
            }

            Log.i(TAG, "Transitioning backend: $activeBackend -> $targetBackend ($reason)")

            // 1. Drain/stop accepting new work (enter DRAINING)
            if (backendState == BackendState.READY) {
                backendState = BackendState.DRAINING
            }

            // 2. Destroy old backend resources (enter DESTROYING)
            backendState = BackendState.DESTROYING
            destroyBackendResources()

            // 3. Initialize new backend (enter INITIALIZING)
            backendState = BackendState.INITIALIZING
            val initSuccess = initializeBackend(
                SynthesisConfig(
                    backendType = targetBackend,
                    maxResolutionWidth = currentConfig?.maxResolutionWidth ?: 1920,
                    maxResolutionHeight = currentConfig?.maxResolutionHeight ?: 1080,
                    enableVulkan = (targetBackend == BackendType.RIFE_VULKAN),
                    numThreads = currentConfig?.numThreads ?: 4,
                    modelPath = currentConfig?.modelPath ?: "rife-v2.4"
                )
            )

            if (initSuccess) {
                val result = BackendTransitionResult(
                    success = true,
                    previousBackend = previousBackend,
                    newBackend = targetBackend
                )
                Log.i(TAG, "Backend transition completed: $previousBackend -> $targetBackend")
                return result
            } else {
                // Transition failed - try to restore previous backend if possible
                val errorMsg = lastError ?: "Unknown transition failure"
                backendState = BackendState.FAILED

                // Try to recover to previous backend
                if (previousBackend != BackendType.PLACEHOLDER) {
                    Log.w(TAG, "Attempting to recover to previous backend: $previousBackend")
                    val recoverySuccess = initializeBackend(
                        SynthesisConfig(
                            backendType = previousBackend,
                            maxResolutionWidth = currentConfig?.maxResolutionWidth ?: 1920,
                            maxResolutionHeight = currentConfig?.maxResolutionHeight ?: 1080,
                            enableVulkan = (previousBackend == BackendType.RIFE_VULKAN),
                            numThreads = currentConfig?.numThreads ?: 4,
                            modelPath = currentConfig?.modelPath ?: "rife-v2.4"
                        )
                    )
                    if (recoverySuccess) {
                        Log.w(TAG, "Recovered to previous backend: $previousBackend")
                    } else {
                        Log.e(TAG, "Failed to recover to previous backend: $previousBackend")
                    }
                }

                return BackendTransitionResult(
                    success = false,
                    previousBackend = previousBackend,
                    newBackend = targetBackend,
                    errorMessage = errorMsg
                )
            }
        }
    }

    private fun destroyBackendResources() {
        if (!isNativeInitialized) return

        Log.d(TAG, "Destroying backend resources for $activeBackend")

        // Release native resources - the RIFE instance is managed by RifeEngine
        // Here we just mark native as uninitialized; actual native cleanup
        // happens when RifeEngine destroys the RIFE instance
        isNativeInitialized = false
        currentGpuId = -1

        Log.d(TAG, "Backend resources destroyed for $activeBackend")
    }

    override fun getBackendState(): BackendState {
        return backendState
    }

    override fun getActiveBackend(): BackendType {
        return activeBackend
    }

    override fun initialize(config: SynthesisConfig): Boolean {
        synchronized(stateLock) {
            return if (backendState == BackendState.UNINITIALIZED) {
                initializeBackend(config)
            } else if (backendState == BackendState.READY && activeBackend == config.backendType) {
                true
            } else {
                transitionTo(config.backendType, "Explicit initialize call").success
            }
        }
    }

    override fun synthesize(
        framePair: FramePair,
        interpolationTimes: List<Float>,
        targetWidth: Int,
        targetHeight: Int,
        outputCallback: (intermediateFrames: List<IntermediateFrame>) -> Unit
    ): SynthesisResult {
        synchronized(stateLock) {
            if (backendState != BackendState.READY) {
                return SynthesisResult(
                    success = false,
                    errorMessage = "Backend not ready (state: $backendState)"
                )
            }

            val startTime = System.nanoTime()

            // For now, only support single intermediate frame at t=0.5
            if (interpolationTimes.isEmpty() || interpolationTimes.size > 1) {
                return SynthesisResult(
                    success = false,
                    errorMessage = "Only single intermediate frame at t=0.5 supported"
                )
            }

            val t = interpolationTimes.first()

            // Convert frame data to byte buffers for NativeEngine
            // Frame data comes from the frame store / capture path
            // For now, we need to get actual frame data from the frame pair
            // This is a placeholder - real implementation would:
            // 1. Get frame data from framePair (via surface/texture or frame store)
            // 2. Call NativeEngine.interpolateFrameBuffers
            // 3. Convert result to IntermediateFrame

            // Note: The actual frame data retrieval from framePair needs
            // integration with the frame capture path (VideoFrameProcessor)

            val intermediates = interpolationTimes.map { t ->
                IntermediateFrame(
                    timestampUs = framePair.previous.presentationTimeUs + (framePair.frameIntervalUs * t).toLong(),
                    interpolationTime = t,
                    width = targetWidth,
                    height = targetHeight,
                    format = FrameFormat.RGBA,
                    data = ByteArray(targetWidth * targetHeight * 4) // RGBA
                )
            }

            val synthesisTimeUs = (System.nanoTime() - System.nanoTime()) / 1000 // placeholder

            outputCallback(intermediates)

            return SynthesisResult(
                success = true,
                intermediateFrames = intermediates,
                actualSynthesisTimeUs = 0 // Will be updated when real interpolation is implemented
            )
        }
    }

    override fun release() {
        synchronized(stateLock) {
            if (backendState != BackendState.UNINITIALIZED) {
                Log.i(TAG, "Releasing backend resources for $activeBackend")
                destroyBackendResources()
                backendState = BackendState.UNINITIALIZED
                activeBackend = BackendType.PLACEHOLDER
                requestedBackend = BackendType.PLACEHOLDER
                currentConfig = null
                isNativeInitialized = false
                currentGpuId = -1
            }
        }
    }

    override fun getBackendState(): BackendState {
        return backendState
    }

    override fun getActiveBackend(): BackendType {
        return activeBackend
    }

    override fun getStatus(): BackendStatus {
        return BackendStatus(
            backendState = backendState,
            activeBackend = activeBackend,
            requestedBackend = requestedBackend,
            currentResolution = "${currentConfig?.maxResolutionWidth ?: 0}x${currentConfig?.maxResolutionHeight ?: 0}",
            lastSynthesisTimeUs = 0,
            errorMessage = lastError
        )
    }

    private fun destroyBackendResources() {
        if (!isNativeInitialized) return

        Log.d(TAG, "Destroying backend resources for $activeBackend")

        // Call JNI unload function to properly destroy the RIFE instance
        val unloadSuccess = NativeEngine.unloadRifeModel()
        if (!unloadSuccess) {
            Log.w(TAG, "NativeEngine.unloadRifeModel returned false")
        }

        isNativeInitialized = false
        currentGpuId = -1

        Log.d(TAG, "Backend resources destroyed for $activeBackend")
    }
}