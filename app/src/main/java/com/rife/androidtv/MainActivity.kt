package com.rife.androidtv

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rife.androidtv.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runAndDisplayDiagnostics()
    }

    private fun runAndDisplayDiagnostics() {
        val abiList = Build.SUPPORTED_ABIS.joinToString(", ")
        val primaryAbi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "Unknown"

        val sb = StringBuilder()
        sb.append("=== DIAGNOSTICS REPORT ===\n\n")
        sb.append("--- SYSTEM INFO ---\n")
        sb.append("Device Model: ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Product: ${Build.PRODUCT}\n")
        sb.append("Hardware: ${Build.HARDWARE}\n")
        sb.append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Primary Target ABI: $primaryAbi\n")
        sb.append("Supported ABIs: $abiList\n\n")

        try {
            val result = NativeEngine.runDiagnostics()
            sb.append("--- NATIVE ENGINE & VULKAN STATUS ---\n")
            sb.append("Native Engine Status: LOADED & ACTIVE\n")
            sb.append("ncnn Version: ${result.ncnnVersion}\n")
            sb.append("Vulkan Supported: ${if (result.vulkanSupported) "YES" else "NO"}\n")
            sb.append("Vulkan API Version: ${result.vulkanApiVersion}\n")
            sb.append("GPU Name: ${if (result.gpuName.isEmpty()) "N/A" else result.gpuName}\n")
            sb.append("Vendor ID: 0x${Integer.toHexString(result.vendorId)}\n")
            sb.append("Device ID: 0x${Integer.toHexString(result.deviceId)}\n")
            sb.append("Driver Info: ${if (result.driverInfo.isEmpty()) "N/A" else result.driverInfo}\n")
            sb.append("Vulkan Features: ${result.relevantFeatures}\n\n")

            sb.append("--- REAL NCNN VULKAN OPERATION ---\n")
            sb.append("Vulkan Compute Test: ${if (result.ncnnVulkanOpSuccess) "PASSED" else "FAILED"}\n")
            sb.append("Operation Details: ${result.ncnnOpDetails}\n")

            if (result.errorMessage.isNotEmpty()) {
                sb.append("Error Message: ${result.errorMessage}\n")
            }
        } catch (e: Throwable) {
            sb.append("--- NATIVE ENGINE ERROR ---\n")
            sb.append("Failed to execute native diagnostics: ${e.message}\n")
            e.printStackTrace()
        }

        binding.tvDiagnosticsOutput.text = sb.toString()
    }
}
