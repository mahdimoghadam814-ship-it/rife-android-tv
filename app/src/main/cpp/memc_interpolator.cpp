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

// Floor of v/256 for a signed 1/256-pixel coordinate. Written out rather than left to >>,
// which C++ leaves implementation-defined for negative operands.
static inline int floorShift8(int32_t v) {
    return v >= 0 ? (v >> 8) : -(((-v) + 255) >> 8);
}

// Round S/16 with round-half-away-from-zero. S is a vector-weighted sum whose denominator is
// 256 (pitch * pitch), so /16 lands the whole-pixel average on the 1/16 px grid the warp needs.
static inline int32_t div16r(int32_t v) {
    return v >= 0 ? (v + 8) >> 4 : -(((-v) + 8) >> 4);
}

// Maps a pixel coordinate onto the motion-vector grid, which holds one vector per [pitch] pixels
// anchored at the block centres. Returns the two bracketing grid indices and the weight of the
// second in 1/pitch units. Within half a block of a frame border the weight is clamped instead
// of extrapolated, so the outermost vector simply stretches to the edge.
static inline void mvGridAxis(int pos, int blocks, int pitch, int* i0, int* i1, int* w1) {
    if (blocks < 2) {
        *i0 = 0;
        *i1 = 0;
        *w1 = 0;
        return;
    }
    int g = (pos - pitch / 2) / pitch;  // truncates toward zero, which is what we want below 0
    if (g < 0) g = 0;
    if (g > blocks - 2) g = blocks - 2;
    // frac is measured from the clamped bracket, not from the bracket the raw index landed in.
    // The last pitch/2 pixels of the frame sit past the final block centre, and measuring them
    // from the discarded index made them interpolate half-way back into the previous block -
    // a visible seam along the bottom and right edges. This also reproduces the clamp-to-edge
    // bilinear the GPU shader uses, so both paths sample the same vector.
    int frac = pos - (g * pitch + pitch / 2);
    if (frac < 0) frac = 0;
    if (frac > pitch) frac = pitch;
    *i0 = g;
    *i1 = g + 1;
    *w1 = frac;
}

// One RGBA pixel, zero-extended into the low four lanes of a vector.
static inline uint8x8_t pixelVec(const uint8_t* p) {
    uint32_t v;
    memcpy(&v, p, 4);
    return vreinterpret_u8_u32(vdup_n_u32(v));
}

// 4-tap bilinear read of one RGBA pixel at a 1/256-pixel coordinate, returned in the low four
// lanes of a vector. Both taps clamp at the frame edge, so a sample that drifts outside the
// picture reuses the border pixel.
//
// The horizontal stage is evaluated as  h = P00 * 256 + (P01 - P00) * fx  rather than as
// P00 * (256 - fx) + P01 * fx, because (256 - fx) can be 256 and therefore does not fit the
// 8-bit multiplier. Every intermediate of the rewritten form stays under 65536, so the whole
// stage runs in 16-bit lanes.
static inline uint8x8_t sampleBilinear(const uint8_t* img, int w, int h,
                                       int32_t sx, int32_t sy) {
    const int x0 = floorShift8(sx);
    const int y0 = floorShift8(sy);
    const int fx = sx - (x0 << 8);
    const int fy = sy - (y0 << 8);
    const int xa = clampi(x0, 0, w - 1);
    const int xb = clampi(x0 + 1, 0, w - 1);
    const int ya = clampi(y0, 0, h - 1);
    const int yb = clampi(y0 + 1, 0, h - 1);

    const uint8_t* rowA = img + (static_cast<size_t>(ya) * w) * 4;
    const uint8_t* rowB = img + (static_cast<size_t>(yb) * w) * 4;

    // Read each tap with memcpy rather than a uint32_t* cast: the frame is a byte buffer, so a
    // wider-typed load would violate strict aliasing. Clang folds every call back to one load.
    const uint8x8_t t0 = pixelVec(rowA + xa * 4);
    const uint8x8_t t1 = pixelVec(rowA + xb * 4);
    const uint8x8_t t2 = pixelVec(rowB + xa * 4);
    const uint8x8_t t3 = pixelVec(rowB + xb * 4);

    const uint16x8_t s0 = vmovl_u8(t0);
    const uint16x8_t s1 = vmovl_u8(t1);
    const uint16x8_t s2 = vmovl_u8(t2);
    const uint16x8_t s3 = vmovl_u8(t3);
    const uint16x8_t dfx = vdupq_n_u16(static_cast<uint16_t>(fx));

    const uint16x8_t h0 = vaddq_u16(vshlq_n_u16(s0, 8), vmulq_u16(vsubq_u16(s1, s0), dfx));
    const uint16x8_t h1 = vaddq_u16(vshlq_n_u16(s2, 8), vmulq_u16(vsubq_u16(s3, s2), dfx));

    const uint32x4_t v = vaddq_u32(
        vmulq_u32(vmovl_u16(vget_low_u16(h0)), vdupq_n_u32(256u - static_cast<uint32_t>(fy))),
        vmulq_u32(vmovl_u16(vget_low_u16(h1)), vdupq_n_u32(static_cast<uint32_t>(fy))));
    const uint32x4_t r = vshrq_n_u32(vaddq_u32(v, vdupq_n_u32(32768)), 16);

    return vmovn_u16(vcombine_u16(vmovn_u32(r), vdup_n_u16(0)));
}

// out = (a * (256 - k) + b * k + 128) >> 8, written as  a * 256 + (b - a) * k + 128  for the
// same reason as above: k can be 256. The exact result always fits 16 bits, so modular
// arithmetic in the intermediates is safe.
static inline uint8x8_t blend256(uint8x8_t a, uint8x8_t b, int k) {
    const uint16x8_t ua = vmovl_u8(a);
    const uint16x8_t d = vsubq_u16(vmovl_u8(b), ua);
    uint16x8_t v = vaddq_u16(vshlq_n_u16(ua, 8), vmulq_u16(d, vdupq_n_u16(static_cast<uint16_t>(k))));
    v = vshrq_n_u16(vaddq_u16(v, vdupq_n_u16(128)), 8);
    return vmovn_u16(v);
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

// ARM YIELD: tells the SMT/scheduler we are in a benign spin. Costs nothing when unsupported.
static inline void spinPause() {
#if defined(__aarch64__) || defined(__arm__)
    __asm__ volatile("yield" ::: "memory");
#elif defined(__x86_64__) || defined(__i386__)
    __builtin_ia32_pause();
#endif
}

// How long a thread may busy-wait for the next parallel phase before parking on the condition
// variable. A futex wake on the box costs 1-2 ms of *wall* time per phase because MediaCodec and
// SurfaceFlinger are fighting over the same four A55 cores: on run 6 the pool measured
// runWall=44.0 ms against work=94.1 ms - 81.9 ms of thread-time idle across 11 phases, i.e. the
// barriers alone were adding ~1.9 ms to every phase. The threads we would have parked are the
// ones about to run the next phase anyway, so spinning for a few ms is close to free here and
// recovers most of that.
static constexpr long long kSpinNs = 3LL * 1000 * 1000;

// Returns true if `pred` became true while spinning, false if the deadline was hit first.
template <typename Pred>
static bool spinUntil(Pred pred) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::nanoseconds(kSpinNs);
    for (int i = 0;; i++) {
        if (pred()) return true;
        spinPause();
        if ((i & 511) == 511 && std::chrono::steady_clock::now() >= deadline) return false;
    }
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
        stop_.store(false, std::memory_order_relaxed);
        pending_.store(0, std::memory_order_relaxed);
        gen_.store(0, std::memory_order_relaxed);
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

        // Wait for the workers without leaving the core: the last worker publishes pending_==0
        // with release, so an acquire load here is enough. The lock is taken only afterwards to
        // merge the caller's own work time into the shared stats.
        if (!spinUntil([this] { return pending_.load(std::memory_order_acquire) == 0; })) {
            std::unique_lock<std::mutex> waitLk(m_);
            cvDone_.wait(waitLk, [this] { return pending_.load(std::memory_order_relaxed) == 0; });
        }
        std::unique_lock<std::mutex> lk(m_);
        work_ns_ += callerWork;
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
                // Lock-free wait for the next phase. run() publishes fn_/begin_/end_/chunk_
                // under m_ and *then* bumps gen_, so an acquire load that observes the new
                // generation is enough; the lock below is only needed to read them.
                const bool got = spinUntil([this, seen] {
                    return stop_.load(std::memory_order_acquire) ||
                           gen_.load(std::memory_order_acquire) != seen;
                });
                if (!got) {
                    std::unique_lock<std::mutex> parkLk(m_);
                    cvStart_.wait(parkLk, [this, seen] {
                        return stop_.load(std::memory_order_relaxed) ||
                               gen_.load(std::memory_order_relaxed) != seen;
                    });
                }
                if (stop_.load(std::memory_order_acquire)) return;
                std::unique_lock<std::mutex> lk(m_);
                seen = gen_.load(std::memory_order_relaxed);
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
            stop_.store(true, std::memory_order_release);
            cvStart_.notify_all();
        }
        for (auto& t : threads_) {
            if (t.joinable()) t.join();
        }
        std::unique_lock<std::mutex> lk(m_);
        threads_.clear();
        started_ = false;
        stop_.store(false, std::memory_order_relaxed);
        pending_.store(0, std::memory_order_relaxed);
    }

    std::mutex m_;
    std::condition_variable cvStart_, cvDone_;
    const std::function<void(int, int)>* fn_ = nullptr;
    int participants_ = 1;
    int begin_ = 0, end_ = 0, chunk_ = 0;
    std::atomic<int> pending_ = 0;    // atomic: read lock-free by the spinning caller
    long long run_ns_ = 0;
    long long work_ns_ = 0;
    long long runs_ = 0;
    std::atomic<uint64_t> gen_ = 0;   // atomic: bumped under m_ after the job is published
    std::atomic<bool> stop_ = false;  // atomic: read lock-free by spinning workers
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
    // Final time blend as a 1/256 weight on in1: (a*(256-k) + b*k + 128) >> 8 is an exact,
    // division-free lerp. k=128 reproduces the old (a+b+1)>>1 exactly, but the weight now
    // tracks the timestep instead of being hard-wired to 50/50.
    const int wt = clampi(static_cast<int>(t * 256.0f + 0.5f), 0, 256);
    // Vectors are whole pixels. Scaling by 16 puts the interpolated field on a 1/16 px grid, and
    // one further multiply by the timestep lands on the 1/256 px fixed point the sampler reads.
    const float ts = t * 16.0f;
    const float us = u * 16.0f;
    // Number of distinct bracketing pairs along x: pixels past the last vector reuse it, and a
    // frame narrower than one block has a single pair covering the whole row.
    const int ncol = bwx < 2 ? 1 : bwx - 1;

    // Dense per-pixel warp: every output pixel interpolates its own vector from the four nearest
    // block centres, so motion varies continuously across the frame instead of stepping at block
    // edges. Each sample is a 4-tap bilinear read, which is what removes the block seams.
    parallelFor(0, h, [&](int rowBegin, int rowEnd) {
        for (int y = rowBegin; y < rowEnd; y++) {
            int iy0, iy1, wy1;
            mvGridAxis(y, bwy, kBlock, &iy0, &iy1, &wy1);
            const int wy0 = kBlock - wy1;
            const int rowA0 = iy0 * bwx;
            const int rowA1 = iy1 * bwx;
            const size_t outRow = static_cast<size_t>(y) * w;

            for (int bc = 0; bc < ncol; bc++) {
                const int xStart = (bc == 0) ? 0 : bc * kBlock + kBlock / 2;
                int xEnd = (bc + 2 >= bwx) ? w : bc * kBlock + kBlock / 2 + kBlock;
                if (xEnd > w) xEnd = w;
                if (xEnd <= xStart) continue;

                const int i00 = rowA0 + bc;
                const int i01 = (bwx < 2) ? i00 : i00 + 1;
                const int i10 = rowA1 + bc;
                const int i11 = (bwx < 2) ? i10 : i10 + 1;

                // Fold the y-axis weight into the vectors once per (row, column), so the inner
                // loop needs two multiplies per vector instead of four. The result is the vector
                // sum in units of whole pixels * (wy0 + wy1); div16r() then divides out the
                // remaining pitch factor and rounds onto the 1/16 px grid.
                const int32_t p0fx = mvf_x[i00] * wy0 + mvf_x[i10] * wy1;
                const int32_t p1fx = mvf_x[i01] * wy0 + mvf_x[i11] * wy1;
                const int32_t p0fy = mvf_y[i00] * wy0 + mvf_y[i10] * wy1;
                const int32_t p1fy = mvf_y[i01] * wy0 + mvf_y[i11] * wy1;
                const int32_t p0bx = mvb_x[i00] * wy0 + mvb_x[i10] * wy1;
                const int32_t p1bx = mvb_x[i01] * wy0 + mvb_x[i11] * wy1;
                const int32_t p0by = mvb_y[i00] * wy0 + mvb_y[i10] * wy1;
                const int32_t p1by = mvb_y[i01] * wy0 + mvb_y[i11] * wy1;
                const int baseX = bc * kBlock + kBlock / 2;

                for (int x = xStart; x < xEnd; x++) {
                    int wx1 = x - baseX;
                    if (wx1 < 0) wx1 = 0;
                    if (wx1 > kBlock) wx1 = kBlock;
                    const int wx0 = kBlock - wx1;

                    const int32_t fxi = div16r(p0fx * wx0 + p1fx * wx1);
                    const int32_t fyi = div16r(p0fy * wx0 + p1fy * wx1);
                    const int32_t bxi = div16r(p0bx * wx0 + p1bx * wx1);
                    const int32_t byi = div16r(p0by * wx0 + p1by * wx1);

                    // Flow points forward (where the pixel went), so the sample position is
                    // traced back along it: pa = in0[x - mvf*t], pb = in1[x - mvb*(1-t)].
                    const int32_t ax = (x << 8) - static_cast<int32_t>(fxi * ts + (fxi >= 0 ? 0.5f : -0.5f));
                    const int32_t ay = (y << 8) - static_cast<int32_t>(fyi * ts + (fyi >= 0 ? 0.5f : -0.5f));
                    const int32_t bx = (x << 8) - static_cast<int32_t>(bxi * us + (bxi >= 0 ? 0.5f : -0.5f));
                    const int32_t by = (y << 8) - static_cast<int32_t>(byi * us + (byi >= 0 ? 0.5f : -0.5f));

                    const uint8x8_t ca = sampleBilinear(in0, w, h, ax, ay);
                    const uint8x8_t cb = sampleBilinear(in1, w, h, bx, by);

                    uint32_t px = 0;
                    vst1_lane_u32(&px, vreinterpret_u32_u8(blend256(ca, cb, wt)), 0);
                    // Sources come from an opaque EGL readback, but force alpha to match
                    // RifeEngine's 255 contract exactly.
                    uint32_t* po = reinterpret_cast<uint32_t*>(
                        out + (outRow + static_cast<size_t>(x)) * 4);
                    *po = px | 0xFF000000u;
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
