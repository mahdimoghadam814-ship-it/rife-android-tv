#include "memc_interpolator.h"

#include <android/log.h>
#include <algorithm>
#include <arm_neon.h>
#include <chrono>
#include <condition_variable>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#define LOGI_MEMC(...) __android_log_print(ANDROID_LOG_INFO, "RIFE-MEMC", __VA_ARGS__)

namespace rife {

bool producesMotionField(int algorithm) {
    return algorithm == static_cast<int>(InterpolationAlgorithm::MEMC) ||
           algorithm == static_cast<int>(InterpolationAlgorithm::SVPLAYER);
}

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

// Sum of absolute differences over an 8x8 window. Used by the coherence pass, where four
// candidates have to be scored per block and a full 16x16 evaluation for each would cost more
// than the search it is cleaning up. Accumulator stays in uint16: 8 rows * 255 = 2040.
static inline int sad8x8(const uint8_t* a, const uint8_t* b, int stride) {
    uint16x8_t acc = vdupq_n_u16(0);
    for (int y = 0; y < 8; y++) {
        uint8x8_t d = vabd_u8(vld1_u8(a + y * stride), vld1_u8(b + y * stride));
        acc = vaddq_u16(acc, vmovl_u8(d));
    }
    uint16x4_t s = vadd_u16(vget_low_u16(acc), vget_high_u16(acc));
    uint32x2_t p = vpaddl_u16(s);
    uint32x2_t q = vpadd_u32(p, p);
    return static_cast<int>(vget_lane_u32(q, 0));
}

// SAD over an arbitrary bw x bh window, tiled in 8x8 units so every row and column stays on the
// NEON path sad8x8() uses. The matching window is the SVPlayer `block.w` / `block.h` knob: a
// wider or taller window averages over more picture, so noise and repeating texture have a
// harder time faking a match - which is the trade SVP states as "larger blocks less sensitive
// to noise, smaller blocks produce more wavy picture". bw and bh must be multiples of 8 and at
// least 8; callers reach that by rounding their window down to a whole tile.
static inline int sadWin(const uint8_t* a, const uint8_t* b, int stride, int bw, int bh) {
    if (bw == 16 && bh == 16) return sad16x16(a, b, stride);
    if (bw == 8 && bh == 8) return sad8x8(a, b, stride);
    int s = 0;
    for (int y = 0; y + 8 <= bh; y += 8) {
        const uint8_t* ra = a + y * stride;
        const uint8_t* rb = b + y * stride;
        for (int x = 0; x + 8 <= bw; x += 8) {
            s += sad8x8(ra + x, rb + x, stride);
        }
    }
    return s;
}


// MV coherence penalties, in SAD counts per full-resolution pixel of deviation. These are the
// block-matching counterpart of SVP's penalty.* settings and they are what keeps a per-block
// search from producing a field that is locally plausible but globally speckled:
//
//   kPenaltyNew    a candidate is charged for moving away from the estimate inherited from the
//                  coarser pyramid level, so the fine pass refines instead of jumping to a
//                  coincidental match in textured or repetitive areas.
//   kPenaltyZero   a candidate is charged for its own magnitude, which breaks ties in favour of
//                  stillness - flat and out-of-focus regions should read as no motion rather
//                  than as whatever the noise happened to prefer.
//   kPenaltyNeighbour a candidate is charged for differing from the block's current vector, so
//                  the coherence pass only adopts a neighbour when it is clearly better here.
//
// Magnitudes are deliberately small against a 16x16 SAD (which runs into the thousands for a
// mismatch): they decide between near-ties, they do not override the picture.
static constexpr int kPenaltyNew = 16;
static constexpr int kPenaltyZero = 4;
static constexpr int kPenaltyNeighbour = 96;

static inline int clampi(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// 8x8 SAD between plane `a` at (ax, ay) and plane `b` at (bx, by). Both window origins are
// clamped inside the plane, so a motion vector that runs off the picture compares against the
// border instead of reading past it. Returns the sum on the 0..255-per-pixel scale; callers
// divide by 64 for the mean.
static inline int sadAt(const uint8_t* a, const uint8_t* b, int stride, int w, int h,
                        int ax, int ay, int bx, int by) {
    if (w < 8 || h < 8) return 0;
    const int maxX = w - 8;
    const int maxY = h - 8;
    ax = clampi(ax, 0, maxX);
    ay = clampi(ay, 0, maxY);
    bx = clampi(bx, 0, maxX);
    by = clampi(by, 0, maxY);
    return sad8x8(a + static_cast<size_t>(ay) * stride + ax,
                  b + static_cast<size_t>(by) * stride + bx, stride);
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
    tmpx_.resize(blocks);
    tmpy_.resize(blocks);
    maskf_.resize(blocks);
    maskb_.resize(blocks);

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

long long MemcInterpolator::motionEstimate(uint8_t* const tgtPyr[kLevels],
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
    if (w < kBlock || h < kBlock) return 0;   // no full 16x16 window exists; MVs stay zero

    // The SVPlayer tuning only applies when that backend is the one selected. MEMC keeps the
    // measured baseline above unchanged so the two stay comparable from run to run.
    const bool sv = algorithm() == static_cast<int>(InterpolationAlgorithm::SVPLAYER);
    int coarseBase = kCoarseRange;
    int fineBase = kFineRange;
    int newLambdaBase = kPenaltyNew;
    int zeroLambdaBase = kPenaltyZero;
    int searchDistance = 0;
    int winW = kBlock;
    int winH = kBlock;
    if (sv) {
        const SvConfig& c = sv_config_;
        const float q = c.performanceQuality < 0.0f ? 0.0f
                      : (c.performanceQuality > 1.0f ? 1.0f : c.performanceQuality);
        // The performance bar is one ladder read three ways: how far the search looks, how hard
        // the penalties pull it back together, and how much picture each match averages over.
        coarseBase = 4 + static_cast<int>(q * 8.0f + 0.5f);
        fineBase = 1 + static_cast<int>(q * 2.0f + 0.5f);
        newLambdaBase = static_cast<int>(c.penaltyLambda + 0.5f);
        zeroLambdaBase = static_cast<int>(c.penaltyLambda * 0.25f + 0.5f);
        searchDistance = c.searchDistance > 0 ? c.searchDistance : 0;
        switch (c.blockSize) {
            case 1: winW = 16; winH = 8;   break;
            case 2: winW = 32; winH = 8;   break;
            case 3: winW = 32; winH = 16;  break;
            default:
                // AUTO: the quality bar picks the rung, so the two controls never fight over it.
                if (q < 0.4f)       { winW = 16; winH = 8;  }
                else if (q < 0.75f) { winW = 32; winH = 8;  }
                else                { winW = 32; winH = 16; }
                break;
        }
    }

    // Sum of the accepted vectors' plain 16x16 whole-pixel SAD at the finest level, in luma
    // counts over whole blocks. One fetch_add per parallel work unit, so the reduction costs
    // nothing measurable. Measured with a fixed window regardless of what the search scored
    // with, which is what keeps the scene-change threshold meaningful when the matching window
    // changes.
    std::atomic<long long> sadAcc{0};

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
        const int group = div;                      // full-res blocks per side

        int base;
        if (!sv) {
            base = (l == kLevels - 1) ? kCoarseRange : kFineRange;
        } else if (searchDistance > 0) {
            // An explicit distance is a reach in full-resolution pixels. The pyramid spends it
            // where it can afford it - the coarse level, which is what sets the prediction -
            // and the fine level stays inside its quality budget, so a wide setting widens the
            // search instead of blowing the frame budget.
            const int cap = (l == kLevels - 1) ? 16 : fineBase;
            base = clampi(searchDistance >> l, 0, cap);
        } else {
            base = (l == kLevels - 1) ? coarseBase : fineBase;
        }

        // Both penalties are scaled by div so they are charged per full-resolution pixel and
        // their strength is the same at every level. The coarsest level is seeded from an
        // all-zero field, so kPenaltyNew is switched off there - otherwise it would only be
        // charging the search for finding motion at all.
        const int newLambda = (l == kLevels - 1) ? 0 : newLambdaBase * div;
        const int zeroLambda = zeroLambdaBase * div;
        const uint8_t* tgt = tgtPyr[l];
        const uint8_t* ref = refPyr[l];
        // Shown to the SAD only through `range`, so it can be read once per level.
        const bool adaptive = sv && searchDistance == 0 && base > 1;

        parallelFor(0, lrows, [&](int r0, int r1) {
            long long localSad = 0;
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
                    // The matching window for this block, rounded down to a whole 8x8 tile so
                    // the NEON path stays intact, and never past the edge of the level.
                    const int bw = clampi((std::min(winW, lw - bx)) & ~7, 8, winW);
                    const int bh = clampi((std::min(winH, lh - by)) & ~7, 8, winH);

                    int range = base;
                    if (adaptive) {
                        // SVP's negative search distance: derive the reach from how well the
                        // block already explains itself. A block that does not match at zero
                        // shift has real motion to chase and gets the full reach; one that
                        // already lines up only has to confirm it. Averaged over a frame this
                        // lands near a third of nominal, which is where SVP reports the
                        // adaptive setting sitting - and a short look in the flat areas is
                        // what stops a repeating texture from pulling the vector around.
                        const int zero = sadWin(tb, ref + by * lw + bx, lw, bw, bh);
                        const int span = 32 * bw * bh;   // 32 per pixel = clearly unmatched
                        const int hit = clampi(zero, 0, span);
                        range = base / 3 + ((base - base / 3) * hit) / span;
                    }

                    int best = 0x7FFFFFFF;
                    int bestDx = gx, bestDy = gy;
                    // Plain SAD of the winning candidate, without the penalties: that is the
                    // motion-compensated difference the scene-change gate reads.
                    int bestPlain = -1;

                    for (int dy = -range; dy <= range; dy++) {
                        const int yy = by + gy + dy;
                        if (yy < 0 || yy + bh > lh) continue;
                        const uint8_t* row = ref + yy * lw;
                        for (int dx = -range; dx <= range; dx++) {
                            const int xx = bx + gx + dx;
                            if (xx < 0 || xx + bw > lw) continue;
                            const int s = sadWin(tb, row + xx, lw, bw, bh);
                            int score = s;
                            score += newLambda * (std::abs(dx) + std::abs(dy));
                            score += zeroLambda * (std::abs(gx + dx) + std::abs(gy + dy));
                            if (score < best) {
                                best = score;
                                bestDx = gx + dx;
                                bestDy = gy + dy;
                                bestPlain = s;
                            }
                        }
                    }
                    if (l == 0 && bestPlain >= 0) {
                        int gateSad = bestPlain;
                        if (bw != kBlock || bh != kBlock) {
                            // The gate wants a whole 16x16 whole-pixel SAD. Where the matching
                            // window is not that shape, re-measure the winner at 16x16, and
                            // fall back to the zero-shift match if the winner runs off the
                            // level - a partial window would otherwise read a different
                            // number of pixels and rescale the threshold.
                            const int wx = bx + bestDx;
                            const int wy = by + bestDy;
                            gateSad = (wx >= 0 && wy >= 0 && wx + kBlock <= lw && wy + kBlock <= lh)
                                ? sad16x16(tb, ref + wy * lw + wx, lw)
                                : sad16x16(tb, ref + by * lw + bx, lw);
                        }
                        localSad += gateSad;
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
            if (l == 0 && localSad != 0) {
                sadAcc.fetch_add(localSad, std::memory_order_relaxed);
            }
        });
    }
    return sadAcc.load(std::memory_order_relaxed);
}

// One coherence step over the field. Each block may adopt a neighbour's vector when that vector
// scores better on this block, and kPenaltyNeighbour charges it for the difference: speckle is
// removed without flattening genuine motion boundaries, because a neighbour only wins where it
// is actually the better explanation of the picture here.
//
// Every candidate is read from the source field and every result goes to a separate field, so the
// pass is Jacobi-style and parallel rows cannot race. Callers run it twice with the output fed
// back as the input, which lets a correction travel further than one block per invocation.
void MemcInterpolator::regulariseField(const uint8_t* tgt, const uint8_t* ref, int w, int h,
                                       const int32_t* mvx, const int32_t* mvy,
                                       int32_t* outx, int32_t* outy) {
    const int bwx = (w + kBlock - 1) / kBlock;
    const int bwy = (h + kBlock - 1) / kBlock;
    // Same guard as motionEstimate: below one full block there is no field to regularise.
    if (w < kBlock || h < kBlock) return;

    // Window anchored a quarter block in, so it stays clear of the block's outer edges while
    // still covering the part the warp reads from most heavily. Eight pixels rather than sixteen
    // keeps four candidates at about the cost of one extra full-block evaluation.
    const int ox = kBlock / 4;
    const int oy = kBlock / 4;
    static const int kNx[4] = {-1, 1, 0, 0};
    static const int kNy[4] = {0, 0, -1, 1};

    auto inBounds = [&](int bx, int by, int mx, int my) {
        const int xx = bx + ox + mx;
        const int yy = by + oy + my;
        return xx >= 0 && yy >= 0 && xx + 8 <= w && yy + 8 <= h;
    };
    auto score = [&](int bx, int by, int mx, int my) {
        const uint8_t* a = tgt + static_cast<size_t>(by + oy) * w + (bx + ox);
        const uint8_t* b = ref + static_cast<size_t>(by + oy + my) * w + (bx + ox + mx);
        return sad8x8(a, b, w);
    };

    parallelFor(0, bwy, [&](int r0, int r1) {
        for (int r = r0; r < r1; r++) {
            const int by = std::min(r * kBlock, h - kBlock);
            for (int c = 0; c < bwx; c++) {
                const int bx = std::min(c * kBlock, w - kBlock);
                const size_t idx = static_cast<size_t>(r) * bwx + c;

                const int sx = mvx[idx];
                const int sy = mvy[idx];
                int bestCost = inBounds(bx, by, sx, sy) ? score(bx, by, sx, sy) : 0x3FFFFFFF;
                int bestX = sx;
                int bestY = sy;

                for (int k = 0; k < 4; k++) {
                    const int nc = c + kNx[k];
                    const int nr = r + kNy[k];
                    if (nc < 0 || nc >= bwx || nr < 0 || nr >= bwy) continue;
                    const size_t nidx = static_cast<size_t>(nr) * bwx + nc;
                    const int cx = mvx[nidx];
                    const int cy = mvy[nidx];
                    if (!inBounds(bx, by, cx, cy)) continue;
                    int s = score(bx, by, cx, cy);
                    s += kPenaltyNeighbour * (std::abs(cx - sx) + std::abs(cy - sy));
                    if (s < bestCost) {
                        bestCost = s;
                        bestX = cx;
                        bestY = cy;
                    }
                }

                outx[idx] = bestX;
                outy[idx] = bestY;
            }
        }
    });
}

// Cover/uncover masks, after MVTools' MakeVectorOcclusionMaskTime().
//
// The signal is the *divergence* of the field, not its agreement with the other direction. A
// rigid translation leaves every block's vector equal to its neighbours', so the difference is
// zero and the mask stays clear; where a moving object meets a stationary background one side of
// the boundary folds, the two destinations stop covering the same area, and the content behind
// one of them is being covered up. A warp taken from the covered side samples something that is
// no longer there - that is the ghost the masks exist to reject.
//
// MVTools checks only the matching axis (x against the right neighbour, y against the one below)
// and paints the whole cell pair, so a fold marks both blocks it spans and survives the bilinear
// upsample as a band roughly one block wide instead of a single-cell spike.
void MemcInterpolator::buildOcclusionMasks(int w, int h) {
    const int bwx = (w + kBlock - 1) / kBlock;
    const int bwy = (h + kBlock - 1) / kBlock;
    const size_t nblocks = static_cast<size_t>(bwx) * bwy;
    if (nblocks == 0) return;

    auto fill = [&](const int32_t* mx, const int32_t* my, uint8_t* m) {
        std::fill(m, m + nblocks, 0);
        for (int r = 0; r < bwy; r++) {
            const int row = r * bwx;
            for (int c = 0; c + 1 < bwx; c++) {
                const int fold = mx[row + c] - mx[row + c + 1];
                if (fold <= 0) continue;
                const int v = fold * kOccScale;
                const uint8_t b = static_cast<uint8_t>(v > 255 ? 255 : v);
                if (b > m[row + c]) m[row + c] = b;
                if (b > m[row + c + 1]) m[row + c + 1] = b;
            }
        }
        for (int r = 0; r + 1 < bwy; r++) {
            const int row = r * bwx;
            const int below = row + bwx;
            for (int c = 0; c < bwx; c++) {
                const int fold = my[row + c] - my[below + c];
                if (fold <= 0) continue;
                const int v = fold * kOccScale;
                const uint8_t b = static_cast<uint8_t>(v > 255 ? 255 : v);
                if (b > m[row + c]) m[row + c] = b;
                if (b > m[below + c]) m[below + c] = b;
            }
        }
    };
    fill(mvf_x_.data(), mvf_y_.data(), maskf_.data());
    fill(mvb_x_.data(), mvb_y_.data(), maskb_.data());

    // How much of a fold the blend is still allowed to trust. Zero hands every sample back to
    // the motion field unchallenged; one keeps the raw divergence measure. Scaled here rather
    // than where the field is packed, so the CPU warp and the shader see the same mask. This is
    // the SVPlayer artifact-masking bar, and only it - the MEMC path always ran at full mask.
    if (algorithm() == static_cast<int>(InterpolationAlgorithm::SVPLAYER)) {
        const float scale = sv_config_.artifactMaskLevel;
        if (scale < 1.0f) {
            const int cap = scale < 0.0f ? 0 : static_cast<int>(scale * 255.0f + 0.5f);
            auto rescale = [&](uint8_t* m) {
                for (size_t i = 0; i < nblocks; i++) {
                    m[i] = static_cast<uint8_t>((m[i] * cap + 127) / 255);
                }
            };
            rescale(maskf_.data());
            rescale(maskb_.data());
        }
    }
}

void MemcInterpolator::motionCompensate(const uint8_t* in0, const uint8_t* in1,
                                        int w, int h, float timestep,
                                        const int32_t* mvf_x, const int32_t* mvf_y,
                                        const int32_t* mvb_x, const int32_t* mvb_y,
                                        const uint8_t* maskf, const uint8_t* maskb,
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
                // Same weighting for the occlusion masks, folded the same way. The weights sum
                // to kBlock * kBlock, so the two terms below can never exceed 255 * 256 and the
                // shift lands back on a byte without a clamp.
                const int32_t rmf0 = maskf[i00] * wy0 + maskf[i10] * wy1;
                const int32_t rmf1 = maskf[i01] * wy0 + maskf[i11] * wy1;
                const int32_t rmb0 = maskb[i00] * wy0 + maskb[i10] * wy1;
                const int32_t rmb1 = maskb[i01] * wy0 + maskb[i11] * wy1;
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

                    // Sources come from an opaque EGL readback, but force alpha to match
                    // RifeEngine's 255 contract exactly.
                    uint32_t* po = reinterpret_cast<uint32_t*>(
                        out + (outRow + static_cast<size_t>(x)) * 4);

                    const int mf = (rmf0 * wx0 + rmf1 * wx1 + 128) >> 8;
                    const int mb = (rmb0 * wx0 + rmb1 * wx1 + 128) >> 8;
                    if ((mf | mb) == 0) {
                        // The unoccluded case - and the only case a uniform field ever
                        // produces - stays on the original path, byte for byte.
                        uint32_t px = 0;
                        vst1_lane_u32(&px, vreinterpret_u32_u8(blend256(ca, cb, wt)), 0);
                        *po = px | 0xFF000000u;
                        continue;
                    }

                    // MVTools' FlowInter, evaluated in float so the intermediate lerps carry the
                    // same precision the shader's do and rounding happens once, at the store.
                    // A side that is covered up hands over to the other side's warp, and if that
                    // is covered too, to its own frame unwarped; both covered at once therefore
                    // collapses to the plain temporal crossfade.
                    uint8_t ca8[8], cb8[8];
                    vst1_u8(ca8, ca);
                    vst1_u8(cb8, cb);
                    const uint8_t* p0 = in0 + (outRow + static_cast<size_t>(x)) * 4;
                    const uint8_t* p1 = in1 + (outRow + static_cast<size_t>(x)) * 4;
                    const float kf = static_cast<float>(mf) * (1.0f / 255.0f);
                    const float kb = static_cast<float>(mb) * (1.0f / 255.0f);
                    const float kt = static_cast<float>(wt) * (1.0f / 256.0f);
                    const float kf0 = 1.0f - kf;
                    const float kb0 = 1.0f - kb;
                    const float kt0 = 1.0f - kt;
                    uint8_t outPx[4];
                    for (int c = 0; c < 3; c++) {
                        const float va = static_cast<float>(ca8[c]);
                        const float vb = static_cast<float>(cb8[c]);
                        const float innerF = (mf > 0)
                            ? va * kf0 + static_cast<float>(p1[c]) * kf
                            : va;
                        const float innerB = (mb > 0)
                            ? vb * kb0 + static_cast<float>(p0[c]) * kb
                            : vb;
                        const float termF = va * kf0 + innerB * kf;
                        const float termB = vb * kb0 + innerF * kb;
                        const float v = termF * kt0 + termB * kt;
                        outPx[c] = static_cast<uint8_t>(
                            v < 0.0f ? 0.0f : (v > 255.0f ? 255.0f : v + 0.5f));
                    }
                    outPx[3] = 255;
                    memcpy(po, outPx, 4);
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

bool MemcInterpolator::prepare(const uint8_t* src0, const uint8_t* src1,
                               int srcW, int srcHeight,
                               int targetWidth, int targetHeight,
                               bool forwardOnly,
                               const uint8_t** aOut, const uint8_t** bOut) {
    if (!src0 || !src1) return false;
    if (srcW <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return false;
    if (targetWidth > srcW || targetHeight > srcHeight) return false;

    microBench();  // one-shot; logs raw NEON and sad16x16 throughput

    const auto tSetup0 = std::chrono::steady_clock::now();
    sceneCut_ = false;
    if (dirty_.exchange(false, std::memory_order_acq_rel)) {
        // A seek / stream change happened while the previous frame was in flight.
        work_w_ = 0;
        work_h_ = 0;
    }
    pool_->ensureCount(threads_.load(std::memory_order_relaxed));

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
    //
    // Each estimate is followed by two coherence passes, fed output-first so a correction can
    // travel more than one block. Their cost is folded into the same accumulator, so STAGES
    // still reports one number per direction and keeps the same meaning.
    const auto tFwd = std::chrono::steady_clock::now();
    const long long sadFwd = motionEstimate(pyr0, pyr1, w, h, mvf_x_.data(), mvf_y_.data());
    regulariseField(luma0_.data(), luma1_.data(), w, h,
                    mvf_x_.data(), mvf_y_.data(), tmpx_.data(), tmpy_.data());
    regulariseField(luma0_.data(), luma1_.data(), w, h,
                    tmpx_.data(), tmpy_.data(), mvf_x_.data(), mvf_y_.data());
    acc_fwd_ns_ += nsSince(tFwd);

    long long sadBwd = 0;
    if (forwardOnly) {
        // Nothing downstream reads this half: the denoiser samples history along the forward
        // vector, and packMotionField() writes maskB as clear from a zero field. Skipping the
        // search is what keeps the denoise-only stage off the full bidirectional cost it used
        // to pay while producing nothing with it.
        std::fill(mvb_x_.begin(), mvb_x_.end(), 0);
        std::fill(mvb_y_.begin(), mvb_y_.end(), 0);
    } else {
        const auto tBwd = std::chrono::steady_clock::now();
        sadBwd = motionEstimate(pyr1, pyr0, w, h, mvb_x_.data(), mvb_y_.data());
        regulariseField(luma1_.data(), luma0_.data(), w, h,
                        mvb_x_.data(), mvb_y_.data(), tmpx_.data(), tmpy_.data());
        regulariseField(luma1_.data(), luma0_.data(), w, h,
                        tmpx_.data(), tmpy_.data(), mvb_x_.data(), mvb_y_.data());
        acc_bwd_ns_ += nsSince(tBwd);
    }

    // Scene-change gate: MVTools' thSCD1/thSCD2, restated as the mean per-pixel luma SAD of the
    // motion-compensated pair. One shot of video sits in single digits; two unrelated frames sit
    // near 30 whatever the search managed, because there is nothing to match. Zeroing the field
    // then costs nothing extra downstream: a zero field has no divergence, so the masks come out
    // clear and both the CPU warp and the shader fall through to a plain temporal crossfade.
    //
    // Both directions have to fail. A single hard-to-match subject would otherwise be read as a
    // cut, and a false positive replaces a good warp with a crossfade - far more visible than the
    // one bad frame a missed cut costs.
    const int bwx = (w + kBlock - 1) / kBlock;
    const int bwy = (h + kBlock - 1) / kBlock;
    const double nblocks = static_cast<double>(bwx) * bwy;
    // The scene gate is the user's to switch off. With it off the warp always runs, even where
    // the two frames have nothing to do with each other - which is what the switch promises -
    // and the search is left to explain the picture on its own.
    const bool gateScenes = algorithm() != static_cast<int>(InterpolationAlgorithm::SVPLAYER) ||
                            sv_config_.sceneAdaptive;
    if (gateScenes && nblocks > 0.0) {
        const double scale = 1.0 / (nblocks * kBlock * kBlock);
        const double meanFwd = static_cast<double>(sadFwd) * scale;
        // With the backward search skipped there is only one signal to judge, and requiring both
        // would disable the gate outright - the denoiser would then smear history across a cut.
        const double meanBwd = (forwardOnly ? sadFwd : sadBwd) * scale;
        if (meanFwd > kSceneCutMeanSad && meanBwd > kSceneCutMeanSad) {
            sceneCut_ = true;
            std::fill(mvf_x_.begin(), mvf_x_.end(), 0);
            std::fill(mvf_y_.begin(), mvf_y_.end(), 0);
            std::fill(mvb_x_.begin(), mvb_x_.end(), 0);
            std::fill(mvb_y_.begin(), mvb_y_.end(), 0);
            LOGI_MEMC("scene cut: compensated SAD %.1f/%.1f > %d, crossfading instead of warping",
                      meanFwd, meanBwd, kSceneCutMeanSad);
        }
    }

    buildOcclusionMasks(w, h);

    if (aOut) *aOut = a;
    if (bOut) *bOut = b;
    return true;
}

bool MemcInterpolator::interpolate(const uint8_t* src0, const uint8_t* src1,
                                   int srcW, int srcHeight,
                                   int targetWidth, int targetHeight,
                                   float timestep,
                                   uint8_t* out) {
    if (!out) return false;
    if (timestep < 0.0f || timestep > 1.0f) return false;

    const auto t0 = std::chrono::steady_clock::now();
    const uint8_t* a = nullptr;
    const uint8_t* b = nullptr;
    if (!prepare(src0, src1, srcW, srcHeight, targetWidth, targetHeight, false, &a, &b)) {
        return false;
    }

    const auto tWarp = std::chrono::steady_clock::now();
    motionCompensate(a, b, targetWidth, targetHeight, timestep,
                     mvf_x_.data(), mvf_y_.data(),
                     mvb_x_.data(), mvb_y_.data(),
                     maskf_.data(), maskb_.data(),
                     out);
    acc_warp_ns_ += nsSince(tWarp);

    acc_total_ns_ += nsSince(t0);
    acc_frames_ += 1;

    last_ms_.store(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - t0).count(), std::memory_order_relaxed);
    reportStagesIfDue();
    return true;
}

size_t MemcInterpolator::motionFieldBytes(int targetWidth, int targetHeight) {
    if (targetWidth <= 0 || targetHeight <= 0) return 0;
    const size_t bwx = static_cast<size_t>((targetWidth + kBlock - 1) / kBlock);
    const size_t bwy = static_cast<size_t>((targetHeight + kBlock - 1) / kBlock);
    // Two contiguous RGBA halves: the field first, the cover/uncover masks after it. Each is a
    // standalone texture image, so the caller uploads both out of this one buffer.
    return bwx * bwy * 8;
}

void MemcInterpolator::packMotionField(int w, int h, uint8_t* outMv, bool forwardOnly) {
    const int bwx = (w + kBlock - 1) / kBlock;
    const int bwy = (h + kBlock - 1) / kBlock;
    const size_t blocks = static_cast<size_t>(bwx) * bwy;
    // The hierarchical search bounds the field well inside +-127 whole pixels (|mv| <= 38 at
    // three levels with the ranges above), so the +128 bias always fits a byte. Clamp anyway: a
    // future range change must degrade to a saturated vector, never a wrapped one.
    auto bias = [](int32_t v) {
        const int32_t b = v + 128;
        return static_cast<uint8_t>(b < 0 ? 0 : (b > 255 ? 255 : b));
    };
    uint8_t* outMask = outMv + blocks * 4;

    // Residual scores for the non-local-mean blend weights, one per block per direction: how
    // well each vector explains its own frame against the other one, measured as an 8x8 SAD of
    // luma at the motion-compensated position. Where the vector is right the residual is just
    // the noise the two frames do not share; where it is wrong the residual is the misalignment
    // on top. Serial over a few thousand blocks - two clamped SADs each is tens of microseconds,
    // and the histogram that follows is one 256-bin sweep - so it stays inside packMotionField's
    // existing serial section rather than buying a parallel region for microseconds.
    nlmScore_.assign(blocks * 2, 0);
    int hist[256];
    memset(hist, 0, sizeof(hist));
    if (w >= 8 && h >= 8) {
        for (int r = 0; r < bwy; r++) {
            const size_t row = static_cast<size_t>(r) * bwx;
            const int cy = r * kBlock + kBlock / 2;
            for (int c = 0; c < bwx; c++) {
                const size_t i = row + c;
                const int cx = c * kBlock + kBlock / 2;
                // `p - mv` at t = 1, the same position the warp shader samples the partner at.
                const int sadF = sadAt(luma1_.data(), luma0_.data(), w, w, h,
                                       cx, cy, cx - mvf_x_[i], cy - mvf_y_[i]);
                nlmScore_[i * 2] = static_cast<uint8_t>(clampi(sadF / 64, 0, 255));
                hist[nlmScore_[i * 2]]++;
                // The backward residual is only a real residual when the backward field is.
                // With forwardOnly the vectors are all zero, so this would score the *unwarped*
                // pair - which is full of misalignment, not noise - and pooling it drags the
                // percentile that becomes the noise floor up with it, letting the denoiser merge
                // harder than its own measurement justifies. Only the forward half counts then.
                if (!forwardOnly) {
                    const int sadB = sadAt(luma0_.data(), luma1_.data(), w, w, h,
                                           cx, cy, cx - mvb_x_[i], cy - mvb_y_[i]);
                    const int sb = clampi(sadB / 64, 0, 255);
                    nlmScore_[i * 2 + 1] = static_cast<uint8_t>(sb);
                    hist[sb]++;
                }
            }
        }
    }

    // The noise floor: the score the easiest fifth of the blocks sit at. Matching is easiest in
    // the flat majority of any real frame, so a low percentile lands on the noise itself without
    // anyone having to decide which blocks are flat. A cut is handled before this by zeroing the
    // field, which would otherwise make the whole histogram large and the floor claim that two
    // unrelated pictures were one noisy one.
    const long long samples = static_cast<long long>(blocks) * (forwardOnly ? 1 : 2);
    long long target = samples * kNlmFloorPercentile / 100;
    if (target < 1) target = 1;
    long long acc = 0;
    int floorBin = 255;
    for (int i = 0; i < 256; i++) {
        acc += hist[i];
        if (acc >= target) {
            floorBin = i;
            break;
        }
    }
    float floorVal;
    if (sceneCut_ || samples == 0) {
        floorVal = 0.0f;
    } else if (floorBin < static_cast<int>(kNlmFloorMin)) {
        floorVal = kNlmFloorMin;
    } else if (floorBin > static_cast<int>(kNlmFloorMax)) {
        floorVal = kNlmFloorMax;
    } else {
        floorVal = static_cast<float>(floorBin);
    }
    const float invFloor = floorVal > 0.0f ? 1.0f / floorVal : 0.0f;
    // Published in mean-per-channel difference units, the metric the shader's per-pixel gate
    // uses, so the two are comparable without the shader knowing anything about luma.
    const int floorByte = clampi(
        static_cast<int>(floorVal * kNlmChannelScale + 0.5f), 0, 255);

    auto blendWeight = [invFloor](int score) -> uint8_t {
        if (invFloor <= 0.0f) return 0;
        const float r = static_cast<float>(score) * invFloor;
        const float e = -kNlmExponent * r * r;
        if (e < -20.0f) return 0;
        const float v = 255.0f * std::exp(e);
        return static_cast<uint8_t>(v < 0.0f ? 0.0f : (v > 255.0f ? 255.0f : v + 0.5f));
    };

    for (int r = 0; r < bwy; r++) {
        const size_t row = static_cast<size_t>(r) * bwx;
        for (int c = 0; c < bwx; c++) {
            const size_t i = row + c;
            uint8_t* o = outMv + i * 4;
            o[0] = bias(mvf_x_[i]);
            o[1] = bias(mvf_y_[i]);
            o[2] = bias(mvb_x_[i]);
            o[3] = bias(mvb_y_[i]);
            uint8_t* m = outMask + i * 4;
            m[0] = maskf_[i];
            m[1] = maskb_[i];
            // The cover/uncover mask is folded in on top of the similarity weight: a sample the
            // warp rejects as covered is not fit to be averaged in either. Both terms are
            // 0..255, so the product scaled back to a byte is exact enough for a blend factor.
            //
            // Only the forward weight is published: the denoiser walks forward through the
            // stream and merges the history into the new frame, which is the forward direction.
            // Byte 7 carries the floor instead, replicated so the shader reads the same value
            // from every texel of the block grid it already samples.
            const int wf = blendWeight(nlmScore_[i * 2]);
            m[2] = static_cast<uint8_t>((wf * (255 - maskf_[i]) + 127) / 255);
            m[3] = static_cast<uint8_t>(floorByte);
        }
    }
}

bool MemcInterpolator::motionField(const uint8_t* src0, const uint8_t* src1,
                                   int srcW, int srcHeight,
                                   int targetWidth, int targetHeight,
                                   uint8_t* outMv, size_t outMvBytes,
                                   bool forwardOnly) {
    if (!outMv) return false;
    if (outMvBytes < motionFieldBytes(targetWidth, targetHeight)) return false;

    const auto t0 = std::chrono::steady_clock::now();
    if (!prepare(src0, src1, srcW, srcHeight, targetWidth, targetHeight, forwardOnly)) {
        return false;
    }

    // Packed in one serial pass: the GPU is about to read it, so a parallel region would only
    // buy back the microseconds the barriers cost. The warp stage stays at ~0 here, which is the
    // signal in the STAGES line that the shader - not motionCompensate() - is doing the resample.
    const auto tPack = std::chrono::steady_clock::now();
    packMotionField(targetWidth, targetHeight, outMv, forwardOnly);
    acc_warp_ns_ += nsSince(tPack);

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
