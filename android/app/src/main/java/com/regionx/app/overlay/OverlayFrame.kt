package com.regionx.app.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.regionx.app.ui.theme.regionXColors
import kotlin.math.roundToInt

/**
 * 识别框外观：琥珀色 2dp 边框 + 四角手柄 + 操作时的尺寸标签。
 *
 * 触摸目标按手指尺寸放大（整个框可拖动、角手柄 44dp），
 * 与 Windows 端「整条边框可拖动、四角缩放」的交互意图一致。
 */
@Composable
fun OverlayFrame(
    geometry: OverlayGeometry,
    onGeometryChange: (OverlayGeometry) -> Unit,
    screenWidth: Int,
    screenHeight: Int,
) {
    val colors = regionXColors
    var dragging by remember { mutableStateOf(false) }
    val currentGeometry by rememberUpdatedState(geometry)
    val currentChange by rememberUpdatedState(onGeometryChange)

    Box(Modifier.fillMaxSize()) {
        // 整个框（含边框与框内区域）都可拖动移动。
        // 注意 Z 序：四角缩放手柄声明在其后，命中优先。
        Box(
            Modifier
                .fillMaxSize()
                .border(2.dp, colors.accent, RoundedCornerShape(3.dp))
                .pointerInput(screenWidth, screenHeight) {
                    var start = currentGeometry
                    detectDragGestures(
                        onDragStart = {
                            start = currentGeometry
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x.roundToInt()
                        val dy = dragAmount.y.roundToInt()
                        if (dx == 0 && dy == 0) return@detectDragGestures
                        val w = start.width
                        val h = start.height
                        val left = (start.left + dx).coerceIn(0, (screenWidth - w).coerceAtLeast(0))
                        val top = (start.top + dy).coerceIn(0, (screenHeight - h).coerceAtLeast(0))
                        val next = OverlayGeometry(left, top, left + w, top + h)
                        start = next
                        currentChange(next)
                    }
                }
        )

        CornerHandle(Alignment.TopStart, Corner.NW, geometry, screenWidth, screenHeight, onGeometryChange)
        CornerHandle(Alignment.TopEnd, Corner.NE, geometry, screenWidth, screenHeight, onGeometryChange)
        CornerHandle(Alignment.BottomStart, Corner.SW, geometry, screenWidth, screenHeight, onGeometryChange)
        CornerHandle(Alignment.BottomEnd, Corner.SE, geometry, screenWidth, screenHeight, onGeometryChange)

        if (dragging) {
            SizeLabel(geometry)
        }
    }
}

/** 拖动/缩放时显示的尺寸标签，贴在框内左上角（窗口只有框那么大，不能画到框外）。 */
@Composable
private fun BoxScope.SizeLabel(geometry: OverlayGeometry) {
    val colors = regionXColors
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .background(colors.accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            "识别框 ${geometry.width} × ${geometry.height}",
            color = colors.onAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private enum class Corner { NW, NE, SW, SE }

@Composable
private fun BoxScope.CornerHandle(
    alignment: Alignment,
    corner: Corner,
    geometry: OverlayGeometry,
    screenWidth: Int,
    screenHeight: Int,
    onGeometryChange: (OverlayGeometry) -> Unit,
) {
    val colors = regionXColors
    val currentGeometry by rememberUpdatedState(geometry)
    val currentChange by rememberUpdatedState(onGeometryChange)

    Box(
        modifier = Modifier
            .align(alignment)
            .size(44.dp)
            .pointerInput(corner, screenWidth, screenHeight) {
                var start = currentGeometry
                detectDragGestures(
                    onDragStart = { start = currentGeometry },
                ) { change, dragAmount ->
                    change.consume()
                    val dx = dragAmount.x.roundToInt()
                    val dy = dragAmount.y.roundToInt()
                    if (dx == 0 && dy == 0) return@detectDragGestures

                    var left = start.left
                    var top = start.top
                    var right = start.right
                    var bottom = start.bottom

                    when (corner) {
                        Corner.NW -> { left += dx; top += dy }
                        Corner.NE -> { right += dx; top += dy }
                        Corner.SW -> { left += dx; bottom += dy }
                        Corner.SE -> { right += dx; bottom += dy }
                    }

                    if (right - left < OverlayGeometry.MIN_SIZE) {
                        if (corner == Corner.NW || corner == Corner.SW) left = right - OverlayGeometry.MIN_SIZE
                        else right = left + OverlayGeometry.MIN_SIZE
                    }
                    if (bottom - top < OverlayGeometry.MIN_SIZE) {
                        if (corner == Corner.NW || corner == Corner.NE) top = bottom - OverlayGeometry.MIN_SIZE
                        else bottom = top + OverlayGeometry.MIN_SIZE
                    }

                    left = left.coerceIn(0, screenWidth)
                    top = top.coerceIn(0, screenHeight)
                    right = right.coerceIn(0, screenWidth)
                    bottom = bottom.coerceIn(0, screenHeight)

                    if (right - left < OverlayGeometry.MIN_SIZE || bottom - top < OverlayGeometry.MIN_SIZE) {
                        return@detectDragGestures
                    }

                    val next = OverlayGeometry(left, top, right, bottom)
                    start = next
                    currentChange(next)
                }
            },
        contentAlignment = when (alignment) {
            Alignment.TopStart -> Alignment.TopStart
            Alignment.TopEnd -> Alignment.TopEnd
            Alignment.BottomStart -> Alignment.BottomStart
            else -> Alignment.BottomEnd
        },
    ) {
        // 视觉手柄 14dp，居中放在 44dp 命中区内
        Box(
            Modifier
                .padding(6.dp)
                .size(14.dp)
                .background(colors.handleFill, RoundedCornerShape(3.dp))
                .border(2.dp, colors.accent, RoundedCornerShape(3.dp))
        )
    }
}
