#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RIFE-DEBUG", __VA_ARGS__)

#include "rife.h"

#include <algorithm>
#include <vector>
#include "benchmark.h"

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
    delete rife_preproc;
    delete rife_postproc;
    delete rife_flow_tta_avg;
    delete rife_flow_tta_temporal_avg;
    delete rife_out_tta_temporal_avg;
    delete rife_v4_timestep;

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

static int load_param_model(ncnn::Net& net, const std::string& modeldir, const char* name)
{
    char parampath[256];
    char modelpath[256];
    sprintf(parampath, "%s/%s.param", modeldir.c_str(), name);
    sprintf(modelpath, "%s/%s.bin", modeldir.c_str(), name);

    const int param_ret = net.load_param(parampath);
    if (param_ret != 0) return param_ret;

    const int model_ret = net.load_model(modelpath);
    if (model_ret != 0) return model_ret;

    return 0;
}

int RIFE::load(const std::string& modeldir)
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

    if (vkdev)
    {
        flownet.set_vulkan_device(vkdev);
        contextnet.set_vulkan_device(vkdev);
        fusionnet.set_vulkan_device(vkdev);
    }

    flownet.register_custom_layer("rife.Warp", Warp_layer_creator);
    contextnet.register_custom_layer("rife.Warp", Warp_layer_creator);
    fusionnet.register_custom_layer("rife.Warp", Warp_layer_creator);

    {
        const int ret = load_param_model(flownet, modeldir, "flownet");
        if (ret != 0) return -10;
    }

    if (!rife_v4)
    {
        const int context_ret = load_param_model(contextnet, modeldir, "contextnet");
        if (context_ret != 0) return -20;

        const int fusion_ret = load_param_model(fusionnet, modeldir, "fusionnet");
        if (fusion_ret != 0) return -30;
    }

    return 0;
}

int RIFE::process(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const
{
    if (!vkdev)
    {
        if (rife_v4)
            return process_v4_cpu(in0image, in1image, timestep, outimage);
        else
            return process_cpu(in0image, in1image, timestep, outimage);
    }

    if (rife_v4)
        return process_v4(in0image, in1image, timestep, outimage);

    if (timestep == 0.f)
    {
        outimage = in0image;
        return 0;
    }

    if (timestep == 1.f)
    {
        outimage = in1image;
        return 0;
    }

    const unsigned char* pixel0data = (const unsigned char*)in0image.data;
    const unsigned char* pixel1data = (const unsigned char*)in1image.data;
    const int w = in0image.w;
    const int h = in0image.h;
    const int channels = 3;

    ncnn::VkAllocator* blob_vkallocator = vkdev->acquire_blob_allocator();
    ncnn::VkAllocator* staging_vkallocator = vkdev->acquire_staging_allocator();

    ncnn::Option opt = flownet.opt;
    opt.blob_vkallocator = blob_vkallocator;
    opt.workspace_vkallocator = blob_vkallocator;
    opt.staging_vkallocator = staging_vkallocator;

    int w_padded = (w + 31) / 32 * 32;
    int h_padded = (h + 31) / 32 * 32;

    ncnn::Mat in0 = ncnn::Mat::from_pixels(pixel0data, ncnn::Mat::PIXEL_RGB, w, h);
    ncnn::Mat in1 = ncnn::Mat::from_pixels(pixel1data, ncnn::Mat::PIXEL_RGB, w, h);

    ncnn::VkCompute cmd(vkdev);

    ncnn::VkMat in0_gpu, in1_gpu, out_gpu;
    cmd.record_clone(in0, in0_gpu, opt);
    cmd.record_clone(in1, in1_gpu, opt);

    // download
    {
        ncnn::Mat out;
        cmd.record_clone(out_gpu, out, opt);
        cmd.submit_and_wait();

        if (outimage.empty())
        {
            outimage.create(w, h, (size_t)3u, 3);
        }
        out.to_pixels((unsigned char*)outimage.data, ncnn::Mat::PIXEL_RGB);
    }

    vkdev->reclaim_blob_allocator(blob_vkallocator);
    vkdev->reclaim_staging_allocator(staging_vkallocator);

    return 0;
}

int RIFE::process_cpu(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const
{
    if (timestep == 0.f)
    {
        outimage = in0image;
        return 0;
    }

    if (timestep == 1.f)
    {
        outimage = in1image;
        return 0;
    }

    const unsigned char* pixel0data = (const unsigned char*)in0image.data;
    const unsigned char* pixel1data = (const unsigned char*)in1image.data;
    const int w = in0image.w;
    const int h = in0image.h;

    ncnn::Option opt = flownet.opt;

    int w_padded = (w + 31) / 32 * 32;
    int h_padded = (h + 31) / 32 * 32;

    ncnn::Mat in0 = ncnn::Mat::from_pixels(pixel0data, ncnn::Mat::PIXEL_RGB, w, h);
    ncnn::Mat in1 = ncnn::Mat::from_pixels(pixel1data, ncnn::Mat::PIXEL_RGB, w, h);

    ncnn::Mat in0_padded, in1_padded;
    in0_padded.create(w_padded, h_padded, 3);
    in1_padded.create(w_padded, h_padded, 3);

    for (int q = 0; q < 3; q++)
    {
        float* outptr = in0_padded.channel(q);
        int i = 0;
        for (; i < h; i++)
        {
            const float* ptr = in0.channel(q).row(i);
            int j = 0;
            for (; j < w; j++) { *outptr++ = *ptr++ * (1 / 255.f); }
            for (; j < w_padded; j++) { *outptr++ = 0.f; }
        }
        for (; i < h_padded; i++)
        {
            for (int j = 0; j < w_padded; j++) { *outptr++ = 0.f; }
        }
    }

    for (int q = 0; q < 3; q++)
    {
        float* outptr = in1_padded.channel(q);
        int i = 0;
        for (; i < h; i++)
        {
            const float* ptr = in1.channel(q).row(i);
            int j = 0;
            for (; j < w; j++) { *outptr++ = *ptr++ * (1 / 255.f); }
            for (; j < w_padded; j++) { *outptr++ = 0.f; }
        }
        for (; i < h_padded; i++)
        {
            for (int j = 0; j < w_padded; j++) { *outptr++ = 0.f; }
        }
    }

    ncnn::Mat flow, flow0, flow1;
    {
        ncnn::Extractor ex = flownet.create_extractor();
        ex.input("input0", in0_padded);
        ex.input("input1", in1_padded);
        ex.extract("flow", flow);
    }

    if (rife_v2)
    {
        std::vector<ncnn::Mat> inputs(1);
        inputs[0] = flow;
        std::vector<ncnn::Mat> outputs(2);
        rife_v2_slice_flow->forward(inputs, outputs, opt);
        flow0 = outputs[0];
        flow1 = outputs[1];
    }

    ncnn::Mat ctx0[4], ctx1[4];
    {
        ncnn::Extractor ex = contextnet.create_extractor();
        ex.input("input.1", in0_padded);
        ex.input("flow.0", rife_v2 ? flow0 : flow);
        ex.extract("f1", ctx0[0]);
        ex.extract("f2", ctx0[1]);
        ex.extract("f3", ctx0[2]);
        ex.extract("f4", ctx0[3]);
    }
    {
        ncnn::Extractor ex = contextnet.create_extractor();
        ex.input("input.1", in1_padded);
        ex.input("flow.0", rife_v2 ? flow1 : flow);
        ex.extract("f1", ctx1[0]);
        ex.extract("f2", ctx1[1]);
        ex.extract("f3", ctx1[2]);
        ex.extract("f4", ctx1[3]);
    }

    ncnn::Mat out_padded;
    {
        ncnn::Extractor ex = fusionnet.create_extractor();
        ex.input("img0", in0_padded);
        ex.input("img1", in1_padded);
        ex.input("flow", flow);
        ex.input("3", ctx0[0]);
        ex.input("4", ctx0[1]);
        ex.input("5", ctx0[2]);
        ex.input("6", ctx0[3]);
        ex.input("7", ctx1[0]);
        ex.input("8", ctx1[1]);
        ex.input("9", ctx1[2]);
        ex.input("10", ctx1[3]);
        ex.extract("output", out_padded);
    }

    ncnn::Mat out;
    out.create(w, h, 3);
    for (int q = 0; q < 3; q++)
    {
        float* outptr = out.channel(q);
        const float* ptr = out_padded.channel(q);
        for (int i = 0; i < h; i++)
        {
            for (int j = 0; j < w; j++)
            {
                *outptr++ = *ptr++ * 255.f + 0.5f;
            }
        }
    }

    if (outimage.empty())
    {
        outimage.create(w, h, (size_t)3u, 3);
    }

    out.to_pixels((unsigned char*)outimage.data, ncnn::Mat::PIXEL_RGB);

    return 0;
}

int RIFE::process_v4(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const
{
    return process_v4_cpu(in0image, in1image, timestep, outimage);
}

int RIFE::process_v4_cpu(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const
{
    return process_cpu(in0image, in1image, timestep, outimage);
}
