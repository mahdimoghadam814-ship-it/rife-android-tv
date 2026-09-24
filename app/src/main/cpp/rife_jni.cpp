#include <jni.h>
#include <string>
#include "vulkan_diagnostic.h"

extern "C" JNIEXPORT jobject JNICALL
Java_com_rife_androidtv_NativeEngine_runDiagnostics(JNIEnv* env, jclass clazz) {
    DiagnosticResult res = run_vulkan_diagnostics();

    jclass resultClass = env->FindClass("com/rife/androidtv/NativeDiagnosticResult");
    jmethodID constructor = env->GetMethodID(
        resultClass,
        "<init>",
        "(ZLjava/lang/String;Ljava/lang/String;IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;)V"
    );

    jstring vulkanApiVersion = env->NewStringUTF(res.vulkan_api_version.c_str());
    jstring gpuName = env->NewStringUTF(res.gpu_name.c_str());
    jstring driverInfo = env->NewStringUTF(res.driver_info.c_str());
    jstring relevantFeatures = env->NewStringUTF(res.relevant_features.c_str());
    jstring ncnnVersion = env->NewStringUTF(res.ncnn_version.c_str());
    jstring ncnnOpDetails = env->NewStringUTF(res.ncnn_op_details.c_str());
    jstring errorMessage = env->NewStringUTF(res.error_message.c_str());

    jobject objectResult = env->NewObject(
        resultClass,
        constructor,
        res.vulkan_supported,
        vulkanApiVersion,
        gpuName,
        (jint)res.vendor_id,
        (jint)res.device_id,
        driverInfo,
        relevantFeatures,
        ncnnVersion,
        res.ncnn_vulkan_op_success,
        ncnnOpDetails,
        errorMessage
    );

    return objectResult;
}
