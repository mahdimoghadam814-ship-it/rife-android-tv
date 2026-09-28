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

#define LOG_TAG "RifeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool extractAssetFile(
    AAssetManager* mgr,
    const std::string& assetPath,
    const std::string& outFilePath
) {
    if (!mgr) {
        LOGE("AssetManager is null");
        return false;
    }

    AAsset* asset = AAssetManager_open(
        mgr,
        assetPath.c_str(),
        AASSET_MODE_BUFFER
    );

    if (!asset) {
        LOGE("Failed to open asset file: %s", assetPath.c_str());
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
        LOGE(
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
        LOGE(
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
        LOGE(
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
      last_inference_time_ms(-1) {
}

RifeEngine::~RifeEngine() {
    rife_impl.reset();
}

bool RifeEngine::init(int requested_gpu_id) {
    std::lock_guard<std::mutex> lock(mutex);
    gpu_id = requested_gpu_id;

    int gpu_count = ncnn::get_gpu_count();

    LOGI("ncnn reported GPU count: %d", gpu_count);

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

        std::ostringstream ver_stream;
        ver_stream
            << VK_VERSION_MAJOR(props.apiVersion)
            << "."
            << VK_VERSION_MINOR(props.apiVersion)
            << "."
            << VK_VERSION_PATCH(props.apiVersion);

        vulkan_api_version = ver_stream.str();

        LOGI(
            "Detected Vulkan GPU: %s, API %s",
            gpu_name.c_str(),
            vulkan_api_version.c_str()
        );

        // Check for known problematic GPU: Mali-G310 crashes in glslang::GlslangToSpv()
        // during RIFE Vulkan shader compilation. Disable Vulkan for this device to avoid SIGSEGV.
        if (gpu_name.find("Mali-G310") != std::string::npos) {
            LOGW(
                "Mali-G310 detected - known to crash in glslang during RIFE Vulkan shader compilation. "
                "Disabling Vulkan and falling back to CPU mode."
            );
            vulkan_available = false;
            gpu_name.clear();
            vulkan_api_version.clear();
            last_error =
                "Mali-G310 GPU detected. Vulkan disabled due to known glslang crash. "
                "RIFE will use CPU fallback.";
            return true;
        }

        vulkan_available = true;
    } else {
        LOGE(
            "Failed to obtain Vulkan device for GPU id %d",
            gpu_id
        );

        vulkan_available = false;
        gpu_name.clear();
        vulkan_api_version.clear();
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
    std::lock_guard<std::mutex> lock(mutex);
    model_loaded = false;
    last_error.clear();
    op_details.clear();
    last_inference_time_ms = -1;

    if (!mgr) {
        last_error = "AssetManager is null.";
        LOGE("%s", last_error.c_str());
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
        LOGE(
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

        LOGI(
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

            LOGI(
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

                LOGE(
                    "%s",
                    last_error.c_str()
                );

                rife_impl.reset();
                return false;
            }

            model_loaded = true;

            op_details =
                std::string("RIFE model loaded with ") + backend_name + ".";

            LOGI(
                "RIFE model successfully loaded with %s from %s",
                backend_name,
                target_dir.c_str()
            );

            return true;

        } catch (const std::exception& e) {
            last_error =
                std::string("Exception during RIFE ") + backend_name + " load: " + e.what();

            LOGE(
                "%s",
                last_error.c_str()
            );

            rife_impl.reset();
            return false;

        } catch (...) {
            last_error =
                std::string("Unknown exception during RIFE ") + backend_name + " load.";

            LOGE(
                "%s",
                last_error.c_str()
            );

            rife_impl.reset();
            return false;
        }
    };

    int gpu_id_to_use = vulkan_available ? gpu_id : -1;
    const char* backend_name = vulkan_available ? "Vulkan" : "CPU fallback";

    LOGI(
        "Loading RIFE model with %s. Vulkan detected=%s, GPU id=%d",
        backend_name,
        vulkan_available ? "YES" : "NO",
        gpu_id_to_use
    );

    if (!try_load_with_gpu(gpu_id_to_use, backend_name)) {
        if (vulkan_available) {
            LOGW(
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
        LOGI("RIFE backend=%s", vulkan_available ? "Vulkan" : "CPU");
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

        LOGE(
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

    return res;
}
