// rife implemented with ncnn library

#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RIFE-ERROR", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "RIFE-SPIRV", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RIFE-SPIRV", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "RIFE-DEBUG", __VA_ARGS__)

#include "rife.h"

#include <algorithm>
#include <vector>
#include <fstream>
#include <string>
#include <thread>
#include <atomic>
#include "benchmark.h"

static size_t getCurrentRssBytes() {
    std::ifstream status("/proc/self/status");
    std::string line;
    while (std::getline(status, line)) {
        if (line.rfind("VmRSS:", 0) == 0) {
            size_t kb = 0;
            size_t pos = line.find_first_of("0123456789");
            if (pos != std::string::npos) {
                kb = std::stoull(line.substr(pos));
            }
            return kb * 1024;
        }
    }
    return 0;
}

static void logRss(const char* stage, int frame = -1) {
    size_t rss_bytes = getCurrentRssBytes();
    double rss_mb = rss_bytes / (1024.0 * 1024.0);
    if (frame >= 0) {
        LOGI("RIFE-MEM frame=%d stage=%s rss=%.1f MB", frame, stage, rss_mb);
    } else {
        LOGI("RIFE-MEM stage=%s rss=%.1f MB", stage, rss_mb);
    }
}

class RssSampler {
public:
    RssSampler(int frame, const char* stage)
        : frame_(frame), stage_(stage), running_(false) {
        running_ = true;
        thread_ = std::thread([this]() {
            while (running_) {
                size_t rss_bytes = getCurrentRssBytes();
                double rss_mb = rss_bytes / (1024.0 * 1024.0);
                LOGI("RIFE-MEM-SAMPLE frame=%d stage=%s rss=%.1f MB", frame_, stage_, rss_mb);
                std::this_thread::sleep_for(std::chrono::milliseconds(300));
            }
        });
    }

    ~RssSampler() {
        running_ = false;
        if (thread_.joinable()) {
            thread_.join();
        }
    }

private:
    int frame_;
    const char* stage_;
    std::atomic<bool> running_;
    std::thread thread_;
};

static int g_rife_frame_counter = 0;

#include "rife_preproc.comp.hex.h"
#include "rife_postproc.comp.hex.h"
#include "rife_preproc_tta.comp.hex.h"
#include "rife_postproc_tta.comp.hex.h"
#include "rife_flow_tta_avg.comp.hex.h"
#include "rife_v2_flow_tta_avg.comp.hex.h"
#include "rife_v4_flow_tta_avg.comp.hex.h"
#include "rife_flow_tta_temporal_avg.comp.hex.h"
#include "rife_v2_flow_tta_temporal_avg.comp.hex.h"
#include "rife_v4_flow_tta_temporal_avg.comp.hex.h"
#include "rife_out_tta_temporal_avg.comp.hex.h"
#include "rife_v4_timestep.comp.hex.h"
#include "rife_v4_timestep_tta.comp.hex.h"

// Precompiled SPIR-V headers (avoid runtime glslang compilation)
#include "rife_preproc.comp.spv.h"
#include "rife_postproc.comp.spv.h"
#include "rife_preproc_tta.comp.spv.h"
#include "rife_postproc_tta.comp.spv.h"
#include "rife_flow_tta_avg.comp.spv.h"
#include "rife_v2_flow_tta_avg.comp.spv.h"
#include "rife_v4_flow_tta_avg.comp.spv.h"
#include "rife_flow_tta_temporal_avg.comp.spv.h"
#include "rife_v2_flow_tta_temporal_avg.comp.spv.h"
#include "rife_v4_flow_tta_temporal_avg.comp.spv.h"
#include "rife_out_tta_temporal_avg.comp.spv.h"
#include "rife_v4_timestep.comp.spv.h"
#include "rife_v4_timestep_tta.comp.spv.h"

#include "rife_ops.h"

DEFINE_LAYER_CREATOR(Warp)

RIFE::RIFE(int gpuid, bool _tta_mode, bool _tta_temporal_mode, bool _uhd_mode, int _num_threads, bool _rife_v2, bool _rife_v4)
{
    vkdev = gpuid == -1 ? 0 : ncnn::get_gpu_device(gpuid);

    rife_preproc = 0;
    rife_postproc = 0;
    rife_flow_tta_avg = 0;
    rife_flow_tta_temporal_avg = 0;
    rife_out_tta_temporal_avg = 0;
    rife_v4_timestep = 0;
    rife_uhd_downscale_image = 0;
    rife_uhd_upscale_flow = 0;
    rife_uhd_double_flow = 0;
    rife_v2_slice_flow = 0;
    tta_mode = _tta_mode;
    tta_temporal_mode = _tta_temporal_mode;
    uhd_mode = _uhd_mode;
    num_threads = _num_threads;
    rife_v2 = _rife_v2;
    rife_v4 = _rife_v4;
}

RIFE::~RIFE()
{
    // cleanup preprocess and postprocess pipeline
    {
        delete rife_preproc;
        delete rife_postproc;
        delete rife_flow_tta_avg;
        delete rife_flow_tta_temporal_avg;
        delete rife_out_tta_temporal_avg;
        delete rife_v4_timestep;
    }

    if (uhd_mode)
    {
        rife_uhd_downscale_image->destroy_pipeline(flownet.opt);
        delete rife_uhd_downscale_image;

        rife_uhd_upscale_flow->destroy_pipeline(flownet.opt);
        delete rife_uhd_upscale_flow;

        rife_uhd_double_flow->destroy_pipeline(flownet.opt);
        delete rife_uhd_double_flow;
    }

    if (rife_v2)
    {
        rife_v2_slice_flow->destroy_pipeline(flownet.opt);
        delete rife_v2_slice_flow;
    }
}

#if _WIN32
static int load_param_model(ncnn::Net& net, const std::wstring& modeldir, const wchar_t* name)
{
    wchar_t parampath[256];
    wchar_t modelpath[256];
    swprintf(parampath, 256, L"%s/%s.param", modeldir.c_str(), name);
    swprintf(modelpath, 256, L"%s/%s.bin", modeldir.c_str(), name);

    fwprintf(stderr, L"RIFE: loading %ls\n", name);
    fwprintf(stderr, L"RIFE: param=%ls\n", parampath);
    fwprintf(stderr, L"RIFE: model=%ls\n", modelpath);

    FILE* fp = _wfopen(parampath, L"rb");
    if (!fp)
    {
        fwprintf(stderr, L"RIFE: ERROR opening %ls\n", parampath);
        return -1;
    }

    const int param_ret = net.load_param(fp);
    fclose(fp);

    if (param_ret != 0)
    {
        fwprintf(stderr, L"RIFE: ERROR loading %ls.param, return=%d\n", name, param_ret);
        return param_ret;
    }

    fwprintf(stderr, L"RIFE: %ls.param loaded successfully\n", name);

    fp = _wfopen(modelpath, L"rb");
    if (!fp)
    {
        fwprintf(stderr, L"RIFE: ERROR opening %ls\n", modelpath);
        return -2;
    }

    const int model_ret = net.load_model(fp);
    fclose(fp);

    if (model_ret != 0)
    {
        fwprintf(stderr, L"RIFE: ERROR loading %ls.bin, return=%d\n", name, model_ret);
        return model_ret;
    }

    fwprintf(stderr, L"RIFE: %ls.bin loaded successfully\n", name);

    return 0;
}
#else
static int load_param_model(ncnn::Net& net, const std::string& modeldir, const char* name)
{
    char parampath[256];
    char modelpath[256];
    sprintf(parampath, "%s/%s.param", modeldir.c_str(), name);
    sprintf(modelpath, "%s/%s.bin", modeldir.c_str(), name);

    fprintf(stderr, "RIFE: loading %s\n", name);
    fprintf(stderr, "RIFE: param=%s\n", parampath);
    fprintf(stderr, "RIFE: model=%s\n", modelpath);

    FILE* fp = fopen(parampath, "rb");
    if (!fp)
    {
        fprintf(stderr, "RIFE: ERROR opening %s\n", parampath);
        return -1;
    }

    const int param_ret = net.load_param(fp);
    fclose(fp);

    if (param_ret != 0)
    {
        fprintf(stderr, "RIFE: ERROR loading %s.param, return=%d\n", name, param_ret);
        return param_ret;
    }

    fprintf(stderr, "RIFE: %s.param loaded successfully\n", name);

    fp = fopen(modelpath, "rb");
    if (!fp)
    {
        fprintf(stderr, "RIFE: ERROR opening %s\n", modelpath);
        return -2;
    }

    const int model_ret = net.load_model(fp);
    fclose(fp);

    if (model_ret != 0)
    {
        fprintf(stderr, "RIFE: ERROR loading %s.bin, return=%d\n", name, model_ret);
        return model_ret;
    }

    fprintf(stderr, "RIFE: %s.bin loaded successfully\n", name);

    return 0;
}
#endif

#if _WIN32
int RIFE::load(const std::wstring& modeldir)
#else
int RIFE::load(const std::string& modeldir)
#endif
{
    ncnn::Option opt;
    opt.num_threads = num_threads;
    opt.use_vulkan_compute = vkdev ? true : false;
    opt.use_fp16_packed = vkdev ? true : false;
    opt.use_fp16_storage = vkdev ? true : false;
    opt.use_fp16_arithmetic = false;
    opt.use_int8_storage = true;

    flownet.opt = opt;
    contextnet.opt = opt;
    fusionnet.opt = opt;

    flownet.set_vulkan_device(vkdev);
    contextnet.set_vulkan_device(vkdev);
    fusionnet.set_vulkan_device(vkdev);

    flownet.register_custom_layer("rife.Warp", Warp_layer_creator);
    contextnet.register_custom_layer("rife.Warp", Warp_layer_creator);
    fusionnet.register_custom_layer("rife.Warp", Warp_layer_creator);

#if _WIN32
    load_param_model(flownet, modeldir, L"flownet");
    if (!rife_v4)
    {
        load_param_model(contextnet, modeldir, L"contextnet");
        load_param_model(fusionnet, modeldir, L"fusionnet");
    }
#else
    load_param_model(flownet, modeldir, "flownet");
    if (!rife_v4)
    {
        load_param_model(contextnet, modeldir, "contextnet");
        load_param_model(fusionnet, modeldir, "fusionnet");
    }
#endif

    logRss("after_model_load");

    // Helper to validate precompiled SPIR-V data
    auto validate_spirv = [&](const char* shader_name, const uint32_t* data, size_t size) -> bool {
        if (!data || size == 0) {
            LOGE("RIFE-SPIRV: Missing precompiled SPIR-V for %s (size=%zu)", shader_name, size);
            return false;
        }
        // Check SPIR-V magic number (first word should be 0x07230203)
        if (size >= sizeof(uint32_t) && data[0] != 0x07230203u) {
            LOGE("RIFE-SPIRV: Invalid SPIR-V magic for %s: 0x%08x", shader_name, data[0]);
            return false;
        }
        return true;
    };

    // Helper to get precompiled SPIR-V data - NO runtime fallback in production
    auto get_spirv = [&](const char* shader_name, const uint32_t* precompiled_data, size_t precompiled_size,
                         std::vector<uint32_t>& spirv_out) -> bool {
        // Production builds MUST have precompiled SPIR-V
        if (!validate_spirv(shader_name, precompiled_data, precompiled_size)) {
            LOGE("RIFE-SPIRV: Shader %s missing or invalid precompiled SPIR-V. Aborting Vulkan pipeline creation.", shader_name);
            return false;
        }

        spirv_out.assign(precompiled_data, precompiled_data + (precompiled_size / sizeof(uint32_t)));
        LOGI("RIFE-SPIRV: Using precompiled SPIR-V for %s (%zu words)", shader_name, spirv_out.size());
        return true;
    };

    // initialize preprocess and postprocess pipeline
    if (vkdev)
    {
        std::vector<ncnn::vk_specialization_type> specializations(1);
#if _WIN32
        specializations[0].i = 1;
#else
        specializations[0].i = 0;
#endif

        {
            std::vector<uint32_t> spirv;
            if (!get_spirv(tta_mode ? "rife_preproc_tta" : "rife_preproc",
                           tta_mode ? rife_preproc_tta_spv_data : rife_preproc_spv_data,
                           tta_mode ? rife_preproc_tta_spv_data_size : rife_preproc_spv_data_size,
                           spirv)) {
                return -1;
            }

            rife_preproc = new ncnn::Pipeline(vkdev);
            rife_preproc->set_optimal_local_size_xyz(8, 8, 3);
            rife_preproc->create(spirv.data(), spirv.size() * 4, specializations);
        }

        {
            std::vector<uint32_t> spirv;
            if (!get_spirv(tta_mode ? "rife_postproc_tta" : "rife_postproc",
                           tta_mode ? rife_postproc_tta_spv_data : rife_postproc_spv_data,
                           tta_mode ? rife_postproc_tta_spv_data_size : rife_postproc_spv_data_size,
                           spirv)) {
                return -1;
            }

            rife_postproc = new ncnn::Pipeline(vkdev);
            rife_postproc->set_optimal_local_size_xyz(8, 8, 3);
            rife_postproc->create(spirv.data(), spirv.size() * 4, specializations);
        }
    }

    if (vkdev && tta_mode)
    {
        std::vector<uint32_t> spirv;
        const uint32_t* flow_tta_avg_spv_data = nullptr;
        size_t flow_tta_avg_spv_data_size = 0;
        const char* flow_tta_avg_comp_data = nullptr;
        int flow_tta_avg_comp_data_size = 0;

        if (rife_v4) {
            flow_tta_avg_spv_data = rife_v4_flow_tta_avg_spv_data;
            flow_tta_avg_spv_data_size = rife_v4_flow_tta_avg_spv_data_size;
            flow_tta_avg_comp_data = rife_v4_flow_tta_avg_comp_data;
            flow_tta_avg_comp_data_size = sizeof(rife_v4_flow_tta_avg_comp_data);
        } else if (rife_v2) {
            flow_tta_avg_spv_data = rife_v2_flow_tta_avg_spv_data;
            flow_tta_avg_spv_data_size = rife_v2_flow_tta_avg_spv_data_size;
            flow_tta_avg_comp_data = rife_v2_flow_tta_avg_comp_data;
            flow_tta_avg_comp_data_size = sizeof(rife_v2_flow_tta_avg_comp_data);
        } else {
            flow_tta_avg_spv_data = rife_flow_tta_avg_spv_data;
            flow_tta_avg_spv_data_size = rife_flow_tta_avg_spv_data_size;
            flow_tta_avg_comp_data = rife_flow_tta_avg_comp_data;
            flow_tta_avg_comp_data_size = sizeof(rife_flow_tta_avg_comp_data);
        }

        if (!get_spirv("flow_tta_avg",
                       flow_tta_avg_spv_data, flow_tta_avg_spv_data_size,
                       spirv)) {
            return -1;
        }

        std::vector<ncnn::vk_specialization_type> specializations(0);

        rife_flow_tta_avg = new ncnn::Pipeline(vkdev);
        rife_flow_tta_avg->set_optimal_local_size_xyz(8, 8, 1);
        rife_flow_tta_avg->create(spirv.data(), spirv.size() * 4, specializations);
    }

    if (vkdev && tta_temporal_mode)
    {
        std::vector<uint32_t> spirv;
        const uint32_t* flow_tta_temporal_avg_spv_data = nullptr;
        size_t flow_tta_temporal_avg_spv_data_size = 0;
        const char* flow_tta_temporal_avg_comp_data = nullptr;
        int flow_tta_temporal_avg_comp_data_size = 0;

        if (rife_v4) {
            flow_tta_temporal_avg_spv_data = rife_v4_flow_tta_temporal_avg_spv_data;
            flow_tta_temporal_avg_spv_data_size = rife_v4_flow_tta_temporal_avg_spv_data_size;
            flow_tta_temporal_avg_comp_data = rife_v4_flow_tta_temporal_avg_comp_data;
            flow_tta_temporal_avg_comp_data_size = sizeof(rife_v4_flow_tta_temporal_avg_comp_data);
        } else if (rife_v2) {
            flow_tta_temporal_avg_spv_data = rife_v2_flow_tta_temporal_avg_spv_data;
            flow_tta_temporal_avg_spv_data_size = rife_v2_flow_tta_temporal_avg_spv_data_size;
            flow_tta_temporal_avg_comp_data = rife_v2_flow_tta_temporal_avg_comp_data;
            flow_tta_temporal_avg_comp_data_size = sizeof(rife_v2_flow_tta_temporal_avg_comp_data);
        } else {
            flow_tta_temporal_avg_spv_data = rife_flow_tta_temporal_avg_spv_data;
            flow_tta_temporal_avg_spv_data_size = rife_flow_tta_temporal_avg_spv_data_size;
            flow_tta_temporal_avg_comp_data = rife_flow_tta_temporal_avg_comp_data;
            flow_tta_temporal_avg_comp_data_size = sizeof(rife_flow_tta_temporal_avg_comp_data);
        }

        if (!get_spirv("flow_tta_temporal_avg",
                       flow_tta_temporal_avg_spv_data, flow_tta_temporal_avg_spv_data_size,
                       spirv)) {
            return -1;
        }

        std::vector<ncnn::vk_specialization_type> specializations(0);

        rife_flow_tta_temporal_avg = new ncnn::Pipeline(vkdev);
        rife_flow_tta_temporal_avg->set_optimal_local_size_xyz(8, 8, 1);
        rife_flow_tta_temporal_avg->create(spirv.data(), spirv.size() * 4, specializations);
    }

    if (vkdev && tta_temporal_mode)
    {
        std::vector<uint32_t> spirv;
        if (!get_spirv("rife_out_tta_temporal_avg",
                       rife_out_tta_temporal_avg_spv_data, rife_out_tta_temporal_avg_spv_data_size,
                       spirv)) {
            return -1;
        }

        std::vector<ncnn::vk_specialization_type> specializations(0);

        rife_out_tta_temporal_avg = new ncnn::Pipeline(vkdev);
        rife_out_tta_temporal_avg->set_optimal_local_size_xyz(8, 8, 1);
        rife_out_tta_temporal_avg->create(spirv.data(), spirv.size() * 4, specializations);
    }

    if (uhd_mode)
    {
        {
            rife_uhd_downscale_image = ncnn::create_layer("Interp");
            rife_uhd_downscale_image->vkdev = vkdev;

            ncnn::ParamDict pd;
            pd.set(0, 2);// bilinear
            pd.set(1, 0.5f);
            pd.set(2, 0.5f);
            rife_uhd_downscale_image->load_param(pd);

            rife_uhd_downscale_image->create_pipeline(opt);
        }
        {
            rife_uhd_upscale_flow = ncnn::create_layer("Interp");
            rife_uhd_upscale_flow->vkdev = vkdev;

            ncnn::ParamDict pd;
            pd.set(0, 2);// bilinear
            pd.set(1, 2.f);
            pd.set(2, 2.f);
            rife_uhd_upscale_flow->load_param(pd);

            rife_uhd_upscale_flow->create_pipeline(opt);
        }
        {
            rife_uhd_double_flow = ncnn::create_layer("BinaryOp");
            rife_uhd_double_flow->vkdev = vkdev;

            ncnn::ParamDict pd;
            pd.set(0, 2);// mul
            pd.set(1, 1);// with_scalar
            pd.set(2, 2.f);// b
            rife_uhd_double_flow->load_param(pd);

            rife_uhd_double_flow->create_pipeline(opt);
        }
    }

    if (rife_v2)
    {
        {
            rife_v2_slice_flow = ncnn::create_layer("Slice");
            rife_v2_slice_flow->vkdev = vkdev;

            ncnn::Mat slice_points(2);
            slice_points.fill<int>(-233);

            ncnn::ParamDict pd;
            pd.set(0, slice_points);
            pd.set(1, 0);// axis

            rife_v2_slice_flow->load_param(pd);

            rife_v2_slice_flow->create_pipeline(opt);
        }
    }

    if (rife_v4)
    {
        if (vkdev)
        {
            std::vector<uint32_t> spirv;
            if (!get_spirv(tta_mode ? "rife_v4_timestep_tta" : "rife_v4_timestep",
                           tta_mode ? rife_v4_timestep_tta_spv_data : rife_v4_timestep_spv_data,
                           tta_mode ? rife_v4_timestep_tta_spv_data_size : rife_v4_timestep_spv_data_size,
                           spirv)) {
                return -1;
            }

            std::vector<ncnn::vk_specialization_type> specializations;

            rife_v4_timestep = new ncnn::Pipeline(vkdev);
            rife_v4_timestep->set_optimal_local_size_xyz(8, 8, 1);
            rife_v4_timestep->create(spirv.data(), spirv.size() * 4, specializations);
        }
    }

    logRss("after_load");
    return 0;
}