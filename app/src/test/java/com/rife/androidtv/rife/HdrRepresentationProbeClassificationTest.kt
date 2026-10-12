package com.rife.androidtv.rife

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The HDR representation verdict. This is the decision the present path uses to choose whether
 * the shader decodes the source before tone-mapping, so the thresholds are locked here: a
 * regression in them shows up on device as either a clipped-to-white picture (encoded data
 * treated as linear) or a dark picture (linear data treated as encoded).
 */
class HdrRepresentationProbeClassificationTest {

    @Test
    fun highFrameMeanIsEncoded() {
        assertEquals(
            HdrRepresentationProbe.Representation.ENCODED,
            HdrRepresentationProbe.classify(
                mean = 0.35,
                p50 = 0.30f,
                p99 = 0.90f,
                maxIntensity = 1.0f
            )
        )
    }

    @Test
    fun encodedWhenOnlyTheMedianIsHigh() {
        // A mostly-dark frame with typical code values in the tenths: mean alone would miss it.
        assertEquals(
            HdrRepresentationProbe.Representation.ENCODED,
            HdrRepresentationProbe.classify(
                mean = 0.05,
                p50 = 0.12f,
                p99 = 0.60f,
                maxIntensity = 1.0f
            )
        )
    }

    @Test
    fun darkButNonBlackFrameIsLinear() {
        // Linear light over 0..10000 nits: low mean and percentiles, but real highlights present.
        assertEquals(
            HdrRepresentationProbe.Representation.LINEAR,
            HdrRepresentationProbe.classify(
                mean = 0.01,
                p50 = 0.006f,
                p99 = 0.10f,
                maxIntensity = 0.40f
            )
        )
    }

    @Test
    fun blackFrameIsIndeterminateRatherThanLinear() {
        // A fade-to-black scene is numerically "linear" without being linear light: without the
        // max-intensity guard it would flip the conversion off measured blackness.
        assertEquals(
            HdrRepresentationProbe.Representation.INDETERMINATE,
            HdrRepresentationProbe.classify(
                mean = 0.005,
                p50 = 0.002f,
                p99 = 0.01f,
                maxIntensity = 0.02f
            )
        )
    }

    @Test
    fun distributionBetweenThePopulationsIsIndeterminate() {
        // Too bright for LINEAR, too dark for ENCODED: treated as ENCODED by the caller.
        assertEquals(
            HdrRepresentationProbe.Representation.INDETERMINATE,
            HdrRepresentationProbe.classify(
                mean = 0.05,
                p50 = 0.04f,
                p99 = 0.50f,
                maxIntensity = 0.90f
            )
        )
    }

    @Test
    fun meanCeilingSeparatesLinearFromIndeterminate() {
        // p99 sits just under its own ceiling so the mean is what decides. (The ceiling itself is
        // compared as `p99 <= 0.15` against a Float, so exactly 0.15f - which widens to a value
        // just above the Double 0.15 - is already outside it; that precision detail is behaviour
        // of the production expression and is deliberately not asserted as inclusive.)
        assertEquals(
            HdrRepresentationProbe.Representation.LINEAR,
            HdrRepresentationProbe.classify(
                mean = 0.02,
                p50 = 0.01f,
                p99 = 0.149f,
                maxIntensity = 0.06f
            )
        )
        // One step above the mean ceiling and it is no longer linear.
        assertEquals(
            HdrRepresentationProbe.Representation.INDETERMINATE,
            HdrRepresentationProbe.classify(
                mean = 0.021,
                p50 = 0.01f,
                p99 = 0.149f,
                maxIntensity = 0.06f
            )
        )
    }
}
