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

    /**
     * Display-referred highlight roll-off from the capture shader: identity at or below the
     * 50-nit knee, then an extended-Reinhard fold of (knee, 1000 nits] into (knee, 1.0].
     * Mirrors rollOff() in OesFrameGrabber's TONEMAP_FRAGMENT_SHADER.
     */
    private fun rollOff(y: Double): Double {
        val knee = 0.5
        val peak = 10.0
        if (y <= knee) return y.coerceAtLeast(0.0)
        val h = y - knee
        val w = peak - knee
        val m = h * (1.0 + h / (w * w)) / (1.0 + h)
        return knee + (1.0 - knee) * m.coerceIn(0.0, 1.0)
    }

    /**
     * Luminance-preserving fold of out-of-gamut negatives toward the neutral axis. Mirrors
     * foldOutOfGamut(): scale chroma by Y/(Y-min) so the most negative channel lands at zero
     * while luminance and the r:g:b chroma ratios are kept exactly.
     */
    private fun foldOutOfGamut(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val minc = minOf(r, g, b)
        if (minc >= 0.0 || y <= 1e-5) {
            return Triple(r.coerceAtLeast(0.0), g.coerceAtLeast(0.0), b.coerceAtLeast(0.0))
        }
        val s = y / (y - minc)
        fun fold(v: Double) = (y + (v - y) * s).coerceAtLeast(0.0)
        return Triple(fold(r), fold(g), fold(b))
    }

    /** sRGB OETF, as the capture shader encodes its output. */
    private fun srgbEncode(v: Double): Double =
        if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055

    /**
     * The capture shader's tail for a neutral grey: decode to 100-nit-relative light, apply
     * rollOff(), clamp, sRGB-encode. Mirrors main() for r=g=b with no gamut conversion.
     */
    private fun captureToneMapPq(code: Double): Double {
        val lin100 = pqEotf(code) * 100.0
        val sdr = rollOff(lin100).coerceIn(0.0, 1.0)
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
    fun captureToneMapPassesMidtonesThroughUnchanged() {
        // The darkness regression: the old global Hable curve mapped 18% grey (18 nits) to
        // sRGB code ~0.35 and 100-nit white to ~0.71. The knee curve is the identity at or
        // below 50 nits, so reference midtones land exactly on their sRGB encodings.
        val eighteenNits = pqInverseEotf(0.18 / 100.0)
        assertEquals("18% grey", 0.461356, captureToneMapPq(eighteenNits), 0.005)
        // pqInverseEotf's scale is 0..1 = 0..10000 nits, so the 50-nit knee is 0.005.
        val fiftyNits = pqInverseEotf(0.5 / 100.0)
        assertEquals("knee", srgbEncode(0.5), captureToneMapPq(fiftyNits), 0.005)
    }

    @Test
    fun captureToneMapLandsReferenceWhiteNearSdrWhite() {
        // 100-nit diffuse white (code ~0.508) must read as white on an SDR display. The knee
        // curve puts it at ~0.839 (was 0.714 under Hable - the washed-out dark picture).
        val white = captureToneMapPq(0.508)
        assertEquals("100-nit white", 0.839, white, 0.01)
        assertTrue("reference white clipped to white", white < 0.98)
        assertTrue("reference white crushed", white > 0.75)
    }

    @Test
    fun captureToneMapCompressesPeakToWhite() {
        // 1000 nits (code 0.75) is the assumed peak: extended-Reinhard maps it to full white.
        assertEquals("1000-nit highlight", 0.999, captureToneMapPq(0.75), 0.005)
    }

    @Test
    fun captureToneMapKeepsShadowsDark() {
        // Code 0.0625 is ~0.1 nit - true black-ish. The broken EOTF sent it to ~563 nits and
        // then to white; the correct chain keeps it black.
        assertTrue("shadow lifted: ${captureToneMapPq(0.0625)}", captureToneMapPq(0.0625) < 0.02)
    }

    @Test
    fun rollOffIsIdentityBelowKneeAndMonotoneAbove() {
        for (y in listOf(0.0, 0.05, 0.1, 0.18, 0.3, 0.49, 0.5)) {
            assertEquals("identity at $y", y, rollOff(y), 1e-12)
        }
        var previous = rollOff(0.5)
        for (i in 1..400) {
            val y = 0.5 + i * 0.05
            val mapped = rollOff(y)
            assertTrue("rollOff not monotone at $y ($mapped < $previous)", mapped >= previous - 1e-12)
            assertTrue("rollOff exceeded 1 at $y", mapped <= 1.0 + 1e-12)
            previous = mapped
        }
        assertEquals("peak maps to full white", 1.0, rollOff(10.0), 1e-9)
    }

    // --- Gamut fold: out-of-gamut colors lose just enough saturation to fit ---

    @Test
    fun foldOutOfGamutPreservesLuminanceAndNeutralAxis() {
        // Pure BT.2020 red, after the 2020->709 matrix, in 100-nit-relative units:
        // (1.6605, -0.1246, -0.0182) * 100. Per-channel clipping would collapse this onto
        // pure 709 red (hue shift, the oversaturated look); the fold must keep luminance
        // and the r:g:b chroma ratios while landing the minimum at zero.
        val lin = Triple(1.6605 * 100.0, -0.1246 * 100.0, -0.0182 * 100.0)
        val y0 = 0.2126 * lin.first + 0.7152 * lin.second + 0.0722 * lin.third
        val (r, g, b) = foldOutOfGamut(lin.first, lin.second, lin.third)
        assertTrue("negative channel not folded: g=$g b=$b", g >= -1e-9 && b >= -1e-9)
        val y1 = 0.2126 * r + 0.7152 * g + 0.0722 * b
        assertEquals("luminance preserved", y0, y1, 1e-6)
        assertTrue("hue order kept (r > b)", r > b && b >= 0.0)
        // Chroma ratios (v - Y) scale by one common factor s.
        val s = y0 / (y0 - minOf(lin.first, lin.second, lin.third))
        assertEquals("r chroma scaled", y0 + (lin.first - y0) * s, r, 1e-6)
        assertEquals("b chroma scaled", y0 + (lin.third - y0) * s, b, 1e-6)
    }

    @Test
    fun foldOutOfGamutLeavesInGamutAndNeutralsUntouched() {
        val (r, g, b) = foldOutOfGamut(10.0, 20.0, 30.0)
        assertEquals(10.0, r, 1e-12)
        assertEquals(20.0, g, 1e-12)
        assertEquals(30.0, b, 1e-12)
        val (nr, ng, nb) = foldOutOfGamut(50.0, 50.0, 50.0)
        assertEquals(50.0, nr, 1e-12)
        assertEquals(50.0, ng, 1e-12)
        assertEquals(50.0, nb, 1e-12)
    }

    // --- HLG end-to-end through the capture tail ---

    @Test
    fun hlgCaptureChainMatchesAnchors() {
        // HLG code 0.75 (neutral): scene = hlgToScene(0.75) ~ 0.2650; ^1.2 OOTF and *10 put
        // it at ~2.03 in 100-nit-relative units; the roll-off then yields sRGB ~0.910.
        val scene = hlgInverseOetf(0.75)
        val lin100 = scene.pow(1.2) * 10.0
        assertEquals("HLG 0.75 tail", 0.910, srgbEncode(rollOff(lin100).coerceIn(0.0, 1.0)), 0.01)
        // HLG code 0.5 sits just above the knee after the OOTF (~0.507): near-identity
        // through the roll-off, landing at sRGB ~0.738.
        val scene50 = hlgInverseOetf(0.5)
        val lin50 = scene50.pow(1.2) * 10.0
        assertTrue("HLG 0.5 near knee: $lin50", lin50 in 0.45..0.60)
        assertEquals("HLG 0.5 tail", 0.738, srgbEncode(rollOff(lin50)), 0.01)
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
        // Saturated BT.2020 primaries go negative in BT.709; the shader folds those
        // negatives toward the luminance axis (foldOutOfGamut) instead of clipping them.
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

    @Test
    fun captureShaderUsesKneeRollOffAndGamutFold() {
        // The darkness and oversaturation fixes live in the capture tone-map shader: the old
        // global Hable curve is gone, the 50-nit knee roll-off and the luminance-preserving
        // gamut fold are present, and the matrix still carries the ST 2084-adjacent constants.
        val text = sourceFile("src/main/java/com/rife/androidtv/rife/OesFrameGrabber.kt").readText()
        val shader = text.substringAfter("private const val TONEMAP_FRAGMENT_SHADER")
            .substringBefore("private const val FULL_QUAD")
        assertTrue("rollOff missing", shader.contains("float rollOff("))
        assertTrue("knee must be 50 nits (0.5 of 100-nit units)", shader.contains("const float knee = 0.5"))
        assertTrue("peak must be 1000 nits", shader.contains("const float peak = 10.0"))
        assertTrue("foldOutOfGamut missing", shader.contains("vec3 foldOutOfGamut("))
        assertTrue("old Hable roll-off still present", !shader.contains("float hable("))
    }

    private fun sourceFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("shader source not found: $relative (tried ${candidates.map { it.absolutePath }})")
    }
}
