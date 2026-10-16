package com.rife.androidtv.rife

import org.junit.Assert.assertEquals
import org.junit.Test

class MemcLevelSnapTest {

    @Test
    fun `24fps on 60hz snaps both settings to 2_5`() {
        assertEquals(2.5f, MemcLevelSnap.effective(3f, 24.0, 60f), 0.0001f)
        assertEquals(2.5f, MemcLevelSnap.effective(2f, 24.0, 60f), 0.0001f)
    }

    @Test
    fun `30fps on 60hz lands on 2x`() {
        assertEquals(2f, MemcLevelSnap.effective(3f, 30.0, 60f), 0.0001f)
        assertEquals(2f, MemcLevelSnap.effective(2f, 30.0, 60f), 0.0001f)
    }

    @Test
    fun `25fps on 60hz is 2_4`() {
        assertEquals(2.4f, MemcLevelSnap.effective(2f, 25.0, 60f), 0.0001f)
    }

    @Test
    fun `ntsc rates stay near 2_5 without flapping`() {
        assertEquals(2.5025f, MemcLevelSnap.effective(3f, 23.976, 60f), 0.0001f)
    }

    @Test
    fun `unknown refresh or source keeps the user setting`() {
        assertEquals(3f, MemcLevelSnap.effective(3f, 24.0, 0f), 0.0001f)
        assertEquals(2f, MemcLevelSnap.effective(2f, 0.0, 60f), 0.0001f)
    }

    @Test
    fun `even target outside the band clamps instead of escaping`() {
        assertEquals(4f, MemcLevelSnap.effective(3f, 12.0, 60f), 0.0001f)
        assertEquals(2f, MemcLevelSnap.effective(3f, 50.0, 60f), 0.0001f)
    }

    @Test
    fun `user setting itself is clamped into the band`() {
        assertEquals(2f, MemcLevelSnap.effective(1f, 0.0, 0f), 0.0001f)
        assertEquals(4f, MemcLevelSnap.effective(6f, 0.0, 0f), 0.0001f)
    }

    @Test
    fun `sub-centijitter keeps the user value`() {
        // Measured 24.05 fps on a 60 Hz panel: even = 2.4948, within 0.01 of a 2.5 setting.
        assertEquals(2.5f, MemcLevelSnap.effective(2.5f, 24.05, 60f), 0.0001f)
    }
}
