#ifndef RIFE_ENGINE_H
#define RIFE_ENGINE_H

#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include <android/asset_manager.h>
#include "rife.h"
#include "device_policy.h"

struct VulkanCapabilities {
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

    std::string toString() const;
};

using DeviceProfile = rife::DeviceProfile;

struct RifeEngineResult {
    bool success;
    bool vulkan_available;
    std::string gpu_name;
    std::string vulkan_api_version;
    bool model_loaded;
    long last_inference_time_ms;
    std::string last_error;
    std::string op_details;
    VulkanCapabilities vulkan_caps;
    DeviceProfile device_profile = DeviceProfile::UNKNOWN;
    std::string device_model;
};

class RifeEngine {
public:
    RifeEngine();
    ~RifeEngine();

    bool init(int gpu_id = 0);
    bool loadModelFromAssets(AAssetManager* mgr, const std::string& base_cache_dir, const std::string& model_dir, bool is_v2 = true, bool is_v4 = false);
    bool processFrameBuffer(
        const uint8_t* in0_rgba, const uint8_t* in1_rgba,
        int src_w, int src_h,
        int target_w, int target_h,
        float timestep,
        uint8_t* out_rgba
    );
    bool interpolateTest(int width = 256, int height = 256);

    // Fallback lifecycle
    bool tryFallback();

    // Model lifecycle
    bool unloadModel();
    bool reloadModel(AAssetManager* mgr, const std::string& base_cache_dir, const std::string& model_dir, bool is_v2, bool is_v4);

    RifeEngineResult getStatus() const;
    long getLastInferenceTimeMs() const;

    // Device profile and capability accessors
    DeviceProfile getDeviceProfile() const;
    VulkanCapabilities getVulkanCapabilities() const;
    bool getActiveBackend() const;
    rife::NcnnOptionPolicy getNcnnOptionPolicy() const;
    rife::ResolutionPolicy getResolutionPolicy() const;
    rife::MemoryPolicy getMemoryPolicy() const;

private:
    int gpu_id;
    bool vulkan_available;
    std::string gpu_name;
    std::string vulkan_api_version;
    bool model_loaded;
    long last_inference_time_ms;
    std::string last_error;
    std::string op_details;
    VulkanCapabilities vulkan_caps;
    DeviceProfile device_profile = DeviceProfile::UNKNOWN;
    std::string device_model;

    std::unique_ptr<RIFE> rife_impl;
    rife::DevicePolicy device_policy_;

    // Cached model parameters for fallback/reload
    std::string cached_base_cache_dir;
    std::string cached_model_dir;
    bool cached_is_v2 = true;
    bool cached_is_v4 = false;

    // Guards init/loadModelFromAssets/processFrameBuffer against concurrent calls
    mutable std::mutex mutex_;

    // Private helpers - public methods acquire mutex, these assume it's held
    void logStructured(const char* tag, const char* fmt, ...) __attribute__((format(printf, 3, 4)));
    void unloadModel_locked();
    bool loadModelFromAssets_locked(AAssetManager* mgr, const std::string& base_cache_dir, const std::string& model_dir, bool is_v2, bool is_v4);
    bool createRifeInstance_locked(int gpu_id, const std::string& model_dir, bool is_v2, bool is_v4);
    bool tryFallback_locked();
};

#endif // RIFE_ENGINE_H
