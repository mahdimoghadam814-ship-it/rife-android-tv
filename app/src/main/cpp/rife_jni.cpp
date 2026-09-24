#include <jni.h>
#include <string>
#include <android/asset_manager_jni.h>
#include "vulkan_diagnostic.h"
#include "rife_engine.h"

static RifeEngine g_rife_engine;

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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rife_androidtv_NativeEngine_initRife(JNIEnv* env, jclass clazz, jint gpuId) {
    return g_rife_engine.init(gpuId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rife_androidtv_NativeEngine_loadRifeModel(
    JNIEnv* env, jclass clazz,
    jobject assetManager, jstring modelDir, jboolean isV2, jboolean isV4
) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    const char* dirStr = env->GetStringUTFChars(modelDir, nullptr);
    bool res = g_rife_engine.loadModelFromAssets(mgr, dirStr, isV2, isV4);
    env->ReleaseStringUTFChars(modelDir, dirStr);
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rife_androidtv_NativeEngine_interpolateFrameBuffers(
    JNIEnv* env, jclass clazz,
    jobject in0Buffer, jobject in1Buffer,
    jint srcWidth, jint srcHeight,
    jint targetWidth, jint targetHeight,
    jfloat timestep,
    jobject outBuffer
) {
    uint8_t* in0Ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(in0Buffer));
    uint8_t* in1Ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(in1Buffer));
    uint8_t* outPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(outBuffer));

    if (!in0Ptr || !in1Ptr || !outPtr) {
        return false;
    }

    return g_rife_engine.processFrameBuffer(
        in0Ptr, in1Ptr,
        srcWidth, srcHeight,
        targetWidth, targetHeight,
        timestep,
        outPtr
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rife_androidtv_NativeEngine_runRifeTest(
    JNIEnv* env, jclass clazz, jint width, jint height
) {
    return g_rife_engine.interpolateTest(width, height);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_rife_androidtv_NativeEngine_getRifeStatus(JNIEnv* env, jclass clazz) {
    RifeEngineResult res = g_rife_engine.getStatus();

    jclass resultClass = env->FindClass("com/rife/androidtv/RifeDiagnosticResult");
    jmethodID constructor = env->GetMethodID(
        resultClass,
        "<init>",
        "(ZZLjava/lang/String;Ljava/lang/String;ZJLjava/lang/String;Ljava/lang/String;)V"
    );

    jstring gpuName = env->NewStringUTF(res.gpu_name.c_str());
    jstring vulkanApiVersion = env->NewStringUTF(res.vulkan_api_version.c_str());
    jstring lastError = env->NewStringUTF(res.last_error.c_str());
    jstring opDetails = env->NewStringUTF(res.op_details.c_str());

    return env->NewObject(
        resultClass,
        constructor,
        res.success,
        res.vulkan_available,
        gpuName,
        vulkanApiVersion,
        res.model_loaded,
        (jlong)res.last_inference_time_ms,
        lastError,
        opDetails
    );
}
