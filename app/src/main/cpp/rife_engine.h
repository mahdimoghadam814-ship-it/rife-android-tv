#ifndef RIFE_ENGINE_H
#define RIFE_ENGINE_H

#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include <android/asset_manager.h>
#include "rife.h"

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

enum class DeviceProfile {
    UNKNOWN,
    POCO_F7,
    XIAOMI_TV_BOX_S_3RD_GEN,
    GENERIC_HIGH_END,
    GENERIC_MID_RANGE,
    GENERIC_LOW_END,
    CPU_FALLBACK
};

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

    RifeEngineResult getStatus() const;
    long getLastInferenceTimeMs() const {
        std::lock_guard<std::mutex> lock(mutex);
        return last_inference_time_ms;
    }

    // Device profile and capability accessors
    DeviceProfile getDeviceProfile() const;
    const VulkanCapabilities& getVulkanCapabilities() const;

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

    // Guards init/loadModelFromAssets/processFrameBuffer against concurrent calls from
    // different threads (e.g. the RIFE init thread and the frame-processing worker thread).
    mutable std::mutex mutex;

    // Private helpers
    void detectVulkanCapabilities(const ncnn::VulkanDevice* vkdev);
    void selectDeviceProfile();
    void logStructured(const char* tag, const char* fmt, ...) __attribute__((format(printf, 3, 4)));
};

#endif // RIFE_ENGINE_H
