#include "device_policy.h"

#include <android/log.h>
#include <algorithm>
#include <sstream>
#include <cctype>

#define LOGI_DEVICE(...) __android_log_print(ANDROID_LOG_INFO, "RIFE-DEVICE", __VA_ARGS__)
#define LOGW_DEVICE(...) __android_log_print(ANDROID_LOG_WARN, "RIFE-DEVICE", __VA_ARGS__)
#define LOGE_DEVICE(...) __android_log_print(ANDROID_LOG_ERROR, "RIFE-DEVICE", __VA_ARGS__)

namespace rife {

bool DevicePolicy::initialize(int requested_gpu_id) {
    if (initialized_) {
        return true;
    }

    gpu_id_ = requested_gpu_id;
    detectGpuIdentity(gpu_id_);

    int gpu_count = ncnn::get_gpu_count();
    LOGI_DEVICE("ncnn reported GPU count: %d", gpu_count);

    if (gpu_count <= 0) {
        backend_policy_.vulkan_allowed = false;
        backend_policy_.primary_backend = Backend::CPU;
        backend_policy_.disable_reason = "No Vulkan compatible GPU found";
        device_profile_ = DeviceProfile::CPU_FALLBACK;
        active_backend_ = Backend::CPU;
        initialized_ = true;
        LOGI_DEVICE("No GPU available, using CPU fallback");
        return true;
    }

    if (gpu_id_ < 0 || gpu_id_ >= gpu_count) {
        gpu_id_ = ncnn::get_default_gpu_index();
    }

    const ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(gpu_id_);
    if (!vkdev) {
        backend_policy_.vulkan_allowed = false;
        backend_policy_.primary_backend = Backend::CPU;
        backend_policy_.disable_reason = "Failed to obtain Vulkan device";
        device_profile_ = DeviceProfile::CPU_FALLBACK;
        active_backend_ = Backend::CPU;
        initialized_ = true;
        LOGI_DEVICE("Failed to get Vulkan device, using CPU fallback");
        return true;
    }

    detectVulkanCapabilities(vkdev);

    std::string problematic_reason;
    if (isKnownProblematicGpu(identity_, problematic_reason)) {
        backend_policy_.vulkan_allowed = false;
        backend_policy_.primary_backend = Backend::CPU;
        backend_policy_.disable_reason = problematic_reason;
        LOGW_DEVICE("%s", problematic_reason.c_str());
    } else {
        backend_policy_.vulkan_allowed = true;
        backend_policy_.primary_backend = Backend::VULKAN;
        backend_policy_.disable_reason.clear();
    }

    active_backend_ = backend_policy_.vulkan_allowed ? Backend::VULKAN : Backend::CPU;

    selectDeviceProfile();
    buildNcnnOptions();
    buildResolutionLadder();

    initialized_ = true;

    LOGI_DEVICE("RIFE init completed: vulkan=%s, profile=%d, gpu=%s, backend=%s",
                backend_policy_.vulkan_allowed ? "YES" : "NO",
                static_cast<int>(device_profile_),
                identity_.gpu_name.c_str(),
                active_backend_ == Backend::VULKAN ? "Vulkan" : "CPU");

    return true;
}

void DevicePolicy::reinitializeForCPUFallback() {
    // Reset backend state for CPU-only fallback
    // Preserve hardware identity (gpu_name, vendor_id, device_id, etc.)
    backend_policy_.vulkan_allowed = false;
    backend_policy_.primary_backend = Backend::CPU;
    backend_policy_.disable_reason = "Fallback to CPU after Vulkan failure";
    
    active_backend_ = Backend::CPU;
    device_profile_ = DeviceProfile::CPU_FALLBACK;
    
    // Rebuild options for CPU-only mode
    buildNcnnOptions();
    buildResolutionLadder();
    
    LOGI_DEVICE("DevicePolicy reinitialized for CPU fallback: profile=%d, backend=%s, gpu=%s",
                static_cast<int>(device_profile_),
                "CPU",
                identity_.gpu_name.c_str());
}

void DevicePolicy::detectGpuIdentity(int gpu_id) {
    const ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(gpu_id);
    if (!vkdev) {
        return;
    }

    const VkPhysicalDeviceProperties& props = vkdev->info.physicalDeviceProperties();

    identity_.gpu_name = props.deviceName;
    identity_.device_model = props.deviceName;
    identity_.vendor_id = props.vendorID;
    identity_.device_id = props.deviceID;
    identity_.gpu_type = props.deviceType;

    std::ostringstream ver_stream;
    ver_stream << VK_VERSION_MAJOR(props.apiVersion) << "."
               << VK_VERSION_MINOR(props.apiVersion) << "."
               << VK_VERSION_PATCH(props.apiVersion);
    identity_.vulkan_api_version = ver_stream.str();

    LOGI_DEVICE("Detected Vulkan GPU: %s, API %s, VendorID=0x%04x, DeviceID=0x%04x, Type=%d",
                identity_.gpu_name.c_str(),
                identity_.vulkan_api_version.c_str(),
                identity_.vendor_id,
                identity_.device_id,
                identity_.gpu_type);
}

void DevicePolicy::detectVulkanCapabilities(const ncnn::VulkanDevice* vkdev) {
    if (!vkdev) {
        return;
    }

    const auto& info = vkdev->info;

    vulkan_caps_.fp16_storage = info.support_fp16_storage();
    vulkan_caps_.fp16_packed = info.support_fp16_packed();
    vulkan_caps_.fp16_arithmetic = info.support_fp16_arithmetic();
    vulkan_caps_.int8_storage = info.support_int8_storage();
    vulkan_caps_.int8_packed = info.support_int8_packed();
    vulkan_caps_.int8_arithmetic = info.support_int8_arithmetic();
    vulkan_caps_.int16_storage = info.support_int16_storage();
    vulkan_caps_.int16_arithmetic = info.support_int16_arithmetic();
    vulkan_caps_.shader_int16 = info.support_int16_storage() && info.support_int16_arithmetic();
    vulkan_caps_.shader_int64 = info.support_cooperative_matrix() ||
                                 info.support_VK_KHR_shader_integer_dot_product();
    vulkan_caps_.cooperative_matrix = info.support_cooperative_matrix();
    vulkan_caps_.subgroup_size_control = info.support_subgroup_size_control();
    vulkan_caps_.storage_buffer_16bit = info.support_VK_KHR_16bit_storage();
    vulkan_caps_.uniform_storage_buffer_16bit = false;
    vulkan_caps_.rough_score = info.rough_score();

    LOGI_DEVICE("Vulkan capabilities detected:");
    LOGI_DEVICE("  fp16_storage=%s, fp16_packed=%s, fp16_arithmetic=%s",
                vulkan_caps_.fp16_storage ? "YES" : "NO",
                vulkan_caps_.fp16_packed ? "YES" : "NO",
                vulkan_caps_.fp16_arithmetic ? "YES" : "NO");
    LOGI_DEVICE("  int8_storage=%s, int8_packed=%s, int8_arithmetic=%s",
                vulkan_caps_.int8_storage ? "YES" : "NO",
                vulkan_caps_.int8_packed ? "YES" : "NO",
                vulkan_caps_.int8_arithmetic ? "YES" : "NO");
    LOGI_DEVICE("  cooperative_matrix=%s, subgroup_size_control=%s, score=%u",
                vulkan_caps_.cooperative_matrix ? "YES" : "NO",
                vulkan_caps_.subgroup_size_control ? "YES" : "NO",
                vulkan_caps_.rough_score);
}

void DevicePolicy::selectDeviceProfile() {
    std::string gpu_lower = identity_.gpu_name;
    std::transform(gpu_lower.begin(), gpu_lower.end(), gpu_lower.begin(), ::tolower);

    if (isMaliG310(identity_.gpu_name)) {
        device_profile_ = DeviceProfile::XIAOMI_TV_BOX_S_3RD_GEN;
        LOGI_DEVICE("Device profile selected: XIAOMI_TV_BOX_S_3RD_GEN (Mali-G310 detected)");
        return;
    }

    if (isAdreno825(identity_.vendor_id, identity_.device_id)) {
        device_profile_ = DeviceProfile::POCO_F7;
        LOGI_DEVICE("Device profile selected: POCO_F7 (Adreno 825 detected)");
        return;
    }

    if (gpu_lower.find("adreno 8") != std::string::npos) {
        if (vulkan_caps_.rough_score >= 75) {
            device_profile_ = DeviceProfile::GENERIC_HIGH_END;
            LOGI_DEVICE("Device profile selected: GENERIC_HIGH_END (Adreno 8xx, score=%u)",
                        vulkan_caps_.rough_score);
        } else {
            device_profile_ = DeviceProfile::GENERIC_MID_RANGE;
            LOGI_DEVICE("Device profile selected: GENERIC_MID_RANGE (Adreno 8xx, score=%u)",
                        vulkan_caps_.rough_score);
        }
        return;
    }

    if (gpu_lower.find("adreno 7") != std::string::npos ||
        gpu_lower.find("mali-g7") != std::string::npos ||
        gpu_lower.find("immortalis") != std::string::npos) {
        if (vulkan_caps_.rough_score >= 75) {
            device_profile_ = DeviceProfile::GENERIC_HIGH_END;
            LOGI_DEVICE("Device profile selected: GENERIC_HIGH_END (score=%u)", vulkan_caps_.rough_score);
            return;
        }
    }

    if (vulkan_caps_.rough_score >= 75 && vulkan_caps_.fp16_storage && vulkan_caps_.fp16_packed) {
        device_profile_ = DeviceProfile::GENERIC_HIGH_END;
        LOGI_DEVICE("Device profile selected: GENERIC_HIGH_END (score=%u)", vulkan_caps_.rough_score);
    } else if (vulkan_caps_.rough_score >= 25 && (vulkan_caps_.fp16_storage || vulkan_caps_.fp16_packed)) {
        device_profile_ = DeviceProfile::GENERIC_MID_RANGE;
        LOGI_DEVICE("Device profile selected: GENERIC_MID_RANGE (score=%u)", vulkan_caps_.rough_score);
    } else if (backend_policy_.vulkan_allowed) {
        device_profile_ = DeviceProfile::GENERIC_LOW_END;
        LOGI_DEVICE("Device profile selected: GENERIC_LOW_END (score=%u)", vulkan_caps_.rough_score);
    } else {
        device_profile_ = DeviceProfile::CPU_FALLBACK;
        LOGI_DEVICE("Device profile selected: CPU_FALLBACK (Vulkan unavailable)");
    }
}

void DevicePolicy::applyDeviceProfilePolicies() {
    switch (device_profile_) {
        case DeviceProfile::XIAOMI_TV_BOX_S_3RD_GEN:
            // 2 GB TV box: keep only the genuinely memory-saving options. The fast
            // convolution/layout paths must stay enabled - disabling packing_layout made
            // ncnn use its unpacked pack1 shaders, and disabling winograd AND sgemm left
            // only the direct convolution, which together cost ~53 s per 1920x960 frame
            // on Mali-G310 (measured GPU-bound: worker thread used 18% of one core).
            // Packing costs no extra memory; winograd/sgemm cost some workspace but are
            // the difference between unusable and usable frame times.
            memory_policy_.lightmode = true;
            memory_policy_.disable_winograd = false;
            memory_policy_.disable_sgemm = false;
            memory_policy_.disable_packing_layout = false;
            memory_policy_.disable_int8 = true;
            memory_policy_.disable_winograd_variants = false;
            memory_policy_.disable_bf16 = true;
            memory_policy_.disable_shader_local_memory = false;
            memory_policy_.num_threads = 2;
            memory_policy_.max_interpolation_width = 1920;
            memory_policy_.max_interpolation_height = 1080;
            break;

        case DeviceProfile::POCO_F7:
            memory_policy_.lightmode = false;
            memory_policy_.num_threads = 4;
            break;

        case DeviceProfile::GENERIC_HIGH_END:
            memory_policy_.lightmode = false;
            memory_policy_.num_threads = 4;
            break;

        case DeviceProfile::GENERIC_MID_RANGE:
            memory_policy_.lightmode = false;
            memory_policy_.num_threads = 2;
            break;

        case DeviceProfile::GENERIC_LOW_END:
            memory_policy_.lightmode = true;
            memory_policy_.num_threads = 1;
            break;

        case DeviceProfile::CPU_FALLBACK:
            memory_policy_.lightmode = true;
            memory_policy_.num_threads = 1;
            break;

        default:
            memory_policy_.lightmode = false;
            memory_policy_.num_threads = 1;
            break;
    }
}

void DevicePolicy::applyBackendSpecificOptions() {
    if (active_backend_ == Backend::VULKAN && backend_policy_.vulkan_allowed) {
        ncnn_option_policy_.use_vulkan_compute = true;
        ncnn_option_policy_.use_fp16_packed = vulkan_caps_.fp16_packed;
        ncnn_option_policy_.use_fp16_storage = vulkan_caps_.fp16_storage;
        ncnn_option_policy_.use_fp16_arithmetic = vulkan_caps_.fp16_arithmetic;
        ncnn_option_policy_.use_int8_storage = vulkan_caps_.int8_storage;
        ncnn_option_policy_.use_int8_packed = vulkan_caps_.int8_packed;
        ncnn_option_policy_.use_int8_arithmetic = vulkan_caps_.int8_arithmetic;
        ncnn_option_policy_.use_cooperative_matrix = false;
        ncnn_option_policy_.use_shader_local_memory = vulkan_caps_.storage_buffer_16bit;
    } else {
        ncnn_option_policy_.use_vulkan_compute = false;
        ncnn_option_policy_.use_fp16_packed = false;
        ncnn_option_policy_.use_fp16_storage = false;
        ncnn_option_policy_.use_fp16_arithmetic = false;
        ncnn_option_policy_.use_int8_storage = false;
        ncnn_option_policy_.use_int8_packed = false;
        ncnn_option_policy_.use_int8_arithmetic = false;
        ncnn_option_policy_.use_cooperative_matrix = false;
        ncnn_option_policy_.use_shader_local_memory = false;
    }
}

void DevicePolicy::applyMemoryProfileOptions() {
    if (memory_policy_.disable_winograd) {
        ncnn_option_policy_.use_winograd_convolution = false;
    }
    if (memory_policy_.disable_sgemm) {
        ncnn_option_policy_.use_sgemm_convolution = false;
    }
    if (memory_policy_.disable_packing_layout) {
        ncnn_option_policy_.use_packing_layout = false;
    }
    if (memory_policy_.disable_int8) {
        ncnn_option_policy_.use_int8_storage = false;
        ncnn_option_policy_.use_int8_packed = false;
        ncnn_option_policy_.use_int8_arithmetic = false;
    }
    if (memory_policy_.disable_winograd_variants) {
        ncnn_option_policy_.use_winograd23_convolution = false;
        ncnn_option_policy_.use_winograd43_convolution = false;
        ncnn_option_policy_.use_winograd63_convolution = false;
    }
    if (memory_policy_.disable_bf16) {
        ncnn_option_policy_.use_bf16_storage = false;
        ncnn_option_policy_.use_bf16_packed = false;
    }
    if (memory_policy_.disable_shader_local_memory) {
        ncnn_option_policy_.use_shader_local_memory = false;
    }
    ncnn_option_policy_.lightmode = memory_policy_.lightmode;
    ncnn_option_policy_.num_threads = memory_policy_.num_threads;
}

void DevicePolicy::buildNcnnOptions() {
    ncnn_option_policy_ = NcnnOptionPolicy();
    applyDeviceProfilePolicies();
    applyBackendSpecificOptions();
    applyMemoryProfileOptions();

    LOGI_DEVICE("NCNN Option Policy:");
    LOGI_DEVICE("  use_vulkan_compute=%s, use_fp16_packed=%s, use_fp16_storage=%s, use_fp16_arithmetic=%s",
                ncnn_option_policy_.use_vulkan_compute ? "YES" : "NO",
                ncnn_option_policy_.use_fp16_packed ? "YES" : "NO",
                ncnn_option_policy_.use_fp16_storage ? "YES" : "NO",
                ncnn_option_policy_.use_fp16_arithmetic ? "YES" : "NO");
    LOGI_DEVICE("  use_int8_storage=%s, use_int8_packed=%s, use_int8_arithmetic=%s",
                ncnn_option_policy_.use_int8_storage ? "YES" : "NO",
                ncnn_option_policy_.use_int8_packed ? "YES" : "NO",
                ncnn_option_policy_.use_int8_arithmetic ? "YES" : "NO");
    LOGI_DEVICE("  use_cooperative_matrix=%s, use_winograd_convolution=%s, use_sgemm_convolution=%s",
                ncnn_option_policy_.use_cooperative_matrix ? "YES" : "NO",
                ncnn_option_policy_.use_winograd_convolution ? "YES" : "NO",
                ncnn_option_policy_.use_sgemm_convolution ? "YES" : "NO");
    LOGI_DEVICE("  use_packing_layout=%s, lightmode=%s, num_threads=%d",
                ncnn_option_policy_.use_packing_layout ? "YES" : "NO",
                ncnn_option_policy_.lightmode ? "YES" : "NO",
                ncnn_option_policy_.num_threads);
}

void DevicePolicy::buildResolutionLadder() {
    resolution_policy_.resolution_ladder.clear();

    switch (device_profile_) {
        case DeviceProfile::XIAOMI_TV_BOX_S_3RD_GEN:
            resolution_policy_.resolution_ladder = {{1920, 1080}, {1280, 720}, {960, 540}};
            resolution_policy_.adaptive = true;
            break;

        case DeviceProfile::POCO_F7:
            resolution_policy_.resolution_ladder = {{3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}};
            resolution_policy_.adaptive = true;
            break;

        case DeviceProfile::GENERIC_HIGH_END:
            resolution_policy_.resolution_ladder = {{3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}};
            resolution_policy_.adaptive = true;
            break;

        case DeviceProfile::GENERIC_MID_RANGE:
            resolution_policy_.resolution_ladder = {{2560, 1440}, {1920, 1080}, {1280, 720}};
            resolution_policy_.adaptive = true;
            break;

        case DeviceProfile::GENERIC_LOW_END:
            resolution_policy_.resolution_ladder = {{1920, 1080}, {1280, 720}, {960, 540}};
            resolution_policy_.adaptive = true;
            break;

        case DeviceProfile::CPU_FALLBACK:
            resolution_policy_.resolution_ladder = {{1920, 1080}, {1280, 720}, {960, 540}};
            resolution_policy_.adaptive = true;
            break;

        default:
            resolution_policy_.resolution_ladder = {{1920, 1080}, {1280, 720}};
            resolution_policy_.adaptive = false;
            break;
    }
}

void DevicePolicy::buildResolutionPolicy(int input_width, int input_height) {
    resolution_policy_.source_width = input_width;
    resolution_policy_.source_height = input_height;
    buildResolutionLadder();

    if (!resolution_policy_.adaptive || resolution_policy_.resolution_ladder.empty()) {
        resolution_policy_.target_width = input_width;
        resolution_policy_.target_height = input_height;
        return;
    }

    int max_w = memory_policy_.max_interpolation_width > 0 ? memory_policy_.max_interpolation_width : input_width;
    int max_h = memory_policy_.max_interpolation_height > 0 ? memory_policy_.max_interpolation_height : input_height;

    // Never upscale - target dimensions cannot exceed input dimensions
    max_w = std::min(max_w, input_width);
    max_h = std::min(max_h, input_height);

    float input_aspect = static_cast<float>(input_width) / static_cast<float>(input_height);

    for (const auto& res : resolution_policy_.resolution_ladder) {
        int ladder_w = std::min(res.first, input_width);
        int ladder_h = std::min(res.second, input_height);

        // Check if ladder entry fits within memory policy constraints
        if (ladder_w > max_w || ladder_h > max_h) {
            continue;
        }

        // Calculate target dimensions preserving aspect ratio, fitting within ladder entry
        int target_w = ladder_w;
        int target_h = static_cast<int>(std::round(target_w / input_aspect));

        // If height exceeds ladder_h, clamp by height instead
        if (target_h > ladder_h) {
            target_h = ladder_h;
            target_w = static_cast<int>(std::round(target_h * input_aspect));
        }

        // Ensure we don't exceed input dimensions (no upscaling)
        target_w = std::min(target_w, input_width);
        target_h = std::min(target_h, input_height);

        resolution_policy_.target_width = target_w;
        resolution_policy_.target_height = target_h;
        LOGI_DEVICE("Resolution policy: input=%dx%d -> target=%dx%d (ladder max %dx%d, aspect preserved)",
                    input_width, input_height,
                    resolution_policy_.target_width, resolution_policy_.target_height,
                    ladder_w, ladder_h);
        return;
    }

    // Fallback: use input dimensions if no ladder entry fits
    resolution_policy_.target_width = input_width;
    resolution_policy_.target_height = input_height;
    LOGI_DEVICE("Resolution policy: input=%dx%d -> target=%dx%d (no ladder entry fits, using input dims)",
                input_width, input_height,
                resolution_policy_.target_width, resolution_policy_.target_height);
}

bool DevicePolicy::isKnownProblematicGpu(const DeviceIdentity& id, std::string& reason) {
    if (isAdreno825(id.vendor_id, id.device_id)) {
        reason = "Adreno 825 GPU detected. Vulkan disabled due to known glslang crash in TIntermSelection traversal. RIFE will use CPU fallback.";
        return true;
    }

    return false;
}

bool DevicePolicy::isMaliG310(const std::string& gpu_name) {
    std::string lower = gpu_name;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    return lower.find("mali-g310") != std::string::npos;
}

bool DevicePolicy::isAdreno825(uint32_t vendor_id, uint32_t device_id) {
    return vendor_id == 0x5143 && (device_id & 0xFFFF0000) == 0x44030000;
}

bool DevicePolicy::hasFallback() const {
    if (active_backend_ == Backend::VULKAN) {
        return true;
    }
    if (active_backend_ == Backend::CPU && resolution_policy_.adaptive && !resolution_policy_.resolution_ladder.empty()) {
        auto current = std::make_pair(resolution_policy_.target_width, resolution_policy_.target_height);
        auto it = std::find(resolution_policy_.resolution_ladder.begin(), resolution_policy_.resolution_ladder.end(), current);
        if (it != resolution_policy_.resolution_ladder.end() && std::next(it) != resolution_policy_.resolution_ladder.end()) {
            return true;
        }
    }
    return false;
}

Backend DevicePolicy::getFallbackBackend() const {
    if (active_backend_ == Backend::VULKAN) {
        return Backend::CPU;
    }
    return Backend::CPU; // Already on CPU, no further backend fallback
}

std::pair<int, int> DevicePolicy::getFallbackResolution() const {
    if (active_backend_ == Backend::CPU && resolution_policy_.adaptive && !resolution_policy_.resolution_ladder.empty()) {
        auto current = std::make_pair(resolution_policy_.target_width, resolution_policy_.target_height);
        auto it = std::find(resolution_policy_.resolution_ladder.begin(), resolution_policy_.resolution_ladder.end(), current);
        if (it != resolution_policy_.resolution_ladder.end() && std::next(it) != resolution_policy_.resolution_ladder.end()) {
            return *std::next(it);
        }
    }
    return {resolution_policy_.target_width, resolution_policy_.target_height};
}

bool DevicePolicy::applyFallbackResolution() {
    if (active_backend_ != Backend::CPU || !resolution_policy_.adaptive || resolution_policy_.resolution_ladder.empty()) {
        return false;
    }
    auto current = std::make_pair(resolution_policy_.target_width, resolution_policy_.target_height);
    auto it = std::find(resolution_policy_.resolution_ladder.begin(), resolution_policy_.resolution_ladder.end(), current);
    if (it != resolution_policy_.resolution_ladder.end() && std::next(it) != resolution_policy_.resolution_ladder.end()) {
        auto fallback = *std::next(it);
        resolution_policy_.target_width = fallback.first;
        resolution_policy_.target_height = fallback.second;
        LOGI_DEVICE("Applied fallback resolution: %dx%d", fallback.first, fallback.second);
        return true;
    }
    return false;
}

} // namespace rife