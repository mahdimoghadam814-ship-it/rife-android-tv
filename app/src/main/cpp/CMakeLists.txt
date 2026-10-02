cmake_minimum_required(VERSION 3.22.1)

project("rife_native")

# Configure ncnn options
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_SYSTEM_GLSLANG OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS ON CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_RTTI OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_EXCEPTION OFF CACHE BOOL "" FORCE)

# Path to ncnn root from app/src/main/cpp
get_filename_component(NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ncnn" ABSOLUTE)

if (EXISTS "${NCNN_DIR}/CMakeLists.txt")
    # Check if ncnn already contains the precompiled SPIR-V registry changes.
    # The ncnn_interp_spirv.patch stores precompiled SPIR-V in the shader
    # registry, so src/gpu.cpp switches the registry entry fields to
    # "const uint32_t* spv_data; int spv_data_size;" (size in bytes).
    # Detect that marker so a second configure does not re-apply the patch.
    set(NCNN_ALREADY_PATCHED FALSE)
    if(EXISTS "${NCNN_DIR}/src/gpu.cpp")
        file(READ "${NCNN_DIR}/src/gpu.cpp" ncnn_gpu_content)
        if(ncnn_gpu_content MATCHES "spv_data_size; // in bytes")
            set(NCNN_ALREADY_PATCHED TRUE)
            message(STATUS "ncnn already contains precompiled SPIR-V registry changes. Skipping patch.")
        endif()
    endif()

    if(NOT NCNN_ALREADY_PATCHED)
        # Apply ncnn interp shader SPIR-V precompilation patch before building ncnn
        # Patch file is at repository root (4 levels up from app/src/main/cpp)
        get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../" ABSOLUTE)
        execute_process(
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${REPO_ROOT}/ncnn_interp_spirv.patch"
                "${CMAKE_CURRENT_BINARY_DIR}/ncnn_interp_spirv.patch"
            WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        )
        # First check if patch can be applied cleanly
        execute_process(
            COMMAND git apply --check "${CMAKE_CURRENT_BINARY_DIR}/ncnn_interp_spirv.patch"
            WORKING_DIRECTORY "${NCNN_DIR}"
            RESULT_VARIABLE PATCH_CHECK_RESULT
        )
        if(PATCH_CHECK_RESULT EQUAL 0)
            execute_process(
                COMMAND git apply "${CMAKE_CURRENT_BINARY_DIR}/ncnn_interp_spirv.patch"
                WORKING_DIRECTORY "${NCNN_DIR}"
                RESULT_VARIABLE PATCH_RESULT
            )
            if(NOT PATCH_RESULT EQUAL 0)
                message(FATAL_ERROR "Failed to apply ncnn interp SPIR-V patch. Build cannot continue without SPIR-V precompilation.")
            endif()
            message(STATUS "Applied ncnn interp SPIR-V patch successfully.")
        else()
            message(FATAL_ERROR "ncnn_interp_spirv.patch does not apply cleanly to the current ncnn revision. Build cannot continue without guaranteed SPIR-V precompilation. Ensure the ncnn submodule is at the pinned commit with a clean working tree.")
        endif()
    endif()
    
    add_subdirectory("${NCNN_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/ncnn")
else()
    message(FATAL_ERROR "ncnn source directory not found at ${NCNN_DIR}")
endif()

# Find Vulkan package/library from NDK
find_library(VULKAN_LIB vulkan)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

# ============================================================
# PREFLIGHT CHECK - Runs before shader generation to verify
# all required dependencies are available
# ============================================================
add_custom_target(rife-preflight
    COMMAND ${CMAKE_COMMAND}
        -DNCNN_DIR=${NCNN_DIR}
        -DGLSLANG_VALIDATOR=${GLSLANG_VALIDATOR}
        -DSHADER_DIR=${CMAKE_CURRENT_SOURCE_DIR}
        -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/preflight_check.cmake
    COMMENT "Running RIFE preflight checks..."
    VERBATIM
)

# Shader compilation macro for RIFE - generates both GLSL hex (for fallback) and SPIR-V (for precompiled)
# Usage: rife_add_shader(SHADER_SRC [REQUIRED])
#   SHADER_SRC: shader source file (e.g., rife_preproc.comp)
#   REQUIRED:   if TRUE (default), fail build if SPIR-V precompilation fails
#               if FALSE, generate zero-size fallback for optional shaders
macro(rife_add_shader SHADER_SRC)
    get_filename_component(SHADER_SRC_NAME_WE ${SHADER_SRC} NAME_WE)
    set(SHADER_COMP_HEADER ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_SRC_NAME_WE}.comp.hex.h)
    set(SHADER_SPV_HEADER ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_SRC_NAME_WE}.comp.spv.h)

    # Determine if this shader is required for production
    # warp_pack8 uses afpvec8 which is not available in current ncnn_glsl_ext
    set(SHADER_REQUIRED TRUE)
    if(SHADER_SRC_NAME_WE STREQUAL "warp_pack8")
        set(SHADER_REQUIRED FALSE)
    endif()

    # Generate GLSL hex header (fallback for runtime compilation - DEBUG only)
    add_custom_command(
        OUTPUT ${SHADER_COMP_HEADER}
        COMMAND ${CMAKE_COMMAND} -DSHADER_SRC=${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC} -DSHADER_COMP_HEADER=${SHADER_COMP_HEADER} -P "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rife-ncnn-vulkan/src/generate_shader_comp_header.cmake"
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC}
        COMMENT "Preprocessing shader source ${SHADER_SRC_NAME_WE}.comp"
        VERBATIM
    )
    set_source_files_properties(${SHADER_COMP_HEADER} PROPERTIES GENERATED TRUE)

    # Generate SPIR-V header (precompiled, avoids runtime glslang crash)
    add_custom_command(
        OUTPUT ${SHADER_SPV_HEADER}
        COMMAND ${CMAKE_COMMAND}
            -DSHADER_SRC=${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC}
            -DSHADER_SPV_HEADER=${SHADER_SPV_HEADER}
            -DSHADER_REQUIRED=${SHADER_REQUIRED}
            -DNCNN_DIR=${NCNN_DIR}
            -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/precompile_spirv.cmake"
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC}
        COMMENT "Precompiling shader ${SHADER_SRC_NAME_WE}.comp to SPIR-V"
        VERBATIM
    )
    set_source_files_properties(${SHADER_SPV_HEADER} PROPERTIES GENERATED TRUE)

    list(APPEND SHADER_SPV_HEX_FILES ${SHADER_COMP_HEADER})
    list(APPEND SHADER_SPV_FILES ${SHADER_SPV_HEADER})
endmacro()

# All RIFE custom shaders - REQUIRED for production Vulkan path
rife_add_shader(rife_preproc.comp)
rife_add_shader(rife_postproc.comp)
rife_add_shader(rife_preproc_tta.comp)
rife_add_shader(rife_postproc_tta.comp)
rife_add_shader(rife_flow_tta_avg.comp)
rife_add_shader(rife_v2_flow_tta_avg.comp)
rife_add_shader(rife_v4_flow_tta_avg.comp)
rife_add_shader(rife_flow_tta_temporal_avg.comp)
rife_add_shader(rife_v2_flow_tta_temporal_avg.comp)
rife_add_shader(rife_v4_flow_tta_temporal_avg.comp)
rife_add_shader(rife_out_tta_temporal_avg.comp)
rife_add_shader(rife_v4_timestep.comp)
rife_add_shader(rife_v4_timestep_tta.comp)
rife_add_shader(warp.comp)
rife_add_shader(warp_pack4.comp)
# warp_pack8 is OPTIONAL - uses afpvec8 not available in ncnn_glsl_ext
rife_add_shader(warp_pack8.comp)

message(STATUS "SHADER_SPV_FILES = ${SHADER_SPV_FILES}")

add_custom_target(generate-rife-spirv DEPENDS ${SHADER_SPV_HEX_FILES} ${SHADER_SPV_FILES} rife_spv_headers_file)

# Build-time SPIR-V header validation
# Runs after SPIR-V headers are generated to validate magic number, size, alignment
# Write the header list to a file for the validation script to read
set(VALIDATION_LIST_FILE "${CMAKE_CURRENT_BINARY_DIR}/rife_spv_headers.txt")
add_custom_command(
    OUTPUT "${VALIDATION_LIST_FILE}"
    COMMAND ${CMAKE_COMMAND} -E echo "${SHADER_SPV_FILES}" > "${VALIDATION_LIST_FILE}"
    DEPENDS ${SHADER_SPV_FILES}
    COMMENT "Writing SPIR-V header list for validation"
    VERBATIM
)
add_custom_target(rife_spv_headers_file DEPENDS "${VALIDATION_LIST_FILE}")

add_custom_target(validate-rife-spirv
    COMMAND ${CMAKE_COMMAND}
        -DVALIDATION_LIST_FILE="${VALIDATION_LIST_FILE}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/validate_spirv_headers.cmake"
    DEPENDS ${SHADER_SPV_FILES} rife_spv_headers_file
    COMMENT "Validating generated SPIR-V headers"
    WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
    VERBATIM
)

# Make validation run after generation but before native library build
# Preflight runs first, then generation, then validation
add_custom_target(rife-spirv-pipeline DEPENDS rife-preflight generate-rife-spirv validate-rife-spirv)


# Native library target
add_library(
    rife_native
    SHARED
    rife_jni.cpp
    vulkan_diagnostic.cpp
    rife_engine.cpp
    rife.cpp
    warp.cpp
    device_policy.cpp
)

add_dependencies(rife_native rife-spirv-pipeline)

target_include_directories(
    rife_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_BINARY_DIR}
    ${NCNN_DIR}/src
    ${NCNN_DIR}/build/src
)

target_link_libraries(
    rife_native
    PRIVATE
    ncnn
    ${VULKAN_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
)
# Build-time SPIR-V header validation
# Runs after SPIR-V headers are generated to validate magic number, size, alignment
# Write the header list to a file for the validation script to read
set(VALIDATION_LIST_FILE "${CMAKE_CURRENT_BINARY_DIR}/rife_spv_headers.txt")
add_custom_command(
    OUTPUT "${VALIDATION_LIST_FILE}"
    COMMAND ${CMAKE_COMMAND} -E echo "${SHADER_SPV_FILES}" > "${VALIDATION_LIST_FILE}"
    DEPENDS ${SHADER_SPV_FILES}
    COMMENT "Writing SPIR-V header list for validation"
    VERBATIM
)

# Make validation run after generation but before native library build
# Preflight runs first, then generation, then validation
