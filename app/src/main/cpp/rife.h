// rife implemented with ncnn library

#ifndef RIFE_H
#define RIFE_H

#include <string>

// ncnn
#include "net.h"
#include "option.h"

namespace rife {

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

} // namespace rife

class RIFE
{
public:
    RIFE(int gpuid, bool tta_mode = false, bool tta_temporal_mode = false, bool uhd_mode = false, int num_threads = 1, bool rife_v2 = false, bool rife_v4 = false, const std::string& gpu_name = "");
    ~RIFE();

#if _WIN32
    int load(const std::wstring& modeldir);
#else
    int load(const std::string& modeldir);
#endif

    int process(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const;

    int process_cpu(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const;

    int process_v4(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const;

    int process_v4_cpu(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const;

    void setOptions(const rife::NcnnOptionPolicy& policy);

private:
    ncnn::VulkanDevice* vkdev;
    ncnn::Net flownet;
    ncnn::Net contextnet;
    ncnn::Net fusionnet;
    ncnn::Pipeline* rife_preproc;
    ncnn::Pipeline* rife_postproc;
    ncnn::Pipeline* rife_flow_tta_avg;
    ncnn::Pipeline* rife_flow_tta_temporal_avg;
    ncnn::Pipeline* rife_out_tta_temporal_avg;
    ncnn::Pipeline* rife_v4_timestep;
    ncnn::Layer* rife_uhd_downscale_image;
    ncnn::Layer* rife_uhd_upscale_flow;
    ncnn::Layer* rife_uhd_double_flow;
    ncnn::Layer* rife_v2_slice_flow;
    bool tta_mode;
    bool tta_temporal_mode;
    bool uhd_mode;
    int num_threads;
    bool rife_v2;
    bool rife_v4;
    bool is_tv_box;
    rife::NcnnOptionPolicy option_policy_;
};

#endif // RIFE_H
