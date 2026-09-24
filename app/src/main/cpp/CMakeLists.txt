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

# Native library target
add_library(
    rife_native
    SHARED
    rife_jni.cpp
    vulkan_diagnostic.cpp
)

target_include_directories(
    rife_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
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
