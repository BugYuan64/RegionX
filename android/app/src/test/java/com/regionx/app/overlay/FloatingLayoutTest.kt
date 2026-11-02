package com.regionx.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLayoutTest {
    @Test
    fun `release snaps to the nearest of all four usable edges`() {
        assertEquals(FloatingPoint(0, 300), FloatingLayout.snapToEdge(12, 300, 40, 40, 360, 800, 24, 32))
        assertEquals(FloatingPoint(320, 300), FloatingLayout.snapToEdge(305, 300, 40, 40, 360, 800, 24, 32))
        assertEquals(FloatingPoint(150, 24), FloatingLayout.snapToEdge(150, 40, 40, 40, 360, 800, 24, 32))
        assertEquals(FloatingPoint(150, 728), FloatingLayout.snapToEdge(150, 710, 40, 40, 360, 800, 24, 32))
    }

    @Test
    fun `snap ties are stable and an already docked bubble does not move`() {
        // Equal 20px left/top distance uses the horizontal edge first.
        assertEquals(FloatingPoint(0, 44), FloatingLayout.snapToEdge(20, 44, 40, 40, 360, 800, 24, 32))
        assertEquals(FloatingPoint(320, 24), FloatingLayout.snapToEdge(320, 24, 40, 40, 360, 800, 24, 32))
        assertEquals(FloatingPoint(0, 20), FloatingLayout.snapToEdge(999, -999, 58, 40, 20, 20, 24, 32))
    }

    @Test
    fun `snapping preserves bounds after rotation and for oversized windows`() {
        for ((screenWidth, screenHeight) in listOf(360 to 800, 800 to 360, 20 to 20, 0 to 0)) {
            for ((width, height) in listOf(40 to 40, 58 to 40, 900 to 1000)) {
                for ((x, y) in listOf(-500 to -500, 150 to 300, 1200 to 1800)) {
                    val snapped = FloatingLayout.snapToEdge(x, y, width, height, screenWidth, screenHeight, 24, 32)
                    assertEquals(snapped, FloatingLayout.clampPoint(snapped.x, snapped.y, width, height, screenWidth, screenHeight, 24, 32))
                    assertEquals(snapped, FloatingLayout.snapToEdge(snapped.x, snapped.y, width, height, screenWidth, screenHeight, 24, 32))
                }
            }
        }
    }

    @Test
    fun `dragging clamps both corners outside system bars`() {
        assertEquals(FloatingPoint(0, 24), FloatingLayout.clampPoint(-20, -30, 104, 64, 360, 800, 24, 32))
        assertEquals(FloatingPoint(256, 704), FloatingLayout.clampPoint(999, 999, 104, 64, 360, 800, 24, 32))
    }

    @Test
    fun `menu keeps its requested height above a low bubble`() {
        assertEquals(
            FloatingPlacement(80, 412, 280, 280),
            FloatingLayout.modelMenu(256, 700, 104, 64, 280, 280, 360, 800, 8, 24, 32),
        )
    }

    @Test
    fun `menu uses the larger side even when the smaller side also fits`() {
        val menu = FloatingLayout.modelMenu(256, 320, 104, 64, 280, 280, 360, 1000, 8, 24, 32)
        assertEquals(392, menu.y)
        assertEquals(280, menu.height)
    }

    @Test
    fun `menu width and height shrink only when the screen cannot fit them`() {
        val menu = FloatingLayout.modelMenu(76, 48, 104, 64, 280, 280, 180, 160, 8, 24, 16)
        assertEquals(FloatingPlacement(0, 120, 180, 24), menu)
    }

    @Test
    fun `rotation reclamps the old bubble position and preserves menu bounds`() {
        val menu = FloatingLayout.modelMenu(900, 1800, 104, 64, 280, 280, 800, 360, 8, 24, 24)
        assertEquals(FloatingPlacement(520, 24, 280, 240), menu)
    }

    @Test
    fun `edge placements stay inside portrait landscape and tiny viewports`() {
        for ((screenWidth, screenHeight) in listOf(360 to 800, 800 to 360, 120 to 100, 20 to 20, 0 to 0)) {
            val top = 24.coerceAtMost(screenHeight)
            val bottom = (screenHeight - 32.coerceAtMost(screenHeight)).coerceAtLeast(top)
            for (x in listOf(-100, 0, screenWidth / 2, screenWidth + 100)) {
                for (y in listOf(-100, 0, screenHeight / 2, screenHeight + 100)) {
                    val menu = FloatingLayout.modelMenu(x, y, 104, 64, 280, 280, screenWidth, screenHeight, 8, 24, 32)
                    assertTrue(menu.x >= 0)
                    assertTrue(menu.y >= top)
                    assertTrue(menu.width >= 0 && menu.height >= 0)
                    assertTrue(menu.x + menu.width <= screenWidth)
                    assertTrue(menu.y + menu.height <= bottom)
                }
            }
        }
    }
}
