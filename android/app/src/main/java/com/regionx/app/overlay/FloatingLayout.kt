package com.regionx.app.overlay

data class FloatingPoint(val x: Int, val y: Int)

data class FloatingPlacement(val x: Int, val y: Int, val width: Int, val height: Int)

/** Shared window dimensions in dp; WindowManager converts them to pixels. */
object FloatingSizes {
    const val Ball = 40
    const val AnswerWidth = 58
    const val ModelWidth = 173
    const val ModelHeight = 187
    const val QueueWidth = ModelWidth
    const val QueueHeight = ModelHeight
}

/** Pixel coordinates in the full display, with system bars excluded from placement. */
object FloatingLayout {
    fun clampPoint(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
        insetTop: Int,
        insetBottom: Int,
    ): FloatingPoint {
        val displayWidth = screenWidth.coerceAtLeast(0)
        val displayHeight = screenHeight.coerceAtLeast(0)
        val top = insetTop.coerceIn(0, displayHeight)
        val bottom = (displayHeight - insetBottom.coerceIn(0, displayHeight)).coerceAtLeast(top)
        // A point cannot resize an oversized window; pin that window to the usable origin.
        val maxX = (displayWidth - width.coerceAtLeast(0)).coerceAtLeast(0)
        val maxY = (bottom - height.coerceAtLeast(0)).coerceAtLeast(top)
        return FloatingPoint(x.coerceIn(0, maxX), y.coerceIn(top, maxY))
    }

    /** Dock only on release. Equal distances prefer left, right, top, then bottom. */
    fun snapToEdge(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
        insetTop: Int,
        insetBottom: Int,
    ): FloatingPoint {
        val point = clampPoint(x, y, width, height, screenWidth, screenHeight, insetTop, insetBottom)
        val origin = clampPoint(0, 0, width, height, screenWidth, screenHeight, insetTop, insetBottom)
        val end = clampPoint(Int.MAX_VALUE, Int.MAX_VALUE, width, height, screenWidth, screenHeight, insetTop, insetBottom)
        val candidates = listOf(
            (point.x - origin.x) to FloatingPoint(origin.x, point.y),
            (end.x - point.x) to FloatingPoint(end.x, point.y),
            (point.y - origin.y) to FloatingPoint(point.x, origin.y),
            (end.y - point.y) to FloatingPoint(point.x, end.y),
        )
        return candidates.minBy { it.first }.second
    }

    /**
     * Right-align with the answer bubble and use the side with more vertical room.
     * The requested size is preserved whenever that side can fit it. Only physically
     * constrained displays shrink the panel; list length never changes its height.
     */
    fun modelMenu(
        anchorX: Int,
        anchorY: Int,
        anchorWidth: Int,
        anchorHeight: Int,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
        gap: Int,
        insetTop: Int,
        insetBottom: Int,
    ): FloatingPlacement {
        val displayWidth = screenWidth.coerceAtLeast(0)
        val displayHeight = screenHeight.coerceAtLeast(0)
        val top = insetTop.coerceIn(0, displayHeight)
        val bottom = (displayHeight - insetBottom.coerceIn(0, displayHeight)).coerceAtLeast(top)
        val safeAnchorWidth = anchorWidth.coerceIn(0, displayWidth)
        val safeAnchorHeight = anchorHeight.coerceIn(0, bottom - top)
        // Re-clamp a stored portrait position before laying out on a rotated display.
        val anchor = clampPoint(
            anchorX, anchorY, safeAnchorWidth, safeAnchorHeight,
            displayWidth, displayHeight, top, displayHeight - bottom,
        )
        val safeGap = gap.coerceIn(0, displayHeight)
        val above = (anchor.y - top - safeGap).coerceAtLeast(0)
        val below = (bottom - anchor.y - safeAnchorHeight - safeGap).coerceAtLeast(0)
        val useAbove = above >= below
        val panelWidth = width.coerceIn(0, displayWidth)
        val panelHeight = height.coerceIn(0, maxOf(above, below))
        val x = (anchor.x + safeAnchorWidth - panelWidth).coerceIn(0, displayWidth - panelWidth)
        val y = if (useAbove) anchor.y - safeGap - panelHeight else anchor.y + safeAnchorHeight + safeGap
        return FloatingPlacement(x, y.coerceIn(top, bottom - panelHeight), panelWidth, panelHeight)
    }
}
