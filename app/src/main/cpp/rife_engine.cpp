#include "rife_engine.h"
#include "gpu.h"

#include <android/log.h>
#include <chrono>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <memory>
#include <cstdlib>
#include <cstdarg>
#include <cstdio>

#define LOG_TAG_RIFE_DEVICE "RIFE-DEVICE"
#define LOG_TAG_RIFE_SPIRV "RIFE-SPIRV"
#define LOG_TAG_RIFE_LIFECYCLE "RIFE-LIFECYCLE"
#define LOG_TAG_RIFE_ERROR "RIFE-ERROR"

#define LOGI_DEVICE(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG_RIFE_DEVICE, __VA_ARGS__)
#define LOGW_DEVICE(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG_RIFE_DEVICE, __VA_ARGS__)
#define LOGE_DEVICE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_RIFE_DEVICE, __VA_ARGS__)

#define LOGI_SPIRV(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG_RIFE_SPIRV, __VA_ARGS__)
#define LOGW_SPIRV(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG_RIFE_SPIRV, __VA_ARGS__)
#define LOGE_SPIRV(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_RIFE_SPIRV, __VA_ARGS__)

#define LOGI_LIFECYCLE(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG_RIFE_LIFECYCLE, __VA_ARGS__)
#define LOGW_LIFECYCLE(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG_RIFE_LIFECYCLE, __VA_ARGS__)
#define LOGE_LIFECYCLE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_RIFE_LIFECYCLE, __VA_ARGS__)

#define LOGI_ERROR(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG_RIFE_ERROR, __VA_ARGS__)
#define LOGW_ERROR(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG_RIFE_ERROR, __VA_ARGS__)
#define LOGE_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_RIFE_ERROR, __VA_ARGS__)

// Generic log helper for internal use with dynamic tags
static inline void logWithTag(const char* tag, int priority, const char* fmt, va_list args) {
    __android_log_vprint(priority, tag, fmt, args);
}

void RifeEngine::logStructured(const char* tag, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    logWithTag(tag, ANDROID_LOG_INFO, fmt, args);
    va_end(args);
}

static bool extractAssetFile(
    AAssetManager* mgr,
    const std::string& assetPath,
    const std::string& outFilePath
) {
    if (!mgr) {
        LOGE_ERROR("AssetManager is null");
        return false;
    }

    AAsset* asset = AAssetManager_open(
        mgr,
        assetPath.c_str(),
        AASSET_MODE_BUFFER
    );

    if (!asset) {
        LOGE_ERROR("Failed to open asset file: %s", assetPath.c_str());
        return false;
    }

    size_t size = AAsset_getLength(asset);

    std::vector<char> buffer(size);

    int readBytes = AAsset_read(
        asset,
        buffer.data(),
        size
    );

    AAsset_close(asset);

    if (readBytes != static_cast<int>(size)) {
        LOGE_ERROR(
            "Failed to read complete asset file: %s "
            "(expected=%zu read=%d)",
            assetPath.c_str(),
            size,
            readBytes
        );
        return false;
    }

    std::ofstream outFile(
        outFilePath,
        std::ios::binary
    );

    if (!outFile.is_open()) {
        LOGE_ERROR(
            "Failed to write output file: %s",
            outFilePath.c_str()
        );
        return false;
    }

    outFile.write(
        buffer.data(),
        static_cast<std::streamsize>(size)
    );

    outFile.close();

    if (!outFile) {
        LOGE_ERROR(
            "Failed while writing output file: %s",
            outFilePath.c_str()
        );
        return false;
    }

    return true;
}

RifeEngine::RifeEngine()
    : gpu_id(0),
      vulkan_available(false),
      model_loaded(false),
      last_inference_time_ms(-1),
      device_profile(DeviceProfile::UNKNOWN) {
}

RifeEngine::~RifeEngine() {
    rife_impl.reset();
}

bool RifeEngine::init(int requested_gpu_id) {
    std::lock_guard<std::mutex> lock(mutex);
    gpu_id = requested_gpu_id;

    int gpu_count = ncnn::get_gpu_count();

    LOGI_DEVICE("ncnn reported GPU count: %d", gpu_count);

    if (gpu_count <= 0) {
        vulkan_available = false;
        gpu_name.clear();
        vulkan_api_version.clear();
        last_error =
            "No Vulkan compatible GPU found. "
            "RIFE will use CPU fallback.";
        return true;
    }

    if (gpu_id < 0 || gpu_id >= gpu_count) {
        gpu_id = ncnn::get_default_gpu_index();
    }

    const ncnn::VulkanDevice* vkdev =
        ncnn::get_gpu_device(gpu_id);

    if (vkdev) {
        const VkPhysicalDeviceProperties& props =
            vkdev->info.physicalDeviceProperties();

        gpu_name = props.deviceName;
        device_model = props.deviceName;

        std::ostringstream ver_stream;
        ver_stream
            << VK_VERSION_MAJOR(props.apiVersion)
            << "."
            << VK_VERSION_MINOR(props.apiVersion)
            << "."
            << VK_VERSION_PATCH(props.apiVersion);

        vulkan_api_version = ver_stream.str();

        LOGI_DEVICE(
            "Detected Vulkan GPU: %s, API %s, VendorID=0x%04x, DeviceID=0x%04x",
            gpu_name.c_str(),
            vulkan_api_version.c_str(),
            props.vendorID,
            props.deviceID
        );

        // Check for known problematic GPU: Mali-G310 crashes in glslang::GlslangToSpv()
        // during RIFE Vulkan shader compilation. Disable Vulkan for this device to avoid SIGSEGV.
        if (gpu_name.find("Mali-G310") != std::string::npos) {
            LOGW_DEVICE(
                "Mali-G310 detected - known to crash in glslang during RIFE Vulkan shader compilation. "
                "Disabling Vulkan and falling back to CPU mode."
            );
            vulkan_available = false;
            gpu_name.clear();
            vulkan_api_version.clear();
            device_profile = DeviceProfile::CPU_FALLBACK;
            last_error =
                "Mali-G310 GPU detected. Vulkan disabled due to known glslang crash. "
                "RIFE will use CPU fallback.";
            return true;
        }

        // Detect Vulkan capabilities
        detectVulkanCapabilities(vkdev);

        // Select device profile based on detected GPU and capabilities
        selectDeviceProfile();

        vulkan_available = true;
    } else {
        LOGE_ERROR(
            "Failed to obtain Vulkan device for GPU id %d",
            gpu_id
        );

        vulkan_available = false;
        gpu_name.clear();
        vulkan_api_version.clear();
        device_profile = DeviceProfile::CPU_FALLBACK;
    }

    LOGI_LIFECYCLE("RIFE init completed: vulkan=%s, profile=%d, gpu=%s",
                   vulkan_available ? "YES" : "NO",
                   static_cast<int>(device_profile),
                   gpu_name.c_str());

    return true;
}

bool RifeEngine::loadModelFromAssets(
    AAssetManager* mgr,
    const std::string& base_cache_dir,
    const std::string& model_dir,
    bool is_v2,
    bool is_v4
) {
    std::lock_guard<std::mutex> lock(mutex);
    model_loaded = false;
    last_error.clear();
    op_details.clear();
    last_inference_time_ms = -1;

    if (!mgr) {
        last_error = "AssetManager is null.";
        LOGE_ERROR("%s", last_error.c_str());
        return false;
    }

/*
     * The Android TV device previously crashed inside
     * glslang::GlslangToSpv() while RIFE::load() was building
     * Vulkan shader modules.
     *
     * Mali-G310 is detected in init() and Vulkan is disabled for it.
     * For other devices, we attempt Vulkan first (if available) and fall back to
     * CPU only if Vulkan initialization genuinely fails.
     */

    std::string target_dir =
        base_cache_dir + "/" + model_dir;

    std::string cmd =
        "mkdir -p \"" + target_dir + "\"";

    int mkdir_result = std::system(cmd.c_str());

    if (mkdir_result != 0) {
        LOGE_ERROR(
            "mkdir failed for model directory: %s "
            "(result=%d)",
            target_dir.c_str(),
            mkdir_result
        );

        last_error =
            "Failed to create model cache directory: " +
            target_dir;

        return false;
    }

    std::vector<std::string> files = {
        "flownet.param",
        "flownet.bin"
    };

    if (!is_v4) {
        files.push_back("contextnet.param");
        files.push_back("contextnet.bin");
        files.push_back("fusionnet.param");
        files.push_back("fusionnet.bin");
    }

    for (const auto& file_name : files) {
        std::string asset_path =
            model_dir + "/" + file_name;

        std::string output_path =
            target_dir + "/" + file_name;

        LOGI_LIFECYCLE(
            "Extracting model asset: %s",
            asset_path.c_str()
        );

        if (!extractAssetFile(
                mgr,
                asset_path,
                output_path
            )) {
            last_error =
                "Failed to extract asset: " +
                asset_path;

            model_loaded = false;
            return false;
        }
    }

    auto try_load_with_gpu = [&](int gpu_id, const char* backend_name) -> bool {
        try {
            rife_impl = std::make_unique<RIFE>(
                gpu_id,
                false, // tta_mode
                false, // tta_temporal_mode
                false, // uhd_mode
                1,     // num_threads
                is_v2,
                is_v4
            );

            LOGI_LIFECYCLE(
                "Calling RIFE::load() with %s (gpu_id=%d) from: %s",
                backend_name,
                gpu_id,
                target_dir.c_str()
            );

            int ret = rife_impl->load(target_dir);

            if (ret != 0) {
                last_error =
                    std::string("RIFE ") + backend_name + " load failed with error code: " +
                    std::to_string(ret);

LOGE_ERROR(
                "%s",
                last_error.c_str()
            );

                rife_impl.reset();
                return false;
            }

            model_loaded = true;

            op_details =
                std::string("RIFE model loaded with ") + backend_name + ".";

LOGI_LIFECYCLE(
            "RIFE model successfully loaded with %s from %s",
            backend_name,
            target_dir.c_str()
        );

            return true;

        } catch (const std::exception& e) {
            last_error =
                std::string("Exception during RIFE ") + backend_name + " load: " + e.what();

            LOGE_ERROR(
                "%s",
                last_error.c_str()
            );

            rife_impl.reset();
            return false;

        } catch (...) {
            last_error =
                std::string("Unknown exception during RIFE ") + backend_name + " load.";

            LOGE_ERROR(
                "%s",
                last_error.c_str()
            );

            rife_impl.reset();
            return false;
        }
    };

    int gpu_id_to_use = vulkan_available ? gpu_id : -1;
    const char* backend_name = vulkan_available ? "Vulkan" : "CPU fallback";

LOGI_LIFECYCLE(
            "Loading RIFE model with %s. Vulkan detected=%s, GPU id=%d",
            backend_name,
            vulkan_available ? "YES" : "NO",
            gpu_id_to_use
        );

    if (!try_load_with_gpu(gpu_id_to_use, backend_name)) {
        if (vulkan_available) {
            LOGW_LIFECYCLE(
                "Vulkan load failed, falling back to CPU mode. Error: %s",
                last_error.c_str()
            );
            last_error.clear();
            if (try_load_with_gpu(-1, "CPU fallback")) {
                vulkan_available = false;
                gpu_name.clear();
                vulkan_api_version.clear();
                return true;
            }
        }
        return false;
    }

    return true;
}

bool RifeEngine::processFrameBuffer(
    const uint8_t* in0_rgba,
    const uint8_t* in1_rgba,
    int src_w,
    int src_h,
    int target_w,
    int target_h,
    float timestep,
    uint8_t* out_rgba
) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!model_loaded || !rife_impl) {
        last_error =
            "RIFE model not loaded.";

        return false;
    }

    static bool logged_backend = false;
    if (!logged_backend) {
        LOGI_LIFECYCLE("RIFE backend=%s", vulkan_available ? "Vulkan" : "CPU");
        logged_backend = true;
    }

    if (!in0_rgba || !in1_rgba || !out_rgba) {
        last_error =
            "Invalid frame buffer pointer.";

        return false;
    }

    if (src_w <= 0 ||
        src_h <= 0 ||
        target_w <= 0 ||
        target_h <= 0) {

        last_error =
            "Invalid frame dimensions.";

        return false;
    }

    auto start =
        std::chrono::high_resolution_clock::now();

    ncnn::Mat in0_mat =
        ncnn::Mat::from_pixels_resize(
            in0_rgba,
            ncnn::Mat::PIXEL_RGBA2RGB,
            src_w,
            src_h,
            target_w,
            target_h
        );

    ncnn::Mat in1_mat =
        ncnn::Mat::from_pixels_resize(
            in1_rgba,
            ncnn::Mat::PIXEL_RGBA2RGB,
            src_w,
            src_h,
            target_w,
            target_h
        );

    if (in0_mat.empty() || in1_mat.empty()) {
        last_error =
            "Failed to create ncnn input matrices.";

        return false;
    }

    ncnn::Mat out_mat;

    int ret =
        rife_impl->process(
            in0_mat,
            in1_mat,
            timestep,
            out_mat
        );

    if (ret != 0 || out_mat.empty()) {
        last_error =
            std::string("RIFE ") + (vulkan_available ? "Vulkan" : "CPU") + " process failed with error: " +
            std::to_string(ret);

        LOGE_ERROR(
            "%s",
            last_error.c_str()
        );

        return false;
    }

    out_mat.to_pixels_resize(
        out_rgba,
        ncnn::Mat::PIXEL_RGB2RGBA,
        target_w,
        target_h
    );

    auto end =
        std::chrono::high_resolution_clock::now();

    last_inference_time_ms =
        std::chrono::duration_cast<
            std::chrono::milliseconds
        >(end - start).count();

    std::ostringstream ss;

    ss
        << (vulkan_available ? "Vulkan" : "CPU") << " frame processed ("
        << src_w
        << "x"
        << src_h
        << " -> RIFE "
        << target_w
        << "x"
        << target_h
        << ") in "
        << last_inference_time_ms
        << " ms";

    op_details = ss.str();

    return true;
}

bool RifeEngine::interpolateTest(
    int width,
    int height
) {
    if (!model_loaded || !rife_impl) {
        last_error =
            "RIFE model not loaded before running test.";

        return false;
    }

    if (width <= 0 || height <= 0) {
        last_error =
            "Invalid test dimensions.";

        return false;
    }

    std::vector<uint8_t> in0(
        static_cast<size_t>(width) *
        static_cast<size_t>(height) *
        4,
        0
    );

    std::vector<uint8_t> in1(
        static_cast<size_t>(width) *
        static_cast<size_t>(height) *
        4,
        255
    );

    std::vector<uint8_t> out(
        static_cast<size_t>(width) *
        static_cast<size_t>(height) *
        4,
        0
    );

    return processFrameBuffer(
        in0.data(),
        in1.data(),
        width,
        height,
        width,
        height,
        0.5f,
        out.data()
    );
}

RifeEngineResult RifeEngine::getStatus() const {
    std::lock_guard<std::mutex> lock(mutex);
    RifeEngineResult res;

    res.success =
        model_loaded &&
        (last_inference_time_ms >= 0);

    res.vulkan_available =
        vulkan_available;

    res.gpu_name =
        gpu_name;

    res.vulkan_api_version =
        vulkan_api_version;

    res.model_loaded =
        model_loaded;

    res.last_inference_time_ms =
        last_inference_time_ms;

    res.last_error =
        last_error;

    res.op_details =
        op_details;

    res.vulkan_caps = vulkan_caps;
    res.device_profile = device_profile;
    res.device_model = device_model;

    return res;
}

void RifeEngine::detectVulkanCapabilities(const ncnn::VulkanDevice* vkdev) {
    if (!vkdev) {
        LOGE_ERROR("detectVulkanCapabilities: vkdev is null");
        return;
    }

    const auto& info = vkdev->info;

    vulkan_caps.fp16_storage = info.support_fp16_storage();
    vulkan_caps.fp16_packed = info.support_fp16_packed();
    vulkan_caps.fp16_arithmetic = info.support_fp16_arithmetic();
    vulkan_caps.int8_storage = info.support_int8_storage();
    vulkan_caps.int8_packed = info.support_int8_packed();
    vulkan_caps.int8_arithmetic = info.support_int8_arithmetic();
    vulkan_caps.int16_storage = info.support_int16_storage();
    vulkan_caps.int16_arithmetic = info.support_int16_arithmetic();

    // shaderInt16 requires both int16 storage and arithmetic support
    vulkan_caps.shader_int16 = info.support_int16_storage() && info.support_int16_arithmetic();

    // shaderInt64 - check for 64-bit shader support via subgroup or cooperative matrix
    // Conservative: requires cooperative matrix or explicit 64-bit support
    vulkan_caps.shader_int64 = info.support_cooperative_matrix() ||
                               info.support_VK_KHR_shader_integer_dot_product();

    vulkan_caps.cooperative_matrix = info.support_cooperative_matrix();
    vulkan_caps.subgroup_size_control = info.support_subgroup_size_control();
    vulkan_caps.storage_buffer_16bit = info.support_VK_KHR_16bit_storage();
    vulkan_caps.uniform_storage_buffer_16bit = false; // info.support_VK_KHR_uniform_buffer_standard_layout() not available in this ncnn version

    LOGI_DEVICE("Vulkan capabilities detected:");
    LOGI_DEVICE("  fp16_storage=%s, fp16_packed=%s, fp16_arithmetic=%s",
                vulkan_caps.fp16_storage ? "YES" : "NO",
                vulkan_caps.fp16_packed ? "YES" : "NO",
                vulkan_caps.fp16_arithmetic ? "YES" : "NO");
    LOGI_DEVICE("  int8_storage=%s, int8_packed=%s, int8_arithmetic=%s",
                vulkan_caps.int8_storage ? "YES" : "NO",
                vulkan_caps.int8_packed ? "YES" : "NO",
                vulkan_caps.int8_arithmetic ? "YES" : "NO");
    LOGI_DEVICE("  int16_storage=%s, int16_arithmetic=%s, shader_int16=%s, shader_int64=%s",
                vulkan_caps.int16_storage ? "YES" : "NO",
                vulkan_caps.int16_arithmetic ? "YES" : "NO",
                vulkan_caps.shader_int16 ? "YES" : "NO",
                vulkan_caps.shader_int64 ? "YES" : "NO");
    LOGI_DEVICE("  cooperative_matrix=%s, subgroup_size_control=%s",
                vulkan_caps.cooperative_matrix ? "YES" : "NO",
                vulkan_caps.subgroup_size_control ? "YES" : "NO");
    LOGI_DEVICE("  storage_buffer_16bit=%s, uniform_storage_buffer_16bit=%s",
                vulkan_caps.storage_buffer_16bit ? "YES" : "NO",
                vulkan_caps.uniform_storage_buffer_16bit ? "YES" : "NO");
}

void RifeEngine::selectDeviceProfile() {
    // Try to identify specific device models from GPU name
    std::string gpu_lower = gpu_name;
    std::transform(gpu_lower.begin(), gpu_lower.end(), gpu_lower.begin(), ::tolower);

    // Xiaomi TV Box S 3rd Gen - typically Mali-G310 or similar low-end GPU
    // Also check device model if available via system properties
    if (gpu_lower.find("mali-g310") != std::string::npos) {
        device_profile = DeviceProfile::XIAOMI_TV_BOX_S_3RD_GEN;
        LOGI_DEVICE("Device profile selected: XIAOMI_TV_BOX_S_3RD_GEN (Mali-G310 detected)");
        return;
    }

    // Poco F7 - would typically have Adreno 740/750 or high-end Mali
    // Check for high-end GPU indicators
    if (gpu_lower.find("adreno 7") != std::string::npos ||
        gpu_lower.find("mali-g7") != std::string::npos ||
        gpu_lower.find("immortalis") != std::string::npos) {
        // Additional check for high performance score
        const ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(gpu_id);
        if (vkdev && vkdev->info.rough_score() >= 75) {
            device_profile = DeviceProfile::POCO_F7;
            LOGI_DEVICE("Device profile selected: POCO_F7 (high-end GPU detected, score=%u)",
                        vkdev->info.rough_score());
            return;
        }
    }

    // Generic profile selection based on capabilities and performance score
    const ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(gpu_id);
    uint32_t score = vkdev ? vkdev->info.rough_score() : 0;

    if (score >= 75 && vulkan_caps.fp16_storage && vulkan_caps.fp16_packed) {
        device_profile = DeviceProfile::GENERIC_HIGH_END;
        LOGI_DEVICE("Device profile selected: GENERIC_HIGH_END (score=%u)", score);
    } else if (score >= 25 && (vulkan_caps.fp16_storage || vulkan_caps.fp16_packed)) {
        device_profile = DeviceProfile::GENERIC_MID_RANGE;
        LOGI_DEVICE("Device profile selected: GENERIC_MID_RANGE (score=%u)", score);
    } else if (vulkan_available) {
        device_profile = DeviceProfile::GENERIC_LOW_END;
        LOGI_DEVICE("Device profile selected: GENERIC_LOW_END (score=%u)", score);
    } else {
        device_profile = DeviceProfile::CPU_FALLBACK;
        LOGI_DEVICE("Device profile selected: CPU_FALLBACK (Vulkan unavailable)");
    }
}

DeviceProfile RifeEngine::getDeviceProfile() const {
    std::lock_guard<std::mutex> lock(mutex);
    return device_profile;
}

const VulkanCapabilities& RifeEngine::getVulkanCapabilities() const {
    std::lock_guard<std::mutex> lock(mutex);
    return vulkan_caps;
}

std::string VulkanCapabilities::toString() const {
    std::ostringstream ss;
    ss << "VulkanCapabilities{"
       << "fp16_storage=" << (fp16_storage ? "YES" : "NO")
       << ", fp16_packed=" << (fp16_packed ? "YES" : "NO")
       << ", fp16_arithmetic=" << (fp16_arithmetic ? "YES" : "NO")
       << ", int8_storage=" << (int8_storage ? "YES" : "NO")
       << ", int8_packed=" << (int8_packed ? "YES" : "NO")
       << ", int8_arithmetic=" << (int8_arithmetic ? "YES" : "NO")
       << ", int16_storage=" << (int16_storage ? "YES" : "NO")
       << ", int16_arithmetic=" << (int16_arithmetic ? "YES" : "NO")
       << ", shader_int16=" << (shader_int16 ? "YES" : "NO")
       << ", shader_int64=" << (shader_int64 ? "YES" : "NO")
       << ", cooperative_matrix=" << (cooperative_matrix ? "YES" : "NO")
       << ", subgroup_size_control=" << (subgroup_size_control ? "YES" : "NO")
       << ", storage_buffer_16bit=" << (storage_buffer_16bit ? "YES" : "NO")
       << ", uniform_storage_buffer_16bit=" << (uniform_storage_buffer_16bit ? "YES" : "NO")
       << "}";
    return ss.str();
}
