package com.regionx.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.regionx.app.ui.components.CaptionText
import com.regionx.app.ui.components.IconAction
import com.regionx.app.ui.components.PrimaryButton
import com.regionx.app.ui.components.RegionXTextField
import com.regionx.app.ui.components.StatusDot
import com.regionx.app.ui.components.SubtleButton
import com.regionx.app.ui.icons.RegionXIcons
import com.regionx.app.ui.theme.regionXColors
import java.io.File

/**
 * 任务详情（对应 UI 方案 6.1 / 6.6 / 7.7）：
 * 顶部操作类型徽章 + 模型 + 时间；依次为截图、我的要求、AI 回复、追问轮次；
 * 底部是追问输入框与「追问时附带原截图」开关。
 */
@Composable
fun TaskDetailScreen(
    detail: TaskDetailUi,
    imageFile: File?,
    includeImage: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSendFollowUp: (String) -> Unit,
    onStopFollowUp: () -> Unit,
    onToggleIncludeImage: (Boolean) -> Unit,
    floating: Boolean = false,
) {
    val colors = regionXColors
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember(detail.task.id) { mutableStateOf("") }

    // 追问流式增长时保持在最新内容
    LaunchedEffect(detail.followUps.size, detail.isStreaming) {
        if (detail.followUps.isNotEmpty()) listState.animateScrollToItem(detail.followUps.size)
    }

    Column(Modifier.fillMaxSize().background(colors.windowBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(if (floating) RegionXIcons.Close else RegionXIcons.ChevronLeft, if (floating) "关闭对话窗口" else "返回", onBack)
            Spacer(Modifier.width(4.dp))
            Text(
                TaskUi(detail.task).title,
                color = colors.textPrimary,
                fontSize = if (floating) 13.sp else 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (!floating) {
                KindBadge(detail.task.kind.displayName)
                SubtleButton("重新回答", onRetry)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp,
            ),
        ) {
            item {
                Column {
                    CaptionText(
                        "截图 · ${detail.task.regionWidth} × ${detail.task.regionHeight}" +
                            " · ${detail.task.modelName.ifBlank { "—" }} · ${detail.sessionName}"
                    )
                    Spacer(Modifier.height(8.dp))
                    val bitmap = remember(imageFile?.absolutePath) {
                        imageFile?.let { path ->
                            runCatching {
                                android.graphics.BitmapFactory.decodeFile(path.absolutePath)?.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (floating) 120.dp else 320.dp)
                            .background(colors.paper, RoundedCornerShape(8.dp))
                            .padding(4.dp),
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "任务截图",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(
                                "截图文件已不存在",
                                color = colors.paperText,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    CaptionText("我的要求：${detail.task.kind.displayName}")
                    Spacer(Modifier.height(16.dp))
                    CaptionText("AI 回复")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        detail.mainAnswer.ifBlank { "暂无内容" },
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        lineHeight = 26.sp,
                    )

                    if (!detail.task.error.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(detail.task.status)
                            Spacer(Modifier.width(6.dp))
                            Text(detail.task.error.orEmpty(), color = colors.danger, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubtleButton("复制", {
                            copyToClipboard(context, detail.mainAnswer)
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        })
                        SubtleButton("重新回答", onRetry)
                        if (detail.isStreaming) {
                            SubtleButton("停止生成", onStopFollowUp, danger = true)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = colors.borderSoft)
                    Spacer(Modifier.height(12.dp))
                }
            }

            items(detail.followUps, key = { it.id }) { message ->
                if (message.isUser) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.82f)
                                .background(colors.surfaceAlt, RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(message.content, color = colors.textPrimary, fontSize = 14.sp)
                        }
                    }
                } else {
                    Text(
                        message.content.ifEmpty { if (detail.isStreaming) "正在生成…" else "" },
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        lineHeight = 26.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .background(colors.windowBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RegionXTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "追问…",
                    modifier = Modifier.weight(1f),
                    enabled = !detail.isStreaming,
                )
                Spacer(Modifier.width(8.dp))
                if (detail.isStreaming) {
                    PrimaryButton("停止", onClick = onStopFollowUp, height = 44.dp)
                } else {
                    PrimaryButton(
                        "发送",
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                onSendFollowUp(text)
                                input = ""
                            }
                        },
                        icon = RegionXIcons.Send,
                        height = 44.dp,
                        enabled = input.isNotBlank(),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = includeImage,
                    onCheckedChange = onToggleIncludeImage,
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accent,
                        uncheckedColor = colors.borderStrong,
                        checkmarkColor = colors.onAccent,
                    ),
                )
                Text("追问时附带原截图", color = colors.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun KindBadge(text: String) {
    val colors = regionXColors
    Box(
        Modifier
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = colors.accent, fontSize = 11.sp)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("RegionX", text))
}
