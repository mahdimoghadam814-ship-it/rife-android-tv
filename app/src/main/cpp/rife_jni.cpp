#include <jni.h>
#include <string>
#include <atomic>
#include <dlfcn.h>
#include <android/asset_manager_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "vulkan_diagnostic.h"
#include "rife_engine.h"
#include "memc_interpolator.h"

static RifeEngine g_rife_engine;
static rife::MemcInterpolator g_memc;
static std::atomic<int> g_interp_algorithm{static_cast<int>(rife::InterpolationAlgorithm::MEMC)};

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

extern "C" JNIEXPORT void JNICALL
Java_com_rife_androidtv_NativeEngine_setInterpolationAlgorithm(
    JNIEnv* env, jclass clazz, jint algorithm
) {
    g_interp_algorithm.store(static_cast<int>(algorithm), std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rife_androidtv_NativeEngine_setMemcThreadCount(
    JNIEnv* env, jclass clazz, jint threads
) {
    g_memc.setThreadCount(static_cast<int>(threads));
}

extern "C" JNIEXPORT void JNICALL
Java_com_rife_androidtv_NativeEngine_setSvPlayerSettings(
    JNIEnv* env, jclass clazz,
    jfloat performanceQuality, jfloat artifactMaskLevel,
    jint blockSize, jint searchDistance, jint subpel, jint overlap,
    jfloat penaltyLambda, jint blendAlgorithm, jint sceneAdaptive, jint meScale
) {
    rife::SvConfig config;
    config.performanceQuality = static_cast<float>(performanceQuality);
    config.artifactMaskLevel = static_cast<float>(artifactMaskLevel);
    config.blockSize = static_cast<int>(blockSize);
    config.searchDistance = static_cast<int>(searchDistance);
    config.subpel = static_cast<int>(subpel);
    config.overlap = static_cast<int>(overlap);
    config.penaltyLambda = static_cast<float>(penaltyLambda);
    config.blendAlgorithm = static_cast<int>(blendAlgorithm);
    config.sceneAdaptive = static_cast<int>(sceneAdaptive);
    config.meScale = static_cast<int>(meScale);
    g_memc.setSvConfig(config);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rife_androidtv_NativeEngine_resetMemcState(JNIEnv* env, jclass clazz) {
    g_memc.reset();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_rife_androidtv_NativeEngine_getMemcLastDurationMs(JNIEnv* env, jclass clazz) {
    return static_cast<jdouble>(g_memc.lastDurationMs());
}

// Failure codes for setOutputDataSpace(). Deliberately outside the -1..-38 range any status_t
// the platform can return, so a zero means the panel took the value, a small negative means it
// refused it, and one of these three means we never got as far as asking.
static constexpr jint kNullSurface = -1001;
static constexpr jint kNullWindow = -1002;
static constexpr jint kSymbolMissing = -1003;

// The processed frames are blitted into a plain RGBA8888 window, which the display stack
// otherwise treats as SDR no matter what the source was: PQ/HLG code values get shown through an
// sRGB transfer curve and the picture comes out flat and milky. Tagging the buffers is what makes
// the panel decode them as HDR again, and it is the one thing the bypass path gets for free from
// the platform because MediaCodec writes the dataspace itself.
//
// ANativeWindow_setBuffersDataSpace() only exists from API 28 while minSdk is 24, so it is
// resolved lazily instead of called directly: a direct reference would not link on the devices
// the app still installs on.
//
// Resolving it through RTLD_DEFAULT alone is not enough. The symbol is no longer exported by
// libandroid.so - the native window implementation lives in libnativewindow.so, and
// RTLD_DEFAULT only walks the default namespace's global group, which is where libandroid
// itself sits, not where this symbol was left. The lookup therefore asks the owning library
// directly and falls back to the ambient scope. Nothing here can fail silently: every stage
// returns a distinct code far enough from any status_t the platform can produce that the
// caller can tell "the symbol is missing" from "the surface refused the value".
template <typename Fn>
static Fn resolveWindowSymbol(const char* name) {
    // RTLD_NOLOAD first: if the library is already resident its handle comes back without a
    // path search, which keeps this working on the linker namespaces that refuse to open a
    // second copy by name.
    static const char* const kLibraries[] = {"libnativewindow.so", "libandroid.so"};
    for (const char* library : kLibraries) {
        void* handle = dlopen(library, RTLD_NOW | RTLD_NOLOAD);
        if (handle == nullptr) {
            handle = dlopen(library, RTLD_NOW);
        }
        if (handle == nullptr) {
            continue;
        }
        if (void* symbol = dlsym(handle, name)) {
            return reinterpret_cast<Fn>(symbol);
        }
    }
    return reinterpret_cast<Fn>(dlsym(RTLD_DEFAULT, name));
}

static jint setBuffersDataSpaceCompat(ANativeWindow* window, int32_t dataSpace) {
    using Setter = int32_t (*)(ANativeWindow*, int32_t);
    static Setter setter = resolveWindowSymbol<Setter>("ANativeWindow_setBuffersDataSpace");
    if (setter == nullptr) return kSymbolMissing;
    return static_cast<jint>(setter(window, dataSpace));
}

// Reading the dataspace back is what tells a tag that survived from one that EGL quietly replaced
// when it (re)created the window surface, and it is also how the decoder's own choice is observed:
// MediaCodec stamps its output window itself, which is exactly the value the bypass path displays.
// Returns the dataspace on success and one of the k* sentinels when it could not be asked.
static jint getBuffersDataSpaceCompat(ANativeWindow* window) {
    using Getter = int32_t (*)(ANativeWindow*);
    static Getter getter = resolveWindowSymbol<Getter>("ANativeWindow_getBuffersDataSpace");
    if (getter == nullptr) return kSymbolMissing;
    return static_cast<jint>(getter(window));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rife_androidtv_NativeEngine_setOutputDataSpace(
    JNIEnv* env, jclass clazz, jobject surface, jint dataSpace) {
    if (surface == nullptr) return kNullSurface;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return kNullWindow;
    const jint rc = setBuffersDataSpaceCompat(window, static_cast<int32_t>(dataSpace));
    ANativeWindow_release(window);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rife_androidtv_NativeEngine_getOutputDataSpace(
    JNIEnv* env, jclass clazz, jobject surface) {
    if (surface == nullptr) return kNullSurface;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return kNullWindow;
    const jint value = getBuffersDataSpaceCompat(window);
    ANativeWindow_release(window);
    return value;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rife_androidtv_NativeEngine_computeMotionField(
    JNIEnv* env, jclass clazz,
    jobject in0Buffer, jobject in1Buffer,
    jint srcWidth, jint srcHeight,
    jint targetWidth, jint targetHeight,
    jobject mvBuffer,
    jboolean forwardOnly
) {
    uint8_t* in0Ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(in0Buffer));
    uint8_t* in1Ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(in1Buffer));
    uint8_t* mvPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(mvBuffer));
    if (!in0Ptr || !in1Ptr || !mvPtr) {
        return false;
    }
    // The field only exists for the block-matching algorithms. In RIFE mode there is nothing to
    // hand to the shader, so report failure and let the caller take its CPU path instead.
    if (!rife::producesMotionField(
            g_interp_algorithm.load(std::memory_order_relaxed))) {
        return false;
    }
    const jlong mvCapacity = env->GetDirectBufferCapacity(mvBuffer);
    if (mvCapacity < 0) {
        return false;
    }
    const size_t needed = rife::MemcInterpolator::motionFieldBytes(targetWidth, targetHeight);
    if (static_cast<size_t>(mvCapacity) < needed) {
        return false;
    }
    return g_memc.motionField(in0Ptr, in1Ptr,
                              srcWidth, srcHeight,
                              targetWidth, targetHeight,
                              mvPtr, static_cast<size_t>(mvCapacity),
                              forwardOnly == JNI_TRUE);
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

    // Check output buffer capacity - native code writes RGBA (4 bytes per pixel)
    // The maximum output size is targetWidth * targetHeight * 4 bytes (RGBA)
    // since resolution fallback never upscales beyond requested target dimensions
    jlong outCapacity = env->GetDirectBufferCapacity(outBuffer);
    if (outCapacity < 0) {
        // Not a direct buffer
        return false;
    }

    // Calculate required capacity: max possible output is targetWidth * targetHeight * 4 bytes (RGBA)
    // Resolution fallback only downscales, so this is the maximum required capacity
    const int64_t requiredCapacity = static_cast<int64_t>(targetWidth) * targetHeight * 4;
    if (static_cast<int64_t>(outCapacity) < requiredCapacity) {
        // Buffer too small for the requested output resolution
        return false;
    }

    if (rife::producesMotionField(
            g_interp_algorithm.load(std::memory_order_relaxed))) {
        return g_memc.interpolate(
            in0Ptr, in1Ptr,
            srcWidth, srcHeight,
            targetWidth, targetHeight,
            timestep,
            outPtr
        );
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

    // Get DeviceProfile enum value.
    //
    // DeviceProfile.values() is a static *method*, not a field, so GetStaticFieldID throws
    // NoSuchFieldError. The next JNI call (GetStaticObjectField) was then entered with that
    // exception still pending, which CheckJNI turns into a SIGABRT of the whole process -
    // the Kotlin try/catch around this call never sees it. Use Class.getEnumConstants(),
    // which works for any enum regardless of how it is named, and never call into JNI while
    // an exception is pending.
    jclass profileClass = env->FindClass("com/rife/androidtv/DeviceProfile");
    if (profileClass == nullptr) {
        return nullptr;
    }

    jobjectArray profileValues = nullptr;
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass != nullptr && !env->ExceptionCheck()) {
        jmethodID getEnumConstants =
            env->GetMethodID(classClass, "getEnumConstants", "()[Ljava/lang/Object;");
        if (getEnumConstants != nullptr && !env->ExceptionCheck()) {
            profileValues = static_cast<jobjectArray>(
                env->CallObjectMethod(profileClass, getEnumConstants));
        }
        env->DeleteLocalRef(classClass);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        profileValues = nullptr;
    }

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
