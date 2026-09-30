# CMake script for preflight checks before RIFE shader precompilation
# Usage: cmake -DNCNN_DIR=<path> -DGLSLANG_VALIDATOR=<path> -DSHADER_DIR=<path> -P preflight_check.cmake

message(STATUS "=== RIFE Preflight Checks ===")

# Check 1: ncnn directory exists
if(NOT DEFINED NCNN_DIR OR NOT EXISTS "${NCNN_DIR}")
    message(FATAL_ERROR "NCNN_DIR not defined or does not exist: ${NCNN_DIR}")
endif()
message(STATUS "PASS: NCNN_DIR exists: ${NCNN_DIR}")

# Check 2: ncnn CMakeLists.txt exists
if(NOT EXISTS "${NCNN_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "ncnn CMakeLists.txt not found at ${NCNN_DIR}/CMakeLists.txt")
endif()
message(STATUS "PASS: ncnn CMakeLists.txt found")

# Check 3: ncnn_glsl_ext.comp exists
set(NCNN_GLSL_EXT "${NCNN_DIR}/src/ncnn_glsl_ext.comp")
if(NOT EXISTS "${NCNN_GLSL_EXT}")
    message(FATAL_ERROR "ncnn_glsl_ext.comp not found at ${NCNN_GLSL_EXT}. Ensure third_party/ncnn submodule is initialized.")
endif()
message(STATUS "PASS: ncnn_glsl_ext.comp found at ${NCNN_GLSL_EXT}")

# Check 4: Verify ncnn_glsl_ext.comp has required symbols
file(READ "${NCNN_GLSL_EXT}" ncnn_ext_content)
string(FIND "${ncnn_ext_content}" "sfp" has_sfp)
string(FIND "${ncnn_ext_content}" "afp" has_afp)
string(FIND "${ncnn_ext_content}" "buffer_ld1" has_buffer_ld1)
string(FIND "${ncnn_ext_content}" "buffer_st1" has_buffer_st1)
string(FIND "${ncnn_ext_content}" "ncnn_glsl_version" has_version)
if(has_sfp EQUAL -1 OR has_afp EQUAL -1 OR has_buffer_ld1 EQUAL -1 OR has_buffer_st1 EQUAL -1 OR has_version EQUAL -1)
    message(FATAL_ERROR "ncnn_glsl_ext.comp is missing required symbols (sfp, afp, buffer_ld1, buffer_st1, ncnn_glsl_version)")
endif()
message(STATUS "PASS: ncnn_glsl_ext.comp contains required symbols")

# Check 5: glslangValidator availability (non-fatal - precompile_spirv.cmake handles fallback)
if(NOT DEFINED GLSLANG_VALIDATOR OR NOT EXISTS "${GLSLANG_VALIDATOR}")
    # Try to find it
    find_program(GLSLANG_VALIDATOR_FIND
        NAMES glslangValidator
        PATHS
            $ENV{ANDROID_NDK_HOME}/shader-tools
            $ENV{ANDROID_NDK_ROOT}/shader-tools
            /usr/bin
            /usr/local/bin
    )
    if(GLSLANG_VALIDATOR_FIND)
        set(GLSLANG_VALIDATOR "${GLSLANG_VALIDATOR_FIND}")
        message(STATUS "PASS: glslangValidator found at ${GLSLANG_VALIDATOR}")
    else()
        message(WARNING "glslangValidator not found in PATH. Precompilation will use fallback headers (size=0). Ensure CI has glslang-tools for production SPIR-V.")
        set(GLSLANG_VALIDATOR "NOT_FOUND")
    endif()
else()
    message(STATUS "PASS: glslangValidator found at ${GLSLANG_VALIDATOR}")
endif()

# Check 6: Verify glslangValidator version
execute_process(
    COMMAND ${GLSLANG_VALIDATOR} --version
    OUTPUT_VARIABLE glslang_version
    ERROR_VARIABLE glslang_error
    RESULT_VARIABLE glslang_result
)
if(glslang_result EQUAL 0)
    string(REGEX MATCH "Glslang Version: [0-9:]+" version_line "${glslang_version}")
    message(STATUS "PASS: glslangValidator version: ${version_line}")
else()
    message(WARNING "Could not determine glslangValidator version: ${glslang_error}")
endif()

# Check 7: List all required shader sources
# SHADER_DIR should be passed as -DSHADER_DIR=<path>
if(NOT DEFINED SHADER_DIR OR NOT EXISTS "${SHADER_DIR}")
    message(FATAL_ERROR "SHADER_DIR not defined or does not exist: ${SHADER_DIR}")
endif()

set(REQUIRED_SHADERS
    rife_preproc.comp
    rife_postproc.comp
    rife_preproc_tta.comp
    rife_postproc_tta.comp
    rife_flow_tta_avg.comp
    rife_v2_flow_tta_avg.comp
    rife_v4_flow_tta_avg.comp
    rife_flow_tta_temporal_avg.comp
    rife_v2_flow_tta_temporal_avg.comp
    rife_v4_flow_tta_temporal_avg.comp
    rife_out_tta_temporal_avg.comp
    rife_v4_timestep.comp
    rife_v4_timestep_tta.comp
    warp.comp
    warp_pack4.comp
)

set(OPTIONAL_SHADERS
    warp_pack8.comp
)

foreach(SHADER ${REQUIRED_SHADERS})
    set(SHADER_PATH "${SHADER_DIR}/${SHADER}")
    if(NOT EXISTS "${SHADER_PATH}")
        message(FATAL_ERROR "Required shader source not found: ${SHADER_PATH}")
    endif()
endforeach()
message(STATUS "PASS: All required shader sources found in ${SHADER_DIR}")

foreach(SHADER ${OPTIONAL_SHADERS})
    set(SHADER_PATH "${SHADER_DIR}/${SHADER}")
    if(EXISTS "${SHADER_PATH}")
        message(STATUS "INFO: Optional shader found: ${SHADER}")
    else()
        message(WARNING "Optional shader not found (will use fallback): ${SHADER}")
    endif()
endforeach()

# Check 8: rife-ncnn-vulkan generate_shader_comp_header.cmake exists
# This is relative to the shader directory (app/src/main/cpp)
set(GENERATE_HEADER_SCRIPT "${SHADER_DIR}/../../../../third_party/rife-ncnn-vulkan/src/generate_shader_comp_header.cmake")
if(NOT EXISTS "${GENERATE_HEADER_SCRIPT}")
    message(FATAL_ERROR "generate_shader_comp_header.cmake not found at ${GENERATE_HEADER_SCRIPT}. Ensure third_party/rife-ncnn-vulkan submodule is initialized.")
endif()
message(STATUS "PASS: generate_shader_comp_header.cmake found")

message(STATUS "=== All Preflight Checks Passed ===")