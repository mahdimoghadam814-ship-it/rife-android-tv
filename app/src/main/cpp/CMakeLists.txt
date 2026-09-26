cmake_minimum_required(VERSION 3.22.1)

project("rife_native")

# Configure ncnn options
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_SYSTEM_GLSLANG OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_RTTI OFF CACHE BOOL "" FORCE)
set(NCNN_DISABLE_EXCEPTION OFF CACHE BOOL "" FORCE)

# Path to ncnn root from app/src/main/cpp
get_filename_component(NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ncnn" ABSOLUTE)

if (EXISTS "${NCNN_DIR}/CMakeLists.txt")
    add_subdirectory("${NCNN_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/ncnn")
else()
    message(FATAL_ERROR "ncnn source directory not found at ${NCNN_DIR}")
endif()

# Find Vulkan package/library from NDK
find_library(VULKAN_LIB vulkan)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

# Shader compilation macro for RIFE
macro(rife_add_shader SHADER_SRC)
    get_filename_component(SHADER_SRC_NAME_WE ${SHADER_SRC} NAME_WE)
    set(SHADER_COMP_HEADER ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_SRC_NAME_WE}.comp.hex.h)

    add_custom_command(
        OUTPUT ${SHADER_COMP_HEADER}
        COMMAND ${CMAKE_COMMAND} -DSHADER_SRC=${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC} -DSHADER_COMP_HEADER=${SHADER_COMP_HEADER} -P "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rife-ncnn-vulkan/src/generate_shader_comp_header.cmake"
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${SHADER_SRC}
        COMMENT "Preprocessing shader source ${SHADER_SRC_NAME_WE}.comp"
        VERBATIM
    )
    set_source_files_properties(${SHADER_COMP_HEADER} PROPERTIES GENERATED TRUE)

    list(APPEND SHADER_SPV_HEX_FILES ${SHADER_COMP_HEADER})
endmacro()

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
rife_add_shader(warp_pack8.comp)

add_custom_target(generate-rife-spirv DEPENDS ${SHADER_SPV_HEX_FILES})

# Native library target
add_library(
    rife_native
    SHARED
    rife_jni.cpp
    vulkan_diagnostic.cpp
    rife_engine.cpp
    rife.cpp
    warp.cpp
    fastdvdnet.cpp
)

add_dependencies(rife_native generate-rife-spirv)

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
