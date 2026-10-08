package com.rife.androidtv.rife

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.rife.androidtv.NativeEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders already-computed RGBA frames to the Media3 output surface with OpenGL ES.
 *
 * This replaces the previous `Surface.lockCanvas()` + `Canvas.drawBitmap()` path, which rasterised
 * every output frame on the CPU (three full-screen bitmaps per interpolated pair) and was one of
 * the dominant costs on the TV box. Here the frame is uploaded to a texture and blitted to the
 * output EGL window surface entirely on the GPU:
 *
 * ```
 * RGBA direct buffer -> glTexImage2D -> full-screen quad -> eglSwapBuffers
 * ```
 *
 * All methods must be called on the thread that owns [context] (the RIFE worker thread), which is
 * also the thread that owns the EGL context used by [OesFrameGrabber], so the two stages share one
 * GL context and no texture or buffer has to cross threads.
 */
@UnstableApi
class GlOutputRenderer {

    companion object {
        private const val TAG = "GlOutputRenderer"

        /**
         * Per-frame render diagnostics. Runs twice per interpolated pair and includes whole-buffer
         * checksums plus three EGL queries, so it is off while profiling the real cost.
         */
        private const val VERBOSE_DIAGNOSTICS = false

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform vec2 uContentScale;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * uContentScale, aPosition.z, aPosition.w);
                vTextureCoord = aTextureCoord.xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTextureCoord;
            uniform sampler2D uTexture;
            uniform int uIsHdr;

            // SMPTE ST 2084 (PQ) EOTF inverse - converts linear light to PQ code values
            vec3 linearToPQ(vec3 linear) {
                const float m1 = 2610.0 / 4096.0;
                const float m2 = 2523.0 / 4096.0 * 128.0;
                const float c1 = 3424.0 / 4096.0;
                const float c2 = 2413.0 / 4096.0 * 32.0;
                const float c3 = 2392.0 / 4096.0 * 32.0;
                vec3 cp = pow(max(linear, vec3(0.0)), vec3(m1));
                vec3 numerator = vec3(c1) + vec3(c2) * cp;
                vec3 denominator = vec3(1.0) + vec3(c3) * cp;
                return pow(numerator / denominator, vec3(m2));
            }

            void main() {
                vec3 color = texture2D(uTexture, vTextureCoord).rgb;
                if (uIsHdr != 0) {
                    color = linearToPQ(color);
                }
                gl_FragColor = vec4(color, 1.0);
            }
        """

        private const val WARP_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform vec2 uContentScale;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * uContentScale, aPosition.z, aPosition.w);
                vTextureCoord = aTextureCoord.xy;
            }
        """

        /**
         * Motion-compensated blend of two frames, one fragment per output pixel.
         *
         * This is the CPU warp from `MemcInterpolator::motionCompensate()` re-expressed in GLSL.
         * The three identities it relies on, all of which must keep matching the native code:
         *
         *  * `p` is the continuous processing-space position. The quad's texcoords run 0..1 over
         *    the frame, so `p * uTargetSize - 0.5` lands on the pixel's integer index exactly
         *    when mv == 0 and the whole expression collapses to the plain texcoord used by the
         *    single-texture blit above - which is what guarantees the same orientation.
         *  * `(p + uMotionOffset) / uMotionGrid` reproduces `mvGridAxis()`: a field of
         *    ceil(w/step) vectors, one every `step` pixels and anchored `16/2` into each cell
         *    (the anchor is the centre of the 16 px search window, not half the pitch), so the
         *    effective texel is `(p - 8)/step`. `uMotionGrid` is `step * gridW` and
         *    `uMotionOffset` is `step/2 - 8`, which is exactly zero at `step == 16` - so with
         *    SVPlayer's overlap off, or with MEMC, the expression collapses back to `p / uMotionGrid`
         *    unchanged. CLAMP_TO_EDGE is the border clamp rather than an extrapolation.
         *  * the two sample positions are the native `x - mv*t` and `x - mv*(1-t)`; adding the
         *    half texel back converts pixel index to texture coordinate. The packed field is in
         *    half-pel, so `* 0.5` in the decode below is what turns it back into pixels.
         *
         * `highp` is requested because the field is stored biased by 128 and carries half-pel
         * precision, so it survives the `(v * 255.0 - 128.0) * 0.5` decode intact: a mediump
         * (fp16) `mv` would only resolve about an eighth of a pixel, which is too coarse for the
         * sub-pixel interpolation it feeds.
         *
         * `uMask` holds the cover/uncover masks from `MemcInterpolator::buildOcclusionMasks()`,
         * one byte per block, sampled at the same coordinate as the field. Where a field folds
         * the content behind it is being covered up, so that side hands over to the other side's
         * warp; if both are covered the pair collapses to a plain crossfade. The `occ == 0`
         * branch is the overwhelmingly common one and reproduces `mix(ca, cb, t)` exactly, so an
         * unoccluded frame costs two texture fetches more than it did before the masks existed.
         * The fetch is inside `uBlendMode == 2`, because algo 11 and algo 13 deliberately do not
         * read the masks at all.
         *
         * `uBlendMode` selects between those three: 0 is algo 11 (the plain time blend), 1 is
         * algo 13 (plus the dynamic median), 2 is algo 21 (plus cover/uncover). SVP documents
         * them as alternatives - 21 does not include the median - and ships 13; MEMC is pinned
         * to 2 by `NativeEngine.motionFieldBlendMode()` so that picking a different renderer
         * cannot move it.
         */
private const val WARP_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTextureCoord;
            uniform sampler2D uFrame0;
            uniform sampler2D uFrame1;
            uniform sampler2D uMotion;
            uniform sampler2D uMask;
            uniform vec2 uTargetSize;
            uniform vec2 uMotionGrid;
            uniform vec2 uMotionOffset;
            uniform float uTimestep;
            // 0 = algo 11, 1 = algo 13, 2 = algo 21 - SVP's three renderers, which are
            // alternatives rather than cumulative rungs. It comes from
            // NativeEngine.motionFieldBlendMode(), the same accessor the CPU warp uses, so the
            // fallback path and this one can never disagree about what they are rendering.
            uniform int uBlendMode;
            uniform int uIsHdr;
            // Per-channel median of three, as a + b + c - min - max.
            vec3 median3(vec3 a, vec3 b, vec3 c) {
                return a + b + c - min(min(a, b), c) - max(max(a, b), c);
            }

            // SMPTE ST 2084 (PQ) EOTF inverse - converts linear light to PQ code values
            vec3 linearToPQ(vec3 linear) {
                const float m1 = 2610.0 / 4096.0;
                const float m2 = 2523.0 / 4096.0 * 128.0;
                const float c1 = 3424.0 / 4096.0;
                const float c2 = 2413.0 / 4096.0 * 32.0;
                const float c3 = 2392.0 / 4096.0 * 32.0;
                vec3 cp = pow(max(linear, vec3(0.0)), vec3(m1));
                vec3 numerator = vec3(c1) + vec3(c2) * cp;
                vec3 denominator = vec3(1.0) + vec3(c3) * cp;
                return pow(numerator / denominator, vec3(m2));
            }

            void main() {
                vec2 p = vTextureCoord * uTargetSize - 0.5;
                vec2 g = (p + uMotionOffset) / uMotionGrid;
                vec4 mv = texture2D(uMotion, g);

                vec2 mvf = (mv.rg * 255.0 - 128.0) * 0.5;
                vec2 mvb = (mv.ba * 255.0 - 128.0) * 0.5;
                vec2 pa = p - mvf * uTimestep;
                vec2 pb = p - mvb * (1.0 - uTimestep);
                vec3 ca = texture2D(uFrame0, (pa + 0.5) / uTargetSize).rgb;
                vec3 cb = texture2D(uFrame1, (pb + 0.5) / uTargetSize).rgb;
                vec3 blended;
                if (uBlendMode == 1) {
                    // Algo 13. The third candidate is the plain unwarped crossfade: two of the
                    // three coincide with in0 at t = 0 and with in1 at t = 1, so the median is
                    // still the source frame at both ends of the interval; in between it throws
                    // away whichever candidate is the outlier, which is what buys the minimum
                    // artifacts and what costs the halos around moving objects.
                    vec2 raw = (p + 0.5) / uTargetSize;
                    vec3 plain = mix(texture2D(uFrame0, raw).rgb,
                                     texture2D(uFrame1, raw).rgb, uTimestep);
                    blended = median3(ca, cb, plain);
                } else {
                    vec3 termF = ca;
                    vec3 termB = cb;
                    if (uBlendMode == 2) {
                        vec2 occ = texture2D(uMask, g).rg;
                        // The masks describe full-pair flow. At time t the forward path has
                        // traversed t of that flow and the backward path has traversed 1-t.
                        occ *= vec2(uTimestep, 1.0 - uTimestep);
                        if (occ.r > 0.0 || occ.g > 0.0) {
                            vec2 raw = (p + 0.5) / uTargetSize;
                            vec3 innerF = ca;
                            if (occ.r > 0.0) {
                                innerF = mix(ca, texture2D(uFrame1, raw).rgb, occ.r);
                            }
                            vec3 innerB = cb;
                            if (occ.g > 0.0) {
                                innerB = mix(cb, texture2D(uFrame0, raw).rgb, occ.g);
                            }
                            termF = mix(ca, innerB, occ.r);
                            termB = mix(cb, innerF, occ.g);
                        }
                    }
                    blended = mix(termF, termB, uTimestep);
                }
                if (uIsHdr != 0) {
                    blended = linearToPQ(blended);
                }
                gl_FragColor = vec4(blended, 1.0);
            }
        """
        /**
         * Temporal denoiser: merge the frame being decoded into the history the previous call
         * produced, sampled where the motion field says that content has moved to.
         *
         * This is the recursion the motion-aligned denoiser is - `mix(current, warp(history), w)`
         * with `w` from `MemcInterpolator::packMotionField()` - so it cannot live inside
         * [WARP_FRAGMENT_SHADER]. Merging the two sides there, before the timestep blend, collapses
         * `mix(mix(a, b, t), ...)` onto a plain crossfade, which is the quality problem this
         * pipeline exists to fix; the warp runs on the denoised textures instead, at the price of
         * one extra pass for the current frame.
         *
         * The per-pixel gate is what stops it ghosting. A block straddles a motion boundary, so its
         * vector is wrong for part of that block and the sample lands somewhere unrelated; the gate
         * asks whether the two frames would have shown this difference anyway and shuts the merge as
         * soon as they would not. It compares the mean over the three channels rather than
         * luminance alone - a sample can match in brightness and be entirely wrong in colour - and
         * `uMask.a` is already published in those units, so the two agree without the shader
         * knowing anything about luma. `uMask.b` has been folded with the forward cover/uncover
         * mask on the native side, so a covered sample is rejected here too.
         *
         * `highp` matters for the same reason it does in the warp: the field is biased by 128, and
         * a mediump `mvf` would quantise to about a quarter of a pixel.
         */
        private const val DENOISE_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTextureCoord;
            uniform sampler2D uCurrent;
            uniform sampler2D uHistory;
            uniform sampler2D uMotion;
            uniform sampler2D uMask;
            uniform vec2 uTargetSize;
            uniform vec2 uMotionGrid;
            uniform vec2 uMotionOffset;
            uniform float uHasHistory;
            uniform int uIsHdr;
            // Multiplier on the history blend, from the denoise level. It scales how strongly a
            // pixel may be replaced by its own motion-compensated counterpart - the similarity
            // gate below still rejects the moment the two frames disagree by more than their own
            // noise does, so turning it up merges more of what agrees and does not soften what
            // does not.
            uniform float uStrength;

            // SMPTE ST 2084 (PQ) EOTF inverse - converts linear light to PQ code values
            vec3 linearToPQ(vec3 linear) {
                const float m1 = 2610.0 / 4096.0;
                const float m2 = 2523.0 / 4096.0 * 128.0;
                const float c1 = 3424.0 / 4096.0;
                const float c2 = 2413.0 / 4096.0 * 32.0;
                const float c3 = 2392.0 / 4096.0 * 32.0;
                vec3 cp = pow(max(linear, vec3(0.0)), vec3(m1));
                vec3 numerator = vec3(c1) + vec3(c2) * cp;
                vec3 denominator = vec3(1.0) + vec3(c3) * cp;
                return pow(numerator / denominator, vec3(m2));
            }

            void main() {
                // The quad's V runs opposite to framebuffer row order: the vertex at the top of the
                // viewport carries v = 0 but lands in framebuffer row height-1. Sampling at
                // vTextureCoord directly would therefore store image row h-1-R into row R, and
                // everything downstream - the present, and the warp that reads this as if it were
                // a raw frame - would show the picture upside down, with the history mirrored
                // against the current frame so the gate rejected every merge. Inverting v makes the
                // pass orientation-neutral: row R receives image row R, which is the same layout a
                // frame straight out of the readback has.
                vec2 uv = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
                vec2 p = uv * uTargetSize - 0.5;
                vec3 cur = texture2D(uCurrent, uv).rgb;
                vec3 merged = cur;
                if (uHasHistory > 0.5) {
                    vec2 g = (p + uMotionOffset) / uMotionGrid;
                    vec2 mvf = (texture2D(uMotion, g).rg * 255.0 - 128.0) * 0.5;
                    vec4 aux = texture2D(uMask, g);
                    float w = aux.b * (255.0 - aux.r) / (255.0 * 255.0);
                    if (w > 0.0) {
                        vec3 his = texture2D(uHistory, (p - mvf + 0.5) / uTargetSize).rgb;
                        float f = max(aux.a, 1.0);
                        float d = (abs(cur.r - his.r) + abs(cur.g - his.g) + abs(cur.b - his.b)) / 3.0;
                        float gate = 1.0 - clamp((d - 2.0 * f) / (3.0 * f), 0.0, 1.0);
                        merged = mix(cur, his, clamp(w * gate * uStrength, 0.0, 1.0));
                    }
                }
                if (uIsHdr != 0) {
                    merged = linearToPQ(merged);
                }
                gl_FragColor = vec4(merged, 1.0);
            }
        """

        /** Clip-space positions of a full-screen triangle strip. */
        private val FULL_QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
        )

        /**
         * Texture coordinates of the same strip. The readback buffer holds the image top row first
         * (see [OesFrameGrabber]), which becomes texture row 0 after `glTexImage2D`; GL texture row
         * 0 is the BOTTOM of the sampled image, so V is flipped to keep the picture upright.
         */
        private val FULL_QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f
        )

        /**
         * Texture coordinates for the preview mirror. [FULL_QUAD_TEX_COORDS] flips V because the
         * picture is uploaded top row first; the mirror does not upload anything, it samples a
         * `glCopyTexSubImage2D` of the window, and that copy starts at the window's *bottom-left*
         * corner. So texture row 0 of the mirror already holds the bottom of the picture, and the
         * flip that keeps [FULL_QUAD_TEX_COORDS] upright turns the preview upside down. Same strip,
         * same vertices, V unflipped.
         */
        private val MIRROR_QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f
        )
    }

    private var program = 0
    private var aPositionHandle = -1
    private var aTextureCoordHandle = -1
    private var uTextureHandle = -1
    private var uContentScaleHandle = -1
    private var textureId = 0

    /** Dimensions currently described by [textureId]; 0 until the first upload. */
    private var textureWidth = 0
    private var textureHeight = 0

    // ---- GPU motion-compensation path (see WARP_FRAGMENT_SHADER) ----
    private var warpProgram = 0
    private var warpAPosition = -1
    private var warpATexCoord = -1
    private var warpUContentScale = -1
    private var warpUFrame0 = -1
    private var warpUFrame1 = -1
    private var warpUMotion = -1
    private var warpUMask = -1
    private var warpUTargetSize = -1
    private var warpUMotionGrid = -1
    private var warpUMotionOffset = -1
    private var warpUBlendMode = -1
    private var warpUTimestep = -1
    private var warpTex0 = 0
    private var warpTex1 = 0
    private var warpMotionTex = 0
    private var warpMaskTex = 0
    private var warpTex0W = 0
    private var warpTex0H = 0
    private var warpTex1W = 0
    private var warpTex1H = 0
    private var warpGridW = 0
    private var warpGridH = 0
    // Pitch the motion texture above was uploaded at, so drawWarp() can build the same
    // uMotionGrid/uMotionOffset pair the native side packed the field with.
    private var warpGridStep = 16
    // Renderer drawWarp() should blend with, read from the same accessor the CPU warp uses.
    private var warpBlendMode = 2
    private var warpDrawCalls = 0L

    // ---- Temporal denoiser (see DENOISE_FRAGMENT_SHADER) ----
    private var denoiseProgram = 0
    private var denDPosition = -1
    private var denDTexCoord = -1
    /**
     * Strength of the history blend, from the denoise level: one is the balanced default and the
     * value the stage shipped with, above one merges more of what the similarity gate has already
     * accepted, below one less. It is a shader uniform rather than a recompiled program, so a
     * change takes effect on the next pass.
     */
    @Volatile
    var denoiseStrength = 1f

    @Volatile
    var isHdr = false

    private var uIsHdrHandle = -1
    private var warpUIsHdr = -1

    private var denUContentScale = -1
    private var denUCurrent = -1
    private var denUHistory = -1
    private var denUMotion = -1
    private var denUMask = -1
    private var denUTargetSize = -1
    private var denUMotionGrid = -1
    private var denUMotionOffset = -1
    private var denUHasHistory = -1
    private var denUStrength = -1
    private var denUIsHdr = -1

    /** The frame being denoised, uploaded once per call. Kept separate from the warp's pair. */
    private var denCurrentTex = 0
    private var denCurrentW = 0
    private var denCurrentH = 0

    /** Ping-pong history: one holds the previous call's output, the other receives the new one. */
    private var denTexA = 0
    private var denTexB = 0
    private var denFbo = 0
    private var denTexW = 0
    private var denTexH = 0

    /** History the next call merges into; 0 while there is none (first frame, or after a reset). */
    private var denHistoryTex = 0

    /**
     * The pair the last [renderDenoise] produced: [denPair0] the history it read, [denPair1] the
     * frame it wrote. The warp blends these two instead of the raw sources. [denPair0] is 0 on the
     * first call after a reset, which is the one cycle where there is no previous denoised frame
     * to warp from and the caller has to keep the raw one.
     */
    private var denPair0 = 0
    private var denPair1 = 0
    private var denoiseDrawCalls = 0L

    /**
     * Clip-space scale that letterboxes the frame into the surface. Seeded to 0 so the very first
     * [updateContentScale] always reports a change and the uniform (defaulting to 0) gets set.
     */
    private var contentScaleX = 0.0f
    private var contentScaleY = 0.0f

    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var windowSurface: EGLSurface? = null
    private var outputSurface: Surface? = null
    private var mirrorSurface: Surface? = null
    private var mirrorEglSurface: EGLSurface? = null
    private var mirrorTexture = 0
    private var mirrorTextureWidth = 0
    private var mirrorTextureHeight = 0
    private var mirrorWidth = 0
    private var mirrorHeight = 0
    private var remoteDroppedFrameCount = 0L
    val droppedRemoteFrames: Long get() = remoteDroppedFrameCount
    @Volatile private var remoteFrameIntervalNs = 0L
    private var nextRemotePresentationNs = Long.MIN_VALUE
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /**
     * The window surface the final present draws into, in pixels. This is the hard ceiling on the
     * processing resolution: capturing larger than this only buys pixels the present has to scale
     * straight back down. Zero until the surface exists.
     *
     * Read by [VideoFrameProcessor] to clamp its capture size, so it must stay allocation-free.
     */
    val outputSurfaceWidth: Int
        get() = surfaceWidth

    val outputSurfaceHeight: Int
        get() = surfaceHeight
    val mirrorOutputSurface: Surface? get() = mirrorSurface

    /**
     * Accumulated nanoseconds per phase of [render], in order: eglMakeCurrent (+ surface resize),
     * state setup (viewport/uniforms/clear), glTexImage2D upload, attribute setup + glDrawArrays,
     * eglSwapBuffers. [VideoFrameProcessor] drains them into the PIPELINE TIMING line and resets
     * them, so a single number like "render=28.0" can be attributed to the right call.
     */
    private var nsCurrent = 0L
    private var nsSetup = 0L
    private var nsUpload = 0L
    private var nsDraw = 0L
    private var nsSwap = 0L
    private var renderCalls = 0L

    /**
     * The presentation time the next [eglSwapBuffers] must carry, in nanoseconds. Set by the
     * caller before every render. Zero means "untimed" and the swap is issued without a timestamp.
     *
     * Without this the encoder reports `presentationTimeUs == 0` for every frame, the muxer falls
     * back to a generated timeline at the *configured* frame rate, and a 24 fps source at 3x is
     * streamed as 60 fps. The value is the frame's own presentation time, so the stream carries
     * the real media timeline.
     */
    var outputTimestampNs: Long = 0L

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_VERTICES)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_TEX_COORDS)
            position(0)
        }

    /** [MIRROR_QUAD_TEX_COORDS] in direct memory; see there for why it differs from [texCoordBuffer]. */
    private val mirrorTexCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MIRROR_QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(MIRROR_QUAD_TEX_COORDS)
            position(0)
        }

    val isInitialized: Boolean
        get() = program != 0 && textureId != 0

    /**
     * Creates the blit program and the output texture. Must be called with [context] current.
     */
    fun init(context: EGLContext) {
        if (program != 0) {
            return
        }
        this.context = context

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        val newProgram = GLES20.glCreateProgram()
        if (newProgram == 0) {
            throw IllegalStateException("glCreateProgram failed")
        }
        GLES20.glAttachShader(newProgram, vertexShader)
        GLES20.glAttachShader(newProgram, fragmentShader)
        GLES20.glLinkProgram(newProgram)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        val programLog = GLES20.glGetProgramInfoLog(newProgram)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(newProgram)
            throw IllegalStateException("Failed to link output blit program: $programLog")
        }

        program = newProgram
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        uContentScaleHandle = GLES20.glGetUniformLocation(program, "uContentScale")
        uIsHdrHandle = GLES20.glGetUniformLocation(program, "uIsHdr")

        if (aPositionHandle < 0 || aTextureCoordHandle < 0 || uTextureHandle < 0 ||
            uContentScaleHandle < 0
        ) {
            release()
            throw IllegalStateException("Output blit program is missing expected attributes")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        textureWidth = 0
        textureHeight = 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        initWarp()

        Log.i(TAG, "Output blit program ready (textureId=$textureId, warp=${if (warpProgram != 0) "on" else "off"})")

        GpuMeProbe.run()
    }

    /**
     * Compiles the motion-compensation program. Failure is not fatal: [renderWarp] then reports
     * false and the caller keeps using the CPU warp, so a driver that dislikes the shader costs
     * performance rather than the frame.
     */
    private fun initWarp() {
        if (warpProgram != 0) {
            return
        }
        val newProgram = GLES20.glCreateProgram()
        if (newProgram == 0) {
            Log.w(TAG, "glCreateProgram failed for the warp program; CPU warp stays in use")
            return
        }
        val vs = tryCompileShader(GLES20.GL_VERTEX_SHADER, WARP_VERTEX_SHADER)
        val fs = tryCompileShader(GLES20.GL_FRAGMENT_SHADER, WARP_FRAGMENT_SHADER)
        if (vs == 0 || fs == 0) {
            GLES20.glDeleteProgram(newProgram)
            return
        }
        GLES20.glAttachShader(newProgram, vs)
        GLES20.glAttachShader(newProgram, fs)
        GLES20.glLinkProgram(newProgram)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        val programLog = GLES20.glGetProgramInfoLog(newProgram)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(newProgram)
            Log.w(TAG, "warp program link failed: $programLog; CPU warp stays in use")
            return
        }

        warpProgram = newProgram
        warpAPosition = GLES20.glGetAttribLocation(newProgram, "aPosition")
        warpATexCoord = GLES20.glGetAttribLocation(newProgram, "aTextureCoord")
        warpUContentScale = GLES20.glGetUniformLocation(newProgram, "uContentScale")
        warpUFrame0 = GLES20.glGetUniformLocation(newProgram, "uFrame0")
        warpUFrame1 = GLES20.glGetUniformLocation(newProgram, "uFrame1")
        warpUMotion = GLES20.glGetUniformLocation(newProgram, "uMotion")
        warpUMask = GLES20.glGetUniformLocation(newProgram, "uMask")
        warpUTargetSize = GLES20.glGetUniformLocation(newProgram, "uTargetSize")
        warpUMotionGrid = GLES20.glGetUniformLocation(newProgram, "uMotionGrid")
        warpUMotionOffset = GLES20.glGetUniformLocation(newProgram, "uMotionOffset")
        warpUBlendMode = GLES20.glGetUniformLocation(newProgram, "uBlendMode")
        warpUTimestep = GLES20.glGetUniformLocation(newProgram, "uTimestep")
        warpUIsHdr = GLES20.glGetUniformLocation(newProgram, "uIsHdr")

        if (warpAPosition < 0 || warpATexCoord < 0 || warpUContentScale < 0 ||
            warpUFrame0 < 0 || warpUFrame1 < 0 || warpUMotion < 0 || warpUMask < 0 ||
            warpUTargetSize < 0 || warpUMotionGrid < 0 || warpUMotionOffset < 0 ||
            warpUBlendMode < 0 ||
            warpUTimestep < 0
        ) {
            GLES20.glDeleteProgram(newProgram)
            warpProgram = 0
            Log.w(TAG, "warp program is missing expected uniforms; CPU warp stays in use")
            return
        }

        warpTex0 = createWarpTexture()
        warpTex1 = createWarpTexture()
        warpMotionTex = createWarpTexture()
        warpMaskTex = createWarpTexture()
        Log.i(TAG, "warp program ready")

        initDenoise()
    }

    /**
     * Compiles the denoiser and creates its ping-pong targets. Best-effort, like the warp program:
     * a driver that will not compile it costs the denoising stage only, so [VideoFrameProcessor]
     * falls back to the stage's own implementation rather than to no picture at all.
     */
    private fun initDenoise() {
        if (denoiseProgram != 0) {
            return
        }
        val fs = tryCompileShader(GLES20.GL_FRAGMENT_SHADER, DENOISE_FRAGMENT_SHADER)
        if (fs == 0) {
            return
        }
        val vs = tryCompileShader(GLES20.GL_VERTEX_SHADER, WARP_VERTEX_SHADER)
        if (vs == 0) {
            GLES20.glDeleteShader(fs)
            return
        }
        val newProgram = GLES20.glCreateProgram()
        if (newProgram == 0) {
            GLES20.glDeleteShader(fs)
            GLES20.glDeleteShader(vs)
            return
        }
        GLES20.glAttachShader(newProgram, vs)
        GLES20.glAttachShader(newProgram, fs)
        GLES20.glLinkProgram(newProgram)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        val programLog = GLES20.glGetProgramInfoLog(newProgram)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(newProgram)
            Log.w(TAG, "denoise program did not link; $programLog")
            return
        }
        denDPosition = GLES20.glGetAttribLocation(newProgram, "aPosition")
        denDTexCoord = GLES20.glGetAttribLocation(newProgram, "aTextureCoord")
        denUContentScale = GLES20.glGetUniformLocation(newProgram, "uContentScale")
        denUCurrent = GLES20.glGetUniformLocation(newProgram, "uCurrent")
        denUHistory = GLES20.glGetUniformLocation(newProgram, "uHistory")
        denUMotion = GLES20.glGetUniformLocation(newProgram, "uMotion")
        denUMask = GLES20.glGetUniformLocation(newProgram, "uMask")
        denUTargetSize = GLES20.glGetUniformLocation(newProgram, "uTargetSize")
        denUMotionGrid = GLES20.glGetUniformLocation(newProgram, "uMotionGrid")
        denUMotionOffset = GLES20.glGetUniformLocation(newProgram, "uMotionOffset")
        denUHasHistory = GLES20.glGetUniformLocation(newProgram, "uHasHistory")
        denUStrength = GLES20.glGetUniformLocation(newProgram, "uStrength")
        denUIsHdr = GLES20.glGetUniformLocation(newProgram, "uIsHdr")
        if (denDPosition < 0 || denDTexCoord < 0 || denUContentScale < 0 ||
            denUCurrent < 0 || denUHistory < 0 || denUMotion < 0 || denUMask < 0 ||
            denUTargetSize < 0 || denUMotionGrid < 0 || denUMotionOffset < 0 ||
            denUHasHistory < 0 ||
            denUStrength < 0 ||
            denUIsHdr < 0
        ) {
            GLES20.glDeleteProgram(newProgram)
            Log.w(TAG, "denoise program is missing a location; the stage keeps its own path")
            return
        }
        denoiseProgram = newProgram
        Log.i(TAG, "denoise program ready")
    }

    private fun createWarpTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /** True when [renderWarp] can run; false means the caller must use the CPU warp. */
    val isWarpInitialized: Boolean
        get() = warpProgram != 0

    /**
     * (Re)creates the EGL window surface that processed frames are rendered to. Must be called with
     * the worker's EGL context current. Passing `null` releases the window surface.
     */
    fun setOutputSurface(display: EGLDisplay, surface: Surface?) {
        // A redundant call used to be expensive: tearing the window surface down and building it
        // again costs a frame - the hitch seen whenever the player UI brings the SurfaceView
        // through a relayout - and it silently resets the dataspace tag the HDR path depends on.
        // SurfaceView reports the same Surface again on those relayouts, so compare first and
        // only re-query the size, which is the part that genuinely can have changed.
        if (display == this.display &&
            surface == outputSurface &&
            windowSurface != null &&
            (surface == null || surface.isValid)
        ) {
            updateSurfaceSize()
            return
        }
        this.display = display
        releaseWindowSurface()
        outputSurface = surface
        surfaceWidth = 0
        surfaceHeight = 0
        if (surface == null || !surface.isValid) {
            return
        }

        val strict = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        var created = createWindowSurface(display, surface, strict)
        if (created == null) {
            // A Main10 encoder's input surface is a 10-bit buffer, and an 8-bit config cannot
            // match it. RGB10_A2 is the 10-bit layout every GLES2 driver exposes; if the surface
            // is 8-bit this query simply fails and the relaxed fallback below takes over.
            val hdr = intArrayOf(
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            created = createWindowSurface(display, surface, hdr)
        }
        if (created == null) {
            // The display surface has always matched RGBA8888, but a MediaCodec encoder input
            // surface is allocated by the codec and may be 10-bit (RGBA_1010102). An
            // EGL_BAD_MATCH from eglCreateWindowSurface is the only signal that gives us, and
            // giving up there would leave no path at all, so retry with the component sizes
            // unconstrained and let the driver pick the format the native window actually is.
            Log.w(TAG, "RGBA8888 did not match the surface; retrying with the driver's own format")
            val relaxed = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            created = createWindowSurface(display, surface, relaxed)
            if (created == null) return
        }

        this.windowSurface = created
        updateSurfaceSize()
        Log.i(
            TAG,
            "Output window surface created (${surfaceWidth}x$surfaceHeight, " +
                "eglConfig=${describeConfig()})"
        )
    }

    /** Adds a preview consumer while the primary window is the encoder input Surface. */
    fun setMirrorOutputSurface(surface: Surface?) {
        val eglDisplay = display ?: return
        if (surface == mirrorSurface && mirrorEglSurface != null) return
        releaseMirrorSurface()
        mirrorSurface = surface
        if (surface == null || !surface.isValid) return
        // The mirror is always the SurfaceView's RGBA8888 surface, and activeConfig may be a
        // Main10 encoder config by now (setOutputSurface switched the primary to the encoder
        // input first), which EGL rejects against an 8-bit native window with EGL_BAD_MATCH -
        // the exact frozen-preview failure. Pick the config against this surface itself,
        // strict first, and leave the primary's activeConfig alone.
        val strict = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        var egl = createWindowSurface(eglDisplay, surface, strict, recordConfig = false)
        if (egl == null) {
            val relaxed = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            egl = createWindowSurface(eglDisplay, surface, relaxed, recordConfig = false)
        }
        if (egl == null) {
            Log.w(TAG, "Could not create preview mirror EGL surface: 0x${EGL14.eglGetError().toString(16)}")
            mirrorSurface = null
            return
        }
        mirrorEglSurface = egl
        val w = IntArray(1); val h = IntArray(1)
        if (EGL14.eglQuerySurface(eglDisplay, egl, EGL14.EGL_WIDTH, w, 0) &&
            EGL14.eglQuerySurface(eglDisplay, egl, EGL14.EGL_HEIGHT, h, 0)
        ) { mirrorWidth = w[0]; mirrorHeight = h[0] }
        nextRemotePresentationNs = Long.MIN_VALUE
        Log.i(TAG, "Preview mirror active ${mirrorWidth}x$mirrorHeight")
    }

    /** Timestamp ceiling for the encoder output only; zero leaves encoder submissions uncapped. */
    fun setRemoteOutputFrameRate(frameRate: Float) {
        remoteFrameIntervalNs = if (frameRate.isFinite() && frameRate > 0f) (1_000_000_000.0 / frameRate).toLong() else 0L
        nextRemotePresentationNs = Long.MIN_VALUE
    }

    /** The config the current [windowSurface] was created from; null before the first success. */
    private var activeConfig: EGLConfig? = null

    /**
     * Creates the window surface for [surface] under [configAttribs], or null with the reason
     * logged. The two callers differ only in how fussy the config search is.
     */
    private fun createWindowSurface(
        display: EGLDisplay,
        surface: Surface,
        configAttribs: IntArray,
        /** False for surfaces that must not become the config [describeConfig] reports. */
        recordConfig: Boolean = true
    ): EGLSurface? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            Log.e(TAG, "eglChooseConfig failed for the output surface")
            return null
        }
        val candidate = EGL14.eglCreateWindowSurface(
            display,
            configs[0],
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (candidate == null || candidate == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}")
            return null
        }
        if (recordConfig) activeConfig = configs[0]
        return candidate
    }

    /**
     * The colour buffer sizes of the config the window surface actually got. Phase B asked for
     * the exact GPU output format, and this - 8 bits per channel or 10 - is the one number that
     * says whether the pixels rendered into this surface can carry HDR precision.
     */
    private fun describeConfig(): String {
        val display = this.display ?: return "no display"
        val config = activeConfig ?: return "no config"
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE,
            EGL14.EGL_GREEN_SIZE,
            EGL14.EGL_BLUE_SIZE,
            EGL14.EGL_ALPHA_SIZE
        )
        val labels = arrayOf("R", "G", "B", "A")
        val parts = ArrayList<String>(4)
        for (i in attributes.indices) {
            val value = IntArray(1)
            if (EGL14.eglGetConfigAttrib(display, config, attributes[i], value, 0)) {
                parts += "${labels[i]}=${value[0]}"
            }
        }
        return if (parts.isEmpty()) "unknown" else parts.joinToString(" ")
    }

    /**
     * Moves the accumulated per-phase render costs into [into] (which must hold at least 6 longs,
     * filled as makeCurrent, setup, upload, draw, swap, call count) and resets them.
     */
    fun takeRenderBreakdown(into: LongArray) {
        into[0] = nsCurrent
        into[1] = nsSetup
        into[2] = nsUpload
        into[3] = nsDraw
        into[4] = nsSwap
        into[5] = renderCalls
        nsCurrent = 0L
        nsSetup = 0L
        nsUpload = 0L
        nsDraw = 0L
        nsSwap = 0L
        renderCalls = 0L
    }

    @Volatile private var primarySwapCount = 0L
    @Volatile private var mirrorSwapCount = 0L

    /**
     * Swaps since the previous call: [0] is the primary window surface (the encoder input while
     * an encode runs, the phone preview otherwise) and [1] is the mirror preview. [VideoFrameProcessor]
     * drains this once per timing window to report preview and encoder submission rates.
     */
    fun takeSwapCounts(): LongArray = longArrayOf(primarySwapCount, mirrorSwapCount)
        .also { primarySwapCount = 0L; mirrorSwapCount = 0L }

    /**
     * Makes this renderer's window surface current, unless it already is.
     *
     * Two renders run back to back per interpolated pair and both start here, so the second call
     * would otherwise re-bind a context/surface pair the first one just established. Asking EGL
     * first is the same idiom [VideoFrameProcessor.ensureEglContextCurrent] uses, and it stays
     * correct when the grabber has since bound its own surface because the query is live.
     * Returns false when EGL refuses, after logging the error.
     */
    private fun bindWindow(eglDisplay: EGLDisplay, eglSurface: EGLSurface, eglContext: EGLContext): Boolean {
        val tPhase = System.nanoTime()
        val alreadyCurrent = EGL14.eglGetCurrentContext() == eglContext &&
            EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == eglSurface
        if (!alreadyCurrent &&
            !EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        ) {
            Log.e(TAG, "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}")
            return false
        }
        nsCurrent += System.nanoTime() - tPhase
        return true
    }

    /**
     * Swaps the current window surface, stamping it with [outputTimestampNs] first.
     *
     * `eglPresentationTimeANDROID` is what hands the frame's presentation time to a Surface-input
     * MediaCodec. It must be called on the thread that owns the EGL context, immediately before
     * the swap, with the surface current - which is exactly where every render path ends up.
     */
    private fun swapBuffers(eglDisplay: EGLDisplay, eglSurface: EGLSurface, label: String): Boolean {
        val mirror = mirrorEglSurface
        if (mirror != null && mirrorSurface?.isValid == true) {
            ensureMirrorTexture()
        }
        if (mirror != null && mirrorSurface?.isValid == true && mirrorTexture != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mirrorTexture)
            if (mirrorTextureWidth != surfaceWidth || mirrorTextureHeight != surfaceHeight) {
                GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 0, 0, surfaceWidth, surfaceHeight, 0)
                mirrorTextureWidth = surfaceWidth
                mirrorTextureHeight = surfaceHeight
            } else {
                GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, surfaceWidth, surfaceHeight)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        val interval = remoteFrameIntervalNs
        val submitPrimary = interval <= 0L || outputTimestampNs == 0L ||
            nextRemotePresentationNs == Long.MIN_VALUE || outputTimestampNs >= nextRemotePresentationNs
        if (!submitPrimary) remoteDroppedFrameCount++
        var primarySuccess = true
        if (submitPrimary) {
            if (outputTimestampNs != 0L) EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, outputTimestampNs)
            val swapResult = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            val swapError = EGL14.eglGetError()
            if (!swapResult || swapError != EGL14.EGL_SUCCESS || VERBOSE_DIAGNOSTICS) {
                Log.d(TAG, "eglSwapBuffers ($label): result=$swapResult error=0x${swapError.toString(16)}")
            }
            if (!swapResult || swapError != EGL14.EGL_SUCCESS) {
                primarySuccess = false
            }
            if (interval > 0L && outputTimestampNs != 0L) {
                if (nextRemotePresentationNs == Long.MIN_VALUE) nextRemotePresentationNs = outputTimestampNs + interval
                else while (nextRemotePresentationNs <= outputTimestampNs) nextRemotePresentationNs += interval
            }
            if (primarySuccess) primarySwapCount++
        }

        // Always update the mirror (phone preview) regardless of remote frame rate cap.
        // The local preview must continue showing the latest processed frame even when
        // the remote encoder is rate-limited and dropping frames.
        var mirrorSuccess = true
        if (mirror != null && mirrorSurface?.isValid == true && mirrorTexture != 0 &&
            EGL14.eglMakeCurrent(eglDisplay, mirror, mirror, context)
        ) {
            GLES20.glViewport(0, 0, mirrorWidth, mirrorHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mirrorTexture)
            GLES20.glUniform1i(uTextureHandle, 0)
            val srcAspect = mirrorTextureWidth.toFloat() / mirrorTextureHeight.coerceAtLeast(1)
            val dstAspect = mirrorWidth.toFloat() / mirrorHeight.coerceAtLeast(1)
            val scaleX = if (srcAspect > dstAspect) 1f else srcAspect / dstAspect
            val scaleY = if (srcAspect > dstAspect) dstAspect / srcAspect else 1f
            GLES20.glUniform2f(uContentScaleHandle, scaleX, scaleY)
            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
            // Not texCoordBuffer: the mirror samples a framebuffer copy, whose row 0 is already
            // the bottom of the picture. Reusing the upload path's flipped V inverted the preview.
            mirrorTexCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
            GLES20.glVertexAttribPointer(aTextureCoordHandle, 4, GLES20.GL_FLOAT, false, 16, mirrorTexCoordBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            if (outputTimestampNs != 0L) EGLExt.eglPresentationTimeANDROID(eglDisplay, mirror, outputTimestampNs)
            val mirrorSwapResult = EGL14.eglSwapBuffers(eglDisplay, mirror)
            val mirrorSwapError = EGL14.eglGetError()
            if (!mirrorSwapResult || mirrorSwapError != EGL14.EGL_SUCCESS) {
                mirrorSuccess = false
            }
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, context)
            if (mirrorSuccess) mirrorSwapCount++
        }
        return primarySuccess && mirrorSuccess
    }

    private fun releaseMirrorSurface() {
        val eglDisplay = display
        val egl = mirrorEglSurface
        if (eglDisplay != null && egl != null && egl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, egl)
        }
        mirrorEglSurface = null
        mirrorSurface = null
        mirrorWidth = 0; mirrorHeight = 0
        mirrorTextureWidth = 0; mirrorTextureHeight = 0
    }

    private fun ensureMirrorTexture() {
        if (mirrorTexture != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        mirrorTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mirrorTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Uploads [buffer] (exactly [width] x [height] RGBA bytes, top row first) and presents it.
     * Must be called with the worker's EGL context current.
     *
     * [timestampNs] is the frame's presentation time; see [outputTimestampNs].
     */
    fun render(buffer: ByteBuffer, width: Int, height: Int, timestampNs: Long = 0L): Boolean {
        val eglDisplay = display
        val eglContext = context
        val eglSurface = windowSurface
        if (program == 0 || textureId == 0 || eglDisplay == null ||
            eglContext == null || eglSurface == null
        ) {
            Log.w(TAG, "render(): renderer not ready (program=$program texture=$textureId); frame dropped")
            return false
        }
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "render(): bad size ${width}x${height}; frame dropped")
            return false
        }

        if (!bindWindow(eglDisplay, eglSurface, eglContext)) {
            Log.w(TAG, "render(): bindWindow failed; frame dropped")
            return false
        }

        // DIAGNOSTICS: Log EGL state before rendering
        if (VERBOSE_DIAGNOSTICS) {
            val currentDisplay = EGL14.eglGetCurrentDisplay()
            val currentContext = EGL14.eglGetCurrentContext()
            val currentSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            Log.d(TAG, "RENDER EGL STATE: display=$currentDisplay context=$currentContext surface=$currentSurface")
        }

        var tPhase = System.nanoTime()
        if (surfaceWidth != width || surfaceHeight != height) {
            // The window surface keeps the size of the SurfaceView; the viewport is set from the
            // actual surface size so the frame is never stretched by a stale viewport.
            updateSurfaceSize()
        }
        nsCurrent += System.nanoTime() - tPhase

        if (VERBOSE_DIAGNOSTICS) {
            // DIAGNOSTICS: Calculate output buffer checksum
            val checksum = calculateChecksum(buffer, width, height)
            Log.d(TAG, "RENDER INPUT CHECKSUM: ${width}x$height checksum=$checksum")

            // DIAGNOSTICS: Query framebuffer binding and viewport before drawing
            val boundFbo = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFbo, 0)
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val currentProgram = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, currentProgram, 0)
            Log.d(TAG, "RENDER PRE-DRAW: boundFbo=${boundFbo[0]} viewport=${viewport.contentToString()} currentProgram=${currentProgram[0]} surfaceSize=${surfaceWidth}x$surfaceHeight")
        }

        tPhase = System.nanoTime()
        GLES20.glViewport(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureHandle, 0)

        // Pushed unconditionally rather than on change: contentScaleX/Y are shared with the warp
        // and the denoised present, so "unchanged since the last call" says nothing about whether
        // THIS program's uniform location holds the current value. After a surface resize that
        // difference is a stretched or invisible frame, and one glUniform2f is not worth it.
        updateContentScale(width, height, surfaceWidth, surfaceHeight)
        GLES20.glUniform2f(uContentScaleHandle, contentScaleX, contentScaleY)
        GLES20.glUniform1i(uIsHdrHandle, if (isHdr) 1 else 0)

        // The quad no longer covers the whole surface whenever the aspect ratios differ, so the
        // bars are painted black instead of leaving the previous frame's contents on screen.
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        nsSetup += System.nanoTime() - tPhase

        val requiredBytes = width.toLong() * height.toLong() * 4L
        if (requiredBytes > Int.MAX_VALUE) {
            Log.e(TAG, "render: dimensions ${width}x$height overflow Int")
            return false
        }
        buffer.position(0)
        buffer.limit(requiredBytes.toInt())
        tPhase = System.nanoTime()
        if (textureWidth != width || textureHeight != height) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
            textureWidth = width
            textureHeight = height
        } else {
            // Same shape as last frame: the storage already exists, so this is a plain
            // memcpy into it instead of letting the driver re-specify the texture.
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
        }
        nsUpload += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "Output blit failed with GL error 0x${error.toString(16)}")
            return false
        }

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        nsDraw += System.nanoTime() - tPhase

        // DIAGNOSTICS: Check eglSwapBuffers result and error. Successful swaps are silent: this
        // line fires twice per interpolated pair and is pure overhead at the frame rate we need.
        tPhase = System.nanoTime()
        outputTimestampNs = timestampNs
        val swapResult = swapBuffers(eglDisplay, eglSurface, "")
        if (!swapResult) {
            Log.w(TAG, "render(): eglSwapBuffers failed; frame may not be presented")
        }
        nsSwap += System.nanoTime() - tPhase
        renderCalls++
        return swapResult
    }

    /** Presents a retained GPU source texture without converting it through the RGBA8 analysis buffer. */
    fun renderTexture(sourceTexture: Int, width: Int, height: Int, timestampNs: Long = 0L): Boolean {
        val eglDisplay = display ?: return false
        val eglContext = context ?: return false
        val eglSurface = windowSurface ?: return false
        if (program == 0 || sourceTexture == 0 || width <= 0 || height <= 0 ||
            !bindWindow(eglDisplay, eglSurface, eglContext)
        ) {
            Log.w(TAG, "renderTexture(): renderer not ready (program=$program sourceTexture=$sourceTexture); frame dropped")
            return false
        }
        if (surfaceWidth != width || surfaceHeight != height) updateSurfaceSize()
        GLES20.glViewport(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glUniform1i(uTextureHandle, 0)
        updateContentScale(width, height, surfaceWidth, surfaceHeight)
        GLES20.glUniform2f(uContentScaleHandle, contentScaleX, contentScaleY)
        GLES20.glUniform1i(uIsHdrHandle, if (isHdr) 1 else 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val error = GLES20.glGetError()
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "renderTexture(): GL error 0x${error.toString(16)}; frame dropped")
            return false
        }
        outputTimestampNs = timestampNs
        val swapResult = swapBuffers(eglDisplay, eglSurface, "hdr-source")
        if (!swapResult) {
            Log.w(TAG, "renderTexture(): eglSwapBuffers failed; frame may not be presented")
            return false
        }
        renderCalls++
        return true
    }

    /**
     * Motion-compensated blend of [frame0] and [frame1] using the packed field in [motion], then
     * presents it. Buffer layout and calling-thread contract match [render].
     *
     * [frame0] and [frame1] are the raw source frames at [srcWidth] x [srcHeight]. [motion] holds
     * `ceil(targetWidth/step) * ceil(targetHeight/step) * 8` bytes from
     * `NativeEngine.computeMotionField`, step being `NativeEngine.motionFieldStep()`: a first half
     * of forward x, forward y, backward x, backward y as half-pixel vectors biased by +128, then
     * a second half of forward and backward cover/uncover masks. The expensive part of
     * interpolation - the per-pixel 4-tap resample of both frames - then happens once per output
     * fragment in the shader instead of once per output pixel on the CPU.
     *
     * Returns false when the warp program is unavailable or a size is unserviceable, in which case
     * the caller must fall back to `NativeEngine.interpolateFrameBuffers()`.
     */
    fun renderWarp(
        frame0: ByteBuffer,
        frame1: ByteBuffer,
        motion: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        timestep: Float,
        timestampNs: Long = 0L
    ): Boolean = renderWarp(
        frame0, frame1, motion, srcWidth, srcHeight, targetWidth, targetHeight,
        floatArrayOf(timestep), longArrayOf(timestampNs)
    )

    /**
     * Presents every timestep in [timesteps] in order from a single upload of the pair. A level
     * above 2x needs several interpolations of the same two frames, and re-uploading them per
     * timestep would pay the readback-sized transfer again for data that has not changed; only
     * the draw - one uniform and one swap per timestep - is repeated.
     *
     * [timestamps] is parallel to [timesteps]: the presentation time each interpolated frame
     * must carry, in nanoseconds. See [outputTimestampNs].
     */
    fun renderWarp(
        frame0: ByteBuffer,
        frame1: ByteBuffer,
        motion: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        timesteps: FloatArray,
        timestamps: LongArray = LongArray(0)
    ): Boolean {
        if (timesteps.isEmpty()) {
            Log.w(TAG, "renderWarp: empty timesteps array")
            return false
        }
        val eglDisplay = display
        val eglContext = context
        val eglSurface = windowSurface
        if (warpProgram == 0 || eglDisplay == null || eglContext == null || eglSurface == null) {
            Log.w(TAG, "renderWarp: warp program not ready or EGL invalid (program=$warpProgram)")
            return false
        }
        if (srcWidth <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            Log.w(TAG, "renderWarp: invalid dimensions src=${srcWidth}x$srcHeight target=${targetWidth}x$targetHeight")
            return false
        }
        val gridStep = NativeEngine.motionFieldStep()
        val gridW = (targetWidth + gridStep - 1) / gridStep
        val gridH = (targetHeight + gridStep - 1) / gridStep

        if (!bindWindow(eglDisplay, eglSurface, eglContext)) {
            Log.w(TAG, "renderWarp: bindWindow failed")
            return false
        }

        var tPhase = System.nanoTime()
        if (surfaceWidth != targetWidth || surfaceHeight != targetHeight) {
            updateSurfaceSize()
        }
        nsCurrent += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        if (!uploadRgba(warpTex0, frame0, srcWidth, srcHeight, warpTex0W, warpTex0H) ||
            !uploadRgba(warpTex1, frame1, srcWidth, srcHeight, warpTex1W, warpTex1H) ||
            !uploadRgba(warpMotionTex, motion, gridW, gridH, warpGridW, warpGridH) ||
            !uploadRgba(
                warpMaskTex, motion, gridW, gridH, warpGridW, warpGridH,
                gridW * gridH * 4
            )
        ) {
            Log.w(TAG, "renderWarp: texture upload failed")
            return false
        }
        warpTex0W = srcWidth
        warpTex0H = srcHeight
        warpTex1W = srcWidth
        warpTex1H = srcHeight
        warpGridW = gridW
        warpGridH = gridH
        warpGridStep = gridStep
        warpBlendMode = NativeEngine.motionFieldBlendMode()
        nsUpload += System.nanoTime() - tPhase

        val success = drawWarps(
            eglDisplay, eglSurface, warpTex0, warpTex1, srcWidth, srcHeight,
            targetWidth, targetHeight, gridW, gridH, timesteps
        )
        if (!success) {
            Log.w(TAG, "renderWarp: drawWarps failed")
        }
        return success
    }

    // ---------------------------------------------------------------------------------------------
    // Temporal denoiser
    // ---------------------------------------------------------------------------------------------

    /** True when [renderDenoise] compiled; false means [VideoFrameProcessor] keeps its own stage. */
    val isDenoiseInitialized: Boolean
        get() = denoiseProgram != 0

    /**
     * True when [denPair0] holds a real previous denoised frame, so [renderWarpFromDen] has both
     * sides of the pair. False on the first call after [resetDenoise] or a size change.
     */
    val hasDenoisePair: Boolean
        get() = denPair0 != 0 && denPair1 != 0

    /**
     * Forgets the history. Called on seek and on pipeline reset, where the frame that follows is
     * not temporally adjacent to anything the denoiser has already merged - continuing the
     * recursion across the cut is what turns a seek into a smear.
     */
    fun resetDenoise() {
        denHistoryTex = 0
        denPair0 = 0
        denPair1 = 0
    }

    /**
     * (Re)creates the two history targets and their framebuffer at [width] x [height]. A size
     * change invalidates the history, because the stored frames are the previous size.
     */
    private fun ensureDenoiseTarget(width: Int, height: Int): Boolean {
        if (denTexA == 0) {
            denCurrentTex = createWarpTexture()
            denTexA = createWarpTexture()
            denTexB = createWarpTexture()
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            denFbo = ids[0]
            if (denCurrentTex == 0 || denTexA == 0 || denTexB == 0 || denFbo == 0) {
                return false
            }
        }
        if (denTexW != width || denTexH != height) {
            for (tex in intArrayOf(denTexA, denTexB)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null as ByteBuffer?
                )
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            denTexW = width
            denTexH = height
            resetDenoise()
        }
        return true
    }

    /**
     * Merges [current] into the history from the previous call, using the flow in [motion], and
     * leaves the result ready for [presentDenoised] or [renderWarpFromDen]. Nothing is presented:
     * this pass writes to a framebuffer, so it costs no swap and can be followed by either.
     *
     * [current] is exactly [width] x [height] RGBA, top row first. [motion] is the packed field
     * from `NativeEngine.computeMotionField()` at `ceil(width/step) x ceil(height/step)`, where
     * step is `NativeEngine.motionFieldStep()` - 16 with MEMC or SVPlayer's overlap off, smaller
     * when overlap is on. It is laid out as [renderWarp] documents: vectors in the first half,
     * then the mask the denoiser reads from the second half as (cover mask, unused, blend weight,
     * noise floor).
     *
     * Returns false when the program, the EGL surface or the framebuffer is unusable, or the byte
     * counts overflow, in which case [hasDenoisePair] is left false and the caller must not warp
     * from these textures.
     */
    fun renderDenoise(current: ByteBuffer, motion: ByteBuffer, width: Int, height: Int): Boolean {
        val eglDisplay = display
        val eglContext = context
        val eglSurface = windowSurface
        denPair0 = 0
        denPair1 = 0
        if (denoiseProgram == 0 || eglDisplay == null || eglContext == null || eglSurface == null) {
            Log.w(TAG, "renderDenoise: denoise program not ready or EGL invalid (program=$denoiseProgram)")
            return false
        }
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "renderDenoise: invalid dimensions ${width}x$height")
            return false
        }
        if (!bindWindow(eglDisplay, eglSurface, eglContext)) {
            Log.w(TAG, "renderDenoise: bindWindow failed")
            return false
        }
        val gridStep = NativeEngine.motionFieldStep()
        val gridW = (width + gridStep - 1) / gridStep
        val gridH = (height + gridStep - 1) / gridStep
        if (!ensureDenoiseTarget(width, height)) {
            Log.w(TAG, "renderDenoise: ensureDenoiseTarget failed")
            return false
        }

        var tPhase = System.nanoTime()
        if (!uploadRgba(denCurrentTex, current, width, height, denCurrentW, denCurrentH) ||
            !uploadRgba(warpMotionTex, motion, gridW, gridH, warpGridW, warpGridH) ||
            !uploadRgba(
                warpMaskTex, motion, gridW, gridH, warpGridW, warpGridH,
                gridW * gridH * 4
            )
        ) {
            return false
        }
        denCurrentW = width
        denCurrentH = height
        warpGridW = gridW
        warpGridH = gridH
        warpGridStep = gridStep
        warpBlendMode = NativeEngine.motionFieldBlendMode()
        nsUpload += System.nanoTime() - tPhase

        // The history read and the history written are never the same texture, so there is no
        // feedback loop; on the first call there is no history at all and the current frame stands
        // in as the sampler, which the uHasHistory branch never reads.
        val dst = if (denHistoryTex == denTexA) denTexB else denTexA
        val src = denHistoryTex

        tPhase = System.nanoTime()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, denFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, dst, 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) !=
            GLES20.GL_FRAMEBUFFER_COMPLETE
        ) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            Log.e(TAG, "denoise framebuffer incomplete")
            return false
        }

        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(denoiseProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, denCurrentTex)
        GLES20.glUniform1i(denUCurrent, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (src != 0) src else denCurrentTex)
        GLES20.glUniform1i(denUHistory, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMotionTex)
        GLES20.glUniform1i(denUMotion, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMaskTex)
        GLES20.glUniform1i(denUMask, 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform2f(denUTargetSize, width.toFloat(), height.toFloat())
        GLES20.glUniform2f(
            denUMotionGrid, (gridW * gridStep).toFloat(), (gridH * gridStep).toFloat()
        )
        val motionOffset = motionOffsetFor(gridStep)
        GLES20.glUniform2f(denUMotionOffset, motionOffset, motionOffset)
        GLES20.glUniform1f(denUHasHistory, if (src != 0) 1.0f else 0.0f)
        GLES20.glUniform1f(denUStrength, denoiseStrength)
        GLES20.glUniform1i(denUIsHdr, if (isHdr) 1 else 0)
        // Always full frame: this pass writes a texture, it does not letterbox into a surface, so
        // it must not touch contentScaleX/Y - the present that follows shares those two.
        GLES20.glUniform2f(denUContentScale, 1.0f, 1.0f)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(denDPosition)
        GLES20.glVertexAttribPointer(denDPosition, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(denDTexCoord)
        GLES20.glVertexAttribPointer(denDTexCoord, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(denDPosition)
        GLES20.glDisableVertexAttribArray(denDTexCoord)

        val error = GLES20.glGetError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        nsDraw += System.nanoTime() - tPhase
        denoiseDrawCalls++

        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "denoise pass failed with GL error 0x${error.toString(16)}")
            return false
        }

        denPair0 = src
        denPair1 = dst
        denHistoryTex = dst
        return true
    }

    /**
     * Same blend as [renderWarp], but from the two textures [renderDenoise] produced instead of
     * from ByteBuffers, so the denoised pair does not have to round-trip through memory to reach
     * the surface. The frame upload is the part that disappears; the field is re-uploaded, since
     * it is a few kilobytes and keeping this function self-contained is worth more than saving it.
     *
     * Only valid after a [renderDenoise] that reported [hasDenoisePair]; on the first cycle after a
     * reset there is no denoised history and the caller must use [renderWarp] with the raw frames.
     * Returns false otherwise, so a wrong call degrades to the CPU warp rather than to a black
     * frame.
     */
    fun renderWarpFromDen(
        motion: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        timestep: Float,
        timestampNs: Long = 0L
    ): Boolean = renderWarpFromDen(
        motion, srcWidth, srcHeight, targetWidth, targetHeight, floatArrayOf(timestep),
        longArrayOf(timestampNs)
    )

    /** See [renderWarp]; the denoised pair is uploaded once and drawn once per timestep. */
    fun renderWarpFromDen(
        motion: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        timesteps: FloatArray,
        timestamps: LongArray = LongArray(0)
    ): Boolean {
        val frame0 = denPair0
        val frame1 = denPair1
        if (frame0 == 0 || frame1 == 0 || denTexW != srcWidth || denTexH != srcHeight) {
            return false
        }
        return renderWarpWithTextures(
            frame0, frame1, motion, srcWidth, srcHeight, targetWidth, targetHeight, timesteps
        )
    }

    /** Shared body of [renderWarp] and [renderWarpFromDen]; see [renderWarp] for the contract. */
    private fun renderWarpWithTextures(
        frame0: Int,
        frame1: Int,
        motion: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        timesteps: FloatArray,
        timestamps: LongArray = LongArray(0),
    ): Boolean {
        if (timesteps.isEmpty()) {
            return false
        }
        val eglDisplay = display
        val eglContext = context
        val eglSurface = windowSurface
        if (warpProgram == 0 || eglDisplay == null || eglContext == null || eglSurface == null) {
            return false
        }
        if (srcWidth <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return false
        }
        val gridStep = NativeEngine.motionFieldStep()
        val gridW = (targetWidth + gridStep - 1) / gridStep
        val gridH = (targetHeight + gridStep - 1) / gridStep

        if (!bindWindow(eglDisplay, eglSurface, eglContext)) {
            return false
        }

        var tPhase = System.nanoTime()
        if (surfaceWidth != targetWidth || surfaceHeight != targetHeight) {
            updateSurfaceSize()
        }
        nsCurrent += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        if (!uploadRgba(warpMotionTex, motion, gridW, gridH, warpGridW, warpGridH) ||
            !uploadRgba(
                warpMaskTex, motion, gridW, gridH, warpGridW, warpGridH,
                gridW * gridH * 4
            )
        ) {
            return false
        }
        warpGridW = gridW
        warpGridH = gridH
        warpGridStep = gridStep
        warpBlendMode = NativeEngine.motionFieldBlendMode()
        nsUpload += System.nanoTime() - tPhase

        return drawWarps(
            eglDisplay, eglSurface, frame0, frame1, srcWidth, srcHeight,
            targetWidth, targetHeight, gridW, gridH, timesteps, timestamps
        )
    }

    /** Motion-compensates retained FP16 source textures; only the compact native field is uploaded. */
    fun renderWarpTextures(
        frame0Texture: Int,
        frame1Texture: Int,
        motion: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        timesteps: FloatArray,
        timestamps: LongArray = LongArray(0),
    ): Boolean {
        if (frame0Texture == 0 || frame1Texture == 0 || timesteps.isEmpty()) return false
        val gridStep = NativeEngine.motionFieldStep()
        val gridW = (targetWidth + gridStep - 1) / gridStep
        val gridH = (targetHeight + gridStep - 1) / gridStep
        if (!renderWarpWithTextures(
                frame0Texture, frame1Texture, motion, srcWidth, srcHeight,
                targetWidth, targetHeight, timesteps, timestamps
            )
        ) return false
        return true
    }

    /**
     * Draws the pair once per timestep in [timesteps]. Each draw pushes its own uniform and swaps,
     * so the surface sees them in order; the textures are already bound from the upload.
     */
    private fun drawWarps(
        eglDisplay: EGLDisplay,
        eglSurface: EGLSurface,
        frame0: Int,
        frame1: Int,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        gridW: Int,
        gridH: Int,
        timesteps: FloatArray,
        timestamps: LongArray = LongArray(0)
    ): Boolean {
        if (timesteps.isEmpty()) return false
        if (!bindWindow(eglDisplay, eglSurface, context!!)) return false

        // One-time setup for all timesteps: bind textures, upload static uniforms
        GLES20.glViewport(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
        GLES20.glUseProgram(warpProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame0)
        GLES20.glUniform1i(warpUFrame0, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame1)
        GLES20.glUniform1i(warpUFrame1, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMotionTex)
        GLES20.glUniform1i(warpUMotion, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMaskTex)
        GLES20.glUniform1i(warpUMask, 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform2f(warpUTargetSize, targetWidth.toFloat(), targetHeight.toFloat())
        GLES20.glUniform2f(
            warpUMotionGrid, (gridW * warpGridStep).toFloat(), (gridH * warpGridStep).toFloat()
        )
        val motionOffset = motionOffsetFor(warpGridStep)
        GLES20.glUniform2f(warpUMotionOffset, motionOffset, motionOffset)
        GLES20.glUniform1i(warpUBlendMode, warpBlendMode)
        GLES20.glUniform1i(warpUIsHdr, if (isHdr) 1 else 0)

        updateContentScale(targetWidth, targetHeight, surfaceWidth, surfaceHeight)
        GLES20.glUniform2f(warpUContentScale, contentScaleX, contentScaleY)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(warpAPosition)
        GLES20.glVertexAttribPointer(warpAPosition, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(warpATexCoord)
        GLES20.glVertexAttribPointer(warpATexCoord, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer)

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // Per-timestep: only update timestep uniform, draw, swap
        for (index in timesteps.indices) {
            val timestep = timesteps[index]
            if (timestep < 0.0f || timestep > 1.0f) {
                GLES20.glDisableVertexAttribArray(warpAPosition)
                GLES20.glDisableVertexAttribArray(warpATexCoord)
                return false
            }
            if (timestamps.isNotEmpty() && index < timestamps.size) {
                outputTimestampNs = timestamps[index]
            }

            GLES20.glUniform1f(warpUTimestep, timestep)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "Warp blit failed with GL error 0x${error.toString(16)}")
            }

            swapBuffers(eglDisplay, eglSurface, "warp")
            renderCalls++
            warpDrawCalls++
        }

        GLES20.glDisableVertexAttribArray(warpAPosition)
        GLES20.glDisableVertexAttribArray(warpATexCoord)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return true
    }

    /**
     * Presents the history [renderDenoise] last wrote, scaled into the surface exactly as
     * [render] scales the frame it was handed. Exists so the denoise-only path can present without
     * a readback: the frame is already a texture.
     */
    fun presentDenoised(width: Int, height: Int, timestampNs: Long = 0L): Boolean {
        val eglDisplay = display
        val eglContext = context
        val eglSurface = windowSurface
        if (program == 0 || denPair1 == 0 || eglDisplay == null ||
            eglContext == null || eglSurface == null
        ) {
            Log.w(TAG, "presentDenoised: program or denPair1 not ready or EGL invalid")
            return false
        }
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "presentDenoised: invalid dimensions ${width}x$height")
            return false
        }
        if (!bindWindow(eglDisplay, eglSurface, eglContext)) {
            Log.w(TAG, "presentDenoised: bindWindow failed")
            return false
        }

        var tPhase = System.nanoTime()
        if (surfaceWidth != width || surfaceHeight != height) {
            updateSurfaceSize()
        }
        nsCurrent += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        GLES20.glViewport(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, denPair1)
        GLES20.glUniform1i(uTextureHandle, 0)

        updateContentScale(width, height, surfaceWidth, surfaceHeight)
        GLES20.glUniform2f(uContentScaleHandle, contentScaleX, contentScaleY)
        GLES20.glUniform1i(uIsHdrHandle, if (isHdr) 1 else 0)

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        nsSetup += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(
            aTextureCoordHandle, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "presentDenoised: GL error 0x${error.toString(16)}")
            return false
        }
        nsDraw += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        // Through swapBuffers, not a bare eglSwapBuffers: the denoised present must carry the
        // presentation timestamp (otherwise the encoder stamps presentationTimeUs = 0 and the muxer
        // falls back to arrival order), draw the mirror preview, honour the remote rate cap, and
        // count toward the preview/encoder swap rates.
        outputTimestampNs = timestampNs
        val swapResult = swapBuffers(eglDisplay, eglSurface, "denoised")
        if (!swapResult) {
            Log.w(TAG, "presentDenoised: eglSwapBuffers failed")
            return false
        }
        nsSwap += System.nanoTime() - tPhase
        renderCalls++
        return true
    }

    /**
     * The half of [renderWarp] that both callers share: bind the two frame textures, push the
     * field, blend at [timestep], present. Assumes the window surface is already current and the
     * textures have been uploaded, so it does no allocation and no readback.
     */
    private fun drawWarp(
        eglDisplay: EGLDisplay,
        eglSurface: EGLSurface,
        frame0: Int,
        frame1: Int,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        gridW: Int,
        gridH: Int,
        timestep: Float
    ): Boolean {
        var tPhase = System.nanoTime()
        GLES20.glViewport(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
        GLES20.glUseProgram(warpProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame0)
        GLES20.glUniform1i(warpUFrame0, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame1)
        GLES20.glUniform1i(warpUFrame1, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMotionTex)
        GLES20.glUniform1i(warpUMotion, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMaskTex)
        GLES20.glUniform1i(warpUMask, 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform2f(warpUTargetSize, targetWidth.toFloat(), targetHeight.toFloat())
        // The field covers ceil(size/gridStep) cells, so gridW * gridStep is the padded extent
        // that maps a processing pixel onto the vector straddling it, and motionOffsetFor() is
        // the half-pixel-vs-half-window correction WARP_FRAGMENT_SHADER documents.
        GLES20.glUniform2f(
            warpUMotionGrid, (gridW * warpGridStep).toFloat(), (gridH * warpGridStep).toFloat()
        )
        val motionOffset = motionOffsetFor(warpGridStep)
        GLES20.glUniform2f(warpUMotionOffset, motionOffset, motionOffset)
        GLES20.glUniform1i(warpUBlendMode, warpBlendMode)
        GLES20.glUniform1f(warpUTimestep, timestep)

        // Always uploaded, never gated on updateContentScale(): each program has its own uniform
        // location, so the warp program's copy starts at 0 and would collapse the quad to a point
        // on its first frame if only "changed" values were pushed.
        updateContentScale(targetWidth, targetHeight, surfaceWidth, surfaceHeight)
        GLES20.glUniform2f(warpUContentScale, contentScaleX, contentScaleY)

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        nsSetup += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(warpAPosition)
        GLES20.glVertexAttribPointer(warpAPosition, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(warpATexCoord)
        GLES20.glVertexAttribPointer(warpATexCoord, 4, GLES20.GL_FLOAT, false, 16, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "Warp blit failed with GL error 0x${error.toString(16)}")
            GLES20.glDisableVertexAttribArray(warpAPosition)
            GLES20.glDisableVertexAttribArray(warpATexCoord)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return false
        }
        GLES20.glDisableVertexAttribArray(warpAPosition)
        GLES20.glDisableVertexAttribArray(warpATexCoord)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        nsDraw += System.nanoTime() - tPhase

        tPhase = System.nanoTime()
        val swapResult = swapBuffers(eglDisplay, eglSurface, "warp")
        if (!swapResult) {
            Log.w(TAG, "drawWarp: eglSwapBuffers failed")
            return false
        }
        nsSwap += System.nanoTime() - tPhase
        renderCalls++
        warpDrawCalls++
        return true
    }

    /**
     * The `uMotionOffset` uniform: `step / 2 - 8`, half the grid pitch minus half the search
     * window. A vector always sits 8 px into its cell - that is the centre of the 16 px window the
     * search ran over - while a texture texel sits half a pitch in, so this is the correction that
     * makes `(p + offset) / (step * grid)` land on the same vector `mvGridAxis()` picks. It is
     * exactly 0 whenever the pitch is 16, which covers MEMC and SVPlayer with overlap off, so
     * those paths evaluate `p / uMotionGrid` exactly as before.
     */
    private fun motionOffsetFor(gridStep: Int): Float = gridStep / 2f - 8f

    /**
     * Uploads `width` x `height` RGBA from [buffer] into [tex], re-specifying the texture only
     * when the shape changed; otherwise the storage that already exists is filled in place.
     * Returns false only when the byte count cannot be expressed as an Int.
     */
    private fun uploadRgba(
        tex: Int,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        curWidth: Int,
        curHeight: Int,
        byteOffset: Int = 0
    ): Boolean {
        val bytes = width.toLong() * height.toLong() * 4L
        if (bytes > Int.MAX_VALUE) {
            Log.e(TAG, "uploadRgba: dimensions ${width}x$height overflow Int")
            return false
        }
        if (byteOffset < 0 || byteOffset.toLong() + bytes > buffer.capacity().toLong()) {
            Log.e(
                TAG,
                "uploadRgba: $byteOffset + $bytes exceeds buffer capacity ${buffer.capacity()}"
            )
            return false
        }
        // The packed field is two RGBA halves in one buffer (vectors, then occlusion masks), so
        // the second upload starts at the half boundary rather than at position 0.
        buffer.position(byteOffset)
        buffer.limit(byteOffset + bytes.toInt())
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (curWidth != width || curHeight != height) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
            )
        } else {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return true
    }

    /**
     * Calculates a cheap pixel checksum (sum of all RGBA values) to verify the buffer is not all-zero.
     * Does not modify the buffer position/limit.
     */
    private fun calculateChecksum(buffer: ByteBuffer, width: Int, height: Int): Long {
        val originalPosition = buffer.position()
        val originalLimit = buffer.limit()
        buffer.position(0)
        val pixelCount = width * height
        var sum: Long = 0
        // Sample every 16th pixel to keep it fast
        val step = 16
        for (i in 0 until pixelCount step step) {
            val offset = i * 4
            if (offset + 3 < buffer.capacity()) {
                sum += (buffer.get(offset).toInt() and 0xFF).toLong()
                sum += (buffer.get(offset + 1).toInt() and 0xFF).toLong()
                sum += (buffer.get(offset + 2).toInt() and 0xFF).toLong()
                sum += (buffer.get(offset + 3).toInt() and 0xFF).toLong()
            }
        }
        buffer.position(originalPosition)
        buffer.limit(originalLimit)
        return sum
    }

    /**
     * Recomputes [contentScaleX]/[contentScaleY] so a [srcWidth] x [srcHeight] frame fits a
     * [surfWidth] x [surfHeight] surface undistorted: the tighter of the two aspect ratios
     * governs, the surplus axis is shrunk and the freed margin is pillar- or letterboxed.
     *
     * Returns true only when the value changed, so the uniform upload can be skipped.
     */
    private fun updateContentScale(
        srcWidth: Int,
        srcHeight: Int,
        surfWidth: Int,
        surfHeight: Int
    ): Boolean {
        if (srcWidth <= 0 || srcHeight <= 0 || surfWidth <= 0 || surfHeight <= 0) {
            return false
        }
        val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()
        val surfAspect = surfWidth.toFloat() / surfHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (srcAspect > surfAspect) {
            scaleX = 1.0f
            scaleY = surfAspect / srcAspect
        } else {
            scaleX = srcAspect / surfAspect
            scaleY = 1.0f
        }
        if (scaleX == contentScaleX && scaleY == contentScaleY) {
            return false
        }
        contentScaleX = scaleX
        contentScaleY = scaleY
        return true
    }

    private fun updateSurfaceSize() {
        val eglDisplay = display ?: return
        val eglSurface = windowSurface ?: return
        val width = IntArray(1)
        val height = IntArray(1)
        if (EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, width, 0) &&
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, height, 0)
        ) {
            surfaceWidth = width[0]
            surfaceHeight = height[0]
        }
    }

    private fun releaseWindowSurface() {
        val eglDisplay = display
        val eglSurface = windowSurface
        if (eglDisplay != null && eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        windowSurface = null
        activeConfig = null
        outputSurface = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    /**
     * Releases every GL object this renderer created. The EGL context itself is owned by
     * [VideoFrameProcessor] and is deleted there while it is still current.
     */
    fun release() {
        releaseMirrorSurface()
        if (mirrorTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(mirrorTexture), 0)
            mirrorTexture = 0
        }
        releaseWindowSurface()
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        textureWidth = 0
        textureHeight = 0
        if (warpProgram != 0) {
            GLES20.glDeleteTextures(
                4, intArrayOf(warpTex0, warpTex1, warpMotionTex, warpMaskTex), 0
            )
            warpTex0 = 0
            warpTex1 = 0
            warpMotionTex = 0
            warpMaskTex = 0
            warpTex0W = 0
            warpTex0H = 0
            warpTex1W = 0
            warpTex1H = 0
            warpGridW = 0
            warpGridH = 0
            warpGridStep = 16
            warpBlendMode = 2
            GLES20.glDeleteProgram(warpProgram)
            warpProgram = 0
        }
        if (denoiseProgram != 0) {
            if (denFbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(denFbo), 0)
                denFbo = 0
            }
            if (denCurrentTex != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(denCurrentTex), 0)
                denCurrentTex = 0
            }
            denCurrentW = 0
            denCurrentH = 0
            if (denTexA != 0) {
                GLES20.glDeleteTextures(2, intArrayOf(denTexA, denTexB), 0)
                denTexA = 0
                denTexB = 0
            }
            denTexW = 0
            denTexH = 0
            resetDenoise()
            GLES20.glDeleteProgram(denoiseProgram)
            denoiseProgram = 0
        }
        denDPosition = -1
        denDTexCoord = -1
        denUContentScale = -1
        denUCurrent = -1
        denUHistory = -1
        denUMotion = -1
        denUMask = -1
        denUTargetSize = -1
        denUMotionGrid = -1
        denUMotionOffset = -1
        denUHasHistory = -1
        denUStrength = -1
        warpAPosition = -1
        warpATexCoord = -1
        warpUContentScale = -1
        warpUFrame0 = -1
        warpUFrame1 = -1
        warpUMotion = -1
        warpUMask = -1
        warpUTargetSize = -1
        warpUMotionGrid = -1
        warpUMotionOffset = -1
        warpUBlendMode = -1
        warpUTimestep = -1
        aPositionHandle = -1
        aTextureCoordHandle = -1
        uTextureHandle = -1
        display = null
        context = null
    }

    /** [compileShader] without the throw, for the optional warp program. Returns 0 on failure. */
    private fun tryCompileShader(type: Int, source: String): Int {
        return try {
            compileShader(type, source)
        } catch (t: Throwable) {
            Log.w(TAG, "shader compile failed; the CPU warp stays in use", t)
            0
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw IllegalStateException("glCreateShader failed for type $type")
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Failed to compile shader: $log")
        }
        return shader
    }
}
