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

// Private helper to create and load RIFE instance with given GPU ID
bool RifeEngine::createRifeInstance_locked(int gpu_id, const std::string& model_dir, bool is_v2, bool is_v4) {
    try {
        std::string gpu_name_local;
        const ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(gpu_id);
        if (vkdev) {
            const VkPhysicalDeviceProperties& props = vkdev->info.physicalDeviceProperties();
            gpu_name_local = props.deviceName;
        }

        rife_impl = std::make_unique<RIFE>(
            gpu_id,
            false, // tta_mode
            false, // tta_temporal_mode
            false, // uhd_mode
            1,     // num_threads
            is_v2,
            is_v4,
            gpu_name_local
        );

        // Apply centralized ncnn options from DevicePolicy
        if (device_policy_.isInitialized()) {
            const auto& opt_policy = device_policy_.ncnnOptionPolicy();
            rife_impl->setOptions(opt_policy);
            LOGI_LIFECYCLE("Applied centralized ncnn options: vulkan=%s threads=%d lightmode=%s",
                           opt_policy.use_vulkan_compute ? "YES" : "NO",
                           opt_policy.num_threads,
                           opt_policy.lightmode ? "YES" : "NO");
        }

        LOGI_LIFECYCLE(
            "Calling RIFE::load() with gpu_id=%d from: %s",
            gpu_id,
            model_dir.c_str()
        );

        int ret = rife_impl->load(model_dir);

        if (ret != 0) {
            last_error = std::string("RIFE load failed with error code: ") + std::to_string(ret);
            LOGE_ERROR("%s", last_error.c_str());
            rife_impl.reset();
            return false;
        }

        model_loaded = true;
        LOGI_LIFECYCLE("RIFE model successfully loaded with gpu_id=%d from %s", gpu_id, model_dir.c_str());
        return true;

    } catch (const std::exception& e) {
        last_error = std::string("Exception during RIFE load: ") + e.what();
        LOGE_ERROR("%s", last_error.c_str());
        rife_impl.reset();
        return false;
    } catch (...) {
        last_error = "Unknown exception during RIFE load.";
        LOGE_ERROR("%s", last_error.c_str());
        rife_impl.reset();
        return false;
    }
}

bool RifeEngine::init(int requested_gpu_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    gpu_id = requested_gpu_id;

    int gpu_count = ncnn::get_gpu_count();

    LOGI_DEVICE("ncnn reported GPU count: %d", gpu_count);

    if (gpu_count <= 0) {
        vulkan_available = false;
        gpu_name.clear();
        vulkan_api_version.clear();
        device_profile = DeviceProfile::CPU_FALLBACK;
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

        // Initialize DevicePolicy with the GPU - it handles all device detection,
        // capability assessment, backend selection, and profile assignment
        if (!device_policy_.initialize(gpu_id)) {
            LOGE_ERROR("DevicePolicy initialization failed");
            vulkan_available = false;
            gpu_name.clear();
            vulkan_api_version.clear();
            device_profile = DeviceProfile::CPU_FALLBACK;
            return true;
        }

        // Sync state from DevicePolicy
        vulkan_available = device_policy_.activeBackend() == rife::Backend::VULKAN;
        device_profile = device_policy_.deviceProfile();

        // Get fallback reason if Vulkan was disabled
        if (!vulkan_available) {
            last_error = device_policy_.backendPolicy().disable_reason;
            if (last_error.empty()) {
                last_error = "Vulkan disabled by device policy";
            }
        }

        LOGI_LIFECYCLE("RIFE init completed: vulkan=%s, profile=%d, gpu=%s",
                       vulkan_available ? "YES" : "NO",
                       static_cast<int>(device_profile),
                       gpu_name.c_str());

        return true;
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

    return true;
}

bool RifeEngine::loadModelFromAssets(
    AAssetManager* mgr,
    const std::string& base_cache_dir,
    const std::string& model_dir,
    bool is_v2,
    bool is_v4
) {
    std::lock_guard<std::mutex> lock(mutex_);
    return loadModelFromAssets_locked(mgr, base_cache_dir, model_dir, is_v2, is_v4);
}

bool RifeEngine::loadModelFromAssets_locked(
    AAssetManager* mgr,
    const std::string& base_cache_dir,
    const std::string& model_dir,
    bool is_v2,
    bool is_v4
) {
    model_loaded = false;
    last_error.clear();
    op_details.clear();
    last_inference_time_ms = -1;

    if (!mgr) {
        last_error = "AssetManager is null.";
        LOGE_ERROR("%s", last_error.c_str());
        return false;
    }

    // Cache model parameters for potential fallback/reload
    cached_base_cache_dir = base_cache_dir;
    cached_model_dir = model_dir;
    cached_is_v2 = is_v2;
    cached_is_v4 = is_v4;

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

    int gpu_id_to_use = vulkan_available ? gpu_id : -1;
    const char* backend_name = vulkan_available ? "Vulkan" : "CPU fallback";

    LOGI_LIFECYCLE(
        "Loading RIFE model with %s. Vulkan detected=%s, GPU id=%d",
        backend_name,
        vulkan_available ? "YES" : "NO",
        gpu_id_to_use
    );

    if (!createRifeInstance_locked(gpu_id_to_use, target_dir, is_v2, is_v4)) {
        if (vulkan_available) {
            LOGW_LIFECYCLE(
                "Vulkan load failed, falling back to CPU mode. Error: %s",
                last_error.c_str()
            );
            last_error.clear();
            // Synchronize DevicePolicy with CPU fallback state
            device_policy_.reinitializeForCPUFallback();
            // Sync engine state with DevicePolicy
            vulkan_available = false;
            device_profile = DeviceProfile::CPU_FALLBACK;
            // Preserve hardware identity (gpu_name, vulkan_api_version) for diagnostics
            if (createRifeInstance_locked(-1, target_dir, is_v2, is_v4)) {
                LOGI_LIFECYCLE("Vulkan load failed, CPU fallback initialized successfully");
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
    std::lock_guard<std::mutex> lock(mutex_);
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

    // Build resolution policy with actual input dimensions if not already built
    if (device_policy_.isInitialized() && device_policy_.resolutionPolicy().adaptive) {
        const auto& res_policy = device_policy_.resolutionPolicy();
        if (res_policy.source_width != src_w || res_policy.source_height != src_h) {
            device_policy_.buildResolutionPolicy(src_w, src_h);
        }
    }

    auto start =
        std::chrono::high_resolution_clock::now();

    // Use RIFE's internal target dimensions from DevicePolicy if available
    int effective_target_w = target_w;
    int effective_target_h = target_h;

    if (device_policy_.isInitialized() && device_policy_.resolutionPolicy().adaptive) {
        const auto& res_policy = device_policy_.resolutionPolicy();
        if (res_policy.target_width > 0 && res_policy.target_height > 0) {
            effective_target_w = res_policy.target_width;
            effective_target_h = res_policy.target_height;
        }
    }

    ncnn::Mat in0_mat =
        ncnn::Mat::from_pixels_resize(
            in0_rgba,
            ncnn::Mat::PIXEL_RGBA2RGB,
            src_w,
            src_h,
            effective_target_w,
            effective_target_h
        );

    ncnn::Mat in1_mat =
        ncnn::Mat::from_pixels_resize(
            in1_rgba,
            ncnn::Mat::PIXEL_RGBA2RGB,
            src_w,
            src_h,
            effective_target_w,
            effective_target_h
        );

    if (in0_mat.empty() || in1_mat.empty()) {
        last_error =
            "Failed to create ncnn input matrices.";

        return false;
    }

    ncnn::Mat out_mat(effective_target_w, effective_target_h, 3, ncnn::Mat::PIXEL_RGB);

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

        // Try deterministic fallback on process failure
        // Both Vulkan and CPU failures can trigger fallback
        if (tryFallback_locked()) {
            if (vulkan_available) {
                // Vulkan failure: fallback to CPU (model already reloaded by tryFallback_locked)
                LOGW_LIFECYCLE("RIFE Vulkan process failed, fell back to CPU. Retrying...");
            } else {
                // CPU failure: resolution fallback applied by tryFallback_locked
                LOGW_LIFECYCLE("RIFE CPU process failed, resolution fallback applied. Retrying...");
            }
            // Retry the process with updated state (vulkan_available may have changed)
            // Recompute effective target dimensions from updated policy
            if (device_policy_.isInitialized() && device_policy_.resolutionPolicy().adaptive) {
                const auto& res_policy = device_policy_.resolutionPolicy();
                if (res_policy.target_width > 0 && res_policy.target_height > 0) {
                    effective_target_w = res_policy.target_width;
                    effective_target_h = res_policy.target_height;
                }
            }
            // Resize input mats to new effective target if needed
            if (effective_target_w != target_w || effective_target_h != target_h) {
                in0_mat = ncnn::Mat::from_pixels_resize(
                    in0_rgba, ncnn::Mat::PIXEL_RGBA2RGB, src_w, src_h, effective_target_w, effective_target_h);
                in1_mat = ncnn::Mat::from_pixels_resize(
                    in1_rgba, ncnn::Mat::PIXEL_RGBA2RGB, src_w, src_h, effective_target_w, effective_target_h);
                out_mat = ncnn::Mat(effective_target_w, effective_target_h, 3, ncnn::Mat::PIXEL_RGB);
            }
            // Retry the process
            ret = rife_impl->process(in0_mat, in1_mat, timestep, out_mat);
            if (ret == 0 && !out_mat.empty()) {
                goto process_output;
            }
        }
        return false;
    }

process_output:
    // Copy output to caller's buffer - use PIXEL_RGB2RGBA to convert RGB to RGBA
    out_mat.to_pixels(
        out_rgba,
        ncnn::Mat::PIXEL_RGB2RGBA
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
        << effective_target_w
        << "x"
        << effective_target_h
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
    std::lock_guard<std::mutex> lock(mutex_);
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
    std::lock_guard<std::mutex> lock(mutex_);
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

long RifeEngine::getLastInferenceTimeMs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_inference_time_ms;
}

DeviceProfile RifeEngine::getDeviceProfile() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return device_profile;
}

VulkanCapabilities RifeEngine::getVulkanCapabilities() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return vulkan_caps;
}

bool RifeEngine::getActiveBackend() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return vulkan_available;
}

rife::NcnnOptionPolicy RifeEngine::getNcnnOptionPolicy() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (device_policy_.isInitialized()) {
        return device_policy_.ncnnOptionPolicy();
    }
    return rife::NcnnOptionPolicy{};
}

rife::ResolutionPolicy RifeEngine::getResolutionPolicy() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (device_policy_.isInitialized()) {
        return device_policy_.resolutionPolicy();
    }
    return rife::ResolutionPolicy{};
}

rife::MemoryPolicy RifeEngine::getMemoryPolicy() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (device_policy_.isInitialized()) {
        return device_policy_.memoryPolicy();
    }
    return rife::MemoryPolicy{};
}

bool RifeEngine::tryFallback() {
    std::lock_guard<std::mutex> lock(mutex_);
    return tryFallback_locked();
}

bool RifeEngine::tryFallback_locked() {
    LOGI_LIFECYCLE("RIFE deterministic fallback initiated");

    if (!vulkan_available) {
        // Already on CPU, try resolution fallback via DevicePolicy
        if (device_policy_.isInitialized() && device_policy_.hasFallback()) {
            // Apply the fallback resolution - this mutates the active target state
            if (device_policy_.applyFallbackResolution()) {
                auto fallback_res = device_policy_.getFallbackResolution();
                LOGI_LIFECYCLE("Resolution fallback applied: %dx%d -> %dx%d",
                               device_policy_.resolutionPolicy().target_width,
                               device_policy_.resolutionPolicy().target_height,
                               fallback_res.first, fallback_res.second);
                return true;
            }
        }
        return false;
    }

    // Destroy Vulkan RIFE resources
    unloadModel_locked();

    // Switch to CPU backend - PRESERVE hardware identity (gpu_name, vulkan_api_version)
    vulkan_available = false;
    device_profile = DeviceProfile::CPU_FALLBACK;

    // Re-initialize DevicePolicy for CPU-only mode using dedicated reinitialize method
    // This preserves hardware identity while switching backend state
    device_policy_.reinitializeForCPUFallback();

    // Reload model with CPU backend using cached parameters
    if (!cached_base_cache_dir.empty() && !cached_model_dir.empty()) {
        std::string target_dir = cached_base_cache_dir + "/" + cached_model_dir;
        if (createRifeInstance_locked(-1, target_dir, cached_is_v2, cached_is_v4)) {
            LOGI_LIFECYCLE("Deterministic fallback complete: Vulkan -> CPU. Model reloaded successfully.");
            return true;
        } else {
            LOGE_LIFECYCLE("Model reload failed after fallback");
            return false;
        }
    }

    LOGI_LIFECYCLE("Deterministic fallback complete: Vulkan -> CPU. No cached model params - caller must reload.");
    return true;
}

bool RifeEngine::unloadModel() {
    std::lock_guard<std::mutex> lock(mutex_);
    unloadModel_locked();
    return true;
}

void RifeEngine::unloadModel_locked() {
    if (rife_impl) {
        rife_impl.reset();
        model_loaded = false;
        LOGI_LIFECYCLE("RIFE model unloaded");
    }
}

bool RifeEngine::reloadModel(
    AAssetManager* mgr,
    const std::string& base_cache_dir,
    const std::string& model_dir,
    bool is_v2,
    bool is_v4
) {
    std::lock_guard<std::mutex> lock(mutex_);
    unloadModel_locked();
    return loadModelFromAssets_locked(mgr, base_cache_dir, model_dir, is_v2, is_v4);
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