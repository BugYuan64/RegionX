package com.regionx.app.core.capture

import kotlin.math.ceil
import kotlin.math.floor

object CaptureGeometry {
    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun mapCrop(
        left: Int, top: Int, right: Int, bottom: Int,
        screenWidth: Int, screenHeight: Int, bitmapWidth: Int, bitmapHeight: Int,
    ): Crop {
        require(screenWidth > 0 && screenHeight > 0 && bitmapWidth > 0 && bitmapHeight > 0)
        val sx = bitmapWidth.toDouble() / screenWidth
        val sy = bitmapHeight.toDouble() / screenHeight
        val l = floor(left * sx).toInt().coerceIn(0, bitmapWidth - 1)
        val t = floor(top * sy).toInt().coerceIn(0, bitmapHeight - 1)
        return Crop(
            l, t,
            ceil(right * sx).toInt().coerceIn(l + 1, bitmapWidth),
            ceil(bottom * sy).toInt().coerceIn(t + 1, bitmapHeight),
        )
    }
}
