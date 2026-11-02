package com.regionx.app.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.regionx.app.core.llm.UpstreamModel
import com.regionx.app.ui.components.PrimaryButton
import com.regionx.app.ui.components.SubtleButton
import com.regionx.app.ui.icons.BrandBadge
import com.regionx.app.ui.icons.RegionXIcons
import com.regionx.app.ui.theme.regionXColors

/** A stable pointer coroutine keeps a long press alive while its window moves. */
@Composable
private fun Modifier.floatingDrag(
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier {
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onDragEnd)
    return pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragEnd = { currentEnd() },
            onDragCancel = { currentEnd() },
        ) { change, amount ->
            change.consume()
            currentDrag(amount.x, amount.y)
        }
    }
}

/** Compact answer circle with a small model trigger on the screen-inward side. */
@Composable
fun AnswerBubble(
    busy: Boolean,
    menuOpen: Boolean,
    onAnswer: () -> Unit,
    onToggleModels: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    inwardOnLeft: Boolean = true,
) {
    val colors = regionXColors
    val modelTrigger: @Composable () -> Unit = {
        Box(
            Modifier.width(18.dp).height(FloatingSizes.Ball.dp)
                .clickable(onClick = onToggleModels)
                .semantics { contentDescription = if (menuOpen) "收起模型列表" else "选择上游模型" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(6.dp)) {
                val triangle = Path().apply {
                    if (menuOpen) {
                        moveTo(0f, size.height * .2f)
                        lineTo(size.width, size.height * .2f)
                        lineTo(size.width / 2, size.height * .8f)
                    } else if (inwardOnLeft) {
                        moveTo(size.width * .85f, 0f)
                        lineTo(size.width * .85f, size.height)
                        lineTo(size.width * .15f, size.height / 2)
                    } else {
                        moveTo(size.width * .15f, 0f)
                        lineTo(size.width * .15f, size.height)
                        lineTo(size.width * .85f, size.height / 2)
                    }
                    close()
                }
                drawPath(triangle, if (menuOpen) colors.accent else colors.chromeText)
            }
        }
    }
    Row(
        Modifier.width(FloatingSizes.AnswerWidth.dp).height(FloatingSizes.Ball.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inwardOnLeft) modelTrigger()
        Box(Modifier.size(FloatingSizes.Ball.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.size(36.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .floatingDrag(onDrag, onDragEnd)
                    .clickable(enabled = !busy, onClick = onAnswer)
                    .semantics { contentDescription = if (busy) "正在创建问答任务，长按拖动" else "问答，长按拖动" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(15.dp), color = colors.onAccent, strokeWidth = 1.5.dp)
                } else {
                    Icon(RegionXIcons.Question, null, Modifier.size(17.dp), tint = colors.onAccent)
                }
                Text("问答", color = colors.onAccent, fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!inwardOnLeft) modelTrigger()
    }
}

/** Independent 40dp window; the service owns its position and confirmation state. */
@Composable
fun ExitBubble(
    onExit: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = regionXColors
    Box(Modifier.size(FloatingSizes.Ball.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.size(36.dp)
                .clip(CircleShape)
                .background(colors.chrome)
                .border(1.dp, colors.chromeBorder, CircleShape)
                .floatingDrag(onDrag, onDragEnd)
                .clickable(onClick = onExit)
                .semantics { contentDescription = "退出截图模式，长按拖动" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(RegionXIcons.Close, null, Modifier.size(16.dp), tint = colors.danger)
            Text("退出", color = colors.chromeText, fontSize = 8.sp, lineHeight = 9.sp)
        }
    }
}

/** Unread completions only; the badge stays fully inside the 40dp window. */
@Composable
fun QueueBubble(
    unreadCount: Int,
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colors = regionXColors
    Box(
        Modifier.size(FloatingSizes.Ball.dp)
            .floatingDrag(onDrag, onDragEnd)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "任务队列，${unreadCount.coerceAtLeast(0)} 条未读完成任务，长按拖动" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.size(36.dp).clip(CircleShape).background(colors.chrome)
                .border(1.dp, colors.chromeBorder, CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(RegionXIcons.Queue, null, Modifier.size(16.dp), tint = colors.chromeText)
            Text("队列", color = colors.chromeText, fontSize = 8.sp, lineHeight = 9.sp)
        }
        if (unreadCount > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).height(13.dp)
                    .clip(RoundedCornerShape(7.dp)).background(colors.danger)
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unreadCount > 99) "99+" else unreadCount.toString(),
                    color = colors.onAccent, fontSize = 8.sp, lineHeight = 9.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
        }
    }
}

/** The window provides the fixed height. All variable content lives in a scrollable list. */
@Composable
fun UpstreamModelPanel(
    models: List<UpstreamModel>,
    selectedModel: String?,
    loading: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = regionXColors
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(colors.panel).border(1.dp, colors.border, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择模型", color = colors.textPrimary, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(22.dp).clickable(onClick = onDismiss)
                    .semantics { contentDescription = "关闭模型列表" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(RegionXIcons.Close, null, Modifier.size(13.dp), tint = colors.textSecondary)
            }
        }
        HorizontalDivider(color = colors.borderSoft)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (loading) {
                item {
                    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(13.dp), color = colors.accent, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("正在读取上游模型…", color = colors.textSecondary, fontSize = 10.sp, lineHeight = 13.sp)
                    }
                }
            }
            if (error != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(error, color = colors.danger, fontSize = 10.sp, lineHeight = 13.sp)
                        Text("重试", color = colors.accent, fontSize = 10.sp, lineHeight = 13.sp,
                            modifier = Modifier.clickable(enabled = !loading, onClick = onRetry).padding(vertical = 6.dp))
                    }
                }
            }
            if (!loading && error == null && models.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("上游未返回可用模型", color = colors.textSecondary, fontSize = 10.sp, lineHeight = 13.sp)
                        Text("重新读取", color = colors.accent, fontSize = 10.sp, lineHeight = 13.sp,
                            modifier = Modifier.clickable(onClick = onRetry).padding(vertical = 6.dp))
                    }
                }
            }
            items(models, key = { it.id }) { model ->
                val selected = model.id == selectedModel
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) colors.accentSoft else colors.panel)
                        .clickable { onSelect(model.id) }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandBadge(model.id, 13.dp, if (selected) colors.accent else colors.textSecondary)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            model.label,
                            color = if (selected) colors.accent else colors.textPrimary,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (model.label != model.id) {
                            Text(model.id, color = colors.textSecondary, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (selected) {
                        Spacer(Modifier.width(4.dp))
                        Icon(RegionXIcons.Check, "当前模型", Modifier.size(13.dp), tint = colors.accent)
                    }
                }
            }
        }
    }
}

@Composable
fun ExitConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = regionXColors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(colors.panel)
            .border(1.dp, colors.border, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("退出截图模式？", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("已提交的任务会继续处理。", color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SubtleButton("取消", onDismiss, modifier = Modifier.weight(1f))
            PrimaryButton("退出", onConfirm, modifier = Modifier.weight(1f), height = 36.dp)
        }
    }
}
