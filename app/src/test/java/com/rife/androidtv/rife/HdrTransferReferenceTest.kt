package com.rife.androidtv.rife

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Numerical reference tests for the colour math embedded in the GLSL shaders, and a source scan
 * that pins the constants those shaders were compiled with.
 *
 * The constants are the SMPTE ST 2084:2014 set. In particular `m1 = 2610/4096 * 1/4 =
 * 0.1593017578125` - dropping the `/4` (m1 = 0.6372) made the EOTF return ~31x the true
 * luminance at 100 nits and ~5000x at shadow levels, so every tone-mapped frame mapped to
 * white: the severely overexposed, washed-out HDR-to-SDR picture this branch exists to fix.
 * Each anchor below was computed from the published standard, and the guards fail loudly if
 * the decode is inflated again.
 *
 * The GLSL is mirrored here in Double; the shaders run in Float, so tolerances are 1% relative
 * - far tighter than the difference the bugs make.
 */
class HdrTransferReferenceTest {

    // --- SMPTE ST 2084:2014 constants (must match every shader copy) ---

    private val m1 = 2610.0 / 4096.0 / 4.0
    private val m2 = 2523.0 / 4096.0 * 128.0
    private val c1 = 3424.0 / 4096.0
    private val c2 = 2413.0 / 4096.0 * 32.0
    private val c3 = 2392.0 / 4096.0 * 32.0

    /** PQ EOTF: code value -> linear light, 0..1 = 0..10000 nits. Mirrors pqToLinear(). */
    private fun pqEotf(code: Double): Double {
        val cp = code.coerceAtLeast(0.0).pow(1.0 / m2)
        val num = (cp - c1).coerceAtLeast(0.0)
        val den = c2 - c3 * cp
        return (num / den).pow(1.0 / m1)
    }

    /** PQ inverse-EOTF: linear light -> code value. Mirrors linearToPQ(). */
    private fun pqInverseEotf(linear: Double): Double {
        val x = linear.coerceAtLeast(0.0).pow(m1)
        return ((c1 + c2 * x) / (1.0 + c3 * x)).pow(m2)
    }

    /** HLG inverse OETF (ARIB STD-B67), code -> scene light. Mirrors hlgToScene(). */
    private fun hlgInverseOetf(code: Double): Double {
        val a = 0.17883277
        val b = 1.0 - 4.0 * a
        val c = 0.55991073
        val e = code.coerceAtLeast(0.0)
        return if (e < 0.5) {
            e * e / 3.0
        } else {
            (kotlin.math.exp((e - c) / a) + b) / 12.0
        }
    }

    private fun hable(x: Double): Double {
        val a = 0.22
        val b = 0.30
        val cc = 0.10
        val d = 0.20
        val e = 0.01
        val f = 0.30
        return ((x * (a * x + cc * b) + d * e) / (x * (a * x + b) + d * f)) - e / f
    }

    /** sRGB OETF, as the capture shader encodes its output. */
    private fun srgbEncode(v: Double): Double =
        if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055

    /**
     * The capture shader's tail for a neutral grey: decode to 100-nit-relative light, Hable
     * roll-off normalised at W = 10 (1000 nits), clamp, sRGB-encode.
     */
    private fun captureToneMapPq(code: Double): Double {
        val lin100 = pqEotf(code) * 100.0
        val mappedY = hable(lin100) / hable(10.0)
        val sdr = if (lin100 > 1e-5) mappedY.coerceIn(0.0, 1.0) else 0.0
        return srgbEncode(sdr)
    }

    // --- PQ decoding ---

    @Test
    fun pqConstantsMatchSt2084() {
        assertEquals(0.1593017578125, m1, 0.0)
        assertEquals(78.84375, m2, 0.0)
        assertEquals(0.8359375, c1, 0.0)
        assertEquals(18.8515625, c2, 0.0)
        assertEquals(18.6875, c3, 0.0)
    }

    @Test
    fun pqEotfDecodesPublishedReferenceLuminances() {
        // Published anchors: code 0.51 ~ 100 nits, 0.75 ~ 1000 nits, 0.1513 ~ 1 nit, 1.0 = 10000.
        assertEquals("100 nits", 0.010193, pqEotf(0.51), 0.010193 * 0.01)
        assertEquals("1000 nits", 0.098338, pqEotf(0.75), 0.098338 * 0.01)
        assertEquals("1 nit", 0.000103, pqEotf(0.1513), 0.000103 * 0.02)
        assertEquals("peak", 1.0, pqEotf(1.0), 1e-9)
    }

    @Test
    fun pqEotfNeverInflatesLuminance() {
        // The overexposure regression: with m1 missing its /4, pqEotf(0.51) returned ~0.318
        // (3177 nits) and pqEotf(0.0625) ~0.056 (563 nits instead of 0.1), sending every
        // tone-mapped frame to white. A 100-nit code must decode to ~100 nits, and a shadow
        // code must stay a shadow.
        assertTrue("100 nits inflated: ${pqEotf(0.51)}", pqEotf(0.51) < 0.02)
        assertTrue("shadow inflated: ${pqEotf(0.0625)}", pqEotf(0.0625) < 0.0002)
        assertTrue("1 nit inflated: ${pqEotf(0.1513)}", pqEotf(0.1513) < 0.001)
    }

    @Test
    fun pqInverseEotfMatchesPublishedCodeValues() {
        // oetf(0.01) = 0.508078 (100 nits ~ code 0.51), oetf(0.1) = 0.751827 (1000 nits ~ 0.75).
        assertEquals("100 nits", 0.508078, pqInverseEotf(0.01), 0.508078 * 0.01)
        assertEquals("1000 nits", 0.751827, pqInverseEotf(0.1), 0.751827 * 0.01)
        assertEquals("peak", 1.0, pqInverseEotf(1.0), 1e-6)
    }

    @Test
    fun pqRoundTripsBothDirections() {
        for (code in listOf(0.01, 0.05, 0.15, 0.26, 0.51, 0.75, 0.9)) {
            assertEquals("code $code", code, pqEotf(pqInverseEotf(code)), code * 0.01)
        }
        for (nits in listOf(0.01, 0.1, 1.0, 100.0, 1000.0)) {
            val linear = nits / 10000.0
            assertEquals("linear $linear", linear, pqInverseEotf(pqEotf(linear)), linear * 0.01)
        }
    }

    // --- HLG decoding ---

    @Test
    fun hlgInverseOetfMatchesBt2100Anchors() {
        assertEquals(0.0, hlgInverseOetf(0.0), 1e-12)
        // The two branches meet at 0.5: (0.5)^2/3 == (exp((0.5-c)/a)+b)/12 ~ 1/12.
        assertEquals("branch continuity at 0.5", 1.0 / 12.0, hlgInverseOetf(0.5), 1e-5)
        assertEquals("lower branch below 0.5", 0.499 * 0.499 / 3.0, hlgInverseOetf(0.499), 1e-9)
        assertEquals("peak", 1.0, hlgInverseOetf(1.0), 1e-4)
    }

    // --- Reference white and highlight compression through the capture tail ---

    @Test
    fun captureToneMapLandsReferenceWhiteBelowFullWhite() {
        // 100-nit diffuse white (code ~0.508) must sit at a natural SDR level - not clipped to
        // white (overexposure) and not crushed. Anchor: 0.7139 from the reference chain.
        val white = captureToneMapPq(0.508)
        assertEquals("100-nit white", 0.7139, white, 0.01)
        assertTrue("reference white clipped to white", white < 0.85)
        assertTrue("reference white crushed", white > 0.5)
    }

    @Test
    fun captureToneMapCompressesHighlightToWhite() {
        // 1000 nits (code 0.75) is the tone-map white point: compressed to full SDR white.
        assertEquals("1000-nit highlight", 0.9991, captureToneMapPq(0.75), 0.01)
    }

    @Test
    fun captureToneMapKeepsShadowsDark() {
        // Code 0.0625 is ~0.1 nit - true black-ish. The broken EOTF sent it to ~563 nits and
        // then to white; the correct chain keeps it black.
        assertTrue("shadow lifted: ${captureToneMapPq(0.0625)}", captureToneMapPq(0.0625) < 0.02)
        assertEquals("dark mid", 0.2540, captureToneMapPq(0.3), 0.02)
    }

    // --- sRGB encoding ---

    @Test
    fun srgbOetfMatchesAnchors() {
        assertEquals(0.0, srgbEncode(0.0), 1e-12)
        assertEquals("linear-segment boundary", 0.04045, srgbEncode(0.0031308), 1e-6)
        assertEquals("18% grey", 0.461356, srgbEncode(0.18), 1e-6)
        assertEquals("mid", 0.735357, srgbEncode(0.5), 1e-6)
        assertEquals(1.0, srgbEncode(1.0), 1e-9)
    }

    // --- BT.2020 -> BT.709 gamut conversion ---

    @Test
    fun bt2020To709MatrixPreservesWhiteAndNeutral() {
        // Column-major form as written in the shader; rows are the standard matrix rows.
        val rows = listOf(
            listOf(1.6605, -0.5876, -0.0728),
            listOf(-0.1246, 1.1329, -0.0083),
            listOf(-0.0182, -0.1006, 1.1187),
        )
        for ((i, row) in rows.withIndex()) {
            assertEquals("row $i must map white to white", 1.0, row.sum(), 0.001)
        }
        // Neutral grey stays neutral (all three channels unchanged by the primaries change).
        val grey = 0.18
        for ((i, row) in rows.withIndex()) {
            val out = row.sumOf { it * grey }
            assertEquals("neutral channel $i", grey, out, 0.001)
        }
        // Saturated BT.2020 primaries can go negative in BT.709; the shader clamps with
        // max(lin, 0) after the matrix - here just pin the coefficients that make it happen.
        assertTrue("red row has negative green coefficient", rows[1][0] < 0.0)
        assertTrue("blue row has negative red coefficient", rows[0][2] < 0.0)
    }

    // --- Shader source scan: the compiled constants are the fixed ones ---

    @Test
    fun everyShaderPqM1ConstantUsesTheSt2084Value() {
        val files = listOf(
            "src/main/java/com/rife/androidtv/rife/OesFrameGrabber.kt",
            "src/main/java/com/rife/androidtv/rife/GlOutputRenderer.kt",
        )
        var total = 0
        for (relative in files) {
            val text = sourceFile(relative).readText()
            val m1Lines = text.lines().filter { it.contains("const float m1") }
            assertTrue("no m1 constant found in $relative", m1Lines.isNotEmpty())
            for (line in m1Lines) {
                assertTrue(
                    "ST 2084 m1 must include the /4 in $relative: $line",
                    line.contains("2610.0 / 4096.0 / 4.0"),
                )
            }
            total += m1Lines.size
        }
        assertEquals("every PQ shader copy must be scanned", 7, total)
    }

    private fun sourceFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("shader source not found: $relative (tried ${candidates.map { it.absolutePath }})")
    }
}
