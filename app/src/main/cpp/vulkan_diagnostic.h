#ifndef VULKAN_DIAGNOSTIC_H
#define VULKAN_DIAGNOSTIC_H

#include <string>

struct DiagnosticResult {
    bool vulkan_supported;
    std::string vulkan_api_version;
    std::string gpu_name;
    uint32_t vendor_id;
    uint32_t device_id;
    std::string driver_info;
    std::string relevant_features;
    std::string ncnn_version;
    bool ncnn_vulkan_op_success;
    std::string ncnn_op_details;
    std::string error_message;
};

DiagnosticResult run_vulkan_diagnostics();

#endif // VULKAN_DIAGNOSTIC_H
