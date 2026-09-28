# CMake script to precompile GLSL shaders to SPIR-V using glslangValidator
# Usage: cmake -DSHADER_SRC=<source.comp> -DSHADER_SPV_HEADER=<output.spv.h> -P precompile_spirv.cmake

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

if(NOT GLSLANG_VALIDATOR)
    message(WARNING "glslangValidator not found. SPIR-V precompilation skipped. Runtime compilation will be used.")
    # Create a dummy header that indicates SPIR-V is not available
    file(WRITE ${SHADER_SPV_HEADER} "// SPIR-V precompilation not available - glslangValidator not found\n")
else()
    message(STATUS "Found glslangValidator: ${GLSLANG_VALIDATOR}")

    # Read the GLSL source
    file(READ ${SHADER_SRC} shader_source)

    # Create a temporary file with the shader source plus standard macros
    # RIFE and warp shaders use these macros:
    # - NCNN_fp16_storage (enabled for Vulkan)
    # - NCNN_int8_storage (enabled for Vulkan)
    # - NCNN_fp16_packed (enabled for Vulkan)
    # - NCNN_fp16_arithmetic (disabled for RIFE)
    # We define them based on the baseline RIFE Vulkan configuration
    string(REPLACE "#version 450" "#version 450\n#define NCNN_fp16_storage 1\n#define NCNN_int8_storage 1\n#define NCNN_fp16_packed 1\n#define NCNN_fp16_arithmetic 0" shader_source_with_macros "${shader_source}")

    get_filename_component(SHADER_SRC_NAME_WE ${SHADER_SRC} NAME_WE)
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

        # Read the SPIR-V binary and convert to C array
        file(READ ${TEMP_SPV} spv_data HEX)
        # Convert hex string to 0xXX, format
        string(REGEX REPLACE "([0-9a-fA-F]{2})" "0x\\1," spv_data_hex "${spv_data}")
        string(FIND "${spv_data_hex}" "," last_comma REVERSE)
        if(last_comma GREATER -1)
            string(SUBSTRING "${spv_data_hex}" 0 ${last_comma} spv_data_hex)
        endif()

        # Write the header file with uint32_t array
        file(WRITE ${SHADER_SPV_HEADER}
            "static const uint32_t ${SHADER_SRC_NAME_WE}_spv_data[] = {${spv_data_hex}};\n"
            "static const size_t ${SHADER_SRC_NAME_WE}_spv_data_size = sizeof(${SHADER_SRC_NAME_WE}_spv_data);\n"
        )

        message(STATUS "Generated ${SHADER_SPV_HEADER}")
    else()
        message(WARNING "Failed to compile ${SHADER_SRC} to SPIR-V: ${error}")
        message(WARNING "Output: ${output}")
        # Create a dummy header
        file(WRITE ${SHADER_SPV_HEADER} "// SPIR-V precompilation failed\n")
    endif()

    # Clean up temp files
    file(REMOVE ${TEMP_GLSL} ${TEMP_SPV})
endif()