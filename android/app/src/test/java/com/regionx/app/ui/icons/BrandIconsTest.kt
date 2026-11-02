package com.regionx.app.ui.icons

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandIconsTest {
    @Test
    fun `Google provider and prefixed model IDs resolve to Gemini`() {
        for (name in listOf("gemini-2.5-pro", "models/gemini-2.5-flash", "Google/GEMINI-3-pro", "gemma-3")) {
            assertEquals("gemini", BrandIcons.resolveKey(name))
        }
        assertEquals("gpt", BrandIcons.resolveKey(null))
        assertEquals("gpt", BrandIcons.resolveKey("unknown-model"))
    }

    @Test
    fun `Gemini is a closed centered four point sparkle with valid cubic bounds`() {
        val nodes = PathParser().parsePathString(BrandIcons.GeminiPathData).toNodes()
        assertEquals(6, nodes.size)
        assertEquals(PathNode.MoveTo(12f, 1f), nodes.first())
        assertEquals(PathNode.Close, nodes.last())
        val curves = nodes.filterIsInstance<PathNode.CurveTo>()
        assertEquals(4, curves.size)
        val samples = mutableListOf<Pair<Float, Float>>()
        var startX = 12f
        var startY = 1f
        for (curve in curves) {
            for (step in 0..20) {
                val t = step / 20f
                val u = 1f - t
                samples += (u * u * u * startX + 3 * u * u * t * curve.x1 + 3 * u * t * t * curve.x2 + t * t * t * curve.x3) to
                    (u * u * u * startY + 3 * u * u * t * curve.y1 + 3 * u * t * t * curve.y2 + t * t * t * curve.y3)
            }
            startX = curve.x3
            startY = curve.y3
        }
        assertTrue(samples.all { (x, y) -> x.isFinite() && y.isFinite() && x in 1f..23f && y in 1f..23f })
        assertEquals(1f, samples.minOf { it.first }, 0.001f)
        assertEquals(23f, samples.maxOf { it.first }, 0.001f)
        assertEquals(1f, samples.minOf { it.second }, 0.001f)
        assertEquals(23f, samples.maxOf { it.second }, 0.001f)
        // A non-degenerate area distinguishes the sparkle from a collapsed or empty path.
        val area = samples.zipWithNext().sumOf { (a, b) -> (a.first * b.second - b.first * a.second).toDouble() } / 2
        assertTrue(kotlin.math.abs(area) > 100)
    }
}
