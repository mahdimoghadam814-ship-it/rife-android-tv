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
    if (resultClass == nullptr) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(
        resultClass,
        "<init>",
        "(ZLjava/lang/String;Ljava/lang/String;IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;)V"
    );
    if (constructor == nullptr) {
        return nullptr;
    }

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

    env->DeleteLocalRef(resultClass);
    return objectResult;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rife_androidtv_NativeEngine_initRife(JNIEnv* env, jclass clazz, jint gpuId) {
    return g_rife_engine.init(gpuId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rife_androidtv_NativeEngine_loadRifeModel(
    JNIEnv* env, jclass clazz,
    jobject assetManager, jstring baseCacheDir, jstring modelDir, jboolean isV2, jboolean isV4
) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    const char* cacheStr = env->GetStringUTFChars(baseCacheDir, nullptr);
    const char* dirStr = env->GetStringUTFChars(modelDir, nullptr);
    bool res = g_rife_engine.loadModelFromAssets(mgr, cacheStr, dirStr, isV2, isV4);
    env->ReleaseStringUTFChars(baseCacheDir, cacheStr);
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
    if (resultClass == nullptr) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(
        resultClass,
        "<init>",
        "(ZZLjava/lang/String;Ljava/lang/String;ZJLjava/lang/String;Ljava/lang/String;Lcom/rife/androidtv/VulkanCapabilities;Lcom/rife/androidtv/DeviceProfile;Ljava/lang/String;)V"
    );
    if (constructor == nullptr) {
        return nullptr;
    }

    // Create VulkanCapabilities object
    jclass capsClass = env->FindClass("com/rife/androidtv/VulkanCapabilities");
    if (capsClass == nullptr) {
        return nullptr;
    }
    jmethodID capsConstructor = env->GetMethodID(
        capsClass,
        "<init>",
        "(ZZZZZZZZZZZZZZ)V"
    );
    if (capsConstructor == nullptr) {
        return nullptr;
    }
    jobject capsObj = env->NewObject(
        capsClass,
        capsConstructor,
        res.vulkan_caps.fp16_storage,
        res.vulkan_caps.fp16_packed,
        res.vulkan_caps.fp16_arithmetic,
        res.vulkan_caps.int8_storage,
        res.vulkan_caps.int8_packed,
        res.vulkan_caps.int8_arithmetic,
        res.vulkan_caps.int16_storage,
        res.vulkan_caps.int16_arithmetic,
        res.vulkan_caps.shader_int16,
        res.vulkan_caps.shader_int64,
        res.vulkan_caps.cooperative_matrix,
        res.vulkan_caps.subgroup_size_control,
        res.vulkan_caps.storage_buffer_16bit,
        res.vulkan_caps.uniform_storage_buffer_16bit
    );

    // Get DeviceProfile enum value
    jclass profileClass = env->FindClass("com/rife/androidtv/DeviceProfile");
    if (profileClass == nullptr) {
        return nullptr;
    }
    jfieldID profileField = env->GetStaticFieldID(
        profileClass,
        "values",
        "()[Lcom/rife/androidtv/DeviceProfile;"
    );
    jobjectArray profileValues = static_cast<jobjectArray>(env->GetStaticObjectField(profileClass, profileField));
    jobject profileObj = nullptr;
    if (profileValues) {
        jsize len = env->GetArrayLength(profileValues);
        int profileIndex = static_cast<int>(res.device_profile);
        if (profileIndex >= 0 && profileIndex < len) {
            profileObj = env->GetObjectArrayElement(profileValues, profileIndex);
        }
    }

    jstring gpuName = env->NewStringUTF(res.gpu_name.c_str());
    jstring vulkanApiVersion = env->NewStringUTF(res.vulkan_api_version.c_str());
    jstring lastError = env->NewStringUTF(res.last_error.c_str());
    jstring opDetails = env->NewStringUTF(res.op_details.c_str());
    jstring deviceModel = env->NewStringUTF(res.device_model.c_str());

    jobject objectResult = env->NewObject(
        resultClass,
        constructor,
        res.success,
        res.vulkan_available,
        gpuName,
        vulkanApiVersion,
        res.model_loaded,
        static_cast<jlong>(res.last_inference_time_ms),
        lastError,
        opDetails,
        capsObj,
        profileObj,
        deviceModel
    );

    env->DeleteLocalRef(resultClass);
    env->DeleteLocalRef(capsClass);
    env->DeleteLocalRef(profileClass);
    if (profileValues) env->DeleteLocalRef(profileValues);
    return objectResult;
}
