#include "rife_engine.h"
#include "gpu.h"
#include <android/log.h>
#include <chrono>
#include <fstream>
#include <sstream>

#define LOG_TAG "RifeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool extractAssetFile(AAssetManager* mgr, const std::string& assetPath, const std::string& outFilePath) {
    AAsset* asset = AAssetManager_open(mgr, assetPath.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open asset file: %s", assetPath.c_str());
        return false;
    }

    size_t size = AAsset_getLength(asset);
    std::vector<char> buffer(size);
    int readBytes = AAsset_read(asset, buffer.data(), size);
    AAsset_close(asset);

    if (readBytes != size) {
        LOGE("Failed to read complete asset file: %s", assetPath.c_str());
        return false;
    }

    std::ofstream outFile(outFilePath, std::ios::binary);
    if (!outFile.is_open()) {
        LOGE("Failed to write output file: %s", outFilePath.c_str());
        return false;
    }

    outFile.write(buffer.data(), size);
    outFile.close();
    return true;
}

RifeEngine::RifeEngine()
    : gpu_id(0), vulkan_available(false), model_loaded(false),
      last_inference_time_ms(-1) {}

RifeEngine::~RifeEngine() {
    rife_impl.reset();
}

bool RifeEngine::init(int gpu_id) {
    this->gpu_id = gpu_id;
    int gpu_count = ncnn::get_gpu_count();
    if (gpu_count <= 0) {
        vulkan_available = false;
        last_error = "No Vulkan compatible GPU found.";
        return false;
    }

    vulkan_available = true;
    if (this->gpu_id < 0 || this->gpu_id >= gpu_count) {
        this->gpu_id = ncnn::get_default_gpu_index();
    }

    const ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(this->gpu_id);
    if (vkdev) {
        const VkPhysicalDeviceProperties& props = vkdev->info.physicalDeviceProperties();
        gpu_name = props.deviceName;

        std::ostringstream ver_stream;
        ver_stream << VK_VERSION_MAJOR(props.apiVersion) << "."
                   << VK_VERSION_MINOR(props.apiVersion) << "."
                   << VK_VERSION_PATCH(props.apiVersion);
        vulkan_api_version = ver_stream.str();
    }
    return true;
}

bool RifeEngine::loadModelFromAssets(AAssetManager* mgr, const std::string& model_dir, bool is_v2, bool is_v4) {
    if (!vulkan_available && !init(gpu_id)) {
        last_error = "Vulkan unavailable for RIFE model loading.";
        return false;
    }

    std::string target_dir = "/data/data/com.rife.androidtv/cache/" + model_dir;

    std::string cmd = "mkdir -p " + target_dir;
    system(cmd.c_str());

    std::vector<std::string> files = {
        "flownet.param", "flownet.bin"
    };
    if (!is_v4) {
        files.push_back("contextnet.param");
        files.push_back("contextnet.bin");
        files.push_back("fusionnet.param");
        files.push_back("fusionnet.bin");
    }

    for (const auto& f : files) {
        std::string assetPath = model_dir + "/" + f;
        std::string outPath = target_dir + "/" + f;
        if (!extractAssetFile(mgr, assetPath, outPath)) {
            last_error = "Failed to extract asset: " + assetPath;
            model_loaded = false;
            return false;
        }
    }

    try {
        rife_impl = std::make_unique<RIFE>(gpu_id, false, false, false, 1, is_v2, is_v4);
        int ret = rife_impl->load(target_dir);
        if (ret != 0) {
            last_error = "RIFE load failed with error code: " + std::to_string(ret);
            model_loaded = false;
            return false;
        }
        model_loaded = true;
        LOGI("RIFE model successfully loaded from %s", target_dir.c_str());
        return true;
    } catch (const std::exception& e) {
        last_error = std::string("Exception during RIFE load: ") + e.what();
        model_loaded = false;
        return false;
    }
}

bool RifeEngine::processFrameBuffer(
    const uint8_t* in0_rgba, const uint8_t* in1_rgba,
    int src_w, int src_h,
    int target_w, int target_h,
    float timestep,
    uint8_t* out_rgba
) {
    if (!model_loaded || !rife_impl) {
        last_error = "RIFE model not loaded.";
        return false;
    }

    auto start = std::chrono::high_resolution_clock::now();

    // Create ncnn::Mat from RGBA buffers
    ncnn::Mat in0_mat = ncnn::Mat::from_pixels_resize(
        in0_rgba, ncnn::Mat::PIXEL_RGBA2RGB,
        src_w, src_h, target_w, target_h
    );

    ncnn::Mat in1_mat = ncnn::Mat::from_pixels_resize(
        in1_rgba, ncnn::Mat::PIXEL_RGBA2RGB,
        src_w, src_h, target_w, target_h
    );

    ncnn::Mat out_mat;
    int ret = rife_impl->process(in0_mat, in1_mat, timestep, out_mat);

    if (ret != 0 || out_mat.empty()) {
        last_error = "RIFE process failed with error: " + std::to_string(ret);
        return false;
    }

    // Convert output ncnn::Mat back to RGBA buffer with dimensions target_w x target_h
    out_mat.to_pixels_resize(
        out_rgba, ncnn::Mat::PIXEL_RGB2RGBA,
        target_w, target_h
    );

    auto end = std::chrono::high_resolution_clock::now();
    last_inference_time_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

    std::ostringstream ss;
    ss << "Frame processed (" << src_w << "x" << src_h << " -> RIFE " << target_w << "x" << target_h
       << ") in " << last_inference_time_ms << " ms";
    op_details = ss.str();

    return true;
}

bool RifeEngine::interpolateTest(int width, int height) {
    if (!model_loaded || !rife_impl) {
        last_error = "RIFE model not loaded before running test.";
        return false;
    }

    std::vector<uint8_t> in0(width * height * 4, 0);
    std::vector<uint8_t> in1(width * height * 4, 255);
    std::vector<uint8_t> out(width * height * 4, 0);

    return processFrameBuffer(
        in0.data(), in1.data(),
        width, height,
        width, height,
        0.5f,
        out.data()
    );
}

RifeEngineResult RifeEngine::getStatus() const {
    RifeEngineResult res;
    res.success = vulkan_available && model_loaded && (last_inference_time_ms >= 0);
    res.vulkan_available = vulkan_available;
    res.gpu_name = gpu_name;
    res.vulkan_api_version = vulkan_api_version;
    res.model_loaded = model_loaded;
    res.last_inference_time_ms = last_inference_time_ms;
    res.last_error = last_error;
    res.op_details = op_details;
    return res;
}
