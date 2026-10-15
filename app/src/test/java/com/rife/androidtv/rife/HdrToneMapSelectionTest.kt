package com.rife.androidtv.rife

import androidx.media3.common.C
import com.rife.androidtv.rife.HdrRepresentationProbe.Representation
import com.rife.androidtv.rife.VideoFrameProcessor.Companion.ToneMapDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HDR to SDR tone-map gate. This is the decision that decides whether the GL capture stage
 * runs the pixel-level PQ/HLG conversion or leaves the HDR path untouched, so the positive
 * identification is locked here: a regression shows up on device as either a washed-out or
 * dark SDR picture (tone-map applied to the wrong transfer) or an unchanged HDR picture
 * (tone-map never applied despite a positive match).
 *
 * The conversion-selection policy ([VideoFrameProcessor.toneMapDecision]) is also locked: an
 * unmeasured representation must never receive a transfer decode (that was the washed-out
 * overexposure), a LINEAR measurement must skip the decode, and ENCODED/INDETERMINATE apply it.
 */
class HdrToneMapSelectionTest {

    @Test
    fun pqTransferWantsToneMapWhenEnabled() {
        assertTrue(
            VideoFrameProcessor.toneMapWanted(
                enabled = true,
                colorTransfer = C.COLOR_TRANSFER_ST2084
            )
        )
    }

    @Test
    fun hlgTransferWantsToneMapWhenEnabled() {
        assertTrue(
            VideoFrameProcessor.toneMapWanted(
                enabled = true,
                colorTransfer = C.COLOR_TRANSFER_HLG
            )
        )
    }

    @Test
    fun sdrTransferDoesNotWantToneMap() {
        assertFalse(
            VideoFrameProcessor.toneMapWanted(
                enabled = true,
                colorTransfer = C.COLOR_TRANSFER_SDR
            )
        )
    }

    @Test
    fun unknownTransferDoesNotWantToneMap() {
        // Absent or unrecognized metadata leaves the HDR path exactly as it was.
        assertFalse(
            VideoFrameProcessor.toneMapWanted(
                enabled = true,
                colorTransfer = null
            )
        )
        assertFalse(
            VideoFrameProcessor.toneMapWanted(
                enabled = true,
                colorTransfer = C.COLOR_TRANSFER_LINEAR
            )
        )
    }

    @Test
    fun disabledToggleDoesNotWantToneMapEvenOnHdr() {
        assertFalse(
            VideoFrameProcessor.toneMapWanted(
                enabled = false,
                colorTransfer = C.COLOR_TRANSFER_ST2084
            )
        )
        assertFalse(
            VideoFrameProcessor.toneMapWanted(
                enabled = false,
                colorTransfer = C.COLOR_TRANSFER_HLG
            )
        )
    }

    @Test
    fun encodedPqSourceAppliesTransferDecode() {
        val d = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = true,
            representation = Representation.ENCODED,
        )
        assertTrue(d is ToneMapDecision.ToneMap)
        assertFalse((d as ToneMapDecision.ToneMap).sourceIsLinear)
    }

    @Test
    fun linearPqSourceSkipsTransferDecode() {
        // The washed-out overexposure root cause: applying pqToLinear to already-linear light
        // massively brightens every value. LINEAR must skip the EOTF.
        val d = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = true,
            representation = Representation.LINEAR,
        )
        assertTrue(d is ToneMapDecision.ToneMap)
        assertTrue((d as ToneMapDecision.ToneMap).sourceIsLinear)
    }

    @Test
    fun indeterminateRepresentationTreatedAsEncoded() {
        // The probe's own sourceIsEncoded rule: anything not measured LINEAR is encoded.
        val d = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = true,
            representation = Representation.INDETERMINATE,
        )
        assertTrue(d is ToneMapDecision.ToneMap)
        assertFalse((d as ToneMapDecision.ToneMap).sourceIsLinear)
    }

    @Test
    fun unmeasuredRepresentationDefersToHdrRetain() {
        // UNKNOWN (or null) must not apply a transfer decode: that is the destructive guess.
        val unknown = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = true,
            representation = Representation.UNKNOWN,
        )
        assertTrue(unknown is ToneMapDecision.HdrRetain)
        assertEquals(
            "representation-unmeasured",
            (unknown as ToneMapDecision.HdrRetain).reason,
        )
        val nullRep = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = true,
            representation = null,
        )
        assertTrue(nullRep is ToneMapDecision.HdrRetain)
    }

    @Test
    fun shaderCompileFailureDefersToHdrRetain() {
        val d = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = false,
            representation = Representation.ENCODED,
        )
        assertTrue(d is ToneMapDecision.HdrRetain)
        assertEquals("shader-unavailable", (d as ToneMapDecision.HdrRetain).reason)
    }

    @Test
    fun sdrSourceNeverToneMaps() {
        val d = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_SDR,
            shaderCompiled = true,
            representation = Representation.ENCODED,
        )
        assertTrue(d is ToneMapDecision.HdrRetain)
        assertEquals("not-wanted", (d as ToneMapDecision.HdrRetain).reason)
    }

    @Test
    fun hlgEncodedSourceAppliesHlgDecode() {
        val d = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_HLG,
            shaderCompiled = true,
            representation = Representation.ENCODED,
        )
        assertTrue(d is ToneMapDecision.ToneMap)
        assertFalse((d as ToneMapDecision.ToneMap).sourceIsLinear)
    }

    @Test
    fun decisionIsStatelessAcrossStreamsAndPriorFailures() {
        // A new stream after an earlier tone-map failure or success must get the same verdict
        // for the same evidence: the decision carries no history, so the runtime's only cache
        // is the per-frame ensureToneMap() result. This is the contract behind the
        // first-launch-only freeze, where a cached availability flag survived a grabber
        // recreation and refused every frame of the new session.
        val fail = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = false,
            representation = Representation.ENCODED,
        )
        val succeed = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = true,
            representation = Representation.ENCODED,
        )
        val failAgain = VideoFrameProcessor.toneMapDecision(
            enabled = true,
            colorTransfer = C.COLOR_TRANSFER_ST2084,
            shaderCompiled = false,
            representation = Representation.ENCODED,
        )
        assertTrue(fail is ToneMapDecision.HdrRetain)
        assertTrue(succeed is ToneMapDecision.ToneMap)
        assertTrue(failAgain is ToneMapDecision.HdrRetain)
        assertEquals(
            (fail as ToneMapDecision.HdrRetain).reason,
            (failAgain as ToneMapDecision.HdrRetain).reason,
        )
    }
}
