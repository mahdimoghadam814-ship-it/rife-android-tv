#include <jni.h>
#include <string>
#include <dlfcn.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "memc_interpolator.h"

static rife::MemcInterpolator g_interp;

extern "C" JNIEXPORT void JNICALL
Java_com_rife_androidtv_NativeEngine_setInterpolationThreadCount(
    JNIEnv* env, jclass clazz, jint threads
) {
    g_interp.setThreadCount(static_cast<int>(threads));
}

// The pitch the packed motion grid is laid out on. The Java side cannot work it out for
// itself: it depends on the `overlap` setting, which lives in the interpolator. Everything
// that sizes or reads the packed field - the ByteBuffer it is written into and the texture
// coordinates the shaders index it with - derives gridW/gridH from this, and if it ever
// disagreed with blockStep() the field would be sampled with the wrong pitch and come out
// smeared.
extern "C" JNIEXPORT jint JNICALL
Java_com_rife_androidtv_NativeEngine_motionFieldStep(JNIEnv*, jclass) {
    return static_cast<jint>(g_interp.blockStep());
}

// Which of SVP's three renderers the warp should blend with: 0 = algo 11, 1 = algo 13, 2 = algo
// 21. Taken from the interpolator rather than from the raw setting so the CPU warp and the GL
// shader cannot disagree about what they are rendering.
extern "C" JNIEXPORT jint JNICALL
Java_com_rife_androidtv_NativeEngine_motionFieldBlendMode(JNIEnv*, jclass) {
    return static_cast<jint>(g_interp.blendMode());
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
    g_interp.setSvConfig(config);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rife_androidtv_NativeEngine_resetInterpolationState(JNIEnv* env, jclass clazz) {
    g_interp.reset();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_rife_androidtv_NativeEngine_getInterpolationLastDurationMs(JNIEnv* env, jclass clazz) {
    return static_cast<jdouble>(g_interp.lastDurationMs());
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
    const jlong mvCapacity = env->GetDirectBufferCapacity(mvBuffer);
    if (mvCapacity < 0) {
        return false;
    }
    const size_t needed =
        rife::MemcInterpolator::motionFieldBytes(targetWidth, targetHeight, g_interp.blockStep());
    if (static_cast<size_t>(mvCapacity) < needed) {
        return false;
    }
    return g_interp.motionField(in0Ptr, in1Ptr,
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
    jlong outCapacity = env->GetDirectBufferCapacity(outBuffer);
    if (outCapacity < 0) {
        // Not a direct buffer
        return false;
    }

    // Calculate required capacity: max possible output is targetWidth * targetHeight * 4 bytes (RGBA)
    const int64_t requiredCapacity = static_cast<int64_t>(targetWidth) * targetHeight * 4;
    if (static_cast<int64_t>(outCapacity) < requiredCapacity) {
        // Buffer too small for the requested output resolution
        return false;
    }

    return g_interp.interpolate(
        in0Ptr, in1Ptr,
        srcWidth, srcHeight,
        targetWidth, targetHeight,
        timestep,
        outPtr
    );
}
