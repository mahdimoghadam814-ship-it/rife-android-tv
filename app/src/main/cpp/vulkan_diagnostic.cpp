#include "vulkan_diagnostic.h"
#include "gpu.h"
#include "net.h"
#include "c_api.h"
#include "mat.h"
#include "option.h"
#include <android/log.h>
#include <sstream>
#include <iomanip>

#define LOG_TAG "RifeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

DiagnosticResult run_vulkan_diagnostics() {
    DiagnosticResult res;
    res.vulkan_supported = false;
    res.vendor_id = 0;
    res.device_id = 0;
    res.ncnn_vulkan_op_success = false;
    res.ncnn_version = ncnn_version();

    // Step 1: Initialize ncnn Vulkan environment & check GPU support
    int gpu_count = ncnn::get_gpu_count();
    LOGI("ncnn reported GPU count: %d", gpu_count);

    if (gpu_count <= 0) {
        res.vulkan_supported = false;
        res.vulkan_api_version = "N/A";
        res.error_message = "No Vulkan compatible GPU device detected by ncnn.";
        return res;
    }

    res.vulkan_supported = true;
    int default_gpu = ncnn::get_default_gpu_index();
    if (default_gpu < 0 || default_gpu >= gpu_count) {
        default_gpu = 0;
    }

    const ncnn::VulkanDevice* gpu = ncnn::get_gpu_device(default_gpu);
    if (!gpu) {
        res.error_message = "Failed to retrieve ncnn VulkanDevice handle.";
        return res;
    }

    // Extract device properties
    const VkPhysicalDeviceProperties& props = gpu->info.physicalDeviceProperties();
    res.gpu_name = props.deviceName;
    res.vendor_id = props.vendorID;
    res.device_id = props.deviceID;

    std::ostringstream ver_stream;
    ver_stream << VK_VERSION_MAJOR(props.apiVersion) << "."
               << VK_VERSION_MINOR(props.apiVersion) << "."
               << VK_VERSION_PATCH(props.apiVersion);
    res.vulkan_api_version = ver_stream.str();

    std::ostringstream driver_stream;
    driver_stream << "Driver Version: 0x" << std::hex << props.driverVersion
                  << " (API " << res.vulkan_api_version << ")";
    res.driver_info = driver_stream.str();

    // Relevant Vulkan features supported by device
    bool ahb_export = false;
#if __ANDROID_API__ >= 26
    ahb_export = gpu->info.support_VK_ANDROID_external_memory_android_hardware_buffer();
#endif

    std::ostringstream features_stream;
    features_stream << "FP16 Packed: " << (gpu->info.support_fp16_packed() ? "YES" : "NO") << ", "
                    << "FP16 Storage: " << (gpu->info.support_fp16_storage() ? "YES" : "NO") << ", "
                    << "FP16 Compute: " << (gpu->info.support_fp16_arithmetic() ? "YES" : "NO") << ", "
                    << "INT8 Storage: " << (gpu->info.support_int8_storage() ? "YES" : "NO") << ", "
                    << "INT8 Compute: " << (gpu->info.support_int8_arithmetic() ? "YES" : "NO") << ", "
                    << "Cooperative Matrix: " << (gpu->info.support_cooperative_matrix() ? "YES" : "NO") << ", "
                    << "AHardwareBuffer Export: " << (ahb_export ? "YES" : "NO");
    res.relevant_features = features_stream.str();

    // Step 2: Perform REAL ncnn Vulkan operation
    ncnn::VkAllocator* blob_vkallocator = gpu->acquire_blob_allocator();
    ncnn::VkAllocator* staging_vkallocator = gpu->acquire_staging_allocator();

    if (!blob_vkallocator || !staging_vkallocator) {
        res.ncnn_vulkan_op_success = false;
        res.ncnn_op_details = "Failed to acquire Vulkan allocators from VulkanDevice.";
        return res;
    }

    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 1;
    opt.blob_vkallocator = blob_vkallocator;
    opt.workspace_vkallocator = blob_vkallocator;
    opt.staging_vkallocator = staging_vkallocator;

    int ret = -1;
    {
        ncnn::VkCompute cmd(gpu);

        // Create CPU matrix and upload to GPU VkMat using record_clone
        ncnn::Mat in_cpu(256, 256, 3);
        in_cpu.fill(1.0f);

        ncnn::VkMat in_gpu;
        cmd.record_clone(in_cpu, in_gpu, opt);

        // Execute recorded compute commands synchronously
        ret = cmd.submit_and_wait();

        if (ret == 0 && !in_gpu.empty()) {
            res.ncnn_vulkan_op_success = true;
            std::ostringstream op_stream;
            op_stream << "VkMat upload & compute submit successful (Dimensions: "
                      << in_gpu.w << "x" << in_gpu.h << "x" << in_gpu.c
                      << ", ElemSize: " << in_gpu.elemsize << " bytes)";
            res.ncnn_op_details = op_stream.str();
        } else {
            res.ncnn_vulkan_op_success = false;
            res.ncnn_op_details = "ncnn VkCompute submit_and_wait returned error code " + std::to_string(ret);
        }

        // Explicitly release GPU matrix before reclaiming allocators
        in_gpu.release();
    }

    gpu->reclaim_blob_allocator(blob_vkallocator);
    gpu->reclaim_staging_allocator(staging_vkallocator);

    return res;
}
