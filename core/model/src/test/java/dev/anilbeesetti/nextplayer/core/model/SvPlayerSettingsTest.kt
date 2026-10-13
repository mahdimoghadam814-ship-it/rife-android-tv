package dev.anilbeesetti.nextplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the SVPlayer motion-search defaults. The search distance in particular is
 * evidence-backed: offline measurement at 1080p showed a 48 px default collapsing a 64 px
 * frame-to-frame shift to crossfade quality (28.6 dB), where 60 px - still inside the
 * half-pel field's 63.5 px packing ceiling for real motion - holds 48.9 dB with no
 * measurable cost at or below 48 px.
 */
class SvPlayerSettingsTest {
    @Test
    fun `search distance defaults to 60 px`() {
        assertEquals(60, SvPlayerSettings().searchDistance)
    }

    @Test
    fun `half-resolution motion search stays opt-in`() {
        assertEquals(1, SvPlayerSettings().meScale)
    }

    @Test
    fun `search and blend rungs default to the SVP reference values`() {
        val s = SvPlayerSettings()
        assertEquals(SvBlockSizeSetting.BLOCK_16X16, s.blockSize)
        assertEquals(2, s.subpel)
        assertEquals(2, s.overlap)
        assertEquals(30f, s.penaltyLambda, 0f)
        assertEquals(1.0f, s.performanceQuality, 0f)
        assertEquals(1.0f, s.artifactMaskLevel, 0f)
        assertEquals(SvBlendAlgorithmSetting.COVER, s.blendAlgorithm)
    }

    @Test
    fun `hdr to sdr tone mapping is opt-in`() {
        assertFalse(SvPlayerSettings().hdrToSdrToneMap)
    }
}
