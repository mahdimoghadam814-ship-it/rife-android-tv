package com.rife.androidtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipelineTest {

    @Test
    fun testRifeDataStructureInitialization() {
        val result = RifeDiagnosticResult(
            success = true,
            vulkanAvailable = true,
            gpuName = "Mali-G310 V2",
            vulkanApiVersion = "1.3.0",
            modelLoaded = true,
            lastInferenceTimeMs = 120L,
            lastError = "",
            opDetails = "Test success"
        )

        assertTrue(result.success)
        assertTrue(result.vulkanAvailable)
        assertTrue(result.modelLoaded)
        assertNotNull(result.gpuName)
    }

    @Test
    fun testSubtitleMimeTypeInferencePath() {
        assertEquals("application/x-subrip", inferSubtitleMimeTypeFromPath("/storage/emulated/0/Download/sub.srt"))
        assertEquals("text/vtt", inferSubtitleMimeTypeFromPath("/storage/emulated/0/Download/sub.vtt"))
        assertEquals("text/x-ssa", inferSubtitleMimeTypeFromPath("/storage/emulated/0/Download/sub.ass"))
        assertEquals("text/x-ssa", inferSubtitleMimeTypeFromPath("/storage/emulated/0/Download/sub.ssa"))
    }

    @Test
    fun testTargetDimensionCalculation() {
        val (res1080W, res1080H) = calculateTargetDimensions(3840, 2160, RifeResolution.RES_1080P)
        assertEquals(1920, res1080W)
        assertEquals(1080, res1080H)

        val (res720W, res720H) = calculateTargetDimensions(1920, 1080, RifeResolution.RES_720P)
        assertEquals(1280, res720W)
        assertEquals(720, res720H)

        val (res480W, res480H) = calculateTargetDimensions(1920, 1080, RifeResolution.RES_480P)
        assertEquals(854, res480W)
        assertEquals(480, res480H)

        val (origW, origH) = calculateTargetDimensions(1920, 1080, RifeResolution.ORIGINAL)
        assertEquals(1920, origW)
        assertEquals(1080, origH)
    }

    private fun inferSubtitleMimeTypeFromPath(path: String?): String {
        if (path == null) return "application/x-subrip"
        val lower = path.lowercase()
        return when {
            lower.endsWith(".vtt") -> "text/vtt"
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> "text/x-ssa"
            lower.endsWith(".srt") -> "application/x-subrip"
            else -> "application/x-subrip"
        }
    }

    private fun calculateTargetDimensions(
        srcW: Int,
        srcH: Int,
        res: RifeResolution
    ): Pair<Int, Int> {
        return when (res) {
            RifeResolution.ORIGINAL -> Pair(srcW, srcH)
            RifeResolution.RES_1080P -> {
                val maxDim = 1920
                if (srcW > srcH && srcW > maxDim) {
                    Pair(maxDim, (srcH * maxDim) / srcW)
                } else if (srcH >= srcW && srcH > maxDim) {
                    Pair((srcW * maxDim) / srcH, maxDim)
                } else {
                    Pair(srcW, srcH)
                }
            }
            RifeResolution.RES_720P -> {
                val maxDim = 1280
                if (srcW > srcH && srcW > maxDim) {
                    Pair(maxDim, (srcH * maxDim) / srcW)
                } else if (srcH >= srcW && srcH > maxDim) {
                    Pair((srcW * maxDim) / srcH, maxDim)
                } else {
                    Pair(srcW, srcH)
                }
            }
            RifeResolution.RES_480P -> {
                val maxDim = 854
                if (srcW > srcH && srcW > maxDim) {
                    Pair(maxDim, (srcH * maxDim) / srcW)
                } else if (srcH >= srcW && srcH > maxDim) {
                    Pair((srcW * maxDim) / srcH, maxDim)
                } else {
                    Pair(srcW, srcH)
                }
            }
        }
    }
}
