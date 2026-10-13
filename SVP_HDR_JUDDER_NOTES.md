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
