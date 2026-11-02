package com.regionx.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 线条图标：路径数据与 Windows 端 Styles/Icons.xaml 完全相同（24×24 视窗、描边绘制）。
 * 用同一个视窗和 2dp 描边，保证两端视觉一致。
 */
private fun lineIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            fill = null,
        )
    }.build()

object RegionXIcons {
    val Scan = lineIcon(
        "scan",
        "M4,9 V5 A1,1 0 0 1 5,4 H9 M15,4 H19 A1,1 0 0 1 20,5 V9 " +
            "M20,15 V19 A1,1 0 0 1 19,20 H15 M9,20 H5 A1,1 0 0 1 4,19 V15"
    )
    val Question = lineIcon(
        "question",
        "M9.5,9.5 A2.5,2.5 0 1 1 13,11.8 C12.3,12.2 12,12.8 12,13.5 M12,17 H12.01"
    )
    val Translate = lineIcon(
        "translate",
        "M4,5 H12 M8,3 V5 M6,5 C6,9 8.5,12 12,13 M10,5 C10,8 8,11 4,13 " +
            "M13,20 L17,11 L21,20 M14.5,17 H19.5"
    )
    val Text = lineIcon("text", "M4,7 V5 H20 V7 M12,5 V19 M9,19 H15")
    val Queue = lineIcon("queue", "M8,6 H21 M8,12 H21 M8,18 H21 M3,6 H3.01 M3,12 H3.01 M3,18 H3.01")
    val Pin = lineIcon(
        "pin",
        "M15,4 L20,9 L17,10 L14,13 L14.5,17 L13,18.5 L9.5,15 L5,20 L4,19 L8.5,14.5 L5,11 L6.5,9.5 L10.5,10 L13.5,7 Z"
    )
    val Close = lineIcon("close", "M6,6 L18,18 M18,6 L6,18")
    val ChevronDown = lineIcon("chevronDown", "M6,9 L12,15 L18,9")
    val ChevronRight = lineIcon("chevronRight", "M9,6 L15,12 L9,18")
    val ChevronLeft = lineIcon("chevronLeft", "M15,6 L9,12 L15,18")
    val Plus = lineIcon("plus", "M12,5 V19 M5,12 H19")
    val Search = lineIcon("search", "M17.5,11 A6.5,6.5 0 1 1 4.5,11 A6.5,6.5 0 1 1 17.5,11 M16,16 L20.5,20.5")
    val Trash = lineIcon("trash", "M4,7 H20 M9,7 V5 H15 V7 M6,7 L7,20 H17 L18,7 M10,11 V16 M14,11 V16")
    val Rename = lineIcon("rename", "M4,20 H8 L19,9 L15,5 L4,16 Z")
    val Copy = lineIcon("copy", "M9,9 H20 V20 H9 Z M15,9 V4 H4 V15 H9")
    val Refresh = lineIcon("refresh", "M20,12 A8,8 0 1 1 17.6,6.4 M20,4 V9 H15")
    val Settings = lineIcon(
        "settings",
        "M12,15 A3,3 0 1 1 12,9 A3,3 0 1 1 12,15 " +
            "M19.4,15 A1.6,1.6 0 0 0 19.7,16.8 L19.8,16.9 A2,2 0 1 1 16.9,19.8 L16.8,19.7 " +
            "A1.6,1.6 0 0 0 15,19.4 A1.6,1.6 0 0 0 14,20.9 V21 A2,2 0 1 1 10,21 V20.9 " +
            "A1.6,1.6 0 0 0 9,19.4 A1.6,1.6 0 0 0 7.2,19.7 L7.1,19.8 A2,2 0 1 1 4.2,16.9 " +
            "L4.3,16.8 A1.6,1.6 0 0 0 4.6,15 A1.6,1.6 0 0 0 3.1,14 H3 A2,2 0 1 1 3,10 H3.1 " +
            "A1.6,1.6 0 0 0 4.6,9 A1.6,1.6 0 0 0 4.3,7.2 L4.2,7.1 A2,2 0 1 1 7.1,4.2 L7.2,4.3 " +
            "A1.6,1.6 0 0 0 9,4.6 A1.6,1.6 0 0 0 10,3.1 V3 A2,2 0 1 1 14,3 V3.1 " +
            "A1.6,1.6 0 0 0 15,4.6 A1.6,1.6 0 0 0 16.8,4.3 L16.9,4.2 A2,2 0 1 1 19.8,7.1 " +
            "L19.7,7.2 A1.6,1.6 0 0 0 19.4,9 V9.1 A1.6,1.6 0 0 0 20.9,10 H21 A2,2 0 1 1 21,14 " +
            "H20.9 A1.6,1.6 0 0 0 19.4,15 Z"
    )
    val Send = lineIcon("send", "M12,19 V5 M6,11 L12,5 L18,11")
    val Check = lineIcon("check", "M4,12 L9,17 L20,6")

    /** 任务类型对应图标。 */
    fun forKind(kind: com.regionx.app.core.model.TaskKind): ImageVector = when (kind) {
        com.regionx.app.core.model.TaskKind.Answer -> Question
        com.regionx.app.core.model.TaskKind.Translate -> Translate
        com.regionx.app.core.model.TaskKind.Extract -> Text
    }
}
