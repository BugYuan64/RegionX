package com.regionx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.ui.components.CaptionText
import com.regionx.app.ui.components.EmptyHint
import com.regionx.app.ui.components.IconAction
import com.regionx.app.ui.components.PageTitle
import com.regionx.app.ui.components.PrimaryButton
import com.regionx.app.ui.components.RegionXTextField
import com.regionx.app.ui.components.StatusDot
import com.regionx.app.ui.components.statusColor
import com.regionx.app.ui.icons.RegionXIcons
import com.regionx.app.ui.theme.regionXColors

/**
 * 主界面 · 历史会话（对应 UI 方案 6.1 / 7.7 / 8.3）：
 * 会话为可折叠卡片，任务行为「图标 + 标题 + 首行 + 状态 + 时间」，底部是「开始识别」。
 */
@Composable
fun SessionsScreen(
    viewModel: MainViewModel,
    sessions: List<SessionUi>,
    expandedSessionId: String?,
    sessionTasks: Map<String, List<TaskUi>>,
    activeModelName: String,
    searchText: String,
    onStart: () -> Unit,
    overlayRunning: Boolean,
    needsAuthorization: Boolean = false,
    onOpenSettings: () -> Unit,
) {
    val colors = regionXColors
    var renameTarget by remember { mutableStateOf<SessionUi?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionUi?>(null) }

    Column(Modifier.fillMaxSize().background(colors.windowBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                PageTitle("历史会话")
                Spacer(Modifier.height(4.dp))
                CaptionText("共 ${sessions.size} 个会话，${sessions.sumOf { it.taskCount }} 个任务")
            }
            IconAction(RegionXIcons.Settings, "设置", onOpenSettings)
        }

        if (sessions.size > 4 || searchText.isNotEmpty()) {
            RegionXTextField(
                value = searchText,
                onValueChange = viewModel::updateSearch,
                placeholder = "搜索会话或任务",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
        }

        if (sessions.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyHint(
                    "还没有会话。点下方「开始识别」启动截图，再点悬浮球问答。默认截取全屏，可在设置中切换区域模式。",
                    icon = RegionXIcons.Scan,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.session.id }) { row ->
                    SessionCard(
                        row = row,
                        expanded = row.session.id == expandedSessionId,
                        tasks = sessionTasks[row.session.id].orEmpty(),
                        onToggle = { viewModel.toggleSession(row.session.id) },
                        onRename = { renameTarget = row },
                        onDelete = { deleteTarget = row },
                        onTaskClick = { task -> viewModel.openDetail(task.id) },
                        onTaskRetry = { task -> viewModel.retryTask(task.id) },
                        onTaskCancel = { task -> viewModel.cancelTask(task.id) },
                        onTaskDelete = { task -> viewModel.deleteTask(task.record) },
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = if (needsAuthorization) "开始识别 · 重新授权" else "开始识别",
                onClick = onStart,
                icon = RegionXIcons.Scan,
                height = 52.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            CaptionText(
                if (overlayRunning) "当前模型：$activeModelName · 下拉最近会话可看实时结果"
                else "当前模型：$activeModelName · 点击悬浮球问答后可在此查看任务"
            )
        }
    }

    renameTarget?.let { target ->
        var text by remember(target) { mutableStateOf(target.session.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话", fontSize = 16.sp) },
            text = { RegionXTextField(text, { text = it }, placeholder = "会话名称") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(target.session.id, text)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
            containerColor = colors.panel,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话", fontSize = 16.sp) },
            text = {
                Text(
                    "删除「${target.displayName}」及其中 ${target.taskCount} 个任务？截图文件也会一并删除，且无法恢复。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSession(target.session.id)
                    deleteTarget = null
                }) { Text("删除", color = regionXColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            containerColor = colors.panel,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
        )
    }
}

@Composable
private fun SessionCard(
    row: SessionUi,
    expanded: Boolean,
    tasks: List<TaskUi>,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTaskClick: (TaskUi) -> Unit,
    onTaskRetry: (TaskUi) -> Unit,
    onTaskCancel: (TaskUi) -> Unit,
    onTaskDelete: (TaskUi) -> Unit,
) {
    val colors = regionXColors

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                RegionXIcons.ChevronRight,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                row.displayName,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            CaptionText("${row.taskCount} 个任务")
            Spacer(Modifier.weight(1f))
            if (row.hasActiveTask) {
                Text("进行中", color = colors.accent, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
            }
            IconAction(RegionXIcons.Rename, "重命名", onRename, size = 16.dp)
            IconAction(RegionXIcons.Trash, "删除会话", onDelete, tint = colors.danger, size = 16.dp)
        }

        if (expanded) {
            if (tasks.isEmpty()) {
                CaptionText("这个会话还没有任务。", Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
            }
            tasks.forEach { task ->
                TaskRow(
                    task = task,
                    onClick = { onTaskClick(task) },
                    onRetry = { onTaskRetry(task) },
                    onCancel = { onTaskCancel(task) },
                    onDelete = { onTaskDelete(task) },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskUi,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = regionXColors

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(colors.surfaceHover, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                RegionXIcons.forKind(task.record.kind),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                task.resultLine,
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))

        if (task.canCancel) {
            SmallAction("取消", onCancel)
        } else if (task.canRetry) {
            SmallAction("重试", onRetry)
        }
        StatusDot(task.record.status)
        Spacer(Modifier.width(5.dp))
        Text(task.statusText, color = statusColor(task.record.status), fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        IconAction(RegionXIcons.Trash, "删除任务", onDelete, tint = colors.textTertiary, size = 14.dp)
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = regionXColors.accent,
        fontSize = 11.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
