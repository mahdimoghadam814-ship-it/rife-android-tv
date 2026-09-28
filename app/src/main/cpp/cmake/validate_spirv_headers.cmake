# CMake script to validate generated SPIR-V headers at build time
# Usage: cmake -DVALIDATION_LIST_FILE=<path> -P validate_spirv_headers.cmake
#
# Validates:
# 1. Header file exists
# 2. Contains valid uint32_t array declaration
# 3. SPIR-V magic number (0x07230203) is present as first word
# 4. Array size is consistent with declared size
# 5. Word alignment (size is multiple of 4 bytes)

if(NOT DEFINED VALIDATION_LIST_FILE)
    message(FATAL_ERROR "VALIDATION_LIST_FILE not defined. Provide path to file containing list of generated .spv.h files.")
endif()

# Also check in CMAKE_CURRENT_BINARY_DIR if defined
if(NOT EXISTS "${VALIDATION_LIST_FILE}" AND DEFINED CMAKE_CURRENT_BINARY_DIR)
    set(ALT_VALIDATION_LIST_FILE "${CMAKE_CURRENT_BINARY_DIR}/rife_spv_headers.txt")
    if(EXISTS "${ALT_VALIDATION_LIST_FILE}")
        set(VALIDATION_LIST_FILE "${ALT_VALIDATION_LIST_FILE}")
    endif()
endif()

if(NOT EXISTS "${VALIDATION_LIST_FILE}")
    message(FATAL_ERROR "VALIDATION_LIST_FILE not found: ${VALIDATION_LIST_FILE}")
endif()

file(READ "${VALIDATION_LIST_FILE}" shader_list_content)

message(STATUS "Validating SPIR-V headers from ${VALIDATION_LIST_FILE}...")

set(VALIDATION_FAILED FALSE)

# Parse the semicolon-separated list into a CMake list
string(REPLACE ";" ";" shader_list "${shader_list_content}")

# Verify each header
foreach(shader_path ${shader_list})
    # Strip whitespace from path
    string(STRIP "${shader_path}" shader_path)
    if(shader_path STREQUAL "")
        continue()
    endif()
    set(SPV_HEADER "${shader_path}")

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

    # Extract the array content between { and }
    string(REGEX REPLACE "static const uint32_t ${SHADER_NAME}_spv_data\\[\\] = \\{([^}]*)\\}" "\\1" array_content "${array_match}")

    # Clean up whitespace
    string(REPLACE " " "" array_content "${array_content}")
    string(REPLACE "\n" "" array_content "${array_content}")
    string(REPLACE "\t" "" array_content "${array_content}")

    # Split by comma to get individual hex words
    string(REPLACE "," ";" array_content "${array_content}")

    # Remove empty elements and count words
    set(word_count 0)
    foreach(word ${array_content})
        if(NOT word STREQUAL "")
            math(EXPR word_count "${word_count} + 1")
            if(word_count EQUAL 1)
                set(first_word "${word}")
            endif()
        endif()
    endforeach()

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
    elseif(word_count EQUAL 1 AND first_word STREQUAL "0")
        # Check for single-word fallback (array = { 0 } with size = 0)
        string(REGEX MATCH "spv_data_size = 0" is_fallback "${header_content}")
        if(is_fallback)
            message(STATUS "SPIR-V header ${SPV_HEADER}: zero-size fallback (optional shader)")
            continue()
        else()
            message(WARNING "SPIR-V header ${SPV_HEADER}: single-word array with value 0 but not marked as fallback")
            set(VALIDATION_FAILED TRUE)
            continue()
        endif()
    endif()

    # Validate SPIR-V magic number (first word should be 0x07230203)
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