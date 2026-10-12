package com.rife.androidtv.rife

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-pair upload shortcut decides whether a full-resolution frame upload may be skipped
 * because a warp texture already holds that exact frame. A wrong "yes" presents stale pixels;
 * a wrong "no" only pays a transfer, so the tests pin every condition that must agree before the
 * shortcut is allowed.
 */
class WarpPairResidencyTest {

    private fun resident(
        frameToken: Long = 100L,
        sameBuffer: Boolean = true,
        residentToken: Long = 100L,
        residentW: Int = 3840,
        residentH: Int = 2160,
        srcW: Int = 3840,
        srcH: Int = 2160
    ) = warpPairFrameResident(frameToken, sameBuffer, residentToken, residentW, residentH, srcW, srcH)

    @Test
    fun matchingTokenBufferAndSizeIsResident() {
        assertTrue(resident())
    }

    @Test
    fun zeroFrameTokenMeansUnknownAndNeverMatches() {
        // The single-timestep overload and any caller without an identity pass 0.
        assertFalse(resident(frameToken = 0L, residentToken = 0L))
        assertFalse(resident(frameToken = 0L))
    }

    @Test
    fun differentTokenIsADifferentFrame() {
        assertFalse(resident(frameToken = 101L, residentToken = 100L))
    }

    @Test
    fun recycledBufferInstanceWithMatchingTokenIsNotTrusted() {
        // The pool hands the same ByteBuffer to a later frame; identity must still disagree or
        // the resident texture would be presented as if it held the new frame.
        assertFalse(resident(sameBuffer = false))
    }

    @Test
    fun sizeChangeInvalidatesResidency() {
        assertFalse(resident(residentW = 1920, residentH = 1080))
        assertFalse(resident(srcW = 1920, srcH = 1080))
    }

    @Test
    fun consecutivePairScheduleRotatesThrough() {
        // Pair (A,B) then (B,C): after the first pair, slot1 holds B with token 200. The next
        // pair's frame0 is that same B - same token, same buffer, same size - so it is resident
        // and the upload may be skipped; its frame1 (C, token 300) is not.
        assertTrue(
            warpPairFrameResident(
                frameToken = 200L,
                sameBufferAsResident = true,
                residentToken = 200L,
                residentWidth = 3840,
                residentHeight = 2160,
                srcWidth = 3840,
                srcHeight = 2160
            )
        )
        assertFalse(
            warpPairFrameResident(
                frameToken = 300L,
                sameBufferAsResident = false,
                residentToken = 200L,
                residentWidth = 3840,
                residentHeight = 2160,
                srcWidth = 3840,
                srcHeight = 2160
            )
        )
    }
}
