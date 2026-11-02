package com.regionx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.regionx.app.core.model.TaskStatus
import com.regionx.app.ui.theme.regionXColors

/** 主按钮（强调色胶囊）。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 44.dp,
) {
    val colors = regionXColors
    Row(
        modifier = modifier
            .height(height)
            .background(
                if (enabled) colors.accent else colors.surfaceAlt,
                RoundedCornerShape(height / 2),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) colors.onAccent else colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (enabled) colors.onAccent else colors.textTertiary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 次按钮（描边）。 */
@Composable
fun SubtleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val colors = regionXColors
    val contentColor = when {
        !enabled -> colors.textTertiary
        danger -> colors.danger
        else -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .height(34.dp)
            .background(colors.surface, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 纯图标按钮。 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 18.dp,
) {
    val colors = regionXColors
    Box(
        modifier = modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint ?: colors.textSecondary, modifier = Modifier.size(size))
    }
}

/** 状态色圆点。 */
@Composable
fun StatusDot(status: TaskStatus, size: Dp = 7.dp) {
    Box(Modifier.size(size).background(statusColor(status), CircleShape))
}

@Composable
fun statusColor(status: TaskStatus): Color {
    val colors = regionXColors
    return when (status) {
        TaskStatus.Completed -> colors.success
        TaskStatus.Running -> colors.warning
        TaskStatus.Queued -> colors.pending
        TaskStatus.Failed -> colors.danger
        TaskStatus.Cancelled -> colors.pending
    }
}

/** 页面标题。 */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextStyle(
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = regionXColors.textPrimary,
        ),
        modifier = modifier,
    )
}

/** 次要说明文字。 */
@Composable
fun CaptionText(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, color = regionXColors.textSecondary, modifier = modifier)
}

/** 分组标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = regionXColors.textSecondary,
        modifier = modifier,
    )
}

/** 主题化的输入框（保证深色/浅色主题下文字可读）。 */
@Composable
fun RegionXTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    minLines: Int = 1,
    maxLines: Int = 1,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val colors = regionXColors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it, fontSize = 12.sp) } },
        placeholder = placeholder?.let { { Text(it, fontSize = 13.sp, color = colors.textTertiary) } },
        textStyle = TextStyle(fontSize = 14.sp, color = colors.textPrimary),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        isError = isError,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            disabledTextColor = colors.textTertiary,
            cursorColor = colors.accent,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.border,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textSecondary,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surfaceAlt,
            errorBorderColor = colors.danger,
        ),
    )
}

/** 可折叠卡片的箭头，随展开状态旋转。 */
@Composable
fun Chevron(expanded: Boolean) {
    Icon(
        com.regionx.app.ui.icons.RegionXIcons.ChevronDown,
        contentDescription = if (expanded) "收起" else "展开",
        tint = regionXColors.textTertiary,
        modifier = Modifier
            .size(16.dp)
            .rotate(if (expanded) 180f else 0f),
    )
}

/** 空状态提示。 */
@Composable
fun EmptyHint(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val colors = regionXColors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.borderStrong, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(14.dp))
        }
        Text(text, fontSize = 13.sp, color = colors.textSecondary)
    }
}
