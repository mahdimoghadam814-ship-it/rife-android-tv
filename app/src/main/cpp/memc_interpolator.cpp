#include "memc_interpolator.h"

#include <android/log.h>
#include <algorithm>
#include <arm_neon.h>
#include <chrono>
#include <condition_variable>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#define LOGI_MEMC(...) __android_log_print(ANDROID_LOG_INFO, "RIFE-MEMC", __VA_ARGS__)

namespace rife {
namespace {

// BT.601 luma from 8-bit RGBA. Keeps the search on 1 byte/pixel instead of 4.
static inline uint8_t lumaOf(uint8_t r, uint8_t g, uint8_t b) {
    return static_cast<uint8_t>((77u * r + 150u * g + 29u * b + 128u) >> 8);
}

// Sum of absolute differences over a 16x16 block.
// The per-lane accumulator stays in uint16: worst case is 16 rows * 2 * 255 = 8160.
static inline int sad16x16(const uint8_t* a, const uint8_t* b, int stride) {
    uint16x8_t acc = vdupq_n_u16(0);
    for (int y = 0; y < 16; y++) {
        const uint8_t* ra = a + y * stride;
        const uint8_t* rb = b + y * stride;
        uint8x16_t d = vabdq_u8(vld1q_u8(ra), vld1q_u8(rb));
        acc = vaddq_u16(acc, vmovl_u8(vget_low_u8(d)));
        acc = vaddq_u16(acc, vmovl_u8(vget_high_u8(d)));
    }
    uint16x4_t s = vadd_u16(vget_low_u16(acc), vget_high_u16(acc));
    uint32x2_t p = vpaddl_u16(s);
    uint32x2_t q = vpadd_u32(p, p);
    return static_cast<int>(vget_lane_u32(q, 0));
}

static inline int clampi(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// Round half away from zero without pulling in lround() - the per-pixel MC loop
// would otherwise make millions of libm calls per frame.
static inline int roundScaled(int32_t mv, float scale) {
    const float f = static_cast<float>(mv) * scale;
    return static_cast<int>(f >= 0.0f ? f + 0.5f : f - 0.5f);
}

// CPU time actually consumed by the calling thread, excluding time spent descheduled.
// Comparing this against wall time separates "the core is too slow" from "the thread
// is being starved by MediaCodec / GL / SurfaceFlinger sharing the same four cores".
static long long threadCpuNs() {
    struct timespec ts;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

}  // namespace

static long long nsSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now() - t0)
        .count();
}

// A persistent team of worker threads. The alternative - spawning std::threads inside every
// parallelFor() - costs ~33 thread creations per frame and measured *slower* than running the
// whole frame on one thread.
class MemcPool {
public:
    MemcPool() = default;
    ~MemcPool() { shutdown(); }

    MemcPool(const MemcPool&) = delete;
    MemcPool& operator=(const MemcPool&) = delete;

    // count = caller + background workers.
    void ensureCount(int count) {
        std::unique_lock<std::mutex> lk(m_);
        if (count == participants_ && started_) return;
        // Never join the team out from under a frame that is mid-run.
        if (pending_ > 0) return;
        lk.unlock();
        shutdown();
        lk.lock();
        participants_ = count;
        if (count <= 1) {
            started_ = true;
            return;
        }
        stop_ = false;
        pending_ = 0;
        gen_ = 0;
        threads_.clear();
        threads_.reserve(static_cast<size_t>(count - 1));
        for (int i = 1; i < count; i++) {
            threads_.emplace_back([this, i] { worker(i); });
        }
        started_ = true;
    }

    // Diagnostics: run() wall time versus the summed time spent actually executing the
    // region across the caller and every worker. `work` is what a single thread would
    // roughly cost; if `runWall` >> work / participants then the barriers - not the
    // arithmetic - are what the frame is paying for.
    void stats(long long* runNs, long long* workNs, long long* runs, int* participants) {
        std::unique_lock<std::mutex> lk(m_);
        *runNs = run_ns_;
        *workNs = work_ns_;
        *runs = runs_;
        *participants = participants_;
    }

    void run(int begin, int end, const std::function<void(int, int)>& fn) {
        const auto tStart = std::chrono::steady_clock::now();
        const int total = end - begin;
        if (total <= 1 || participants_ <= 1) {
            if (total > 0) fn(begin, end);
            const long long d = nsSince(tStart);
            std::unique_lock<std::mutex> lk(m_);
            run_ns_ += d;
            work_ns_ += d;
            runs_ += 1;
            return;
        }

        int myB = begin, myE = end;
        {
            std::unique_lock<std::mutex> lk(m_);
            fn_ = &fn;
            begin_ = begin;
            end_ = end;
            chunk_ = (total + participants_ - 1) / participants_;
            myE = std::min(end, begin + chunk_);
            pending_ = participants_ - 1;
            ++gen_;
            cvStart_.notify_all();
        }

        long long callerWork = 0;
        if (myE > myB) {
            const auto tWork = std::chrono::steady_clock::now();
            fn(myB, myE);
            callerWork = nsSince(tWork);
        }

        std::unique_lock<std::mutex> lk(m_);
        work_ns_ += callerWork;
        cvDone_.wait(lk, [this] { return pending_ == 0; });
        run_ns_ += nsSince(tStart);
        runs_ += 1;
    }

private:
    void worker(int index) {
        uint64_t seen = 0;
        for (;;) {
            std::function<void(int, int)> copy;
            int b = 0, e = 0;
            {
                std::unique_lock<std::mutex> lk(m_);
                cvStart_.wait(lk, [this, seen] { return stop_ || gen_ != seen; });
                if (stop_) return;
                seen = gen_;
                copy = *fn_;
                b = begin_ + index * chunk_;
                e = std::min(end_, b + chunk_);
            }
            long long workNs = 0;
            if (e > b) {
                const auto tWork = std::chrono::steady_clock::now();
                copy(b, e);
                workNs = nsSince(tWork);
            }
            {
                std::unique_lock<std::mutex> lk(m_);
                work_ns_ += workNs;
                if (--pending_ == 0) cvDone_.notify_one();
            }
        }
    }

    void shutdown() {
        {
            std::unique_lock<std::mutex> lk(m_);
            if (!started_) return;
            stop_ = true;
            cvStart_.notify_all();
        }
        for (auto& t : threads_) {
            if (t.joinable()) t.join();
        }
        std::unique_lock<std::mutex> lk(m_);
        threads_.clear();
        started_ = false;
        stop_ = false;
        pending_ = 0;
    }

    std::mutex m_;
    std::condition_variable cvStart_, cvDone_;
    const std::function<void(int, int)>* fn_ = nullptr;
    int participants_ = 1;
    int begin_ = 0, end_ = 0, chunk_ = 0;
    int pending_ = 0;
    long long run_ns_ = 0;
    long long work_ns_ = 0;
    long long runs_ = 0;
    uint64_t gen_ = 0;
    bool stop_ = false;
    bool started_ = false;
    std::vector<std::thread> threads_;
};

MemcInterpolator::MemcInterpolator() : pool_(new MemcPool()) {}
MemcInterpolator::~MemcInterpolator() = default;

void MemcInterpolator::setThreadCount(int threads) {
    const int t = threads < 1 ? 1 : (threads > 8 ? 8 : threads);
    threads_.store(t, std::memory_order_relaxed);
    pool_->ensureCount(t);
}

void MemcInterpolator::reset() {
    dirty_.store(true, std::memory_order_release);
}

template <typename F>
void MemcInterpolator::parallelFor(int begin, int end, F&& fn) {
    pool_->run(begin, end, fn);
}

void MemcInterpolator::ensureCapacity(int w, int h) {
    if (w == work_w_ && h == work_h_) return;

    const size_t pixels = static_cast<size_t>(w) * h;
    luma0_.resize(pixels);
    luma1_.resize(pixels);
    resized0_.resize(pixels * 4);
    resized1_.resize(pixels * 4);

    // pyrN_[0] is never allocated: the full-resolution level aliases the luma plane.
    int lw = w, lh = h;
    for (int l = 1; l < kLevels; l++) {
        lw >>= 1;
        lh >>= 1;
        pyr0_[l].resize(static_cast<size_t>(lw) * lh);
        pyr1_[l].resize(static_cast<size_t>(lw) * lh);
    }

    const size_t blocks = static_cast<size_t>((w + kBlock - 1) / kBlock) *
                          static_cast<size_t>((h + kBlock - 1) / kBlock);
    mvf_x_.resize(blocks);
    mvf_y_.resize(blocks);
    mvb_x_.resize(blocks);
    mvb_y_.resize(blocks);

    work_w_ = w;
    work_h_ = h;
    LOGI_MEMC("scratch allocated %dx%d (%zu blocks, %d threads)",
              w, h, blocks, threads_.load(std::memory_order_relaxed));
}

void MemcInterpolator::extractLuma(const uint8_t* rgba, int w, int h, uint8_t* luma) {
    const size_t n = static_cast<size_t>(w) * h;
    size_t i = 0;
    const uint16x8_t bias = vdupq_n_u16(128);

    for (; i + 16 <= n; i += 16) {
        uint8x16x4_t v = vld4q_u8(rgba + i * 4);
        uint16x8_t ylo = vmlaq_n_u16(vmlaq_n_u16(
                vmlaq_n_u16(bias, vmovl_u8(vget_low_u8(v.val[0])), 77),
                vmovl_u8(vget_low_u8(v.val[1])), 150),
                vmovl_u8(vget_low_u8(v.val[2])), 29);
        uint16x8_t yhi = vmlaq_n_u16(vmlaq_n_u16(
                vmlaq_n_u16(bias, vmovl_u8(vget_high_u8(v.val[0])), 77),
                vmovl_u8(vget_high_u8(v.val[1])), 150),
                vmovl_u8(vget_high_u8(v.val[2])), 29);
        uint8x16_t y = vcombine_u8(vmovn_u16(vshrq_n_u16(ylo, 8)),
                                   vmovn_u16(vshrq_n_u16(yhi, 8)));
        vst1q_u8(luma + i, y);
    }
    for (; i < n; i++) {
        const uint8_t* p = rgba + i * 4;
        luma[i] = lumaOf(p[0], p[1], p[2]);
    }
}

void MemcInterpolator::downsample2(const uint8_t* src, int sw, int sh, uint8_t* dst) {
    const int dw = sw >> 1;
    const int dh = sh >> 1;
    parallelFor(0, dh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            const uint8_t* r0 = src + (y * 2) * sw;
            const uint8_t* r1 = src + (y * 2 + 1) * sw;
            uint8_t* o = dst + y * dw;
            for (int x = 0; x < dw; x++) {
                const int i = x * 2;
                o[x] = static_cast<uint8_t>((r0[i] + r0[i + 1] + r1[i] + r1[i + 1]) >> 2);
            }
        }
    });
}

void MemcInterpolator::buildPyramid(const uint8_t* luma, int w, int h, uint8_t** pyr) {
    pyr[0] = const_cast<uint8_t*>(luma);
    int cw = w, ch = h;
    for (int l = 1; l < kLevels; l++) {
        const int nw = cw >> 1;
        const int nh = ch >> 1;
        downsample2(pyr[l - 1], cw, ch, pyr[l]);
        cw = nw;
        ch = nh;
    }
}

void MemcInterpolator::motionEstimate(uint8_t* const tgtPyr[kLevels],
                                      uint8_t* const refPyr[kLevels],
                                      int w, int h,
                                      int32_t* mvx, int32_t* mvy) {
    // Ceil grid: the last column/row of blocks is a partial tile covering the
    // remainder of a frame whose width is not a multiple of 16.
    const int bwx = (w + kBlock - 1) / kBlock;
    const int bwy = (h + kBlock - 1) / kBlock;
    const size_t nblocks = static_cast<size_t>(bwx) * bwy;

    std::fill(mvx, mvx + nblocks, 0);
    std::fill(mvy, mvy + nblocks, 0);
    if (w < kBlock || h < kBlock) return;   // no full 16x16 window exists; MVs stay zero

    // Coarse -> fine. One search at level l covers a `group x group` set of
    // full-resolution blocks; the result is broadcast to that group so the next
    // finer level starts from a real motion estimate instead of from zero.
    for (int l = kLevels - 1; l >= 0; l--) {
        const int div = 1 << l;
        const int lw = w >> l;
        const int lh = h >> l;
        if (lw < kBlock || lh < kBlock) continue;
        // One level-l block covers a div x div group of full-res blocks. Iterating only
        // over complete groups keeps lr -> frow injective, which is what stops two worker
        // threads from writing the same motion-vector cell.
        const int groupsY = (bwy + div - 1) / div;
        const int groupsX = (bwx + div - 1) / div;
        const int lrows = std::min((lh + kBlock - 1) / kBlock, groupsY);
        const int lcols = std::min((lw + kBlock - 1) / kBlock, groupsX);
        const int range = (l == kLevels - 1) ? kCoarseRange : kFineRange;
        const int group = div;                      // full-res blocks per side
        const uint8_t* tgt = tgtPyr[l];
        const uint8_t* ref = refPyr[l];

        parallelFor(0, lrows, [&](int r0, int r1) {
            for (int lr = r0; lr < r1; lr++) {
                const int by = std::min(lr * kBlock, lh - kBlock);
                for (int lc = 0; lc < lcols; lc++) {
                    const int bx = std::min(lc * kBlock, lw - kBlock);

                    const int fcol = lc * div;
                    const int frow = lr * div;
                    const int idx = frow * bwx + fcol;

                    const int gx = mvx[idx] / div;
                    const int gy = mvy[idx] / div;

                    const uint8_t* tb = tgt + by * lw + bx;
                    int best = 0x7FFFFFFF;
                    int bestDx = gx, bestDy = gy;

                    for (int dy = -range; dy <= range; dy++) {
                        const int yy = by + gy + dy;
                        if (yy < 0 || yy + kBlock > lh) continue;
                        const uint8_t* row = ref + yy * lw;
                        for (int dx = -range; dx <= range; dx++) {
                            const int xx = bx + gx + dx;
                            if (xx < 0 || xx + kBlock > lw) continue;
                            const int s = sad16x16(tb, row + xx, lw);
                            if (s < best) {
                                best = s;
                                bestDx = gx + dx;
                                bestDy = gy + dy;
                            }
                        }
                    }

                    const int32_t mvFullX = static_cast<int32_t>(bestDx * div);
                    const int32_t mvFullY = static_cast<int32_t>(bestDy * div);
                    for (int dr = 0; dr < group; dr++) {
                        const int rr = frow + dr;
                        if (rr >= bwy) break;
                        for (int dc = 0; dc < group; dc++) {
                            const int cc = fcol + dc;
                            if (cc >= bwx) break;
                            const int o = rr * bwx + cc;
                            mvx[o] = mvFullX;
                            mvy[o] = mvFullY;
                        }
                    }
                }
            }
        });
    }
}

void MemcInterpolator::motionCompensate(const uint8_t* in0, const uint8_t* in1,
                                        int w, int h, float timestep,
                                        const int32_t* mvf_x, const int32_t* mvf_y,
                                        const int32_t* mvb_x, const int32_t* mvb_y,
                                        uint8_t* out) {
    const int bwx = (w + kBlock - 1) / kBlock;
    const int bwy = (h + kBlock - 1) / kBlock;
    const float t = timestep;
    const float u = 1.0f - t;
    // A full block's source window is clamped into [0, dim-16] so the warp is a fully
    // in-bounds contiguous 16x16 read, which is what makes the NEON path possible. Partial
    // edge tiles (frame size not a multiple of 16) fall back to per-pixel clamping.
    const int maxX = w - kBlock;
    const int maxY = h - kBlock;

    parallelFor(0, bwy, [&](int r0, int r1) {
        for (int br = r0; br < r1; br++) {
            const int y0 = br * kBlock;
            const int bh = std::min(kBlock, h - y0);
            for (int bc = 0; bc < bwx; bc++) {
                const int x0 = bc * kBlock;
                const int bw = std::min(kBlock, w - x0);
                const int idx = br * bwx + bc;

                // Flow points forward (where the pixel went), so the sample position is the
                // requested coordinate traced BACK along it: pa = in0[x - mvf*t],
                // pb = in1[x - mvb*(1-t)].
                const int srcAx = x0 - roundScaled(mvf_x[idx], t);
                const int srcAy = y0 - roundScaled(mvf_y[idx], t);
                const int srcBx = x0 - roundScaled(mvb_x[idx], u);
                const int srcBy = y0 - roundScaled(mvb_y[idx], u);

                if (bw == kBlock && bh == kBlock) {
                    const int ax = clampi(srcAx, 0, maxX);
                    const int ay = clampi(srcAy, 0, maxY);
                    const int bx = clampi(srcBx, 0, maxX);
                    const int by = clampi(srcBy, 0, maxY);

                    for (int i = 0; i < kBlock; i++) {
                        const uint8_t* pa = in0 + (static_cast<size_t>(ay + i) * w + ax) * 4;
                        const uint8_t* pb = in1 + (static_cast<size_t>(by + i) * w + bx) * 4;
                        uint8_t* po = out + (static_cast<size_t>(y0 + i) * w + x0) * 4;
                        for (int k = 0; k < kBlock * 4; k += 16) {
                            // vrhaddq = rounding halving add, i.e. (a + b + 1) >> 1
                            vst1q_u8(po + k, vrhaddq_u8(vld1q_u8(pa + k), vld1q_u8(pb + k)));
                        }
                        // Sources come from an opaque EGL readback so the average is already
                        // 255, but force it to match RifeEngine's alpha=255 contract exactly.
                        for (int k = 0; k < kBlock; k++) po[k * 4 + 3] = 255;
                    }
                } else {
                    for (int i = 0; i < bh; i++) {
                        const size_t dy = static_cast<size_t>(y0 + i);
                        for (int j = 0; j < bw; j++) {
                            const int dx = x0 + j;
                            const uint8_t* pa = in0 +
                                (static_cast<size_t>(clampi(srcAy + i, 0, h - 1)) * w +
                                 static_cast<size_t>(clampi(srcAx + j, 0, w - 1))) * 4;
                            const uint8_t* pb = in1 +
                                (static_cast<size_t>(clampi(srcBy + i, 0, h - 1)) * w +
                                 static_cast<size_t>(clampi(srcBx + j, 0, w - 1))) * 4;
                            uint8_t* po = out + (dy * w + static_cast<size_t>(dx)) * 4;
                            for (int c = 0; c < 3; c++) {
                                po[c] = static_cast<uint8_t>((pa[c] + pb[c]) >> 1);
                            }
                            po[3] = 255;
                        }
                    }
                }
            }
        }
    });
}

void MemcInterpolator::microBench() {
    static bool done = false;
    if (done) return;
    done = true;

    // Streaming NEON: the extractLuma inner loop, 4 reps over 8 MB of RGBA.
    // The result is folded into a sink so the loop cannot be dead-code eliminated.
    {
        const int W = 2048, H = 1024;
        std::vector<uint8_t> rgba(static_cast<size_t>(W) * H * 4);
        std::vector<uint8_t> luma(static_cast<size_t>(W) * H);
        for (size_t i = 0; i < rgba.size(); i++) rgba[i] = static_cast<uint8_t>(i * 131u);

        const auto w0 = std::chrono::steady_clock::now();
        const long long c0 = threadCpuNs();
        unsigned sink = 0;
        for (int rep = 0; rep < 4; rep++) {
            extractLuma(rgba.data(), W, H, luma.data());
            for (size_t i = 0; i < luma.size(); i += 997) sink = sink * 31u + luma[i];
        }
        const long long cpu = threadCpuNs() - c0;
        const long long wall = nsSince(w0);
        const double mb = 4.0 * 10.0;  // 4 reps x (8 MB read + 2 MB written)
        LOGI_MEMC("BENCH neon wall=%.2f cpu=%.2f ms  %.0f MB/s(wall) %.0f MB/s(cpu) "
                  "sink=%u",
                  wall / 1e6, cpu / 1e6, mb * 1e9 / wall, mb * 1e9 / cpu, sink);
    }

    // sad16x16 throughput: 100k calls over two 1 MB planes with the real stride pattern.
    {
        std::vector<uint8_t> planeA(1024 * 1024, 5);
        std::vector<uint8_t> planeB(1024 * 1024, 9);
        volatile int sink = 0;
        int acc = 0;
        const auto w0 = std::chrono::steady_clock::now();
        const long long c0 = threadCpuNs();
        for (int i = 0; i < 100000; i++) {
            const size_t ra = static_cast<size_t>(i % 1000);
            const size_t rb = static_cast<size_t>((i * 1021u) % 1000);
            acc += sad16x16(planeA.data() + ra * 1024 + 16,
                            planeB.data() + rb * 1024 + 16, 1024);
        }
        sink = acc;
        const long long cpu = threadCpuNs() - c0;
        const long long wall = nsSince(w0);
        LOGI_MEMC("BENCH sad wall=%.2f cpu=%.2f ms  %.2f M calls/s(wall) "
                  "%.2f M calls/s(cpu) sink=%d",
                  wall / 1e6, cpu / 1e6, 100.0 / (wall / 1e6), 100.0 / (cpu / 1e6),
                  static_cast<int>(sink));
    }
}

bool MemcInterpolator::interpolate(const uint8_t* src0, const uint8_t* src1,
                                   int srcW, int srcHeight,
                                   int targetWidth, int targetHeight,
                                   float timestep,
                                   uint8_t* out) {
    if (!src0 || !src1 || !out) return false;
    if (srcW <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return false;
    if (targetWidth > srcW || targetHeight > srcHeight) return false;
    if (timestep < 0.0f || timestep > 1.0f) return false;

    microBench();  // one-shot; logs raw NEON and sad16x16 throughput

    const auto tSetup0 = std::chrono::steady_clock::now();
    if (dirty_.exchange(false, std::memory_order_acq_rel)) {
        // A seek / stream change happened while the previous frame was in flight.
        work_w_ = 0;
        work_h_ = 0;
    }
    pool_->ensureCount(threads_.load(std::memory_order_relaxed));

    const auto t0 = std::chrono::steady_clock::now();

    const bool sameSize = (targetWidth == srcW && targetHeight == srcHeight);
    const int w = targetWidth;
    const int h = targetHeight;

    // Capacity must be established before resized0_/resized1_ are written into.
    ensureCapacity(w, h);
    acc_setup_ns_ += nsSince(tSetup0);

    const uint8_t* a = src0;
    const uint8_t* b = src1;
    if (!sameSize) {
        const auto tResize = std::chrono::steady_clock::now();
        auto shrink = [&](const uint8_t* src, uint8_t* dst) {
            for (int y = 0; y < h; y++) {
                const int sy = static_cast<int>(static_cast<int64_t>(y) * srcHeight / h);
                const uint8_t* row = src + static_cast<size_t>(sy) * srcW * 4;
                uint8_t* o = dst + static_cast<size_t>(y) * w * 4;
                for (int x = 0; x < w; x++) {
                    const int sx = static_cast<int>(static_cast<int64_t>(x) * srcW / w);
                    memcpy(o + x * 4, row + sx * 4, 4);
                }
            }
        };
        shrink(src0, resized0_.data());
        shrink(src1, resized1_.data());
        a = resized0_.data();
        b = resized1_.data();
        acc_resize_ns_ += nsSince(tResize);
    }

    const auto tLuma = std::chrono::steady_clock::now();
    const long long tLumaCpu = threadCpuNs();
    extractLuma(a, w, h, luma0_.data());
    extractLuma(b, w, h, luma1_.data());
    acc_luma_cpu_ns_ += threadCpuNs() - tLumaCpu;
    acc_luma_ns_ += nsSince(tLuma);

    // buildPyramid only fills level 0 (it aliases the luma plane); the coarser levels
    // point at the scratch buffers sized by ensureCapacity().
    uint8_t* pyr0[kLevels] = {luma0_.data(), pyr0_[1].data(), pyr0_[2].data()};
    uint8_t* pyr1[kLevels] = {luma1_.data(), pyr1_[1].data(), pyr1_[2].data()};
    const auto tPyr = std::chrono::steady_clock::now();
    buildPyramid(luma0_.data(), w, h, pyr0);
    buildPyramid(luma1_.data(), w, h, pyr1);
    acc_pyr_ns_ += nsSince(tPyr);

    // forward  = where in0's blocks ended up in in1  (ref = in1, tgt = in0)
    // backward = where in1's blocks came from in in0 (ref = in0, tgt = in1)
    const auto tFwd = std::chrono::steady_clock::now();
    motionEstimate(pyr0, pyr1, w, h, mvf_x_.data(), mvf_y_.data());
    acc_fwd_ns_ += nsSince(tFwd);

    const auto tBwd = std::chrono::steady_clock::now();
    motionEstimate(pyr1, pyr0, w, h, mvb_x_.data(), mvb_y_.data());
    acc_bwd_ns_ += nsSince(tBwd);

    const auto tWarp = std::chrono::steady_clock::now();
    motionCompensate(a, b, w, h, timestep,
                     mvf_x_.data(), mvf_y_.data(),
                     mvb_x_.data(), mvb_y_.data(),
                     out);
    acc_warp_ns_ += nsSince(tWarp);

    acc_total_ns_ += nsSince(t0);
    acc_frames_ += 1;

    last_ms_.store(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - t0).count(), std::memory_order_relaxed);
    reportStagesIfDue();
    return true;
}

void MemcInterpolator::reportStagesIfDue() {
    if (acc_frames_ < kStageReportFrames) return;

    const double n = static_cast<double>(acc_frames_);
    auto ms = [n](long long ns) { return ns / n / 1e6; };

    long long poolRun = 0, poolWork = 0, poolRuns = 0;
    int participants = 1;
    pool_->stats(&poolRun, &poolWork, &poolRuns, &participants);
    const long long dRun = poolRun - last_pool_run_;
    const long long dWork = poolWork - last_pool_work_;
    const long long dRuns = poolRuns - last_pool_runs_;
    last_pool_run_ = poolRun;
    last_pool_work_ = poolWork;
    last_pool_runs_ = poolRuns;

    const double setup = ms(acc_setup_ns_);
    const double resize = ms(acc_resize_ns_);
    const double luma = ms(acc_luma_ns_);
    const double lumaCpu = ms(acc_luma_cpu_ns_);
    const double pyr = ms(acc_pyr_ns_);
    const double fwd = ms(acc_fwd_ns_);
    const double bwd = ms(acc_bwd_ns_);
    const double warp = ms(acc_warp_ns_);
    const double frame = setup + resize + luma + pyr + fwd + bwd + warp;

    const double runPerFrame = dRun / n / 1e6;
    const double workPerFrame = dWork / n / 1e6;
    const double runsPerFrame = dRuns / n;
    // Ideally work ~= runWall * participants. A low value means the frame is paying for
    // thread wakeups and barriers rather than for arithmetic; workPerFrame then estimates
    // what the whole frame would cost on a single thread.
    const double eff = (runPerFrame > 0.0 && participants > 1)
                           ? 100.0 * workPerFrame / (runPerFrame * participants)
                           : 100.0;

    LOGI_MEMC("STAGES n=%lld frame=%.1f setup=%.2f resize=%.2f luma=%.2f "
              "lumaCpu=%.2f pyr=%.2f fwd=%.1f bwd=%.1f warp=%.1f ms | pool "
              "runs/frame=%.1f runWall=%.1f work=%.1f eff=%.0f%% thr=%d",
              acc_frames_, frame, setup, resize, luma, lumaCpu, pyr, fwd, bwd, warp,
              runsPerFrame, runPerFrame, workPerFrame, eff, participants);

    acc_frames_ = 0;
    acc_setup_ns_ = 0;
    acc_resize_ns_ = 0;
    acc_luma_ns_ = 0;
    acc_luma_cpu_ns_ = 0;
    acc_pyr_ns_ = 0;
    acc_fwd_ns_ = 0;
    acc_bwd_ns_ = 0;
    acc_warp_ns_ = 0;
    acc_total_ns_ = 0;
}

}  // namespace rife
