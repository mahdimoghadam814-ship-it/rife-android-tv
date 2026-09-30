#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <mutex>

#include "gpu.h"

namespace rife {

enum class Backend {
    VULKAN,
    CPU
};

enum class DeviceProfile {
    UNKNOWN,
    XIAOMI_TV_BOX_S_3RD_GEN,
    POCO_F7,
    GENERIC_HIGH_END,
    GENERIC_MID_RANGE,
    GENERIC_LOW_END,
    CPU_FALLBACK
};

struct VulkanCaps {
    bool fp16_storage = false;
    bool fp16_packed = false;
    bool fp16_arithmetic = false;
    bool int8_storage = false;
    bool int8_packed = false;
    bool int8_arithmetic = false;
    bool int16_storage = false;
    bool int16_arithmetic = false;
    bool shader_int16 = false;
    bool shader_int64 = false;
    bool cooperative_matrix = false;
    bool subgroup_size_control = false;
    bool storage_buffer_16bit = false;
    bool uniform_storage_buffer_16bit = false;
    uint32_t rough_score = 0;
};

struct DeviceIdentity {
    std::string gpu_name;
    std::string device_model;
    std::string vulkan_api_version;
    uint32_t vendor_id = 0;
    uint32_t device_id = 0;
    int gpu_type = 0;
};

struct BackendPolicy {
    Backend primary_backend = Backend::CPU;
    bool vulkan_allowed = false;
    std::string disable_reason;
};

struct MemoryPolicy {
    bool lightmode = false;
    bool disable_winograd = false;
    bool disable_sgemm = false;
    bool disable_packing_layout = false;
    bool disable_int8 = false;
    bool disable_winograd_variants = false;
    bool disable_bf16 = false;
    bool disable_shader_local_memory = false;
    int num_threads = 1;
    int max_interpolation_width = 0;
    int max_interpolation_height = 0;
};

struct NcnnOptionPolicy {
    bool use_vulkan_compute = false;
    bool use_fp16_packed = false;
    bool use_fp16_storage = false;
    bool use_fp16_arithmetic = false;
    bool use_int8_storage = false;
    bool use_int8_packed = false;
    bool use_int8_arithmetic = false;
    bool use_cooperative_matrix = false;
    bool use_winograd_convolution = true;
    bool use_sgemm_convolution = true;
    bool use_packing_layout = true;
    bool use_winograd23_convolution = true;
    bool use_winograd43_convolution = true;
    bool use_winograd63_convolution = true;
    bool use_bf16_storage = false;
    bool use_bf16_packed = false;
    bool use_shader_local_memory = false;
    bool lightmode = false;
    int num_threads = 1;
};

struct ResolutionPolicy {
    int target_width = 0;
    int target_height = 0;
    bool adaptive = false;
    std::vector<std::pair<int, int>> resolution_ladder;
    int source_width = 0;
    int source_height = 0;
};

class DevicePolicy {
public:
    DevicePolicy() = default;
    ~DevicePolicy() = default;

    // Non-copyable, movable
    DevicePolicy(const DevicePolicy&) = delete;
    DevicePolicy& operator=(const DevicePolicy&) = delete;
    DevicePolicy(DevicePolicy&&) = default;
    DevicePolicy& operator=(DevicePolicy&&) = default;

    bool initialize(int requested_gpu_id = 0);
    void reinitializeForCPUFallback();
    void buildResolutionPolicy(int input_width, int input_height);

    const DeviceIdentity& identity() const { return identity_; }
    const VulkanCaps& vulkanCaps() const { return vulkan_caps_; }
    const BackendPolicy& backendPolicy() const { return backend_policy_; }
    const MemoryPolicy& memoryPolicy() const { return memory_policy_; }
    const NcnnOptionPolicy& ncnnOptionPolicy() const { return ncnn_option_policy_; }
    const ResolutionPolicy& resolutionPolicy() const { return resolution_policy_; }
    DeviceProfile deviceProfile() const { return device_profile_; }
    Backend activeBackend() const { return active_backend_; }
    bool isInitialized() const { return initialized_; }
    const std::string& lastError() const { return last_error_; }

    // Fallback is handled by RifeEngine - DevicePolicy provides the next fallback state
    bool hasFallback() const;
    Backend getFallbackBackend() const;
    std::pair<int, int> getFallbackResolution() const;
    bool applyFallbackResolution(); // Apply the next lower resolution from the ladder

private:
    bool initialized_ = false;
    std::string last_error_;
    DeviceIdentity identity_;
    VulkanCaps vulkan_caps_;
    BackendPolicy backend_policy_;
    MemoryPolicy memory_policy_;
    NcnnOptionPolicy ncnn_option_policy_;
    ResolutionPolicy resolution_policy_;
    DeviceProfile device_profile_ = DeviceProfile::UNKNOWN;
    Backend active_backend_ = Backend::CPU;
    int gpu_id_ = 0;

    void detectGpuIdentity(int gpu_id);
    void detectVulkanCapabilities(const ncnn::VulkanDevice* vkdev);
    void selectDeviceProfile();
    void applyDeviceProfilePolicies();
    void applyBackendSpecificOptions();
    void applyMemoryProfileOptions();
    void buildNcnnOptions();
    void buildResolutionLadder();
    static bool isKnownProblematicGpu(const DeviceIdentity& id, std::string& reason);
    static bool isMaliG310(const std::string& gpu_name);
    static bool isAdreno825(uint32_t vendor_id, uint32_t device_id);
};

} // namespace rife