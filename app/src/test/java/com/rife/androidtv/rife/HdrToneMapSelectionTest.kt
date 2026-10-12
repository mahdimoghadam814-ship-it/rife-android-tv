package com.rife.androidtv.rife

import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HDR to SDR tone-map gate. This is the decision that decides whether the GL capture stage
 * runs the pixel-level PQ/HLG conversion or leaves the HDR path untouched, so the positive
 * identification is locked here: a regression shows up on device as either a washed-out or
 * dark SDR picture (tone-map applied to the wrong transfer) or an unchanged HDR picture
 * (tone-map never applied despite a positive match).
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
}
