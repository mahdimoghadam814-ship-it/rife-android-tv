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
    SVPLAYER = 2,
};

// True when the selected algorithm is block matching, i.e. it produces a motion field the warp
// and the denoiser can consume. Both MEMC and SVPLAYER do; only RIFE does not.
bool producesMotionField(int algorithm);

// Tuning surface of the SVPlayer-shaped search, in the same units as the SVPflow configuration
// it is modelled on. The two bars in the UI are derived from these rather than stored beside
// them: performanceQuality selects the rung of the cost ladder, artifactMaskLevel selects how
// much of the bad-area mask is allowed to suppress a blend.
struct SvConfig {
    // 0f is the performance end, 1f the quality end.
    float performanceQuality = 0.6f;
    // 0f disables bad-area masking, 1f masks every block the search could not explain.
    float artifactMaskLevel = 0.5f;
    // 0 = derive from performanceQuality, otherwise 16x8 / 32x8 / 32x16 by index.
    int blockSize = 0;
    // Pixels; 0 derives it from local contrast, which is SVP's negative search distance.
    int searchDistance = 0;
    // 1 = whole pixel, 2 = half pixel.
    int subpel = 2;
    // Quarter-blocks of overlap between neighbours: 0, 1 or 2.
    int overlap = 2;
    float penaltyLambda = 10.0f;
    // 0 = forward/backward average, 1 = plus per-pixel median, 2 = plus cover/uncover.
    int blendAlgorithm = 1;
    int sceneAdaptive = 1;
    // Luma downscale the search runs at: 1 full, 2 half.
    int meScale = 1;
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
// The warp interpolates the motion field bilinearly between block centres and resamples both
// source frames with a 4-tap read, so the motion varies continuously across the frame instead
// of stepping at block edges. interpolate() does that resample on the CPU with NEON;
// motionField() hands the same field to the GPU so the shader can do it for free.

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

    // Everything interpolate() does up to and including both motion estimates, then packs the
    // field instead of warping it: eight bytes per block, row-major over
    // ceil(targetW/kBlock) x ceil(targetH/kBlock).
    //
    //   [0..4)   forward x, forward y, backward x, backward y - whole-pixel vectors biased by +128
    //   [4..6)   forward and backward occlusion mask, 0 = fully trusted, 255 = covered up
    //   [6..7)   forward non-local-mean blend weight, 0 = do not merge this sample
    //   [7..8)   noise floor the weight was measured against, replicated for every block so the
    //            shader can read it out of the texture it is already sampling
    //
    // The weight is what makes the packed field serve a denoiser as well as the interpolator.
    // For every block both directions are scored with an 8x8 SAD against their own frame at the
    // motion-compensated position, so the residual is what alignment failed to explain - pure
    // noise where the vector is right, misalignment where it is wrong. The noise floor is read
    // off the 20th percentile of those scores (the flat blocks, which is where matching is
    // easiest and therefore where the residual is the sensor/compression noise itself) and the
    // weight is exp(-0.6 * (sad/floor)^2) against it. That ratio is the whole denoiser: a block
    // that matches to within its own noise floor merges with the other frame at ~0.55, a block
    // two floors out merges at ~0.09, three floors at ~0.005, and a scene cut - where every
    // field is zeroed before this runs - gets a floor of zero and therefore no weight at all.
    // The cover/uncover mask is folded in on top, so a sample the interpolation would have
    // rejected as covered is rejected here too.
    //
    // The scores are measured in luma, because that is the plane the search already has and one
    // byte per pixel instead of four. The shader gates per pixel on the mean absolute difference
    // across all three colour channels instead, which is strictly more sensitive - a sample can
    // match in luminance and be completely wrong in colour - so the published floor is scaled by
    // kNlmChannelScale to bring it into the units the gate compares against. For independent
    // equal-variance channel noise that ratio is (1.128*s*sqrt(2)) / (1.128*0.669*s*sqrt(2))
    // = 1/0.669 = 1.49, and 1.5 is the rounded value.
    //
    // The weight is a statement about a *block*, and blocks straddle motion boundaries. Between
    // two block centres the field is interpolated, so a pixel inside a moving object close to its
    // own edge gets a half-way vector and therefore samples the wrong part of the previous frame,
    // while its weight is averaged with that of a neighbour that matched perfectly. The block
    // weight alone would merge at full strength on exactly the pixels that are misaligned - which
    // is why the noise floor is published alongside it, so the shader can run a per-pixel
    // similarity gate on top and drop the sample the moment the two frames disagree by more than
    // their own noise does.
    //
    // outMv must have at least motionFieldBytes(targetWidth, targetHeight) bytes of capacity.
    //
    // This is the hand-off to the GPU warp: a fragment shader samples the two source frames with
    // the same bilinear field the CPU path builds, so the per-pixel resample - by far the most
    // expensive thing motionCompensate() does - costs a texture fetch instead of a NEON loop.
    // The two halves are contiguous, so a caller uploads them as two textures out of one buffer.
    //
    // forwardOnly skips the backward estimate entirely. The denoiser samples its history along
    // the forward vector alone, so with interpolation switched off the second search is pure
    // overhead - and at 1080p it is the single largest number in the STAGES line. Scene-cut
    // detection then keys off the forward SAD by itself, which is the half it can still see.
    bool motionField(const uint8_t* src0, const uint8_t* src1,
                     int srcW, int srcHeight,
                     int targetWidth, int targetHeight,
                     uint8_t* outMv, size_t outMvBytes,
                     bool forwardOnly = false);

    // Exact byte count motionField() writes for a processing size: eight bytes per block. Also
    // the size the caller must allocate for the packed field, still only tens of kB at 1080p.
    static size_t motionFieldBytes(int targetWidth, int targetHeight);

    void reset();

    // Sizing of the persistent worker pool. Creating std::threads per parallel region
    // costs ~33 spawns per frame, which measured as *slower* than single-threaded.
    void setThreadCount(int threads);
    int threadCount() const { return threads_.load(std::memory_order_relaxed); }

    // Replaces the SVPlayer tuning surface. Takes effect on the next motionField()/interpolate()
    // call; the caller only writes it from the pipeline thread while a frame pair is not in
    // flight, so it needs no lock of its own.
    void setSvConfig(const SvConfig& config) { sv_config_ = config; }
    const SvConfig& svConfig() const { return sv_config_; }

    // Wall-clock duration of the most recent interpolate() call, for diagnostics.
    double lastDurationMs() const { return last_ms_.load(std::memory_order_relaxed); }

private:
    static constexpr int kBlock = 16;
    static constexpr int kLevels = 3;   // 1/1, 1/2, 1/4
    static constexpr int kCoarseRange = 8;
    static constexpr int kFineRange = 2;

    // Occlusion is where the field folds: a block whose right/bottom neighbour travels *less*
    // than it does means the destinations between them overlap and some of the content is being
    // covered up. MVTools' MakeVectorOcclusionMaskTime works on exactly that divergence, scaled
    // to a byte. This turns one pixel of fold into `kOccScale` counts, so a fold of
    // 255/kOccScale pixels is already a fully untrusted sample.
    static constexpr int kOccScale = 16;
    // MVTools' thSCD1/thSCD2 scene-change gate, restated as the mean per-pixel luma SAD of the
    // *motion-compensated* pair. Consecutive frames of one shot land in single digits; an
    // unrelated pair lands near 30-60 no matter how good the search was.
    static constexpr int kSceneCutMeanSad = 30;
    // Non-local-mean weight shaping. kNlmFloorMin/Max bound the noise floor read off the
    // histogram: below the minimum a perfectly clean static frame would blend against a floor
    // of nothing, above the maximum a cut or a failed search would claim to be noise and blend
    // two unrelated pictures. kNlmExponent decides how sharply the weight falls away from the
    // floor. The weight is the history term of a recursive filter y = (1-w)*x + w*y_prev, whose
    // fixed-point variance is sigma^2 * (1-w)/(1+w), so w = 0.55 at the floor buys 3.4x noise
    // variance reduction (1.85x in sigma) once the window has filled, while the same exponent
    // puts a block two floors out at 0.09 (1.2x) and three floors at 0.005 (no-op) - the
    // adaptive part, since a block that is only as different as its own noise still merges and
    // a block that is differently wrong does not.
    static constexpr float kNlmChannelScale = 1.5f;
    static constexpr float kNlmFloorMin = 1.5f;
    static constexpr float kNlmFloorMax = 12.0f;
    // 0.45 puts a block at its own floor on 0.64 and one that is twice as different on 0.17.
    // The block weight is allowed to be generous because the per-pixel gate in the shader is
    // what actually protects a motion boundary: the block only has to say the match is
    // plausible, the pixel decides whether this sample still agrees with it.
    static constexpr float kNlmExponent = 0.45f;
    // Fraction of the direction scores used to place the noise floor. The flat blocks of a real
    // frame are always the majority, so a low percentile lands on them without needing to know
    // which blocks are flat.
    static constexpr int kNlmFloorPercentile = 20;

    void ensureCapacity(int w, int h);
    // One-shot micro-benchmark logged on the first frame: raw NEON streaming throughput and
    // raw sad16x16 throughput, each in wall time and thread CPU time. Used to tell an
    // under-powered core apart from a thread that is being starved by the rest of the app.
    void microBench();
    void extractLuma(const uint8_t* rgba, int w, int h, uint8_t* luma);
    void downsample2(const uint8_t* src, int sw, int sh, uint8_t* dst);
    void buildPyramid(const uint8_t* luma, int w, int h, uint8_t** pyr);

    // tgt is the frame whose blocks we take; ref is the frame we search in.
    // On return mvx/mvy hold the full-resolution offset such that
    // ref(x + mv) matches tgt(x).
    // Returns the summed plain SAD of the accepted vectors at the finest level: the
    // motion-compensated difference of the pair, which is the scene-change signal.
    long long motionEstimate(uint8_t* const tgtPyr[kLevels], uint8_t* const refPyr[kLevels],
                             int w, int h, int32_t* mvx, int32_t* mvy);

    void motionCompensate(const uint8_t* in0, const uint8_t* in1,
                          int w, int h, float timestep,
                          const int32_t* mvf_x, const int32_t* mvf_y,
                          const int32_t* mvb_x, const int32_t* mvb_y,
                          const uint8_t* maskf, const uint8_t* maskb,
                          uint8_t* out);

    // One coherence pass: each block may adopt a neighbour's vector when that vector scores
    // better on this block. Candidates come from mvx/mvy, results go to outx/outy, so the pass
    // never reads what it is writing and parallel rows cannot race. Call it twice with the
    // output fed back as the input to let a correction travel more than one block.
    void regulariseField(const uint8_t* tgt, const uint8_t* ref, int w, int h,
                         const int32_t* mvx, const int32_t* mvy,
                         int32_t* outx, int32_t* outy);

    // Cover/uncover masks derived from the two fields, one byte per block. Parallel only over
    // the two directions: each pass is a single linear sweep of a few thousand bytes.
    void buildOcclusionMasks(int w, int h);

    // Shared front half of interpolate() and motionField(): scratch sizing, the optional shrink to
    // the processing size, RGBA->luma, the pyramid and both motion estimates. On success *aOut and
    // *bOut (when non-null) point at the processing-sized frames - the inputs themselves when no
    // resize was needed, otherwise resized0_/resized1_. forwardOnly drops the backward estimate
    // (and zeroes that half of the field) for callers that never read it.
    bool prepare(const uint8_t* src0, const uint8_t* src1,
                 int srcW, int srcHeight,
                 int targetWidth, int targetHeight,
                 bool forwardOnly = false,
                 const uint8_t** aOut = nullptr,
                 const uint8_t** bOut = nullptr);

    // Serial: bwy*bwx is a few thousand bytes, so a parallel region would cost more in barrier
    // time than the write itself. forwardOnly drops the backward residual from the noise-floor
    // histogram: with that field zeroed the score measures unwarped misalignment rather than
    // noise, and letting it in would raise the floor and make the published weights merge harder
    // than the forward measurement alone justifies.
    void packMotionField(int w, int h, uint8_t* outMv, bool forwardOnly);

    template <typename F>
    void parallelFor(int begin, int end, F&& fn);

    std::atomic<int> threads_{1};
    std::atomic<double> last_ms_{0.0};
    SvConfig sv_config_{};

    // Per-stage accumulation for diagnostics. Only touched by the pipeline worker thread
    // (interpolate() is never re-entered), so no synchronisation is needed.
    static constexpr long long kStageReportFrames = 60;
    long long acc_frames_ = 0;
    long long acc_setup_ns_ = 0;
    long long acc_resize_ns_ = 0;
    long long acc_luma_ns_ = 0;
    long long acc_luma_cpu_ns_ = 0;
    long long acc_pyr_ns_ = 0;
    long long acc_fwd_ns_ = 0;
    long long acc_bwd_ns_ = 0;
    long long acc_warp_ns_ = 0;
    long long acc_total_ns_ = 0;

    // Last reported MemcPool snapshot, so each report covers only its own window.
    long long last_pool_run_ = 0;
    long long last_pool_work_ = 0;
    long long last_pool_runs_ = 0;

    void reportStagesIfDue();

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
    // Destination for one regulariseField() pass; a few kilobytes even at 1080p.
    std::vector<int32_t> tmpx_, tmpy_;
    // Cover/uncover masks, one byte per block, derived from the two fields above.
    std::vector<uint8_t> maskf_, maskb_;
    // Set by prepare() when the scene-change gate fires, so packMotionField() can suppress the
    // blend weights it is about to derive from a field that was deliberately zeroed.
    bool sceneCut_ = false;
    // Per-block residual scores feeding the noise-floor histogram: one entry per block per
    // direction, so a frame is a few thousand entries and the scratch is reused every time.
    std::vector<uint8_t> nlmScore_;
};

}  // namespace rife
