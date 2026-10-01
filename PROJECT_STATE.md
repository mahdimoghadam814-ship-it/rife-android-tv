# PROJECT_STATE.md

## Current Architecture Status

**Completed Stage 1**: Variant-Aware SPIR-V Precompilation for RIFE v2.4 Vulkan Path

**Completed Stage 2**: SVP-Inspired Architecture Skeleton

**ncnn Commit**: `30cc1902b6eb83b2056cbc9fc413b3c747940bb2`

**Main Repository Commit**: Stage 2 architecture skeleton

## Completed Stage 1 Summary (SPIR-V Precompilation)

### What Was Achieved

1. **Variant-Aware Registry**: `layer_shader_registry_entry` now contains an array of `layer_shader_spv_variant` keyed by `(shader_type_index, opt_bits)`

2. **Exact opt_bits Mapping**: All opt_bits values derived from ncnn's `encode_spirv_cache_opt_bits()`:
   - Base: `0x7FEC` (bf16_p=0, bf16_s=0, fp16_p=1, fp16_s=1, fp16_a=0, fp16_u=1, int8_p=1, int8_s=1, int8_a=1, int8_u=1, sub=1, slm=1, cm=1, i16_p=1, i16_s=1)
   - INT8: `0x1FEC` (bits 4,13,14 cleared)
   - INT8+int16_storage: `0x5FEC` (bit 14 set)
   - INT8+int16_packed: `0x3FEC` (bit 13 set)
   - FP32 weight upload: `0x7FE0` (bits 2,3 cleared)
   - Mali-G310 non-CM: `0x6FEC` (bit 12 cleared)

3. **378 Precompiled SPIR-V Blobs**: One per `(shader_type_index, opt_bits)` covering all production RIFE v2.4 paths

4. **Mali-G310 Non-CM Coverage**: 17 shader types with cooperative-matrix fallbacks precompiled at opt_bits=0x6FEC

5. **Zero Runtime glslang Fallback**: `compile_spirv_module()` returns explicit error on missing variant — no path to `glslang::GlslangToSpv()`

6. **Duplicate Key Detection**: Registry generator raises hard error on duplicate `(shader_type, opt_bits)`

7. **Build-Time Validation**: `validate-ncnn-spirv` target validates magic (0x07230203), size, alignment

8. **Mali-G310 Blacklist Removed**: `rife_engine.cpp` no longer blocks Mali-G310; capability-driven selection now works

## Completed Stage 2 Summary (SVP-Inspired Architecture Skeleton)

### New Architectural Components Added

1. **Frame Metadata / Timestamp Representation** (`SvpPipelineContracts.kt`)
   - `FrameMetadata` - immutable frame data with timestamp, format, dimensions
   - `FramePair` - two consecutive frames for interpolation
   - `FrameFormat` enum for supported pixel formats

2. **Scene/Motion Analysis Interfaces** (`SvpPipelineContracts.kt`)
   - `SceneChangeResult` / `SceneChangeType` - scene change detection results
   - `MotionQuality` - comprehensive motion metrics (magnitude, confidence, occlusion, texture, vector consistency)
   - `InterpolationMode` enum (SAFE, CONSERVATIVE, BALANCED, AGGRESSIVE)

3. **Scheduling & Pipeline Interfaces** (`SvpPipelineContracts.kt`)
   - `ScheduleDecision` - complete interpolation decision with timing, resolution, mode
   - `PipelineState` enum (IDLE, BUFFERING, READY, PROCESSING, DRAINING, STOPPED, ERROR)
   - `SynthesisBackend` interface with `SynthesisConfig`, `SynthesisResult`
   - `OutputQueue` interface with bounded capacity
   - `TemporalFrameStore` interface with configurable eviction
   - `SceneChangeDetector` and `MotionQualityAnalyzer` interfaces
   - `InterpolationScheduler` interface with `SchedulerConfig`, `SchedulerState`
   - `PipelineListener` for monitoring

4. **Concrete Implementations**
   - `TemporalFrameStoreImpl` - ring buffer with age/keyframe eviction
   - `SimpleSceneChangeDetector` - placeholder histogram-based detector
   - `PlaceholderMotionQualityAnalyzer` - conservative quality metrics
   - `InterpolationSchedulerImpl` - basic scheduling with adaptive mode
   - `BoundedOutputQueue` - ArrayBlockingQueue with backpressure
   - `RifeSynthesisBackend` - wraps existing NativeEngine JNI calls
   - `SvpPipelineCoordinator` - main pipeline orchestrator

### Key Design Decisions

- **No new threads/locks** - all components are single-threaded interfaces
- **Bounded queues** - `BoundedOutputQueue` uses `ArrayBlockingQueue` with fixed capacity
- **Explicit ownership** - `SvpPipelineCoordinator` owns all components
- **Clear lifecycle states** - `PipelineState` enum with explicit transitions
- **Policy-ready abstractions** - backend type, resolution, mode all configurable
- **No GPU-name coupling** - backend selection via `BackendType` enum
- **FastDVDnet independent** - no changes to existing FastDVDnet scaffold

### Files Added/Modified

**New Files:**
- `app/src/main/java/com/rife/androidtv/rife/SvpPipelineContracts.kt` - Core interfaces & data classes
- `app/src/main/java/com/rife/androidtv/rife/TemporalFrameStoreImpl.kt` - Frame store implementation
- `app/src/main/java/com/rife/androidtv/rife/SimpleSceneChangeDetector.kt` - Scene detector
- `app/src/main/java/com/rife/androidtv/rife/PlaceholderMotionQualityAnalyzer.kt` - Motion analyzer
- `app/src/main/java/com/rife/androidtv/rife/InterpolationSchedulerImpl.kt` - Scheduler
- `app/src/main/java/com/rife/androidtv/rife/BoundedOutputQueue.kt` - Output queue
- `app/src/main/java/com/rife/androidtv/rife/RifeSynthesisBackend.kt` - Backend wrapper
- `app/src/main/java/com/rife/androidtv/rife/SvpPipelineCoordinator.kt` - Pipeline coordinator

**Files Unchanged:** Existing `RifeEngineController.kt`, `rife_engine.cpp`, ncnn SPIR-V changes

## Next Stage: TemporalFrameStore + Bounded Buffering Integration

### Immediate Goals
1. Integrate `TemporalFrameStoreImpl` with existing `VideoFrameProcessor` frame capture
2. Connect `SvpPipelineCoordinator` to existing `RifeEngineController` flow
3. Implement real `SceneChangeDetector` (histogram/block difference)
4. Implement real `MotionQualityAnalyzer` (motion vector / block analysis)
5. Wire `RifeSynthesisBackend` to actual frame data from `NativeEngine`
5. Add pipeline statistics collection

### Non-Negotiable Constraints (Maintained)
1. No runtime glslang in production paths
2. No silent fallbacks — explicit errors only
3. No unbounded queues or frame copies
4. No GPU-name-based shader selection (capability-driven only)
4. FastDVDnet and RIFE lifecycles remain completely independent
5. Real backend transitions must recreate resources (not just flip flags)
6. All expensive operations must have measurable latency/memory budgets
7. Mali-G310 target: sustained playback without lmkd kills, memory < 1.2GB

## Key References
- ncnn SPIR-V commit: `30cc1902b6eb83b2056cbc9fc413b3c747940bb2`
- Main repo Stage 1 commit: `319fddf`
- Main repo Stage 2 commit: (this commit)
- Historical context: `NEMOTRON_RIFE_LOG_CONTEXT.md`
- Architecture spec: `NEMOTRON_SVP_INSPIRED_ARCHITECTURE.md`