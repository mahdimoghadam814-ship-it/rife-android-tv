#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

namespace rife {

class MemcPool;   // persistent worker threads, defined in memc_interpolator.cpp

// Selects the frame interpolation algorithm used by interpolateFrameBuffers().
// Mirrors the ordinal values used by the Kotlin InterpolationAlgorithm enum.
enum class InterpolationAlgorithm : int {
    RIFE = 0,
    MEMC = 1,
};

// Block-matching motion estimation / motion compensation (MEMC) interpolator.
//
// This is the cheap alternative to the RIFE neural network. It runs on the CPU with
// NEON and needs no model files, no Vulkan and no GPU memory, which is why it is the
// only candidate that can plausibly keep up on a low-end SoC.
//
// Pipeline per frame pair:
//   1. RGBA -> luma (BT.601)              luma is 1/4 the memory traffic of RGBA
//   2. 3-level box pyramid, coarse -> fine
//   3. hierarchical search, bidirectional: in0->in1 (forward) and in1->in0 (backward)
//   4. motion compensation on the RGB channels using both vector fields, averaged
//
// The output is written as tightly packed RGBA8888, top-down, targetW*targetH*4 bytes,
// matching the contract of RifeEngine::processFrameBuffer().
//
// Quality note (v1): the warp samples nearest-neighbour and the motion field is
// piecewise constant per 16x16 block. Overlapped blocks and bilinear sampling are the
// natural follow-ups if blocking artefacts show up.
class MemcInterpolator {
public:
    MemcInterpolator();
    ~MemcInterpolator();

    MemcInterpolator(const MemcInterpolator&) = delete;
    MemcInterpolator& operator=(const MemcInterpolator&) = delete;

    // src0/src1: RGBA8888, srcW*srcH*4, tightly packed, top-down.
    // out:       RGBA8888, targetW*targetH*4, tightly packed, top-down.
    // timestep:  only 0.5 is exercised by the pipeline, but any value in [0,1] is honoured.
    bool interpolate(const uint8_t* src0, const uint8_t* src1,
                     int srcW, int srcHeight,
                     int targetWidth, int targetHeight,
                     float timestep,
                     uint8_t* out);

    void reset();

    // Sizing of the persistent worker pool. Creating std::threads per parallel region
    // costs ~33 spawns per frame, which measured as *slower* than single-threaded.
    void setThreadCount(int threads);
    int threadCount() const { return threads_.load(std::memory_order_relaxed); }

    // Wall-clock duration of the most recent interpolate() call, for diagnostics.
    double lastDurationMs() const { return last_ms_.load(std::memory_order_relaxed); }

private:
    static constexpr int kBlock = 16;
    static constexpr int kLevels = 3;   // 1/1, 1/2, 1/4
    static constexpr int kCoarseRange = 8;
    static constexpr int kFineRange = 2;

    void ensureCapacity(int w, int h);
    void extractLuma(const uint8_t* rgba, int w, int h, uint8_t* luma);
    void downsample2(const uint8_t* src, int sw, int sh, uint8_t* dst);
    void buildPyramid(const uint8_t* luma, int w, int h, uint8_t** pyr);

    // tgt is the frame whose blocks we take; ref is the frame we search in.
    // On return mvx/mvy hold the full-resolution offset such that
    // ref(x + mv) matches tgt(x).
    void motionEstimate(uint8_t* const tgtPyr[kLevels], uint8_t* const refPyr[kLevels],
                        int w, int h, int32_t* mvx, int32_t* mvy);

    void motionCompensate(const uint8_t* in0, const uint8_t* in1,
                          int w, int h, float timestep,
                          const int32_t* mvf_x, const int32_t* mvf_y,
                          const int32_t* mvb_x, const int32_t* mvb_y,
                          uint8_t* out);

    template <typename F>
    void parallelFor(int begin, int end, F&& fn);

    std::atomic<int> threads_{1};
    std::atomic<double> last_ms_{0.0};

    // Only ever touched by the pipeline worker thread.
    int work_w_ = 0;
    int work_h_ = 0;

    // reset() may be called from another thread while interpolate() is in flight, so it
    // only raises a flag that is consumed at the start of the next frame.
    std::atomic<bool> dirty_{false};

    std::unique_ptr<MemcPool> pool_;

    // Scratch, allocated once per resolution instead of per frame.
    std::vector<uint8_t> luma0_, luma1_;
    std::vector<uint8_t> pyr0_[kLevels];
    std::vector<uint8_t> pyr1_[kLevels];
    std::vector<uint8_t> resized0_, resized1_;
    std::vector<int32_t> mvf_x_, mvf_y_, mvb_x_, mvb_y_;
};

}  // namespace rife
