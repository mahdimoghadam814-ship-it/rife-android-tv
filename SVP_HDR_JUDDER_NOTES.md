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
opt-in. Device check pending: HDR sources look natural, not blown out.

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

## Verification status

- Local: `./gradlew :app:compileDebugKotlin :app:ktlintCheck :core:model:test
  :app:testDebugUnitTest --offline --no-daemon` BUILD SUCCESSFUL (all tests pass).
  clang++ -fsyntax-only on memc_interpolator.cpp clean.
- Not verified (needs device): HDR exposure, 1080p-vs-480p quality, 4K FPS,
  no 4K frame skips, judder pattern gone.
- CI: `gh workflow run build.yml --ref fix/svp-hdr-judder-and-quality`.
- Branch-conflict note: repo AGENTS.md mandates
  `feature/media-playback-rife-processing-15429977835613455991`; the task prompt
  (mi-moPrompt0160.md) explicitly requires this work to land on
  `fix/svp-hdr-judder-and-quality`. The task prompt was followed.

## Harness (offline, kept out of the repo)

`/data/data/com.termux/files/usr/tmp/opencode/svpbench/bench.cpp` +
`stub/android/log.h`, built against app/src/main/cpp/memc_interpolator.cpp with
termux clang++ -O2. Modes: `quick` / `full` (quality sweep, CSV), `debug`
(field/pixel trace), `perf` (1080p-vs-4K motion+interp timing). NEON-only, cannot
compile on x86 CI; lives outside the repo deliberately.
