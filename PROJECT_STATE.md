# PROJECT_STATE.md

## Current Architecture Status

**Completed Stage**: Variant-Aware SPIR-V Precompilation for RIFE v2.4 Vulkan Path

**ncnn Commit**: `30cc1902b6eb83b2056cbc9fc413b3c747940bb2`

**Main Repository Target Commit**: RIFE SPIR-V precompilation + Mali-G310 blacklist removal

## Completed SPIR-V Stage Summary

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

7. **Mali-G310 Blacklist Removed**: `rife_engine.cpp` no longer blocks Mali-G310; capability-driven selection now works

## Next Stage: SVP-Inspired Architecture Skeleton

### Immediate Goals
- Implement staged pipeline: `Frame Ingest → TemporalFrameStore → Scene/Motion Analysis → InterpolationScheduler → SynthesisBackend → OutputQueue`
- Separate Analysis (scene/motion/quality) from Synthesis (RIFE as one backend)
- Deadline-driven scheduler with adaptive cadence (SAFE/CONSERVATIVE/BALANCED/AGGRESSIVE)
- Bounded queues with backpressure (drop/cancel obsolete jobs)
- Policy-driven backend/resolution/memory selection

### Non-Negotiable Constraints
1. No runtime glslang in production paths
2. No silent fallbacks — explicit errors only
3. No unbounded queues or frame copies
4. No GPU-name-based shader selection (capability-driven only)
5. FastDVDnet and RIFE lifecycles remain completely independent
6. Real backend transitions must recreate resources (not just flip flags)
6. All expensive operations must have measurable latency/memory budgets
7. Mali-G310 target: sustained playback without lmkd kills, memory < 1.2GB

## Key References
- ncnn SPIR-V commit: `30cc1902b6eb83b2056cbc9fc413b3c747940bb2`
- Historical context: `NEMOTRON_RIFE_LOG_CONTEXT.md`
- Architecture spec: `NEMOTRON_SVP_INSPIRED_ARCHITECTURE.md`