# AGENTS.md

## Branch policy (MANDATORY)

- **Always work on, commit on, and push to `feature/media-playback-rife-processing-15429977835613455991`.**
- **Check the branch immediately before every `git commit`** with:
  `git rev-parse --abbrev-ref HEAD`
  If it is not `feature/media-playback-rife-processing-15429977835613455991`, STOP and switch back first.
- Only that branch triggers the CI run that produces the APK used for on-device ADB verification.

## Temporary Branch Exception — SVP/HDR Regression Fix

For the specific SVP/HDR regression investigation and fix associated with commits "2fd64fc", "b238a9d", and "bb3dfd2", work may be performed on "cleanup/svp-only-root-cause" because that branch contains the regression and the CI workflow required to produce a device-testable APK.

- Limit changes to the verified root cause and the minimum required regression tests.
- Do not change interpolation math, shaders, or unrelated playback components.
- Run the available local checks and use the cleanup branch's CI APK for device validation.
- Do not claim the issue is fixed until the required device tests pass.
- This exception applies only to this regression fix. All other work must continue to follow the existing feature-branch policy.
- After validation, report whether the fix should be merged or cherry-picked elsewhere; do not rewrite branch history or force-push without explicit approval.

## Concurrent session hazard

A second opencode session runs in this same worktree and switches branches (e.g. to
`feature/fastdvdnet-temporal-denoise`). It may change `HEAD` at any moment, so the branch
check above is not optional. Never stage with `git add -A` / `git commit -a`; stage named files only.

## Local verification (no NDK available)

- C++ syntax: `clang++ -std=c++17 -fsyntax-only -Wall -Wextra -I app/src/main/cpp -I third_party/ncnn/src -I third_party/rife-ncnn-vulkan/src -I build/preflight/ncnn/src -I <stubdir> <file>` (stub dir: `/data/data/com.termux/files/usr/tmp/opencode/stub`; JNI headers: `-I /data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/include -I .../include/linux`)
- Kotlin: `./gradlew compileDebugKotlin --offline --no-daemon`
- Do not watch CI output; the user reports CI results.
