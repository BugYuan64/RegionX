package com.regionx.app.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.regionx.app.core.model.TaskStatus
import com.regionx.app.ui.TaskUi
import com.regionx.app.ui.icons.RegionXIcons
import com.regionx.app.ui.theme.regionXColors

/** 与模型面板等大的任务列表；主动关闭时由服务批量标记已读。 */
@Composable
fun FloatingTaskQueue(
    tasks: List<TaskUi>,
    unreadIds: Set<String>,
    onOpenTask: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = regionXColors
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxSize().clip(shape).background(colors.panel).border(1.dp, colors.border, shape)) {
        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("任务队列", color = colors.textPrimary, fontSize = 10.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(22.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(RegionXIcons.Close, "关闭任务队列", Modifier.size(13.dp), tint = colors.textSecondary)
            }
        }
        HorizontalDivider(color = colors.borderSoft)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (tasks.isEmpty()) item {
                Text("暂无任务", color = colors.textSecondary, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(6.dp))
            }
            items(tasks, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().clickable { onOpenTask(task.id) }
                    .background(if (task.id in unreadIds) colors.accentSoft else colors.panel)
                    .padding(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, color = colors.textPrimary, fontSize = 10.sp, lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(4.dp))
                        Text(if (task.record.status == TaskStatus.Completed) {
                            if (task.id in unreadIds) "未读" else "已读"
                        } else task.statusText, color = colors.textSecondary, fontSize = 9.sp, lineHeight = 12.sp)
                    }
                    Text(task.resultLine, color = colors.textSecondary, fontSize = 9.sp, lineHeight = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
