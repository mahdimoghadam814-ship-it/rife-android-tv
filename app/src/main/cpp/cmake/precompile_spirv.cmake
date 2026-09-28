# CMake script to precompile GLSL shaders to SPIR-V using glslangValidator
# Usage: cmake -DSHADER_SRC=<source.comp> -DSHADER_SPV_HEADER=<output.spv.h> -DSHADER_REQUIRED=ON -P precompile_spirv.cmake
#
# SHADER_REQUIRED: If ON (default), fail the build if precompilation fails.
#                  If OFF, generate zero-size fallback header for optional shaders.

# Find glslangValidator
find_program(GLSLANG_VALIDATOR
    NAMES glslangValidator
    PATHS
        $ENV{ANDROID_NDK_HOME}/shader-tools
        $ENV{ANDROID_NDK_ROOT}/shader-tools
        /usr/bin
        /usr/local/bin
    DOC "glslangValidator executable"
)

get_filename_component(SHADER_SRC_NAME_WE ${SHADER_SRC} NAME_WE)

# Default to required if not specified
if(NOT DEFINED SHADER_REQUIRED)
    set(SHADER_REQUIRED ON)
endif()

# Determine if this is a known optional shader that cannot be precompiled
# warp_pack8 uses afpvec8 which is not defined in current ncnn_glsl_ext
set(SHADER_OPTIONAL OFF)
if(SHADER_SRC_NAME_WE STREQUAL "warp_pack8")
    set(SHADER_OPTIONAL ON)
    message(STATUS "Shader ${SHADER_SRC_NAME_WE} is marked as optional (afpvec8 not available)")
endif()

if(NOT GLSLANG_VALIDATOR)
    if(SHADER_REQUIRED AND NOT SHADER_OPTIONAL)
        message(FATAL_ERROR "glslangValidator not found but required for ${SHADER_SRC}. Cannot build production RIFE without precompiled SPIR-V.")
    else()
        message(WARNING "glslangValidator not found. SPIR-V precompilation skipped for ${SHADER_SRC}. Runtime compilation will be used (DEBUG ONLY).")
        # Create a fallback header that defines symbols with size == 0
        file(WRITE ${SHADER_SPV_HEADER}
            "// SPIR-V precompilation not available - glslangValidator not found\n"
            "// DEBUG ONLY: zero-size fallback will trigger runtime compilation\n"
            "static const uint32_t ${SHADER_SRC_NAME_WE}_spv_data[] = { 0 };\n"
            "static const size_t ${SHADER_SRC_NAME_WE}_spv_data_size = 0;\n"
        )
    endif()
else()
    message(STATUS "Found glslangValidator: ${GLSLANG_VALIDATOR}")

    # Read the GLSL source
    file(READ ${SHADER_SRC} shader_source)

    # Get ncnn GLSL extensions path - use passed parameter
    if(NOT DEFINED NCNN_DIR OR NOT EXISTS "${NCNN_DIR}")
        message(FATAL_ERROR "NCNN_DIR not defined or does not exist: ${NCNN_DIR}. Pass -DNCNN_DIR=<path> when invoking this script.")
    endif()
    set(NCNN_GLSL_EXT ${NCNN_DIR}/src/ncnn_glsl_ext.comp)
    message(STATUS "Using ncnn_glsl_ext.comp from: ${NCNN_GLSL_EXT}")

    # Build RIFE Vulkan macros as a single string (avoid CMake list semicolon joining)
    string(CONCAT RIFE_VULKAN_MACROS
        "#define NCNN_fp16_storage 1\n"
        "#define NCNN_int8_storage 1\n"
        "#define NCNN_fp16_packed 1\n"
        "#define NCNN_fp16_arithmetic 0\n"
        "#define ncnn_vendorID 0\n"
        "#define ncnn_storageBuffer16BitAccess 1\n"
        "#define ncnn_uniformAndStorageBuffer16BitAccess 1\n"
        "#define ncnn_shaderInt16 1\n"
        "#define ncnn_shaderInt64 1\n"
        "#define ncnn_enable_validation_layer 0\n"
    )

    # Find the #version line anywhere in the shader (not just at start)
    # Split shader into: preamble (comments before #version), #version line, and body
    string(REGEX MATCH "([^\n]*\n)*#version[^\n]*\n" version_prefix "${shader_source}")
    if(version_prefix)
        # Extract everything before and including #version line
        string(LENGTH "${version_prefix}" prefix_len)
        string(SUBSTRING "${shader_source}" ${prefix_len} -1 shader_body)
        set(version_line "${version_prefix}")
    else()
        # Fallback: assume #version 450 at start
        string(REGEX MATCH "^(#version[^\n]*\n)" version_line "${shader_source}")
        string(REGEX REPLACE "^#version[^\n]*\n" "" shader_body "${shader_source}")
    endif()

    # Read ncnn GLSL extensions and remove any #version line
    set(ncnn_glsl_ext_content "")
    if(EXISTS ${NCNN_GLSL_EXT})
        file(READ ${NCNN_GLSL_EXT} ncnn_glsl_ext_content)
        string(REGEX REPLACE "^#version [0-9]+.*\n" "" ncnn_glsl_ext_content "${ncnn_glsl_ext_content}")
        message(STATUS "Loaded ncnn_glsl_ext.comp from: ${NCNN_GLSL_EXT}")
    else()
        message(FATAL_ERROR "ncnn_glsl_ext.comp not found at ${NCNN_GLSL_EXT}. Required for shader precompilation. Ensure third_party/ncnn submodule is initialized.")
    endif()

    # CORRECT ORDER: preamble + #version + MACROS + ncnn_ext + shader_body
    # Macros MUST come before ncnn extensions because ncnn extensions use #if NCNN_xxx
    set(shader_source_with_macros "${version_line}${RIFE_VULKAN_MACROS}${ncnn_glsl_ext_content}${shader_body}")

    set(TEMP_GLSL ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_SRC_NAME_WE}_with_macros.comp)
    set(TEMP_SPV ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_SRC_NAME_WE}.spv)

    file(WRITE ${TEMP_GLSL} "${shader_source_with_macros}")

    # Compile to SPIR-V
    execute_process(
        COMMAND ${GLSLANG_VALIDATOR}
            -V
            --target-env vulkan1.0
            --entry-point main
            -o ${TEMP_SPV}
            ${TEMP_GLSL}
        RESULT_VARIABLE result
        OUTPUT_VARIABLE output
        ERROR_VARIABLE error
    )

    if(result EQUAL 0)
        message(STATUS "Successfully compiled ${SHADER_SRC} to SPIR-V")

        # Read the SPIR-V binary as hex bytes
        file(READ ${TEMP_SPV} spv_data_hex HEX)

        # Validate SPIR-V magic number (first 4 bytes = 0x07230203 in little-endian = "SpV\0")
        string(SUBSTRING "${spv_data_hex}" 0 8 magic_hex)
        # magic_hex is 8 hex chars = 4 bytes in little-endian
        # SPIR-V magic is 0x07230203 -> bytes 03 02 23 07 in file (little-endian)
        if(NOT magic_hex STREQUAL "03022307")
            message(WARNING "SPIR-V magic number mismatch for ${SHADER_SRC}: got ${magic_hex}, expected 03022307")
            if(SHADER_REQUIRED AND NOT SHADER_OPTIONAL)
                message(FATAL_ERROR "Invalid SPIR-V magic number for required shader ${SHADER_SRC}")
            endif()
        endif()

        # Validate word alignment (hex length must be multiple of 8 = 4 bytes per word)
        string(LENGTH "${spv_data_hex}" hex_len)
        math(EXPR mod "${hex_len} % 8")
        if(NOT mod EQUAL 0)
            message(WARNING "SPIR-V data not word-aligned for ${SHADER_SRC}: ${hex_len} hex chars")
            if(SHADER_REQUIRED AND NOT SHADER_OPTIONAL)
                message(FATAL_ERROR "SPIR-V data not word-aligned for required shader ${SHADER_SRC}")
            endif()
        endif()

        # Convert hex bytes to uint32_t words (little-endian: 4 bytes per word)
        # spv_data_hex is a string of hex byte pairs: "0102030405060708..."
        # We need to group them into 32-bit words: 0x04030201, 0x08070605, ...
        math(EXPR word_count "${hex_len} / 8")

        set(spv_words "")
        # CMake RANGE is inclusive, so iterate 0 to word_count-1
        math(EXPR last_index "${word_count} - 1")
        foreach(i RANGE 0 ${last_index})
            math(EXPR byte_offset "${i} * 8")
            string(SUBSTRING "${spv_data_hex}" ${byte_offset} 8 word_hex)
            # word_hex is 8 hex chars = 4 bytes (e.g., "01020304")
            # Convert to little-endian uint32_t: byte3 byte2 byte1 byte0
            string(SUBSTRING "${word_hex}" 6 2 byte3)
            string(SUBSTRING "${word_hex}" 4 2 byte2)
            string(SUBSTRING "${word_hex}" 2 2 byte1)
            string(SUBSTRING "${word_hex}" 0 2 byte0)
            string(APPEND spv_words "0x${byte3}${byte2}${byte1}${byte0},")
        endforeach()

        # Remove trailing comma
        string(LENGTH "${spv_words}" words_len)
        math(EXPR words_len "${words_len} - 1")
        string(SUBSTRING "${spv_words}" 0 ${words_len} spv_words)

        # Calculate byte size
        math(EXPR spv_data_size "${word_count} * 4")

        # Write the header file with uint32_t array
        file(WRITE ${SHADER_SPV_HEADER}
            "static const uint32_t ${SHADER_SRC_NAME_WE}_spv_data[] = {${spv_words}};\n"
            "static const size_t ${SHADER_SRC_NAME_WE}_spv_data_size = ${spv_data_size};\n"
        )

        message(STATUS "Generated ${SHADER_SPV_HEADER} with ${word_count} uint32_t words")
    else()
        message(WARNING "Failed to compile ${SHADER_SRC} to SPIR-V: ${error}")
        message(WARNING "Output: ${output}")
        if(SHADER_REQUIRED AND NOT SHADER_OPTIONAL)
            message(FATAL_ERROR "Required shader ${SHADER_SRC} failed SPIR-V precompilation. Build aborted.")
        else()
            # Create a fallback header with size == 0 for optional shaders
            file(WRITE ${SHADER_SPV_HEADER}
                "// SPIR-V precompilation failed for optional shader\n"
                "// DEBUG ONLY: zero-size fallback will trigger runtime compilation\n"
                "static const uint32_t ${SHADER_SRC_NAME_WE}_spv_data[] = { 0 };\n"
                "static const size_t ${SHADER_SRC_NAME_WE}_spv_data_size = 0;\n"
            )
        endif()
    endif()

    # Clean up temp files
    file(REMOVE ${TEMP_GLSL} ${TEMP_SPV})
endif()