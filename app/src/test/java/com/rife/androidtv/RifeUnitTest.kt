package com.rife.androidtv

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RifeUnitTest {

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
}
