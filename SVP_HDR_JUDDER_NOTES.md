# SVP HDR / Judder / Quality Notes

Branch: `fix/svp-hdr-judder-and-quality`. All fixes are UNVERIFIED on device until the user
retests; every number below comes from unit tests or the offline native harness on the
development device (5 cores, 4 interpolation threads, NEON).

## Problem A - HDR sources overexposed after tone-map

Root cause: the ST 2084 (PQ) EOTF constant `m1` was wrong in 7 GLSL copies
(`2610.0 / 4096.0` instead of `2610.0 / 4096.0 / 4.0`): the spec defines
m1 = 2610/4096 x 1/4 = 0.1593017578125. The buggy value made EOTF(0.51) = 0.3177
(3177 nits instead of 102), which Hable then rolled to white. Anchors: PQ EOTF(0.51)
= 0.010193, EOTF(0.75) = 0.098338, inverse OETF(0.1) = 0.751827, capture tail code
0.508 -> sRGB 0.7139, 0.75 -> 0.9991, sRGB 0.18 -> 0.461356, 0.5 -> 0.735357;
BT.2020->709 rows sum ~1.0 so neutral 0.18 is preserved. Present shaders are
passthrough at uIsHdr==0; no double conversion.

Fix: corrected all 7 `m1` definitions (OesFrameGrabber.kt:253; GlOutputRenderer.kt
lines 75/90/208/222/349/363) and added `HdrTransferReferenceTest.kt` (12 tests,
including a source scan asserting every `const float m1` contains the /4.0 form and
that exactly 7 exist). Tests PASS. In-player toggle (`hdrToSdrToneMap`) stays
opt-in. Device check pending: HDR sources look natural, not blown out. The capture
tail's tone curve was later replaced (Problem D below), so the tail anchors that
originally lived here (0.508 -> sRGB 0.7139, 0.75 -> 0.9991) are superseded by the
knee-curve anchors in Problem D; the PQ decode anchors (0.51 -> 0.010193 etc.) are
unchanged.

## Problem B - 1080p interpolation worse than 480p (wave-like judder)

meScale audit PASSED (code + measurements): at meScale=2 the published vectors stay in
full-res half-pel units (frac_1px >= 0.90, median error 0 at both scales up to 48 px
shifts), so the half-res search costs quality (-8 to -12 dB psnr_correct at 1080p) but
does not mis-scale motion. It stays opt-in; default meScale=1 is correct.

Root cause (measured): the fixed 48 px default search reach vs pixel-scaled camera
motion. On a deterministic multi-octave noise texture with known global translation
(offline harness, PSNR vs analytic mid-frame, 4 threads): at 1080p meScale=1
searchDistance=48, a 64 px/frame shift collapses to crossfade quality (28.6 dB,
2.6% of blocks within 1 px; crossfade baseline 25.9 dB); 56 px is marginal
(45.0 dB, 94.1%); sd=16 reproduces the header claim (dx=32 collapses). At
meScale=2 the collapse hits already at 56 px (reach ~54 full px vs ~57 at ms=1).

Fix: default `searchDistance` 48 -> 60 (SvPlayerSettings.kt, SvConfig in
memc_interpolator.h; UI option added in ProcessingSettingsView.kt). 60 px coarse +
two finer passes = 69 px reach against the +128 pack bias's 63.5 px ceiling; motion
past the ceiling clamps (half a pixel at 64 px) and the saturation counter reports
it. Measured at 1080p ms=1: dx=64 goes 28.6 -> 48.9 dB (95.6% resolving, sat 95.9%);
dx=56 goes 45.0 -> 53.4 dB; dx<=48 unchanged; ms=2 dx=56 goes 0.069 -> 1.000 frac
(reach 62 px). Motion-stage cost ~10% (1080p 34.7->37.8 ms, 4K 131.7->145.6 ms,
4 threads), inside end-to-end interpolate timing variance. New test:
SvPlayerSettingsTest.kt pins the defaults. gradle build/ktlint/tests PASS;
clang++ syntax check PASS. Device check pending: 1080p quality vs 480p.

Border-band behavior at the left edge (backward vectors fail in the first 1-2 block
columns; masks crossfade them) was traced and accepted as by-design occlusion
handling - not part of this fix.

## Problem C - 4K FPS drop

Audit result: no resolution-specific defect. Offline harness (perf mode): native
motion + interpolate wall time scale linearly with pixel count (1080p ms=1 sd=48:
34.7 / 64.0 ms; 4K: 131.7 / 233.9 ms - 3.8-4.0x for 4.0x the pixels, 4 threads).
The dominant 4K cost is the inherent O(pixels) full-resolution motion search.
Structural waste was already removed in earlier commits on this branch: one motion
per pair (warp path skips the CPU blend and the double-interpolate), preparedMotion
overlaps the pair wait, parking thread pool (no per-frame spawns), PBO ring drained
with zero-timeout fences, one PBO->CPU memcpy per frame, checksums off in release,
mirror copy is phone-preview only, FastDVDnet denoise off by default. The sd=60
change adds ~10% to 4K motion (131.7 -> 145.6 ms). The user-visible lever that
restores 4K headroom without silent downscaling is meScale=2 (4K motion 15-18 ms,
~9x faster; documented quality cost; reach 62 px with the new default).

Device check pending: on the target box, capture the `[PIPELINE]` line (pairMs/
motionMs/interpMs/renderMs/queueLatencyMs/droppedIn/droppedOut) at 4K to confirm
the motion stage dominates; if judder persists, the stale-discard bursts
(`Discarding stale frame: ...`) explain the wave pattern.

## Problem D (mi-moPrompt0170) - HDR-to-SDR freezes after the first use

Root cause: `toneMapAvailable` was a one-shot latch - set true the first time the
capture path compiled the tone-map program, never cleared. Every later path that
recreated the `OesFrameGrabber` (stop/start cycles via `createInputSurfaceOnWorker`,
`enableProcessing` with no attached surface, `releaseStateOnWorker`) deleted the
old grabber's GL program but left the flag true, so
`shaderCompiled = toneMapAvailable || grabber.ensureToneMap()` short-circuited and
the new grabber never compiled. Every frame was then decided as ToneMap and
refused at `enqueueReadback` ("tonemap requested but unavailable") - a permanent
frozen scene. First launch worked because there was no stale flag yet.

Fix (VideoFrameProcessor.kt): the capture decision now computes
`toneMapWanted = hdrToSdrToneMap && isHdrSource()` and always calls
`grabber.ensureToneMap()` (O(1) once compiled, O(1) once failed), syncing
`toneMapAvailable` only on transitions; the cached flag can no longer suppress the
compile. Belt-and-braces: `toneMapAvailable = false` is also set where the grabber
is recreated (`createInputSurfaceOnWorker`) and in `releaseStateOnWorker`. The
decision still routes "shader-unavailable" to HdrRetain (first frames after a
stream change keep HDR rather than freeze; HdrToneMapSelectionTest pins the
policy). P5 audit: pairing/dropped-frame counters are untouched by this path;
`enableProcessing` with an attached surface keeps the grabber (and its program),
so the always-ensure path stays consistent there too. Device check pending:
repeated HDR sessions (enter/exit/switch) never freeze.

## Problem E (mi-moPrompt0170) - tone-mapped picture too dark and oversaturated

Two defects in the capture tone-map shader (OesFrameGrabber.kt), quantified with
the Double reference chain in HdrTransferReferenceTest.kt (now 18 tests):

1. Darkness: the global Hable curve hable(Y)/hable(10) crushed the whole
   display-referred range - 18% grey (Y=0.18) landed at code ~0.352 instead of
   ~0.46, and 100-nit white (Y=1) at ~0.71 instead of a natural white. Fix: a
   display-referred knee curve - identity at or below knee=0.5 (50 nits, so SDR
   shadows/midtones are bit-exact through the chain), then an extended-Reinhard
   fold of (knee, peak=1000 nits] into (knee, 1.0] with m(W)=1 exactly. New
   anchors: Y=0.18 -> sRGB 0.461 (unchanged), Y=0.5 -> 0.735 (unchanged),
   100-nit white -> ~0.839 (was 0.714), 1000 nits -> ~0.999, midtone monotone,
   shadows still black. HLG path unchanged (its scene scaling already lands in
   the same units).
2. Oversaturation: the BT.2020->709 matrix rows are correct (sums ~1.0, neutrals
   preserved), but per-channel clipping of the out-of-gamut negatives is a hue
   collapse onto the 709 primaries - saturated HDR colors saturated further. Fix:
   `foldOutOfGamut` scales chroma by Y/(Y-min) so the most negative channel lands
   at zero while luminance and the r:g:b chroma ratios are kept exactly - the same
   color with just enough less saturation to fit. In-gamut colors and neutrals
   are untouched (pinned by test).

Tests extended: midtone pass-through, knee identity + monotonicity, gamut-fold
luminance/hue preservation, HLG end-to-end anchors (0.75 -> ~0.910, 0.5 -> ~0.738),
and a source scan asserting the knee/fold functions are present and the old Hable
curve is gone. Device check pending: exposure natural (not dark, not
oversaturated) on HDR10 and HLG content.

## Problem F (mi-moPrompt0170) - 1080p/4K quality vs 480p, and the sd60 cost

Resolution-normalized harness (`bench scale`, same texture, PSNR vs analytic
mid-frame, sd=60 default, 4 threads):

- Static rows: byte-exact (99.0 dB) at every resolution - no noise injection,
  no resolution-dependent defect.
- Same absolute displacement: quality IMPROVES with resolution (dx=16 meScale=1:
  46.1 / 50.5 / 53.9 dB at 480p/1080p/4K; dx=48: 39.1 / 42.9 / 45.2 dB).
- Same relative displacement (16/854 of frame width): 1080p matches 480p
  exactly (dx=36: 46.15 dB vs dx=16: 46.11 dB). 4K needs dx=72, which EXCEEDS the
  int8 half-pel packing reach (63.75 full-res px) - fast 4K pans are
  structurally unrepresentable at meScale=1, the one true ceiling in the system.
- meScale=2 measures in half-res pixels, so its representable full-res reach
  doubles to ~127 px (code comment at memc_interpolator.cpp:645; empirically the
  dx=63 rows hold frac_1px 0.95-1.00 at meScale=2 where meScale=1 sd=48 collapses
  to 0.02-0.07). Combined with sd=60 this is the user lever that makes 4K fast
  motion work: dx=63 at 4K goes 29.6 dB (ms1 sd48) -> 43.1 dB (ms1 sd60) and
  40.1 dB at ms2 sd60 with ~2x less motion time.
- sd60 cost stays ~10% on the motion stage (see Problem B); kept as default -
  quality priority per task prompt. Small extra win: the per-timestep glGetError
  in GlOutputRenderer.drawWarps was replaced by one drain after the loop (GL
  errors are sticky, diagnostics unchanged, one driver round-trip saved per
  presented frame).

Device check pending: 1080p/4K interpolation visibly better than before, 4K FPS
with meScale=2, no frame skips on the target box.

## Verification status

- Local: `./gradlew :app:compileDebugKotlin :app:ktlintCheck :core:model:test
  :app:testDebugUnitTest --offline --no-daemon` BUILD SUCCESSFUL (all tests pass;
  HdrTransferReferenceTest now 18 tests). clang++ -fsyntax-only on
  memc_interpolator.cpp clean. Note: `:core:model:ktlintCheck` on the MAIN source
  set has ~35 pre-existing violations and is not CI-gated; it is deliberately
  excluded from the verification command.
- Not verified (needs device): HDR-to-SDR stability across repeated sessions
  (Problem D), HDR exposure/saturation with the new knee curve (Problem E),
  1080p-vs-480p quality (Problem F), 4K FPS with meScale=2, no 4K frame skips,
  judder pattern gone.
- CI: `gh workflow run build.yml --ref fix/svp-hdr-judder-and-quality`.
  `assembleDebug` cannot run locally (NDK install is a stub); the APK comes from
  CI only.
- Branch-conflict note: repo AGENTS.md mandates
  `feature/media-playback-rife-processing-15429977835613455991`; the task prompts
  (mi-moPrompt0160.md / mi-moPrompt0170.md) explicitly require this work to land
  on `fix/svp-hdr-judder-and-quality`. The task prompt was followed.

## Harness (offline, kept out of the repo)

`/data/data/com.termux/files/usr/tmp/opencode/svpbench/bench.cpp` +
`stub/android/log.h`, built against app/src/main/cpp/memc_interpolator.cpp with
termux clang++ -O2. Modes: `quick` / `full` (quality sweep, CSV), `debug`
(field/pixel trace), `perf` (1080p-vs-4K motion+interp timing),
`scale` (resolution-normalized 480p/1080p/4K quality rows: static, small-dx,
absolute dx to the pack limit, and one resolution-relative displacement per
resolution; sd 60 vs 48, meScale 1 and 2; output kept as `scale_sweep.csv`).
NEON-only, cannot compile on x86 CI; lives outside the repo deliberately.

## Problem G (mi-moPrompt0180) - 1080p SDR recurring smoothness pulse

Device observation: 480p SDR extremely smooth; 1080p SDR output FPS recovered to
target but motion "gently" waxes/wanes in a recurring pattern (not harsh stutter).
Investigated offline; no behavioural fix claimed yet.

Refuted by evidence:

- Output-timestamp phase accumulator (outputTimesFor): exact-Kotlin replica sim
  (steady/alternating/jittered 24fps, ratios 2.0/2.5/3.0). At the default 2.0:
  outputs/pair {2: 399/400}; stamped-interval stdev <=0.6 ms even with +-1.5 ms
  arrival jitter. Sub-millisecond, imperceptible.
- Steady-state present-path alternation: SDR pairs take one path (den warp after
  the first post-reset cycle, else the 8-bit upload warp). No steady-state
  den/w8 oscillation; [PIPELINE] path= counters rule it in/out on device.

Best-supported mechanism (needs on-device confirmation): the stale-discard
limit cycle. maxQueueLatencyMs = 2x source interval (~83 ms at 24fps). A
discrete-event sim of the real queue (capacity 4, pairProcessing gate, stale
bound 2I, drop-oldest overflow) shows: pair time P well under I (480p ~15 ms,
1080p ~30 ms) -> zero drops, even spans, outFps 48.0. P approaching I with
content noise (1080p-like 40 ms +- 8 ms) -> ~4 stale discards per minute, each
forcing the next pair to span 2I (doubled motion for that stretch - a "less
smooth" beat) WHILE output FPS stays ~48. P=44 ms+ -> continuous drops (83/min)
and FPS falls to 45.3. The "FPS at target + recurring pulse" signature lives in
the narrow band just below/at P~=I, which only 1080p can be in (480p has ~3x
headroom). Capacity-4 backpressure overflow does not fire at FPS-at-target
(needs P>I sustained) - it stays a separate, now separately counted, path.

Still open, discriminated by the on-device evidence below: presentation-cadence
(e.g. 24fps x2 = 48fps on a 60Hz panel -> vsync beat), and content-dependent
interpolation/denoise quality ("softer" wording; path= counters answer the
path-mixing part).

Change (diagnostics only, per prompt section 6 - no safe behavioural fix was
evidenced): windowed distribution statistics, reusing the existing [PIPELINE]
window cadence.

- New WindowedDistribution: 96-sample ring; closeWindow -> p50/p95/max/n plus
  outliers (samples above 2x the window median - a 2x pair interval is a skipped
  pair, a 2x queue age is the latency policy approaching a discard). recordDelta
  skips its first call and filters non-positive deltas and >5 s lifecycle gaps.
- Recorded: decoder arrival interval, source pair interval, pair duration, queue
  age at poll, and submission interval per successful primary swap
  (GlOutputRenderer.submitPacing - submission cadence, NOT verified display
  pacing; that needs Perfetto FrameTimeline).
- Sub-counters split out of droppedFrameCount: staleDiscardCount (the already
  logged "Discarding stale frame" path) and backpressureDropCount (the silent
  drop-oldest path).
- Emission: one [PACING] line per [PIPELINE] window. Tokens are
  p50/p95/max/outliers; field suffix carries the unit (arrUs, pairUs, pairMs,
  queueMs, submitUs), plus stale=/backpressure= window deltas. Overhead: one
  nanoTime + array write per event, sort of <=96 longs per window.

## Problem H (mi-moPrompt0180) - HDR-to-SDR throughput at 1080p/4K

Code-level invariants confirmed (no defect found, design preserved):

- Tone-map runs once, at capture (grabber with toneMapReq); tone-mapped frames
  keep retainHdrSource=false, so hdrTextureByAnalysisBuffer stays empty and
  downstream takes the plain SDR path - no redundant second conversion.
- With toneMapEffective(): hdrOutputMode()=HDR_OUTPUT_SDR and hdrDataSpace()=0
  (VideoFrameProcessor, output-mode/dataSpace selectors) - SDR output carries no
  PQ dataspace tag.
- Mode transitions go through pipeline reset; ensureToneMap is O(1) after first
  compile (mi-moPrompt0170). No per-frame shader churn.

Structural extra cost vs pure SDR: one fullscreen tone-map fragment pass per
captured frame, inside the capture stage (it lands in readbackMs). Versus the
HDR-retain path the SDR pipeline uploads full-res RGBA8 per new frame (residency
keeps the shared one), where the HDR path uploads nothing - but its readback is
half the bytes (SDR8 vs RGBA16F). Attribution must come from real-device
stage timings: compare pairMs/readbackMs/motionMs/renderMs on the same clip
across 1080p SDR, 1080p HDR-to-SDR, 4K HDR-to-SDR. Offline harness scaling
(4K ~4x pixels) is NOT device evidence.

## On-device evidence needed (both problems)

Install the CI APK, play the same 1080p SDR segment ~2-3 min at 2x, capture
logcat, grep `[PACING]`, `Discarding stale frame`, `[PAIRGAP]`, `[STAGE]`,
`[PIPELINE]`:

- stale=1..20 per [PACING] window + queueMs p95 near ~80 ms + pairMs p95 near
  the source interval -> stale-limit-cycle confirmed; next iteration targets the
  processing-cost stage chosen from that window's motionMs/renderMs/readbackMs.
- stale=0 and tight arrUs/pairUs/submitUs while the pulse persists ->
  presentation-side or content-dependent: repeat with a 30fps clip (2x = 60fps,
  even on a 60Hz panel) and a constant-pan synthetic; Perfetto FrameTimeline for
  actual display pacing.
- backpressure>0 recurring -> decoder bursts overflow the queue (separate
  mechanism, same attribution path).
- path= counts mixing den/w8 during steady playback -> present-path oscillation.

HDR-to-SDR: same clip segment with tone-map toggled on; diff the same fields
across modes; the stage that grew names the bottleneck.

## Problem I (mi-moPrompt0190) - remaining judder mechanisms and the [CADENCE] gap

Base: 1788b3d. Device symptoms unchanged (480p smooth; 1080p SDR/HDR FPS at
target with a gentle recurring speed-up/slow-down of motion; 4K FPS short).
NO device logs were supplied or found in the repo: every device-side claim
below stays unverified, and no behavioural pipeline change was made.

Control-flow answers (current line numbers at this commit's working tree):

1. Stale/backpressure removal creates temporal gaps between adjacent pairs'
   frames. The stale loop (VideoFrameProcessor.kt:2635-2648, bound = 2x
   interval via maxQueueLatencyMs():2608) and the capacity-4 drop-oldest
   overflow (queueCapturedFrame:2585-2593) both remove a queued frame while
   previousFrame stays put; the next pair is (previousFrame, later frame).
2. Yes: the next pair spans ~2 source intervals and its stamps stay monotonic.
   outputTimesFor (3506) derives step from THAT pair's span, carries the phase
   (nextOutputUs) so ordering is preserved, and re-anchors when the phase has
   left the pair (cadDisc). noteOutputTimestamp (4075)/OutputTimelineGuard
   counts regressions (tsRegress). Cost: one missing output slot (double-length
   stamp gap) plus doubled motion displacement for that pair's warps - visible
   as [PAIRGAP] (2865+) and pairUs outliers/stale=/backpressure= in [PACING].
3. The pairProcessing gate (2622) and pendingRenderQueue (2712) shift pair
   START wall-times with processing duration; previousFrame advances only when
   a pair is finally consumed (2862/2874/2925/3041/3082/3480). Stamped output
   times, however, come only from source timestamps + accumulator phase
   (computed once per pair before the engine call), so scheduling jitter moves
   submissions (submitUs spread), not the stamp grid.
4. Yes - submission regularity is not presentation regularity. Every primary
   swap stamps eglPresentationTimeANDROID then swaps (GlOutputRenderer
   swapBuffers; submitPacing records at 1389-1391), but SurfaceFlinger latches
   per vsync: (a) a stamp rate that does not divide the refresh evenly holds or
   skips frames at a fixed beat; (b) mid-pair frames exist only after motion
   completes, so they are inherently ~pairMs past-due at submit; when pairMs
   drifts across a vsync boundary the latch slot shifts; (c) a submit after the
   vsync deadline holds to the next one. None of this is visible app-side;
   display pacing needs Perfetto FrameTimeline.
5. Yes for the stale mechanism: the bound is resolution-independent but pair
   time is not (offline queue sim: limit cycle only when pairMs ~ interval;
   480p ~15ms never, 1080p ~35-45ms borderline). The hold-beat of (4a) is
   resolution-independent in origin but its amplitude scales with displacement,
   which fits "480p fine, 1080p visible" on the same content.

Three candidate mechanisms, none confirmed on device:

- A. 48fps-on-60Hz hold beat (24fps source x selectable 2x on a 60Hz panel):
  deterministic 1,1,1,2 hold pattern, 12Hz wobble - matches "gentle recurring
  speed-up/slow-down" exactly. 3x (the only other option; MemcLevelSetting is
  TWO_X/THREE_X only - no 2.5x exists) gives 72fps: 1-in-6 frames SKIPPED at
  the same 12Hz beat but with smaller amplitude and +50% warp cost. The
  arithmetically even choice for 24fps/60Hz is x2.5=60fps, which the settings
  enum does not offer; adding it is a product decision, not made here.
- B. Past-due mid-pair submits (see 4b): stochastic wobble as pairMs drifts
  relative to the vsync grid.
- C. Stale-limit-cycle hiccups (see 1/2/5): a few per minute, each one skipped
  output slot.

Change (diagnostic only, fills the specific [PACING] gap: submissions are
measured, the display is unknown):

- New OutputCadence (pure, unit-tested): simulate ceil-latch slots for 48
  frames of srcFps x multiplier on refreshHz; report even/uneven, the
  per-frame hold/skip summary ("1x36,2x12"; a 0 count = frames never shown)
  and the beat Hz. Float-robust (1e-9 latch epsilon: i/60*60 can land an ulp
  above i and fake a 0/2 hold pair on an exactly-even cadence).
- RifeEngineController.start() pushes the default display's refresh rate into
  the processor (VFP has no Context; 0 suppresses the line).
- reportStageTiming logs [CADENCE] once per (srcFps, multiplier, refresh)
  change with holds/even/beatHz and, when uneven, the ideal even multiplier.

4K attribution decision table (no code changed; meScale/search/quality
defaults untouched per task rules; offline Problem F numbers remain harness
evidence only): read one [PIPELINE]+[PACING] window per mode and pick the row.

- motionMs dominant and scales with pixels -> full-res motion search at
  meScale=1 is the bottleneck; the documented lever is the explicit meScale=2
  user setting (~2x less motion time, quality trade-off in Problem F), not a
  silent default change.
- readbackMs dominant -> 4K RGBA8 PBO traffic (~33MB/frame); consider PBO ring
  depth/driver, measure first.
- interpMs/renderMs dominant -> output warp/upload; check renderSplit up=/dr=.
- pairMs p95 > source interval with stale>0 -> pipeline is processing-paced;
  output FPS will sit under target regardless of the counter's average.
- submitUs tight but judder -> presentation-side; see mechanisms A/B.

Unverified / next evidence (device): [CADENCE] line for each mode (names A
directly); stale=/backpressure=/queueMs/pairMs in [PACING] (names C/B);
[PIPELINE] stage split per mode (names the 4K bottleneck); Perfetto
FrameTimeline for actual display pacing. Behavioural fix for A, if confirmed,
is a user-side multiplier choice now and possibly an explicit 2.5x setting
later - never a silent default change.

Problem J - device evidence, 3x capture 2026-10-14 (194k-line logcat,
svp_3x_4k.log, [CADENCE]/[PACING]/[PIPELINE] from commit 586ed03)

Setup: display reports refreshHz=120.0 (not 60); multiplier THREE_X; sessions
20:45:23-20:48:04 src=1920x960 (the "1080p" clip is 2:1 1920x960), 4K session
20:48:12-20:49:59 src=3840x2076, then two further sessions the user called
"480p SDR"/"480p HDR-to-SDR" whose [PIPELINE] src= was actually 1920x960 and
3840x2076 - not 480p inputs (unresolved label/content mismatch; ask user).

Mechanism A - device-confirmed arithmetically, refresh-mode independent:
- 24fps x3 = 72fps on 120Hz: holds=1x16,2x32 (two-thirds of frames held
  2 vsyncs, one-third 1), beatHz=24.0, even=false. The recurring 24Hz
  micro-speed-up the user feels is exactly this hold-length alternation.
- The same clip showed srcFps=20.018 windows with holds=2x48 even=true
  (20x3=60 divides 120) and srcFps=8.007 even=true - content/decoder-cadence
  windows where presentation is perfectly even; these are the stretches that
  feel smooth. (Some 12.011/8.007 readings are skip-pair artifacts: pairs
  spanning 2I inflate lastPairIntervalUs; see pairUs outliers below.)
- User retested with the panel forced to 60Hz: same perceived issues. That
  matches the model: at 60Hz, 72fps cannot be held (hold<1) so presentation
  becomes 12 dropped frames/s - the beat stays at a 24Hz irregularity, only
  its flavor changes (holds vs skips). For 24fps content NEITHER 2x NOR 3x
  is even on ANY mode: even requires outFps | refresh, i.e. 2.5x (=60) on
  120Hz or 60Hz, or 5x (=120) on 120Hz. Both are product decisions (new
  MemcLevelSetting entries), deliberately not taken here.

Mechanism B - processing pace, device-confirmed at 4K:
- 1920x960 x3 (~160s): outFps med 64.8 vs target 72.1; backpressure=450
  (~2.8 input drops/s); pairUs p50=41.6ms p95=83.3ms (2I-span pairs in 129
  windows); stale=0; motionMs med 46.0, readbackMs med 2.8, pairMs med 27.9
  p95 45.0; submitUs p50=2.1ms p95=45.2ms.
- 3840x2076 x3 (~108s): outFps med 39.7 vs target 72.1 (recover windows hit
  72.7); backpressure=1129 (~10.5 drops/s); pairUs p50=83.3ms = half pair
  rate = 12 pairs/s = the 36-40fps output; stale=0; motionMs med 73.9
  (dominant), readbackMs med 15.2 (5x the 1080p value - matches the RGBA8 PBO
  traffic row), pairMs med 30.2 p95 50.0. Even at 2x the pair budget would be
  41.7ms vs ~80ms effective pair time - 4K cannot reach target FPS without
  ~2x motion-path speedup; documented lever is the explicit user meScale=2
  setting (Problem F trade-off), to be tested on device, not defaulted.

Mechanism C - stale-limit-cycle, REFUTED on device: stale=0 in all four
sessions; backpressure drop-oldest fires first and the stale deadline never
reaches. The offline Problem G threshold band does not engage on this device
at either resolution.

Open follow-ups: interpMs reads 0.00 in every window (w8 path counts high,
den:0) - verify what the stage timer measures on this path; forced-60Hz
capture for the refresh-agnostic claim in numbers; meScale=2 4K capture;
clarify the "480p" session resolutions; structural options for A remain
(a) new 2.5x MemcLevelSetting entry (product decision), or (b) outFps locked
to refresh with time-scaled warps (feature-sized).

Problem J addendum - "480p processing engine" sessions resolve the attribution
(user clarification: S3/S4 ran the SAME 1920x960 and 3840x2076 files with the
motion-search engine at 480p; the earlier label mismatch is resolved)

Per-session medians (same log, same x3 multiplier, same 120Hz panel):

- S1 1080p full engine: outFps 64.3 (target 72.1), backpressure ~3.7/window
  (~3 drops/s), motionMs 46.7, readbackMs 2.7, pairUs p95=83.3ms (2I spans).
  User feels the subtle recurring pulse.
- S2 4K full engine: outFps 37.9, backpressure ~26/window (~10.5 drops/s),
  motionMs 76.6, readbackMs 16.1, pairs at half rate. User feels judder +
  insufficient FPS.
- S3 1080p + 480p engine: outFps 72.0, backpressure ~1/window, motionMs 13.7.
  User feels no judder.
- S4 4K + 480p engine: outFps 72.0, backpressure ~0.1/window, motionMs 15.0,
  readbackMs 3.5. User feels no judder.

Decisive result: [CADENCE] beat=24.0 even=false was present in ALL FOUR
sessions - including S3/S4 where the user perceives zero judder. Mechanism A
(the clean 24Hz hold-wobble of 72fps on 120Hz) is below this user's
perception threshold when the incoming cadence is regular. What is felt as
pulse/judder is the IRREGULARITY B layers on top of it: backpressure
drop-oldest skipping input frames, pairer spanning 2I (srcFps briefly reads
12.011), warps emitted in irregular bursts, holds fed an irregular cadence.

Causal chain, fully device-measured: full-res motion search (46.7ms at
1080p, 76.6ms at 4K vs the 27.8ms pair budget at x3) -> pair processing over
budget -> input overflow -> drop-oldest -> pair-skip -> outFps under target
(64.3/37.9) -> irregular presentation -> perceived judder. At 480p engine
motion collapses to 13.7-15.0ms, backpressure ~0, outFps pinned at 72.0 at
BOTH content resolutions, and only the imperceptible clean beat remains.

Consequences: the 1080p pulse and the 4K judder/FPS shortfall share one root
cause (full-res motion-path throughput); resolution only scales severity.
Stale cycle (C) stays refuted. Mechanism A is demoted from villain to
invisible background noise for this user. The practical fix is the existing
user-facing processing-engine/meScale lever (device-proven here); full-res
path optimization (motion search, the 4K readback's 16.1ms PBO traffic) and
a 2.5x MemcLevelSetting entry remain optional, larger directions.

Problem J correction - what the "480p engine" actually costs, and the
quality-preserving lever

Two errors in the previous addendum, corrected:

1. Budget framing. The pair period at a 24fps source is 41.7ms regardless of
   multiplier (one pair per source interval; x2/x3 only change how many
   outputs each pair packs), not 27.8ms. The measured law outFps ~
   72 x 41.7/motionMs fits both sessions (1080p: 72x41.7/46.7=64.4 vs 64.3
   measured; 4K: 72x41.7/76.6=39.3 vs 37.9). motionMs against the 41.7ms
   pair period is the operative ratio; backpressure drops are the overflow
   symptom of the same overrun. Multiplier does not change the pair budget.

2. Resolution cost. RifeResolutionSetting.RES_480P downscales the capture,
   and the capture IS the whole pipeline: captureFrameFromInputSurface
   reads back at resolveCaptureDimensions() size, so motion search, blend,
   denoise and the frame the output renderer draws are all at 854-class
   resolution, upscaled to the surface at present. Watching "480p engine"
   is genuinely watching upscaled 480p - the user correctly refused it.
   It was never a fix for full-quality viewing, only proof of attribution.

Quality-preserving lever: meScale (SvPlayerSettings.meScale, "luma downscale
the SEARCH runs at", 1=full 2=half) reduces motion-search cost while blend
and output stay at capture resolution - full-resolution picture. Device
untested. Predictions against the 41.7ms period: 1080p ORIGINAL + meScale=2
should land under budget and remove the pulse at full picture quality; 4K
ORIGINAL + meScale=2 is borderline (readback 16.1ms and the non-search share
remain); fallback for 4K is RES_1080P (1080p-class picture upscaled to 4K,
far above 480p) or a code-side motion-path optimization (feature-sized).
Next evidence: 1080p x3 meScale=2 capture (expect motionMs ~25-30, outFps
72.0, backpressure ~0), then the same at 4K.

Problem K - the fix: lease-bucket pair pacing under sustained motion overrun

Device attribution (Problem J) showed the felt judder is not the display's
hold arithmetic but the capacity-4 overflow race: when motion search costs
more than the 41.7ms pair period (46.7ms at 1080p, 76.6ms at 4K full
engine), drop-oldest fires at random frame positions, pair spans jump to
2I, and warps are emitted in bursts - the irregularity the user feels. The
clean 24Hz beat of an even 72-on-120 stream was proven imperceptible by the
user's own 480p-engine A/B (S3/S4).

Fix (Kotlin pipeline control only; no frame math, shader or native change):
PairPacing, a lease bucket on the pair submit path. It accrues one source
interval per candidate pair and charges the EWMA'd motion cost of every
processed pair; a pair is declined while the credit sits below the motion
cost, which makes declines land on a fixed period (~1 per 8 pairs at 1080p,
~1 per 2 at 4K) instead of wherever the overflow race fires. A declined
pair releases the anchor, advances it to the next frame without synthesis
and never reaches motion, so every processed pair keeps the original
one-interval span - no PAIRGAPs, no 2I pair readings, no srcFps=12.011
pollution. While motion fits the interval the estimator is inert: a healthy
pipeline never declines. Pipeline resets clear the estimator; [PACING] grew
a pairSkip= window delta.

Expected effect: 1080p full engine keeps its full-resolution picture and
presents ~63 regular fps (one missed grid slot per ~333ms - the
imperceptible class) instead of 64.3 irregular fps with burst clumping; 4K
full engine presents ~36 regular fps instead of 37.9 bursty - the
burst-judder component goes, the FPS shortfall does not, because 76.6ms of
motion per pair is a compute limit no pacing policy can erase. Full-quality
4K x3 at 72fps remains: meScale=2 (user setting, full-res output, untested
on device), or a motion-path optimization (separate, larger work).

Verified: PairPacingTest 9/9 (deficit-rate decline, bounded period,
cost-collapse release, reset), full gradle suite green.

Problem L - black HDR and residual 1080p judder on the PairPacing build (5a30acf)

Observed (device, user): the 5a30acf build plays HDR completely black, with
HDR-to-SDR both enabled and disabled; 1080p full-resolution still judders.
Timeline evidence: the 586ed03 build showed working HDR-to-SDR on device
(the 0190 capture sessions S2/S4), and the only code delta since is the
PairPacing commit.

Cause (code trace, both symptoms one defect): the PairPacing decline at the
CHAIN submit site (trySubmitNextMotion) had invalid ownership. There `prev`
is the current pair's second frame - still motion-leased, about to render,
and about to become previousFrame again through that render. The decline
called releaseFrameBuffer(prev), which DEFERRED the release behind the
motion lease, then promoted the peeked frame into previousFrame. The render
that followed overwrote previousFrame back to `prev`, orphaning the promoted
frame (its buffer and HDR source slot were never released again - a leak per
decline), and dropMotionLeases then executed the DEFERRED release on `prev`
itself: the live anchor's buffer re-entered the pool (subsequent captures
aliased it, corrupting motion inputs - the residual 1080p judder) and, in
HDR-retain mode, its retained OES texture was freed while still needed
(hdrTextureByAnalysisBuffer lookup returns 0 -> hdrComposition false -> the
warp falls off the retained-texture path), while each orphaned frame
permanently consumed one hdrSourceSlot. Slot exhaustion ends in
"hdr source leases exhausted" refusing every enqueue - a permanent
blackout. The CAPTURE-side decline (processNextFramePair) was correct: its
prev is a stable unleased anchor and its next has already left the queue.

Fix: the chain site no longer applies declines. It parks the decision in
pendingChainDecline and holds pairProcessing (the in-flight gate) through
the render; renderPendingQueue applies the decline AFTER dropMotionLeases,
at the exact point where the rendered pair's second frame has finished both
of its roles: the finished anchor is released (buffer pool + HDR slot) and
the peeked frame is promoted, one accrual per pair, chain pipelining
preserved for every processed pair. resetPipelineOnWorker clears the parked
decision. The ownership contract is documented at declinePairForPacing
(capture-site only) and at the chain gate.

Tests: PairPacingTest 15/15 - the original nine plus bounded consecutive
declines at mild (<=1) and heavy (<=2) overrun (no starvation, no unbounded
credit), jittered cost around the interval, sudden cost-rise with a bounded
decline-onset window and settled heavy rate, jittered source intervals, and
post-overrun release within a few pairs. HdrToneMapSelectionTest 13/13 -
plus decision statelessness across streams/prior failures (the contract
behind the first-launch freeze). Pure tests do not prove a nonblack device
image; device verification is pending.

OES/FBO/PBO audit (01100 section 3.2): refusals log with full readback state
at 1/s; [RECOVER] discards-and-retries-once per stuck ring; dead-sync and
null-map heads are discarded; RGBA16F slot allocation checks framebuffer
completeness and fails closed. No additional defect found on those paths.
Existing logs discriminate the black-origin stages as follows: [CONTENT]
capture checksum nonzero => pixels were born sane before the queue;
[RECOVER]/refuse => capture-stage starvation; [HDR] status + readbackState
leases => retain-path health; [PACING] backpressure/stale/pairSkip =>
queue pacing; [PIPELINE] path= + previewFps => renderer activity. No new
instrumentation was required.

Verification: compile, ktlint, app unit tests, model tests all green
locally; no native files touched. Device verification (HDR10/PQ with
tone-map on and off, HLG, session transitions, 1080p Original vs 480p
diagnostic, 4K) not yet performed - required procedure is section 6 of
mi-moPrompt01100.md.

Problem M (mi-moPrompt01100 motion task) - 4K motion-estimation throughput

Profiling (before any optimization edit): the offline harness gained a `profile` mode
(64 motionField calls per config so the 60-frame STAGES/SEARCH report fires) and the
native gained per-pyramid-level wall/SAD counters plus per-direction pool work/run
deltas (SEARCH line, same cadence as STAGES). Measured at the shipped defaults
(16x16, overlap 2, q=1.0, sd=60, 4 threads, this device): 4K motion 182-190 ms
(fwd+bwd buckets), split level2 (coarsest) ~46% / level1 ~13% / level0 (finest)
~45% of candidate SADs; pool efficiency 80-82% (compute-bound, not thread-waiting);
raw sad16x16 microbench 7.8 M calls/s per core. The bottleneck is the exhaustive
square candidate scan itself at both pyramid ends: 961 candidates per coarse group
(range 15) and 49 per fine block x 57,600 blocks per direction.

Optimization (quality-exact by construction, applied only to the candidate scan):
1. Penalty-aware elimination - a candidate whose newLambda/zeroLambda penalty sum
   already meets the best score is never measured (at 4K, 14.4 M of 15.4 M candidates
   per frame die here; most are O(pixels) far from the prediction).
2. Centre-out square rings - the prediction is scored first so `best` is established
   before the outer rings; only tie preference changes (favouring the near-prediction
   minimum), the candidate set and scoring are identical.
3. Row-PDE bounded SAD (sadWinBounded) - rows are summed with an early exit once the
   monotone partial total exceeds best-minus-penalties; returns the exact SAD when the
   candidate qualifies, so winners are unchanged. One real bug was caught in review/
   differential testing: the first 8x8 bounded kernel dropped bytes 4-7 of each row
   (half-sum), corrupting every meScale=2 search; fixed and re-verified field-exact.

Results (paired before/after on identical synthetic frames, tMin of 64 runs):
1080p ms=1 sd=60: 51-52 -> 32-33 ms (1.6x); 4K ms=1 sd=60: 184-190 -> 112-121 ms
(1.5-1.6x; a cool-device run measured 73 ms after vs 124 before); 4K ms=2 sd=60:
~20 ms unchanged (already cheap). The 4K full-resolution stage therefore sits at
~2-3x the 41.7 ms pair budget on this hardware class even after the win: the budget
is unattainable at meScale=1 here. Best measured trade-off remains the existing
user-selectable meScale=2 (~20 ms, fits with headroom), which is NOT defaulted and
whose quality cost is the documented -8..-12 dB psnr_correct at 1080p (Problem B);
it remains a settings choice with the trade-off stated in SvPlayerSettings.

Quality: scale-sweep (480p/1080p/4K, ms 1/2, sd 60/48, shifts 0..63 + resolution-
relative) before vs after: max |delta| = 0.01 dB on every PSNR column, frac_1px /
frac_both / sat_pct identical on all 58 rows; two rows shift the median error by
1 half-pel at sd=48 (non-default) pack-clamped dx=63. Packed-field diffs: only the
known left/right border bands (a handful of blocks per thousands) move, by
tie-breaks between equal-score candidates, exactly where Problem B documented the
by-design crossfade masking. Half-pel units, the +128 bias pack, the 63.5 px pack
ceiling, scene-cut SAD measurement and mask construction are untouched.

Regression tests: the harness `verify` mode pins 16 checks and passes identically on
the pre-change and post-change builds - vector accuracy at ms=1/2 for shifts
0/16/24/32/48/63 and diagonal, the sd=16 short-reach collapse (dx=48 unresolvable),
whole-pixel mode producing no odd half-pel vectors outside pack clamps, odd
(partial-tile) frame sizes, reset()/scratch reuse byte-identical to a fresh instance
(buffer ownership), and CPU-warp static passthrough PSNR>40 (CPU/GPU consistency
proxy for the shared prepare/field path). Build: clang++ -std=c++17 -O2 -pthread
-I stub -I <repo>/app/src/main/cpp bench.cpp <repo>/app/src/main/cpp/memc_interpolator.cpp
; run `./bench verify`. gradle suite + clang -Wall -Wextra syntax check PASS.
Changed repo files: app/src/main/cpp/memc_interpolator.{h,cpp} only (search loop,
bounded SAD kernel, SEARCH profiling). HDR, tone-map, PairPacing, output timing,
packing contracts and defaults untouched.

Device-unverified: all timings are from the offline harness on the development device
(5 cores, 4 threads, NEON); the target box's [PIPELINE] motionMs and [SEARCH] lines
must be re-captured after installing the CI APK, and 4K fast-pan behavior watched for
the pack-ceiling saturation counter. No new judder is expected (PairPacing and the
output grid are unchanged) but this remains unverified on physical hardware.

Problem M addendum - meScale=1 vs meScale=2 on identical inputs (post-optimization,
sd=60, synthetic translation, harness scale + profile modes; interpolate() times below
include the CPU NEON warp, which the GPU motionField path does not pay - use the
motionField tMin from profile for stage comparisons):

  res    ms  dx=16 psnr_cor frac1px | dx=32 psnr_cor frac1px | dx=48 psnr_cor frac1px | motion ms (profile tMin)
  1080p   1    64.3   0.993       |  62.7   0.981       |  61.7   0.966       | 32-33
  1080p   2    58.4   1.000       |  52.5   1.000       |  49.2   0.999       | ~8
  4K      1    69.0   0.997       |  63.2   0.991       |  65.8   0.983       | 112-121 (73 cool)
  4K      2    60.9   1.000       |  54.9   1.000       |  50.3   1.000       | ~20

Reading: ms=2 keeps the vectors inside one full-res pixel (its precision floor) but
loses 5-12 dB of psnr_correct against the analytic mid-frame as the resample quantises
to whole-pixel positions; ms=1 holds the half-pel lattice. At 4K, ms=1 after the
optimization is still 2-3x over the 41.7 ms pair budget on this device class, while
ms=2 fits with headroom - the explicit, user-selectable lever (ProcessingSettingsView,
default stays 1). No silent reduction was made; the trade-off remains as documented in
Problem B and in the SvConfig header comment.

Fresh-eyes re-audit (prompt 01200, HEAD=4020a8a) - confirmed vs hypothesis.
Scope: full code trace of capture -> pair -> motion -> render -> present for SDR and
HDR, ownership/lifetime of every buffer class, pacing gates, ME contracts, output
cadence. No device was involved; every CONFIRMED item below is code-read evidence
(this session), every HYPOTHESIS needs the target box.

CONFIRMED (code):
 1. Single-worker event loop is what makes the gate/pendingChainDecline windows
    safe: capture (queueCapturedFrame, backpressure drop-oldest, stale-discard
    loop) and render (renderPendingQueue) are separate handler posts on one
    looper, so no capture can interleave inside a render callback. The peeked
    frame a parked chain-decline will promote therefore cannot be stolen by
    backpressure between peek (trySubmitNextMotion) and poll (apply block), and
    declinePairForPacing always sees a stable unleased anchor.
 2. Chain-decline apply ordering is exact: gate cleared -> render -> dropMotionLeases
    (drops prev/next leases, 'next' count 2->1 when chain re-leased it) -> poll
    promote -> release old anchor -> pairSkipCount -> gate false. Stale-generation
    branch: drop(recycle=false) removes the deferred entry, then the explicit
    releaseFrameBuffer() finalizes exactly once (finishFrameBufferRelease is
    idempotent: pool dedup guard + hdr map remove-nothing on second call).
 3. HDR source lifetime has no leak path: enqueue sets inUse (refuses on any inUse
    for resize; refuses when leases exhausted, with recovery), poll publishes
    hdrTextureByAnalysisBuffer[buffer], finishFrameBufferRelease releases it,
    releasePbos() releases still-pending sources on PBO size change and teardown,
    releaseHdrSources() deletes fb+textures, reset clears map after leases drop.
    One texture <-> one buffer at a time throughout.
 4. Async capture never blocks: glClientWaitSync is zero-timeout; a not-ready head
    frees the just-obtained analysis buffer and breaks; the drain loop empties
    whatever the GPU finished between captures.
 5. Output cadence survives declines: after a skipped span nextOutputUs lies in
    (lastPairEnd, lastPairEnd+step] < next baseUs, so outputTimesFor's
    re-anchor condition (nextOutputUs <= baseUs) fires - phase restarts with one
    emission per pair, no burst and no missed slot. OutputTimelineGuard stays
    monotonic by construction; 3x pins thirds; MAX_OUTPUTS_PER_PAIR caps bursts.
 6. Residency shortcut requires token!=0 AND token==resident AND same buffer
    instance AND same dims, and is reset on pipeline boundaries (seek/stream
    change), so pool recycling and timestamp replay cannot present stale pixels.
 7. interp_mutex_ serializes interpolate() vs motionField() (shared prepare()
    pyramid scratch). Contentious only when the CPU fallback renders while the
    next pair's motion runs off-thread - a rare path that itself requires a
    failed/refused motion or warp. Deliberate tradeoff (splitting needs a second
    scratch set); recorded, not fixed.
 8. 1 Hz glReadPixels GPU probe in GlOutputRenderer.swapBuffers() forces a
    pipeline flush once per second by design; rate-limited, intentional, not a
    cadence-period stall.
 9. motionExecutor is never shut down (daemon thread), so execute() cannot throw
    RejectedExecutionException; teardown safety comes from workerHandler==null and
    handler.post()==false branches, both of which drop leases without recycling.

INSTRUMENTATION ADDED (this session, VideoFrameProcessor.kt only):
 - pboW8=<n> in [PACING]: windowed count of polls where a PBO was pending but its
   fence had not signalled. Discriminates "GPU readback bound ring" (pboW8 rising
   while readback=pending fills) from an idle ring; previously head-not-ready and
   ring-empty were indistinguishable.
 - motionLeases=/deferred=/pool= gauges in [PIPELINE]: live motion lease count,
   deferred-release backlog, pool size. Discriminates lease leak / pool starvation
   from stage-cost regressions when output degrades.
 Nothing else was added: [PIPELINE]/[PACING]/[SCHED]/[CADENCE]/[HDR]/[CONTENT] plus
 native STAGES/SEARCH already split capture, poll, copy, jni, motion, interp,
 render, swap, queue age, worker gaps, decoder intervals and cadence arithmetic;
 actual display pacing remains a Perfetto FrameTimeline question (platform tool).

OFFLINE BASELINES (todo 4/8; Termux host CPU, 4 threads, medians of 4 runs -
NOT the target device): perf mode before(parent of 4020a8a) vs after(4020a8a):
 1080p ms1 sd60 motion 64.2 -> 61.3 ms (noise band); 1080p ms1 sd48 66.7 -> 33.7;
 4K ms1 sd48 158.5 -> 96.7 (-39%); 4K ms1 sd60 171.1 -> 106.6 (-38%);
 4K ms2 rows ~24 ms unchanged. profile mode (64 calls, sd60) tMed:
 1080p ms1 37.3 -> 30.4; 4K ms1 178.8 -> 117.7 (-34%); 4K ms2 17.7 -> 17.2.
 interp_ms deltas before/after are run noise (the ME change does not touch
 interpolate; the before-run shows the expected sd48<sd60 inversion). verify mode
 on the after build: 16/16 PASS exit 0 (accuracy, half-pel, reach, pack ceiling,
 odd sizes, reset byte-identity, CPU-warp passthrough). 4K ms1 motion alone
 (107 ms) is ~2.5x the 41.7 ms pair budget - the measured basis for Problem C
 and for PairPacing declining pairs. Saved: svpbench/perf_baseline_01200.txt,
 profile_{before,after}_01200.txt.

DECISION: no new behavioral fix staged. Across capture, pairing, gates, leases,
HDR lifetimes, fences, cadence and present paths the re-audit found no remaining
code-level defect; the only gaps were observational (pboW8, lease gauges), which
are the instrumentation above. Stage A-F stays empty pending device evidence.

HYPOTHESES still open (need target box): (h1) residual 4K stutter is pacing
periodicity (pairSkip beat) rather than stage overrun - check [PACING]
pairSkip/pairUs p95 against submitUs outliers; (h2) any black frame correlates
with hfb= or [RECOVER] lines, not with the warp path; (h3) HDR lifetime errors,
if seen, will show as motionLeases/deferred gauges never returning to 0 across a
seek - if so, the reset sequence is the first place to look, and these gauges
now make that observable.

===========================================================================
PROBLEM N - REAL-TIME LAG + 4K THROUGHPUT (mi-moPrompt01300.md, bacadbf)
===========================================================================
SCOPE: user reports the latest installed APK still shows (1) playback lag /
momentary slowdown while interpolation runs and (2) a persistent 4K
full-resolution throughput deficit. Baseline commit bacadbf (instrumentation
gauges only; chain-decline + ME optimisation already shipped in 797c9bb and
4020a8a). adb available but NO DEVICE ATTACHED this session - every device
claim below is either quoted from Problem J's pre-optimisation capture or
labelled as a code/measurement finding; the installed-APK run number
(38741199459 vs 38753508692) cannot be verified from here.

STAGE A - TIMER AUDIT (acceptance: resolve interpMs ~ 0.00 misattribution):
 - Root cause confirmed in code: on the GPU-warp path the interpolate dispatch
   returns true with no CPU work, so nsInterp legitimately reads ~0; the real
   per-pixel resample runs as a shader inside the render window and was only
   visible inside renderMs. That is why Problem J's every-window interpMs was
   0.00 - not a broken pipeline.
 - Added nsWarp / warpMs= / warp=: wall time of the shader present block
   (upload-warp 3428 / retained-HDR-texture 3412 / denoised 3388 region),
   strictly a SUBSET of renderMs (no double counting; CPU fallback still
   owned by renderMs and its JNI cost still lands in interpMs by design).
   Field + reset + [PIPELINE] + PIPELINE TIMING lines updated together.
 - All other stage timers re-checked against their windows (readback, poll,
   copy, jni, motion, render splits, queue latency, worker gap, decode
   interval): no other hidden-work or misattributed-wait found.

HITCH CANDIDATES FOUND IN THE PRESENT PATH (both unconditional, both 1 Hz):
 1. GlOutputRenderer.swapBuffers centre-patch probe: glReadPixels is a
    synchronous full-pipeline flush - up to a frame of GPU time on the caller
    - and it ran once per second on the live playback path. Gated behind
    VERBOSE_DIAGNOSTICS (false in shipped builds) with the reason at the site;
    black-discrimination stays observable via [CONTENT] + preview/swap
    counters. This is the strongest code-level explanation for periodic
    "momentary slowdown" at exactly 1 Hz cadence that survived the earlier
    audits (they checked correctness, not flush cost).
 2. [CONTENT] calculateChecksum walked every 16th pixel (~520K samples,
    ~2-6 ms of worker time) once per second; density buys nothing - a black
    frame sums to 0 at any rate. Now fixed ~4096 samples regardless of
    resolution. Constant cost, same discriminator.
 Static scan of remaining GL syncs: only zero-timeout fence polls (already
 audited), sticky glGetError queries, and the gated probe - nothing else.

THREAD COUNT - NOT CHANGED (refutes the naive fix):
 - RifeEngineController:202 hardcodes setInterpolationThreadCount(4), but the
   site comment records a measured optimum: 8 threads was ~2x SLOWER than 4 on
   an 8-core big.LITTLE device because ME/MC loops are memory-bound. An
   availableProcessors() bump would be a regression; left at 4.

4K BUDGET QUANTIFICATION (acceptance: measured+improved or quantified):
 - Problem J pre-M device: 4K (3840x2076) motionMs 76.6 / outFps 37.9 vs the
   41.7 ms pair budget (72 fps target); host harness post-M: -34..-38%.
   Extrapolated device post-M motion ~47-50 ms - still over budget, so
   PairPacing should now skip roughly every 7th pair (~62-63 outFps), a large
   gain over 37.9 but short of 72 until ME drops under 41.7 ms.
 - meScale=2 measured ~20 ms in-harness and audited search-ONLY (meScaleOf
   touches the luma search domain; motion grid, blend and output stay
   full-res; verify mode 16/16 PASS includes ms2 cases) - the concrete lever
   to full 72 fps at 4K if the post-M capture still shows motionMs > 41.7.
 - Next device capture must log: [PIPELINE] warpMs/motionMs/renderMs,
   [PACING] pboW8/pairSkip/backpressure, encoderFps/previewFps, at 1080p and
   4K, meScale 1 and 2 - warpMs>0 with interpMs~0 confirms the split is
   reporting the shader; warpMs~0 with renderMs high indicts upload/swap.

OPEN HYPOTHESIS (encode path): while an encode runs, every frame also does a
full-surface glCopyTexImage2D into the mirror plus a second eglSwapBuffers
for the phone preview - GPU work proportional to the PRIMARY surface size.
If the next capture's lag windows correlate with encoderFps>0, that copy is
the next measured candidate; left unchanged (pre-existing, device-specific).

VERIFICATION: :app:compileDebugKotlin :app:ktlintCheck :app:testDebugUnitTest
--offline --no-daemon BUILD SUCCESSFUL (227 tasks; PairPacing/Windowed/
Quality/decoder suites green). No native file touched - clang++ not applicable;
offline harness untouched, previous verify 16/16 still stands.

===========================================================================
PROBLEM O - 480p-CLASS SMOOTHNESS AT FULL RESOLUTION (meScale 4/8)
===========================================================================
USER DEMAND: the 480P processing-resolution mode plays smoothly; ORIGINAL
at 1080p/4K does not. They want the SAME smoothness at the exact original
resolution - i.e. the SVP model: estimate motion on a ~480p-class luma,
motion-compensate at full resolution.

DIAGNOSIS:
 - RifeResolution 480P is smooth because capture, readback, ME AND the
   warp are all tiny; ORIGINAL at 4K died on ME alone (Problem J device:
   motionMs 76.6 vs the 41.7 ms pair budget at 72 fps).
 - The engine already contained every piece of the SVP architecture:
   blockStep() = (kBlock - overlapPx) * meScale (full-res pitch grows
   with the scale, so the vector count falls with its square); the warp
   is dense per-pixel with a 4-tap bilinear read of the field (block
   seams removed by construction); pack units, search distance, refine
   publish and nlm residuals all convert through ms generically.
 - THREE things blocked the coarse ladder:
   1. meScaleOf clamped at 2 (`>= 2 ? 2 : 1`);
   2. the ms>1 luma build hardwired ONE downsample2 (correct only for
      ms=2 - a ms=4 build would have searched half-res luma in
      quarter-res buffers);
   3. the warp's mvGridAxis weight table was kBlock*2+1 (33) while the
      step reaches kBlock*8 (128) at ms8 - a stack overflow waiting for
      the clamp to open.

CHANGES (commit below):
 - Native: meScaleOf 1/2/4/8; downsampleBox (one-pass factor-4/8 box
   average; ms2 keeps the exact downsample2, bit-identical); wtab
   kBlock*8+1; regulariseField anchor floor 1 domain px (was 0 at ms8);
   scratch LOGI prints ms=; header docs.
 - Kotlin: RifeEngineController derives effective meScale at runtime -
   user's Search Resolution setting is a FLOOR, auto raises until the
   search domain is <=900 px tall, both held to a >=240 px domain floor
   (below that the coarse pyramid levels vanish and the wide coarse pass
   disappears). Applied on setSvPlayerSettings, setResolution and
   setInputFrameSize (first point the processing size is known). Policy:
   480p/720p -> ms1; 1080p -> ms2 (540p domain); 4K -> ms4 (540p domain);
   >3600px -> ms8. UI gains Quarter/Eighth rungs; SvPlayerSettings doc
   updated. Output resolution NEVER changes - only the search coarsens.
 - Harness: verify extended (ms4 at 480p, ms8 at 1080p, ms4 reset-reuse
   byte-identity, ms4 CPU-warp passthrough). 27/27 PASS. ms4 resolves
   16/32/48 px shifts at frac=1.000 (perfect); ms8 at 1080p 16/32 px
   frac=1.000; the ms8/64px row is a documented reach-boundary probe
   (ms8 reliable reach ~50-60 px, why the policy prefers ms4 for 4K).
 - Perf (host, 4 threads, 3 runs, medians): 4K motion ms1 110-192 ms ->
   ms2 25-64 -> ms4 14.5-27.8 -> ms8 10.6-32.7. ms4 lands UNDER the
   41.7 ms pair budget on the host; device pre-M ms1 was 76.6 ms, so
   device ms4 is expected ~10-20 ms (device-unverified). NOTE: harness
   interp_ms is the CPU warp fallback, not the shipped GPU path.
 - Quality: at ms4 the field quantization floors at 2 full-res px and
   the dense warp interpolates between vectors - the same look class as
   the smooth 480p processing mode, now composited at full resolution.

VERIFICATION: clang++ -fsyntax-only clean; harness verify 27/27;
gradlew :app (compile+ktlint+test), :feature:player (compile+ktlint),
:core:model (compile+test), :core:ui (compile) all BUILD SUCCESSFUL.
Device: no adb this session - on-device judder/4K fps claims pending the
CI APK; check [PIPELINE] motionMs (expect << 41.7 ms at 4K) and the new
"meScale=4 (user=1)" lifecycle line.
