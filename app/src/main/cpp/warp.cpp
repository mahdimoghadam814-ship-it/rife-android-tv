// rife implemented with ncnn library

#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RIFE-ERROR", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "RIFE-SPIRV", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RIFE-SPIRV", __VA_ARGS__)

#include "rife_ops.h"

#include "warp.comp.hex.h"
#include "warp_pack4.comp.hex.h"
#include "warp_pack8.comp.hex.h"

// Precompiled SPIR-V headers (avoid runtime glslang compilation)
#include "warp.comp.spv.h"
#include "warp_pack4.comp.spv.h"
#include "warp_pack8.comp.spv.h"

using namespace ncnn;

Warp::Warp()
{
    support_vulkan = true;

    pipeline_warp = 0;
    pipeline_warp_pack4 = 0;
    pipeline_warp_pack8 = 0;
}

// Helper to validate precompiled SPIR-V data
static inline bool validate_spirv(const char* shader_name, const uint32_t* data, size_t size) {
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
}

int Warp::create_pipeline(const Option& opt)
{
    if (!vkdev)
        return 0;

    std::vector<vk_specialization_type> specializations(0 + 0);

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

    // pack1 (warp)
    {
        std::vector<uint32_t> spirv;
        if (!get_spirv("warp", warp_spv_data, warp_spv_data_size, spirv)) {
            return -1;
        }

        pipeline_warp = new Pipeline(vkdev);
        pipeline_warp->set_optimal_local_size_xyz();
        pipeline_warp->create(spirv.data(), spirv.size() * 4, specializations);
    }

    // pack4 (warp_pack4)
    {
        std::vector<uint32_t> spirv;
        if (!get_spirv("warp_pack4", warp_pack4_spv_data, warp_pack4_spv_data_size, spirv)) {
            return -1;
        }

        pipeline_warp_pack4 = new Pipeline(vkdev);
        pipeline_warp_pack4->set_optimal_local_size_xyz();
        pipeline_warp_pack4->create(spirv.data(), spirv.size() * 4, specializations);
    }

    // pack8 (warp_pack8) - OPTIONAL: only if fp16 packed/storage supported AND precompiled SPIR-V available
    if (vkdev->info.support_fp16_packed() || vkdev->info.support_fp16_storage())
    {
        std::vector<uint32_t> spirv;
        if (validate_spirv("warp_pack8", warp_pack8_spv_data, warp_pack8_spv_data_size)) {
            if (get_spirv("warp_pack8", warp_pack8_spv_data, warp_pack8_spv_data_size, spirv)) {
                pipeline_warp_pack8 = new Pipeline(vkdev);
                pipeline_warp_pack8->set_optimal_local_size_xyz();
                pipeline_warp_pack8->create(spirv.data(), spirv.size() * 4, specializations);
                LOGI("RIFE-SPIRV: warp_pack8 pipeline created successfully");
            } else {
                LOGW("RIFE-SPIRV: warp_pack8 precompiled SPIR-V validation failed, skipping pack8 pipeline");
            }
        } else {
            LOGW("RIFE-SPIRV: warp_pack8 precompiled SPIR-V not available (optional), skipping pack8 pipeline");
        }
    }

    return 0;
}

int Warp::destroy_pipeline(const Option& opt)
{
    delete pipeline_warp;
    pipeline_warp = 0;

    delete pipeline_warp_pack4;
    pipeline_warp_pack4 = 0;

    delete pipeline_warp_pack8;
    pipeline_warp_pack8 = 0;

    return 0;
}

int Warp::forward(const std::vector<Mat>& bottom_blobs, std::vector<Mat>& top_blobs, const Option& opt) const
{
    const Mat& image_blob = bottom_blobs[0];
    const Mat& flow_blob = bottom_blobs[1];

    int w = image_blob.w;
    int h = image_blob.h;
    int channels = image_blob.c;

    Mat& top_blob = top_blobs[0];
    top_blob.create(w, h, channels);
    if (top_blob.empty())
        return -100;

    #pragma omp parallel for num_threads(opt.num_threads)
    for (int q = 0; q < channels; q++)
    {
        float* outptr = top_blob.channel(q);

        const Mat image = image_blob.channel(q);

        const float* fxptr = flow_blob.channel(0);
        const float* fyptr = flow_blob.channel(1);

        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                float flow_x = fxptr[0];
                float flow_y = fyptr[0];

                float sample_x = x + flow_x;
                float sample_y = y + flow_y;

                // bilinear interpolate
                float v;
                {
                    int x0 = floor(sample_x);
                    int y0 = floor(sample_y);
                    int x1 = x0 + 1;
                    int y1 = y0 + 1;

                    x0 = std::min(std::max(x0, 0), w - 1);
                    y0 = std::min(std::max(y0, 0), h - 1);
                    x1 = std::min(std::max(x1, 0), w - 1);
                    y1 = std::min(std::max(y1, 0), h - 1);

                    float alpha = sample_x - x0;
                    float beta = sample_y - y0;

                    float v0 = image.row(y0)[x0];
                    float v1 = image.row(y0)[x1];
                    float v2 = image.row(y1)[x0];
                    float v3 = image.row(y1)[x1];

                    float v4 = v0 * (1 - alpha) + v1 * alpha;
                    float v5 = v2 * (1 - alpha) + v3 * alpha;

                    v = v4 * (1 - beta) + v5 * beta;
                }

                outptr[0] = v;

                outptr += 1;

                fxptr += 1;
                fyptr += 1;
            }
        }
    }

    return 0;
}

int Warp::forward(const std::vector<VkMat>& bottom_blobs, std::vector<VkMat>& top_blobs, VkCompute& cmd, const Option& opt) const
{
    const VkMat& image_blob = bottom_blobs[0];
    const VkMat& flow_blob = bottom_blobs[1];

    int w = image_blob.w;
    int h = image_blob.h;
    int channels = image_blob.c;
    size_t elemsize = image_blob.elemsize;
    int elempack = image_blob.elempack;

    VkMat& top_blob = top_blobs[0];
    top_blob.create(w, h, channels, elemsize, elempack, opt.blob_vkallocator);
    if (top_blob.empty())
        return -100;

    std::vector<VkMat> bindings(3);
    bindings[0] = image_blob;
    bindings[1] = flow_blob;
    bindings[2] = top_blob;

    std::vector<vk_constant_type> constants(4);
    constants[0].i = top_blob.w;
    constants[1].i = top_blob.h;
    constants[2].i = top_blob.c;
    constants[3].i = top_blob.cstep;

    if (elempack == 8)
    {
        if (pipeline_warp_pack8) {
            cmd.record_pipeline(pipeline_warp_pack8, bindings, constants, top_blob);
        } else {
            // Fallback to pack4 if pack8 pipeline not available
            cmd.record_pipeline(pipeline_warp_pack4, bindings, constants, top_blob);
        }
    }
    else if (elempack == 4)
    {
        cmd.record_pipeline(pipeline_warp_pack4, bindings, constants, top_blob);
    }
    else // if (elempack == 1)
    {
        cmd.record_pipeline(pipeline_warp, bindings, constants, top_blob);
    }

    return 0;
}