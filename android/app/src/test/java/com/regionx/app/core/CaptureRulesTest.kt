package com.regionx.app.core

import com.regionx.app.core.capture.CaptureDisplayPolicy
import com.regionx.app.core.capture.CaptureGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureRulesTest {
    @Test
    fun `same size capture reuses the display without resize or recreation`() {
        val policy = CaptureDisplayPolicy()
        assertEquals(CaptureDisplayPolicy.Action.CREATE, policy.configure(1080, 2400, 420))
        repeat(10) {
            assertEquals(CaptureDisplayPolicy.Action.ATTACH, policy.configure(1080, 2400, 420))
        }
    }

    @Test
    fun `rotation and half size fallback resize the same display`() {
        val policy = CaptureDisplayPolicy()
        policy.configure(1080, 2400, 420)
        assertEquals(CaptureDisplayPolicy.Action.RESIZE, policy.configure(2400, 1080, 420))
        assertEquals(CaptureDisplayPolicy.Action.RESIZE, policy.configure(1200, 540, 420))
        assertEquals(CaptureDisplayPolicy.Action.ATTACH, policy.configure(1200, 540, 420))
    }

    @Test
    fun `stopped session cannot reuse a consumed projection token`() {
        val policy = CaptureDisplayPolicy()
        policy.configure(1080, 2400, 420)
        policy.close()
        assertThrows(IllegalStateException::class.java) { policy.configure(1080, 2400, 420) }
    }

    @Test
    fun `screen region scales down into half size bitmap`() {
        assertEquals(
            CaptureGeometry.Crop(200, 300, 300, 400),
            CaptureGeometry.mapCrop(400, 600, 600, 800, 1080, 2400, 540, 1200),
        )
    }

    @Test
    fun `fractional scaling preserves all selected pixels and clamps to image`() {
        assertEquals(
            CaptureGeometry.Crop(0, 1, 101, 51),
            CaptureGeometry.mapCrop(-20, 3, 500, 300, 300, 150, 101, 51),
        )
    }

    @Test
    fun `off screen region still yields a valid edge pixel`() {
        assertEquals(
            CaptureGeometry.Crop(539, 1199, 540, 1200),
            CaptureGeometry.mapCrop(2000, 3000, 2100, 3100, 1080, 2400, 540, 1200),
        )
    }
}
