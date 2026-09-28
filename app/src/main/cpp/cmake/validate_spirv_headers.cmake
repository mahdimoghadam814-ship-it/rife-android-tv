# CMake script to validate generated SPIR-V headers at build time
# Usage: cmake -DSHADER_SPV_HEADERS="header1.h;header2.h;..." -P validate_spirv_headers.cmake
#
# Validates:
# 1. Header file exists
# 2. Contains valid uint32_t array declaration
# 3. SPIR-V magic number (0x07230203) is present as first word
# 4. Array size is consistent with declared size
# 5. Word alignment (size is multiple of 4 bytes)

if(NOT DEFINED SHADER_SPV_HEADERS)
    message(FATAL_ERROR "SHADER_SPV_HEADERS not defined. Provide list of generated .spv.h files to validate.")
endif()

message(STATUS "Validating ${SHADER_SPV_HEADERS} SPIR-V headers...")

set(VALIDATION_FAILED FALSE)

foreach(SPV_HEADER ${SHADER_SPV_HEADERS})
    if(NOT EXISTS "${SPV_HEADER}")
        message(WARNING "SPIR-V header not found: ${SPV_HEADER}")
        set(VALIDATION_FAILED TRUE)
        continue()
    endif()

    file(READ "${SPV_HEADER}" header_content)

    # Extract shader name from filename
    get_filename_component(SHADER_NAME "${SPV_HEADER}" NAME_WE)
    string(REPLACE ".comp" "" SHADER_NAME "${SHADER_NAME}")

    # Check for required array declaration pattern
    string(REGEX MATCH "static const uint32_t ${SHADER_NAME}_spv_data\\[\\] = \\{([^}]*)\\}" array_match "${header_content}")
    if(NOT array_match)
        message(WARNING "SPIR-V header ${SPV_HEADER}: missing or malformed uint32_t array declaration")
        set(VALIDATION_FAILED TRUE)
        continue()
    endif()

    # Extract the array content
    string(REGEX REPLACE "static const uint32_t ${SHADER_NAME}_spv_data\\[\\] = \\{([^}]*)\\}" "\\1" array_content "${array_match}")

    # Count elements (comma-separated hex values)
    string(REPLACE " " "" array_content "${array_content}")
    string(REPLACE "\n" "" array_content "${array_content}")
    string(REPLACE "\t" "" array_content "${array_content}")
    string(REPLACE "0x" ";" array_content "${array_content}")
    string(LENGTH "${array_content}" content_len)
    # Count semicolons (each element starts with 0x)
    string(REGEX MATCHALL "0x[0-9a-fA-F]+" elements "${header_content}")
    # Use CMake list to count
    separate_arguments(elements_list UNIX_COMMAND "${elements}")
    list(LENGTH elements_list word_count)

    if(word_count EQUAL 0)
        # Check if it's a zero-size fallback
        string(REGEX MATCH "spv_data_size = 0" is_fallback "${header_content}")
        if(is_fallback)
            message(STATUS "SPIR-V header ${SPV_HEADER}: zero-size fallback (optional shader)")
            continue()
        else()
            message(WARNING "SPIR-V header ${SPV_HEADER}: empty array but not marked as fallback")
            set(VALIDATION_FAILED TRUE)
            continue()
        endif()
    endif()

    # Validate SPIR-V magic number (first word should be 0x07230203)
    list(GET elements_list 0 first_word)
    if(NOT first_word STREQUAL "0x07230203")
        message(WARNING "SPIR-V header ${SPV_HEADER}: invalid magic number ${first_word}, expected 0x07230203")
        set(VALIDATION_FAILED TRUE)
        continue()
    endif()

    # Validate word alignment (already enforced by uint32_t array)
    # Validate declared size matches array size
    string(REGEX MATCH "spv_data_size = [0-9]+" size_match "${header_content}")
    if(size_match)
        string(REGEX REPLACE "spv_data_size = ([0-9]+)" "\\1" declared_size "${size_match}")
        math(EXPR expected_size "${word_count} * 4")
        if(NOT declared_size EQUAL expected_size)
            message(WARNING "SPIR-V header ${SPV_HEADER}: size mismatch declared=${declared_size} expected=${expected_size}")
            set(VALIDATION_FAILED TRUE)
            continue()
        endif()
    else()
        message(WARNING "SPIR-V header ${SPV_HEADER}: missing spv_data_size declaration")
        set(VALIDATION_FAILED TRUE)
        continue()
    endif()

    message(STATUS "SPIR-V header ${SPV_HEADER}: VALID (${word_count} words, ${declared_size} bytes)")

endforeach()

if(VALIDATION_FAILED)
    message(FATAL_ERROR "One or more SPIR-V headers failed validation. Build aborted.")
else()
    message(STATUS "All SPIR-V headers validated successfully.")
endif()