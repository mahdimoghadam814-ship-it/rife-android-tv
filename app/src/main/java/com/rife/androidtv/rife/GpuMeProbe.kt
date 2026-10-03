package com.rife.androidtv.rife

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import com.rife.androidtv.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Measures whether moving motion estimation onto the GPU is viable on the device we are running on.
 *
 * The CPU motion estimator owns 70-75% of a MEMC cycle on the TV box (82-105 ms of a 117-142 ms
 * total against a 41.6 ms budget), so no amount of CPU tuning reaches the budget. Running ME on the
 * GPU removes that cost and, because the frames then never leave the GPU, also removes the
 * `glReadPixels` + `glTexImage2D` round trip the pipeline pays to get them back (a further ~24 ms).
 *
 * Whether the GPU can carry ME is a question about integer throughput, which version strings do not
 * answer, so this probes the live context directly:
 *
 *  1. capability: GL version, renderer, compute limits, and whether a compute shader compiles and
 *     a dispatch with a read-back actually completes;
 *  2. throughput: texture fetches/s (the cost of staging padded search windows) and float ops/s
 *     (which dominates - the search is ~546 M scalar operations per frame pair against only ~13 M
 *     global loads once each block stages its window in shared memory);
 *  3. the estimated cost of the full hierarchical bidirectional search, reported against a 25 ms
 *     ceiling (the 41.6 ms cycle budget less the ~24 ms the GPU-resident path recovers).
 *
 * Debug builds only, at most once per process, and never allowed to fail the pipeline: everything
 * sits behind a try/catch so a driver that dislikes one of the probes degrades to a log line.
 */
internal object GpuMeProbe {

    private const val TAG = "GpuMeProbe"

    /** Offscreen target for the throughput passes; the per-fragment loop carries the work. */
    private const val BENCH_W = 480
    private const val BENCH_H = 270

    /** Loop iterations per fragment. Injected as a literal so the loop bound stays constant. */
    private const val BENCH_ITERS = 256

    /** One warm-up pass then one sample, best of both: cheaper than a full median. */
    private const val BENCH_RUNS = 2

    /** Texture fetches the throughput shader performs per fragment per iteration. */
    private const val FETCHES_PER_ITER = 2

    /** Float ops the ALU shader performs per iteration (fract, muls, adds, abs). */
    private const val ALU_OPS_PER_ITER = 10

    /** Scalar operations per block-pixel SAD evaluation: subtract, absolute, accumulate. */
    private const val OPS_PER_PIXEL_EVAL = 3

    /**
     * Hierarchical bidirectional search for a 1920x960 source: 91 M block-pixel SAD evaluations per
     * direction across the three pyramid levels, doubled for both directions.
     */
    private const val ME_PIXEL_EVALS = 182_000_000L

    /**
     * Global loads when each block stages its padded search window in shared memory once instead of
     * fetching it once per candidate: 656 per fine block (400 window + 256 reference), 1280 per
     * coarse block (1024 + 256), summed over all three levels and both directions.
     */
    private const val ME_GLOBAL_LOADS = 13_000_000L

    /** Barrier, dispatch and pyramid-build overhead assumed on top of raw throughput. */
    private const val OVERHEAD_FACTOR = 1.30

    /**
     * Ceiling for the whole search: the 41.6 ms cycle budget less the ~24 ms that keeping frames
     * resident on the GPU recovers from `glReadPixels` plus `glTexImage2D`.
     */
    private const val BUDGET_MS = 25.0

    /** Elements the compute probe writes and reads back: 10, 20, 30, 40. */
    private const val COMPUTE_PROBE_ELEMENTS = 4

    /** Square work-group edge for the compute throughput bench; 64 invocations per group. */
    private const val LOCAL_SIZE = 8

    private var attempted = false

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1.0f, -1.0f, 0.0f, 1.0f, -1.0f, 0.0f,
                    -1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f
                )
            )
            position(0)
        }

    private const val BENCH_VERT = """
        attribute vec4 aPosition;
        void main() {
            gl_Position = aPosition;
        }
    """

    fun run() {
        if (!BuildConfig.DEBUG || attempted) {
            return
        }
        attempted = true
        try {
            val es = logCapabilities()
            val compute = probeCompute(es)
            Log.i(TAG, "compute=${if (compute) "YES" else "NO"}")
            val gtex = bench("fetch") { measureFetchThroughput() }
            val fragmentGops = bench("alu-fragment") { measureAluThroughput() }
            val computeGops = bench("alu-compute") { measureComputeAluThroughput() }
            val gops = if (computeGops > 0.0) computeGops else fragmentGops
            val used = if (computeGops > 0.0) "compute" else "fragment"
            Log.i(
                TAG,
                ("throughput bench=$used gtex=%.2f Gtex/s gops=%.2f Gops/s " +
                    "fragmentGops=%.2f (%d fragments x %d iters, %d fetches, %d alu ops)")
                    .format(
                        gtex,
                        gops,
                        fragmentGops,
                        BENCH_W.toLong() * BENCH_H,
                        BENCH_ITERS,
                        BENCH_W.toLong() * BENCH_H * BENCH_ITERS * FETCHES_PER_ITER,
                        BENCH_W.toLong() * BENCH_H * BENCH_ITERS * ALU_OPS_PER_ITER
                    )
            )
            reportEstimate(gtex, gops, used)
        } catch (t: Throwable) {
            Log.w(TAG, "GPU ME bench: unavailable (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    private fun bench(name: String, block: () -> Double): Double = try {
        block()
    } catch (t: Throwable) {
        Log.i(TAG, "$name bench: unavailable (${t.javaClass.simpleName}: ${t.message})")
        0.0
    }

    /** Logs the context identity and the compute limits the GPU ME design depends on. */
    private fun logCapabilities(): Float {
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "unknown"
        val glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "unknown"
        val es = parseEsVersion(version)
        Log.i(
            TAG,
            "GL ES=$es renderer=$renderer vendor=$vendor glsl=$glsl " +
                "maxTexture=${queryInt(GLES20.GL_MAX_TEXTURE_SIZE)}"
        )
        if (es >= 3.1f) {
            Log.i(
                TAG,
                "GL compute: workGroupMax=${query3(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE)} " +
                    "workGroupCount=${query3(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT)} " +
                    "invocations=${queryInt(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS)} " +
                    "ssboBindings=${queryInt(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)} " +
                    "sharedMemory=${queryInt(GLES31.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE)}"
            )
        }
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        Log.i(
            TAG,
            "GL extensions: count=${extensions.split(' ').size} " +
                "timerQuery=${extensions.contains("GL_EXT_disjoint_timer_query")} " +
                "debug=${extensions.contains("GL_KHR_debug")} " +
                "oesExternal=${extensions.contains("GL_OES_EGL_image_external")}"
        )
        return es
    }

    /**
     * Compiles a compute shader, fills an SSBO from it and reads the result back. A context that
     * reports 3.1 but does not actually run compute is exactly the case this has to catch.
     */
    private fun probeCompute(es: Float): Boolean {
        if (es < 3.1f) {
            Log.i(TAG, "compute probe: skipped, GLES $es does not report 3.1")
            return false
        }
        var program = 0
        val buffers = IntArray(1)
        try {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // no-op: drain errors left by an earlier stage
            }
            val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            if (shader == 0) {
                Log.i(TAG, "compute probe: glCreateShader failed, gl=0x${GLES20.glGetError().toString(16)}")
                return false
            }
            GLES20.glShaderSource(shader, COMPUTE_PROBE_SRC)
            GLES20.glCompileShader(shader)
            if (!shaderCompiled(shader)) {
                val log = GLES20.glGetShaderInfoLog(shader) ?: ""
                GLES20.glDeleteShader(shader)
                Log.i(TAG, "compute probe: compile failed: $log")
                return false
            }
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(shader)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(program) ?: ""
                GLES20.glDeleteProgram(program)
                program = 0
                Log.i(TAG, "compute probe: link failed: $log")
                return false
            }

            GLES20.glGenBuffers(1, buffers, 0)
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
            GLES20.glBufferData(
                GLES31.GL_SHADER_STORAGE_BUFFER, COMPUTE_PROBE_ELEMENTS * 4, null,
                GLES30.GL_DYNAMIC_COPY
            )
            bindSsboEverywhere(buffers[0])
            GLES20.glUseProgram(program)
            GLES31.glDispatchCompute(1, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            GLES20.glFinish()

            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                Log.i(TAG, "compute probe: gl error 0x${error.toString(16)}")
                return false
            }

            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
            val mapped = GLES30.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER, 0, COMPUTE_PROBE_ELEMENTS * 4,
                GLES30.GL_MAP_READ_BIT
            )
            var first = -1
            var third = -1
            if (mapped != null && mapped.remaining() >= COMPUTE_PROBE_ELEMENTS * 4) {
                val bytes = mapped as ByteBuffer
                first = bytes.getInt(0)
                third = bytes.getInt(COMPUTE_PROBE_ELEMENTS * 4 - 4)
                GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            } else {
                Log.i(TAG, "compute probe: map failed, mapped=${mapped != null}")
            }
            if (first != 10 || third != 40) {
                Log.i(TAG, "compute probe: unexpected result first=$first third=$third")
                return false
            }
            return true
        } finally {
            GLES20.glUseProgram(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            clearSsboBindings()
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
            if (buffers[0] != 0) {
                GLES20.glDeleteBuffers(1, buffers, 0)
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
            }
        }
    }

    /**
     * The buffer block carries no explicit binding, so its index is whatever the driver assigned.
     * Occupying every legal binding point removes that as a variable; the bindings are cleared
     * again by the caller's `finally`.
     */
    private fun bindSsboEverywhere(buffer: Int) {
        for (index in 0 until ssboBindingCount()) {
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, index, buffer)
        }
    }

    private fun clearSsboBindings() {
        for (index in 0 until ssboBindingCount()) {
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, index, 0)
        }
    }

    private fun ssboBindingCount(): Int =
        queryInt(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS).coerceIn(1, 16)

    private fun measureFetchThroughput(): Double {
        val fetches = BENCH_W.toLong() * BENCH_H * BENCH_ITERS * FETCHES_PER_ITER
        val ns = timeFragmentPass(texFragmentSource(), withSourceTextures = true)
        return fetches.toDouble() / (ns / 1_000_000_000.0)
    }

    private fun measureAluThroughput(): Double {
        val ops = BENCH_W.toLong() * BENCH_H * BENCH_ITERS * ALU_OPS_PER_ITER
        val ns = timeFragmentPass(ALU_FRAGMENT, withSourceTextures = false)
        return ops.toDouble() / (ns / 1_000_000_000.0)
    }

    /**
     * Same recurrence as [measureAluThroughput] but dispatched as compute, because that is the
     * shape Build 2 would actually use. The result is read back so a driver cannot discard the
     * loop as dead work and report a misleadingly fast pass. Returns 0 when the dispatch cannot be
     * established, having logged why.
     */
    private fun measureComputeAluThroughput(): Double {
        val work = BENCH_W.toLong() * BENCH_H * BENCH_ITERS
        var program = 0
        val buffers = IntArray(1)
        try {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // no-op: drain errors left by an earlier stage
            }
            val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            if (shader == 0) {
                Log.i(TAG, "compute alu bench: glCreateShader failed, gl=0x${GLES20.glGetError().toString(16)}")
                return 0.0
            }
            GLES20.glShaderSource(shader, COMPUTE_ALU_SRC)
            GLES20.glCompileShader(shader)
            if (!shaderCompiled(shader)) {
                val log = GLES20.glGetShaderInfoLog(shader) ?: ""
                GLES20.glDeleteShader(shader)
                Log.i(TAG, "compute alu bench: compile failed: $log")
                return 0.0
            }
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(shader)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(program) ?: ""
                Log.i(TAG, "compute alu bench: link failed: $log")
                return 0.0
            }

            GLES20.glGenBuffers(1, buffers, 0)
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
            GLES20.glBufferData(
                GLES31.GL_SHADER_STORAGE_BUFFER, BENCH_W * BENCH_H * 4, null,
                GLES30.GL_DYNAMIC_COPY
            )
            bindSsboEverywhere(buffers[0])
            GLES20.glUseProgram(program)

            GLES20.glFinish()
            var best = Long.MAX_VALUE
            repeat(BENCH_RUNS) {
                GLES20.glFinish()
                val start = System.nanoTime()
                GLES31.glDispatchCompute(
                    (BENCH_W + LOCAL_SIZE - 1) / LOCAL_SIZE,
                    (BENCH_H + LOCAL_SIZE - 1) / LOCAL_SIZE,
                    1
                )
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                GLES20.glFinish()
                val elapsed = System.nanoTime() - start
                if (elapsed in 1 until best) {
                    best = elapsed
                }
            }

            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                Log.i(TAG, "compute alu bench: gl error 0x${error.toString(16)}")
                return 0.0
            }
            if (best == Long.MAX_VALUE) {
                Log.i(TAG, "compute alu bench: no sample")
                return 0.0
            }

            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
            val mapped = GLES30.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, GLES30.GL_MAP_READ_BIT
            )
            val sample = if (mapped != null && mapped.remaining() >= 4) {
                val value = (mapped as ByteBuffer).getFloat(0)
                GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                value
            } else {
                -1.0f
            }
            if (!(sample > 0.0f)) {
                Log.i(TAG, "compute alu bench: output not written, sample=$sample")
                return 0.0
            }
            return (work * ALU_OPS_PER_ITER).toDouble() / (best / 1_000_000_000.0)
        } finally {
            GLES20.glUseProgram(0)
            clearSsboBindings()
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
            if (buffers[0] != 0) {
                GLES20.glDeleteBuffers(1, buffers, 0)
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
            }
        }
    }

    private fun reportEstimate(gtex: Double, gops: Double, bench: String) {
        if (gtex <= 0.0 || gops <= 0.0) {
            Log.i(TAG, "GPU ME bench: unavailable (bench=$bench gtex=$gtex gops=$gops)")
            return
        }
        val loadMs = ME_GLOBAL_LOADS / gtex * 1000.0
        val aluMs = ME_PIXEL_EVALS * OPS_PER_PIXEL_EVAL / gops * 1000.0
        val totalMs = (loadMs + aluMs) * OVERHEAD_FACTOR
        val verdict = if (totalMs <= BUDGET_MS) "PASS" else "FAIL"
        Log.i(
            TAG,
            ("GPU ME estimate bench=$bench: load=%.1fms alu=%.1fms bidirectional=%.1fms " +
                "vs %.0fms budget -> %s")
                .format(loadMs, aluMs, totalMs, BUDGET_MS, verdict)
        )
    }

    /**
     * Renders [fragmentSource] into a private FBO and returns the fastest of [BENCH_RUNS] timed
     * passes. The framebuffer, viewport, active texture unit and program binding are restored before
     * returning so the caller's pipeline state is untouched.
     */
    private fun timeFragmentPass(fragmentSource: String, withSourceTextures: Boolean): Long {
        val previousFbo = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFbo, 0)
        val previousViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)

        var program = 0
        val textures = IntArray(3)
        val fbo = IntArray(1)
        try {
            // Drain anything a previous stage left behind so the check at the end is ours.
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // no-op
            }
            val vertexShader = compile(GLES20.GL_VERTEX_SHADER, BENCH_VERT)
            val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] != GLES20.GL_TRUE) {
                throw IllegalStateException(
                    "bench program link failed: ${GLES20.glGetProgramInfoLog(program)}"
                )
            }

            GLES20.glGenTextures(3, textures, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (withSourceTextures) {
                val noise = ByteBuffer.allocateDirect(BENCH_W * BENCH_H * 4)
                var state = 0x12345678
                for (i in 0 until BENCH_W * BENCH_H * 4) {
                    state = state * 1103515245 + 12345
                    noise.put(((state ushr 16) and 0xff).toByte())
                }
                noise.position(0)
                for (i in 0..1) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    noise.position(0)
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BENCH_W, BENCH_H, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, noise
                    )
                }
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BENCH_W, BENCH_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                textures[2], 0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw IllegalStateException("bench framebuffer incomplete: 0x${status.toString(16)}")
            }

            GLES20.glViewport(0, 0, BENCH_W, BENCH_H)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uA"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uB"), 1)

            val position = GLES20.glGetAttribLocation(program, "aPosition")
            if (position < 0) {
                throw IllegalStateException("bench program is missing aPosition")
            }
            GLES20.glEnableVertexAttribArray(position)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

            GLES20.glFinish()
            var best = Long.MAX_VALUE
            repeat(BENCH_RUNS) {
                GLES20.glFinish()
                val start = System.nanoTime()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glFinish()
                val elapsed = System.nanoTime() - start
                if (elapsed in 1 until best) {
                    best = elapsed
                }
            }
            GLES20.glDisableVertexAttribArray(position)

            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw IllegalStateException("bench draw failed with GL error 0x${error.toString(16)}")
            }
            if (best == Long.MAX_VALUE) {
                throw IllegalStateException("bench produced no sample")
            }
            return best
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFbo[0])
            GLES20.glViewport(
                previousViewport[0], previousViewport[1],
                previousViewport[2], previousViewport[3]
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glUseProgram(0)
            if (fbo[0] != 0) {
                GLES20.glDeleteFramebuffers(1, fbo, 0)
            }
            if (textures[0] != 0) {
                GLES20.glDeleteTextures(3, textures, 0)
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
            }
        }
    }

    /**
     * Two independent `fract` recurrences per iteration feeding an accumulator, which is what a
     * block-pixel SAD inner loop looks like once the search window is already in shared memory:
     * arithmetic with no memory traffic for the compiler to hoist out of the loop.
     */
    private fun texFragmentSource(): String = """
        precision highp float;
        uniform sampler2D uA;
        uniform sampler2D uB;
        void main() {
            vec2 base = floor(gl_FragCoord.xy);
            vec2 texel = vec2(${1.0 / BENCH_W}, ${1.0 / BENCH_H});
            float acc = 0.0;
            for (int i = 0; i < $BENCH_ITERS; i++) {
                float fi = float(i);
                vec2 o = vec2(mod(fi, 16.0) - 7.5, floor(fi / 16.0) - 7.5);
                acc += texture2D(uA, (base + o) * texel).r;
                acc += texture2D(uB, (base - o) * texel).r;
            }
            gl_FragColor = vec4(acc * 0.00001, 0.0, 0.0, 1.0);
        }
    """

    private val ALU_FRAGMENT: String = """
        precision highp float;
        void main() {
            float a = fract(gl_FragCoord.x * 0.017);
            float b = fract(gl_FragCoord.y * 0.031);
            float acc = 0.0;
            for (int i = 0; i < $BENCH_ITERS; i++) {
                a = fract(a * 1.001 + 0.037 + float(i) * 0.0001);
                b = fract(b * 1.003 + 0.071 + float(i) * 0.0002);
                acc += abs(a - b);
            }
            gl_FragColor = vec4(acc * 0.01, 0.0, 0.0, 1.0);
        }
    """

    private val COMPUTE_PROBE_SRC: String =
        "#version 310 es\n" +
            "layout(local_size_x = $COMPUTE_PROBE_ELEMENTS, local_size_y = 1, local_size_z = 1) in;\n" +
            "layout(std430) buffer ProbeBuf { int values[]; } probeOut;\n" +
            "void main() {\n" +
            "    uint i = gl_GlobalInvocationID.x;\n" +
            "    if (i < ${COMPUTE_PROBE_ELEMENTS}u) {\n" +
            "        probeOut.values[i] = int(i) * 10 + 10;\n" +
            "    }\n" +
            "}\n"

    private val COMPUTE_ALU_SRC: String =
        "#version 310 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "layout(local_size_x = $LOCAL_SIZE, local_size_y = $LOCAL_SIZE, local_size_z = 1) in;\n" +
            "layout(std430) buffer AluBuf { float values[]; } aluOut;\n" +
            "void main() {\n" +
            "    uvec2 id = gl_GlobalInvocationID.xy;\n" +
            "    if (id.x >= ${BENCH_W}u || id.y >= ${BENCH_H}u) {\n" +
            "        return;\n" +
            "    }\n" +
            "    float a = fract(float(id.x) * 0.017);\n" +
            "    float b = fract(float(id.y) * 0.031);\n" +
            "    float acc = 0.0;\n" +
            "    for (int i = 0; i < $BENCH_ITERS; i++) {\n" +
            "        a = fract(a * 1.001 + 0.037 + float(i) * 0.0001);\n" +
            "        b = fract(b * 1.003 + 0.071 + float(i) * 0.0002);\n" +
            "        acc += abs(a - b);\n" +
            "    }\n" +
            "    aluOut.values[id.y * ${BENCH_W}u + id.x] = acc * 0.01;\n" +
            "}\n"

    private fun parseEsVersion(version: String): Float {
        val match = Regex("""(\d+)\.(\d+)""").find(version) ?: return 0.0f
        val major = match.groupValues[1].toFloatOrNull() ?: 0.0f
        val minor = match.groupValues[2].toFloatOrNull() ?: 0.0f
        return major + minor / 10.0f
    }

    private fun queryInt(pname: Int): Int {
        val out = IntArray(1)
        GLES20.glGetIntegerv(pname, out, 0)
        return out[0]
    }

    /**
     * Work-group size and count are indexed per dimension, so they answer to `glGetIntegeri_v`;
     * `glGetIntegerv` on them raises GL_INVALID_ENUM and leaves the caller's zeros untouched.
     */
    private fun query3(pname: Int): String {
        val out = IntArray(3)
        for (dimension in 0 until 3) {
            val single = IntArray(1)
            GLES30.glGetIntegeri_v(pname, dimension, single, 0)
            out[dimension] = single[0]
        }
        val error = GLES20.glGetError()
        return if (error != GLES20.GL_NO_ERROR) {
            "err=0x${error.toString(16)}"
        } else {
            "${out[0]}x${out[1]}x${out[2]}"
        }
    }

    private fun shaderCompiled(shader: Int): Boolean {
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        return status[0] == GLES20.GL_TRUE
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw IllegalStateException("glCreateShader failed for type $type")
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        if (!shaderCompiled(shader)) {
            val log = GLES20.glGetShaderInfoLog(shader) ?: ""
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }
}
