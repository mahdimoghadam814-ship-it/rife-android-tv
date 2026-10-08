cmake_minimum_required(VERSION 3.22.1)

project("rife_native")

# Gradle's "debug" variant -- the only variant CI builds -- passes
# -DCMAKE_BUILD_TYPE=Debug, and for Clang Debug is just "-g": there is no -O flag.
# Every target below was therefore compiling at -O0. Measured cost:
# the identical sources built with -O2 outside Gradle ran 14-26x faster on the same
# phone (sad16x16 was still an out-of-line call). Appended after project() so the NDK
# toolchain has already run and cannot clobber it, and as a base flag rather than a
# per-config override so it applies even if Gradle leaves CMAKE_BUILD_TYPE unset. -g from
# the Debug config still follows, so debuggable builds keep their symbols.
#
# This is not cosmetic for the SVP block-matching search: the whole ME/MC path is NEON
# intrinsics compiled here, and at -O0 it loses most of its throughput.
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -O2")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2")

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

# The SVP-only native library: the JNI bridge plus the CPU block-matching interpolator
# that implements the SVPlayer search. Neither depends on ncnn, Vulkan or a model file,
# so the library no longer carries a GPU runtime at all.
add_library(
    rife_native
    SHARED
    rife_jni.cpp
    memc_interpolator.cpp
)

target_include_directories(
    rife_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_BINARY_DIR}
)

target_link_libraries(
    rife_native
    PRIVATE
    ${LOG_LIB}
    ${ANDROID_LIB}
)
