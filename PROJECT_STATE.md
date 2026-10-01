# PROJECT_STATE.md

## Current Architecture Status

**Completed Stage 1**: Variant-Aware SPIR-V Precompilation for RIFE v2.4 Vulkan Path

**Completed Stage 2**: SVP-Inspired Architecture Skeleton

**Completed Stage 3**: TemporalFrameStore + Bounded Buffering Integration

**Completed Stage 4**: Scene/Motion Analysis Implementation

**Completed Stage 5**: Motion Quality + Interpolation Scheduler Integration

**ncnn Commit**: `30cc1902b6eb83b2056cbc9fc413b3c747940bb2`

**Main Repository Commit**: Stage 5 scheduler integration complete

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
- `app/src/main/java/com/rife/androidtv/rife/TemporalFrameStoreImpl.kt` - Frame store implementation

**Files Unchanged:** Existing `RifeEngineController.kt`, `rife_engine.cpp`, ncnn SPIR-V changes

## Completed Stage 3 Summary (TemporalFrameStore + Bounded Buffering Integration)

### Integration Point

**Integration Point**: `RifeEngineController` - the central controller that owns `VideoFrameProcessor` and manages the RIFE/FastDVDnet lifecycle.

**Integration Details**:
- Added `TemporalFrameStoreImpl` as a private member in `RifeEngineController`
- Store is cleared on lifecycle transitions: `start()`, `stop()`, `resetForDiscontinuity()`
- Added `getTemporalFrameStore()` getter for pipeline access
- Added `submitFrameToTemporalStore(metadata: FrameMetadata)` for frame ingestion
- Store config can be updated via `setInputFrameSize()` / `setResolution()` hooks

### Bounded Buffering Guarantees

- **Max History Size**: 3 frames (configurable via `TemporalFrameStoreConfig`)
- **Max Frame Age**: 500ms (configurable)
- **Eviction Policy**: Oldest non-keyframe first, then oldest frame
- **Keyframe Retention**: Configurable (default: true)
- **Memory Bound**: Strict upper bound on frame count and age

### Lifecycle Event Handling

| Event | Handler | Action |
|-------|---------|--------|
| `start()` | `RifeEngineController.start()` | Clear store, start processor |
| `stop()` | `RifeEngineController.stop()` | Clear store, stop processor |
| `seek/discontinuity` | `resetForDiscontinuity()` | Clear store, reset processor |
| `EOS/reset` | (via existing flow) | Clear store via existing reset path |

### Timestamp/Order Semantics

- **Frame IDs**: Monotonically increasing `Long` assigned by `TemporalFrameStoreImpl`
- **Presentation Timestamps**: Microseconds from media timeline (via `FrameMetadata.presentationTimeUs`)
- **Ordering**: Strictly increasing frame IDs + timestamp validation in `getLatestFramePair()`
- **Duplicate/Out-of-order**: Rejected by timestamp validation (intervalUs <= 0 returns null)
- **Reset/Seek**: Store cleared, frame ID counter continues (monotonic)

### Bounded Buffering Guarantees

- **Max Frames**: 3 (configurable, default conservative for 2GB device)
- **Max Age**: 500ms (configurable)
- **No Unbounded Collections**: `MutableList` bounded by `maxHistorySize`
- **No Frame Retention**: Frames evicted by age/count immediately

### Files Modified

- `app/src/main/java/com/rife/androidtv/rife/RifeEngineController.kt` - TemporalFrameStore integration

### Files Unchanged (Preserved Existing Behavior)

- `VideoFrameProcessor` (external dependency) - no changes
- `VideoFrameProcessor` callbacks - no changes
- `MediaCodec` / `SurfaceView` / `Audio` paths - no changes
- `NativeEngine` JNI inference - no changes
- FastDVDnet scaffold - no changes

## Completed Stage 4 Summary (Scene/Motion Analysis Implementation)

### New Analysis Components Added

1. **SceneChangeDetectorImpl** (`SceneChangeDetectorImpl.kt`)
   - Lightweight deterministic scene change detector using metadata and compact statistics
   - Detection signals (weighted):
     - Keyframe (I-frame) transition: 0.5 weight
     - Resolution change: 0.3 weight
     - Format change: 0.2 weight
     - Decoder metadata discontinuity (frame size, QP, frame type): 0.4 weight
     - Timestamp anomaly (>100ms): 0.1 weight
   - Hysteresis/debouncing:
     - Confirmation frames: 2 consecutive above threshold
     - Minimum 5 frames between confirmed scene changes
   - Change type classification: NONE, HARD_CUT, GRADUAL_TRANSITION, FLASH, UNKNOWN
   - Configurable thresholds and weights via `SceneChangeConfig`

2. **MotionQualityAnalyzerImpl** (`MotionQualityAnalyzerImpl.kt`)
   - Conservative motion quality classification based on cheap metadata signals:
     - Frame interval jitter (rolling statistics)
     - QP variance from decoder metadata
     - Frame size variance from decoder metadata
     - Keyframe interval regularity
   - Quality classes: UNKNOWN, LOW, MEDIUM, HIGH, UNRELIABLE
   - Produces `MotionQuality` with:
     - motionMagnitude, confidence, occlusionRatio, textureComplexity, vectorConsistency
     - isInterpolationSuitable flag
     - recommendedMode (SAFE, CONSERVATIVE, BALANCED, AGGRESSIVE)
   - Rolling statistics over 30-frame history (configurable)
   - Warm-up period: 15 frames before reliable classification
   - Configurable thresholds and weights via `MotionQualityConfig`

### Signal Sources (No Full-Resolution Analysis)

The analysis operates **exclusively on metadata and compact statistics** already available in the playback path:

1. **FrameMetadata fields**: `isKeyFrame`, `width`, `height`, `format`, `presentationTimeUs`
2. **Decoder metadata** (via `FrameMetadata.decoderMetadata`):
   - `frame_type` (I/P/B frame classification)
   - `frame_size` (compressed frame byte size)
   - `qp` (quantization parameter)
3. **Derived temporal statistics** (maintained in rolling windows):
   - Frame interval jitter (stddev/mean)
   - QP variance
   - Frame size variance
   - Keyframe interval regularity

**No full-resolution pixel processing, no optical flow, no GPU readbacks, no Vulkan compute.**

### Hysteresis/Debouncing

- **SceneChangeDetector**: Requires 2 consecutive frames above threshold, minimum 5 frames between confirmed changes
- **MotionQualityAnalyzer**: 15-frame warm-up, rolling 30-frame statistics, classification changes only on sustained shifts

### Memory Characteristics

- **SceneChangeDetector**: O(1) state (few scalars, no frame buffers)
- **MotionQualityAnalyzer**: O(historySize) = O(30) rolling statistics (primitive arrays)
- **Total analysis overhead**: < 1KB additional memory, zero full-resolution frame buffers

### Lifecycle Reset Correctness

- `SceneChangeDetector.reset()`: Clears hysteresis counters, resets last change tracking
- `MotionQualityAnalyzer.reset()`: Clears all rolling statistics, frame counter, classification state
- Both called automatically via `TemporalFrameStore.clear()` on seek/discontinuity/start/stop

### Files Added/Modified

**New Files:**
- `app/src/main/java/com/rife/androidtv/rife/SceneChangeDetectorImpl.kt` - Real scene change detector
- `app/src/main/java/com/rife/androidtv/rife/MotionQualityAnalyzerImpl.kt` - Real motion quality analyzer

**Files Replaced/Updated:**
- `app/src/main/java/com/rife/androidtv/rife/SimpleSceneChangeDetector.kt` - Kept for reference but not used
- `app/src/main/java/com/rife/androidtv/rife/PlaceholderMotionQualityAnalyzer.kt` - Kept for reference but not used

**Files Unchanged:** Existing pipeline contracts, frame store, scheduler, backend, coordinator

## Completed Stage 5 Summary (Motion Quality + Interpolation Scheduler Integration)

### Integration Summary

**Updated Components:**

1. **InterpolationSchedulerImpl** (`InterpolationSchedulerImpl.kt`)
   - Now accepts `SceneChangeDetector`, `MotionQualityAnalyzer`, and `OutputQueue` as constructor dependencies
   - Implements deterministic decision policy:
     - **Scene change detected** → REPEAT_FRAME (SAFE mode, 0 intermediates)
     - **Motion quality UNKNOWN/warmup** (< 15 frames) → HOLD (SAFE, 0 intermediates)
     - **Motion quality UNKNOWN** (confidence < 0.3) → HOLD
     - **Motion quality UNRELIABLE** → BYPASS (SAFE, 0 intermediates)
     - **Motion quality LOW** → CONSERVATIVE interpolation (1 intermediate at t=0.5)
     - **Motion quality MEDIUM** → CONSERVATIVE interpolation (1 intermediate at t=0.5)
     - **Motion quality HIGH** → defaultMode interpolation (up to maxIntermediates)
   - **Backpressure**: Checks OutputQueue capacity (80% threshold), applies HOLD/BYPASS under pressure
   - **Timing**: Uses frame presentation timestamps only, no wall-clock
   - **Lifecycle**: `reset()` clears internal state and calls `reset()` on analysis components

2. **MotionQualityAnalyzerImpl** - Added `motionQualityClass` to returned `MotionQuality`
3. **SvpPipelineContracts** - Added `motionQualityClass` field to `MotionQuality` data class
4. **SvpPipelineCoordinator** - Now instantiates real `SceneChangeDetectorImpl`, `MotionQualityAnalyzerImpl`, and configures `InterpolationSchedulerImpl` with all dependencies

### Decision Policy Summary

| Condition | Decision | Mode | Intermediates |
|-----------|----------|------|---------------|
| Scene change | REPEAT_FRAME | SAFE | 0 |
| UNKNOWN quality / warmup (<15 frames) | HOLD | SAFE | 0 |
| UNKNOWN quality (confidence < 0.3) | HOLD | SAFE | 0 |
| UNRELIABLE | BYPASS | SAFE | 0 |
| LOW | INTERPOLATE* | CONSERVATIVE | 1 @ t=0.5 |
| MEDIUM | INTERPOLATE* | CONSERVATIVE | 1 @ t=0.5 |
| HIGH | INTERPOLATE* | defaultMode | up to maxIntermediates |

*Under backpressure (queue > 80%): HIGH→HOLD, MEDIUM→HOLD, LOW→BYPASS

### Files Modified

- `app/src/main/java/com/rife/androidtv/rife/InterpolationSchedulerImpl.kt` - Full scheduler policy implementation
- `app/src/main/java/com/rife/androidtv/rife/MotionQualityAnalyzerImpl.kt` - Added motionQualityClass to output
- `app/src/main/java/com/rife/androidtv/rife/SvpPipelineContracts.kt` - Added motionQualityClass field
- `app/src/main/java/com/rife/androidtv/rife/SvpPipelineCoordinator.kt` - Wire real implementations

### Non-Negotiable Constraints (Maintained)

1. No runtime glslang in production paths
2. No silent fallbacks — explicit errors only
3. No unbounded queues or frame copies
4. No GPU-name-based shader selection (capability-driven only)
5. FastDVDnet and RIFE lifecycles remain completely independent
6. Real backend transitions must recreate resources (not just flip flags)
7. All expensive operations must have measurable latency/memory budgets
8. Mali-G310 target: sustained playback without lmkd kills, memory < 1.2GB

## Completed Stage 6 Summary (Synthesis Backend + Real Backend Transitions)

### Integration Summary

**Updated Components:**

1. **Backend State Machine** (`SvpPipelineContracts.kt`)
   - New `BackendState` enum: UNINITIALIZED, INITIALIZING, READY, DRAINING, DESTROYING, FAILED
   - `BackendTransitionResult` for explicit transition tracking
   - Updated `SynthesisBackend` interface with `transitionTo()`, `getBackendState()`, `getActiveBackend()`

2. **RifeSynthesisBackend** (`RifeSynthesisBackend.kt`)
   - Full state machine implementation with real transitions
   - `transitionTo()` performs: DRAINING → DESTROYING (unloadRifeModel) → INITIALIZING → READY
   - Real resource cleanup via `NativeEngine.unloadRifeModel()` JNI call
   - Backpressure awareness (queue > 80% triggers HOLD/BYPASS)
   - Lifecycle: `reset()` on start/stop/seek/discontinuity/EOS/error
   - No fake transitions - explicit resource destruction and creation

3. **NativeEngine JNI** (`NativeEngine.kt`, `rife_jni.cpp`, `rife_engine.cpp/h`)
   - Added `unloadRifeModel()` JNI function
   - `RifeEngine::unloadModel()` properly destroys RIFE instance
   - JNI binding for `unloadRifeModel()`

4. **SvpPipelineCoordinator** - Wired to use real backend transition logic

### Backend State Machine

```
UNINITIALIZED → INITIALIZING → READY ↔ DRAINING → DESTROYING → INITIALIZING → READY
                    ↓                    ↓
                 FAILED ←───────────────┘
```

### Transition Sequence (Vulkan ↔ CPU)

1. **DRAINING** - Stop accepting new synthesis work
2. **DESTROYING** - Call `NativeEngine.unloadRifeModel()` → destroys RIFE instance
3. **INITIALIZING** - Call `initRife()` + `loadRifeModel()` with new backend
4. **READY** - Backend operational

### Failure Handling

- Transition failure → explicit `BackendTransitionResult` with error
- Automatic recovery attempt to previous backend
- No silent fallbacks or silent retries

### Files Modified

- `app/src/main/cpp/rife_engine.cpp` - Added `unloadModel()`
- `app/src/main/cpp/rife_engine.h` - Added `unloadModel()` declaration
- `app/src/main/cpp/rife_jni.cpp` - Added `unloadRifeModel` JNI binding
- `app/src/main/java/com/rife/androidtv/NativeEngine.kt` - Added `unloadRifeModel()` external
- `app/src/main/java/com/rife/androidtv/rife/SvpPipelineContracts.kt` - Added backend state machine
- `app/src/main/java/com/rife/androidtv/rife/RifeSynthesisBackend.kt` - Full state machine
- `app/src/main/java/com/rife/androidtv/rife/SvpPipelineCoordinator.kt` - Wired real implementations
- `app/src/main/cpp/rife_engine.cpp` - Added `unloadModel()` implementation
- `app/src/main/cpp/rife_engine.h` - Added `unloadModel()` declaration
- `app/src/main/cpp/rife_jni.cpp` - Added `unloadRifeModel` JNI binding
- `app/src/main/java/com/rife/androidtv/NativeEngine.kt` - Added `unloadRifeModel` external

### Non-Negotiable Constraints (Maintained)

1. No runtime glslang in production paths
2. No silent fallbacks — explicit errors only
3. No unbounded queues or frame copies
4. No GPU-name-based shader selection (capability-driven only)
5. FastDVDnet and RIFE lifecycles remain completely independent
6. Real backend transitions must recreate resources (not just flip flags)
7. All expensive operations must have measurable latency/memory budgets
8. Mali-G310 target: sustained playback without lmkd kills, memory < 1.2GB

## Next Stage: Scheduler Integration with Output Timing/Backpressure

### Immediate Goals

1. Connect `SvpPipelineCoordinator` to `RifeEngineController` frame flow
2. Implement real `RifeSynthesisBackend` synthesis path (connect to actual NativeEngine JNI)
3. Add pipeline statistics collection
4. Implement backend transition triggers from scheduler decisions

### Non-Negotiable Constraints (Maintained)

1. No runtime glslang in production paths
2. No silent fallbacks — explicit errors only
3. No unbounded queues or frame copies
4. No GPU-name-based shader selection (capability-driven only)
5. FastDVDnet and RIFE lifecycles remain completely independent
6. Real backend transitions must recreate resources (not just flip flags)
7. All expensive operations must have measurable latency/memory budgets
8. Mali-G310 target: sustained playback without lmkd kills, memory < 1.2GB

## Key References

- ncnn SPIR-V commit: `30cc1902b6eb83b2056cbc9fc413b3c747940bb2`
- Main repo Stage 1 commit: `319fddf`
- Main repo Stage 2 commit: `001cf30`
- Main repo Stage 3 commit: `810dc40`
- Main repo Stage 4 commit: `69ab50b`
- Main repo Stage 5 commit: `dc7cc2e`
- Main repo Stage 6 commit: `f1987ac`
- Main repo Stage 6 commit: `f1987ac`
- Historical context: `NEMOTRON_RIFE_LOG_CONTEXT.md`
- Architecture spec: `NEMOTRON_SVP_INSPIRED_ARCHITECTURE.md`

---

**Audit Status**: PASSED
- git diff --check: PASS
- No new threads: PASS
- No new queues beyond existing bounded queue: PASS
- No new dependencies: PASS
- No runtime glslang changes: PASS
- No ncnn changes: PASS
- No GPU-name-specific logic: PASS
- Normal playback path intact: PASS
- Bounded capacity verified: PASS (analysis history bounded to 30 frames, frame store max 3)
- Lifecycle paths verified: PASS (start/stop/seek/EOS reset analysis state)
- No duplicate frame ownership: PASS
- No retained Surface/MediaCodec buffers: PASS
- No full-resolution frame analysis: PASS (metadata-only)
- No optical flow/GPU compute: PASS
- Hysteresis/debouncing implemented: PASS
- Backpressure respected: PASS (queue > 80% triggers HOLD/BYPASS)
- No new threads: PASS
- Deterministic policy: PASS
- Real backend transitions: PASS (explicit destroy → create sequence)
- Resource cleanup verified: PASS (unloadRifeModel called on transition)
- No fake transitions: PASS (explicit state machine)