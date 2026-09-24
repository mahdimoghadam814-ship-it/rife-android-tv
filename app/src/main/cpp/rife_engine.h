#ifndef RIFE_ENGINE_H
#define RIFE_ENGINE_H

#include <string>
#include <memory>
#include <vector>
#include <android/asset_manager.h>
#include "rife.h"

struct RifeEngineResult {
    bool success;
    bool vulkan_available;
    std::string gpu_name;
    std::string vulkan_api_version;
    bool model_loaded;
    long last_inference_time_ms;
    std::string last_error;
    std::string op_details;
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
    long getLastInferenceTimeMs() const { return last_inference_time_ms; }

private:
    int gpu_id;
    bool vulkan_available;
    std::string gpu_name;
    std::string vulkan_api_version;
    bool model_loaded;
    long last_inference_time_ms;
    std::string last_error;
    std::string op_details;

    std::unique_ptr<RIFE> rife_impl;
};

#endif // RIFE_ENGINE_H
