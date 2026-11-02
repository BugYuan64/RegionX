package com.regionx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 语义化颜色令牌，与 Windows 端 Styles/Themes 下的主题文件一一对应。
 * 界面只引用这些令牌，不再写死颜色值；新增主题只需在这里多加一个
 * [RegionXColors] 实例并登记到 [ThemeRegistry]。
 */
@Immutable
data class RegionXColors(
    // 强调色
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val accentSoft: Color,
    val onAccent: Color,

    // 背景与表面
    val windowBackground: Color,
    val titleBar: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceHover: Color,
    val surfacePressed: Color,
    val panel: Color,

    // 描边
    val border: Color,
    val borderSoft: Color,
    val borderStrong: Color,
    val scrollThumb: Color,

    // 文字
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    // 语义色
    val success: Color,
    val warning: Color,
    val danger: Color,
    val pending: Color,
    val successSoft: Color,
    val warningSoft: Color,
    val dangerSoft: Color,

    // 识别框
    val handleFill: Color,
    val chrome: Color,
    val chromeBorder: Color,
    val chromeText: Color,

    // 截图预览：浅色纸张，两种主题一致
    val paper: Color,
    val paperText: Color,

    val isDark: Boolean,
)

val DarkColors = RegionXColors(
    accent = Color(0xFFFFB224),
    accentHover = Color(0xFFFFC14F),
    accentPressed = Color(0xFFE39A12),
    accentSoft = Color(0xFF3A2E14),
    onAccent = Color(0xFF1A1405),

    windowBackground = Color(0xFF17181C),
    titleBar = Color(0xFF1D1F24),
    surface = Color(0xFF212328),
    surfaceAlt = Color(0xFF2A2D33),
    surfaceHover = Color(0xFF2F3238),
    surfacePressed = Color(0xFF34373E),
    panel = Color(0xFF1D1F24),

    border = Color(0xFF34373E),
    borderSoft = Color(0xFF2B2E35),
    borderStrong = Color(0xFF44484F),
    scrollThumb = Color(0xFF3A3D45),

    textPrimary = Color(0xFFECEDEF),
    textSecondary = Color(0xFFA3A7B0),
    textTertiary = Color(0xFF6B707A),

    success = Color(0xFF5FD39A),
    warning = Color(0xFFFFB224),
    danger = Color(0xFFFF7A72),
    pending = Color(0xFF6B707A),
    successSoft = Color(0xFF1E2F27),
    warningSoft = Color(0xFF3A2E14),
    dangerSoft = Color(0xFF3A2124),

    handleFill = Color(0xFFFFF6E0),
    chrome = Color(0xF21D1F24),
    chromeBorder = Color(0xFF3A3D45),
    chromeText = Color(0xFFECEDEF),

    paper = Color(0xFFF2EFE8),
    paperText = Color(0xFF1F1F1F),
    isDark = true,
)

val LightColors = RegionXColors(
    accent = Color(0xFF2F6FED),
    accentHover = Color(0xFF4C84F2),
    accentPressed = Color(0xFF255BC7),
    accentSoft = Color(0xFFE8F0FE),
    onAccent = Color(0xFFFFFFFF),

    windowBackground = Color(0xFFF7F8FA),
    titleBar = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF1F3F6),
    surfaceHover = Color(0xFFE9EDF3),
    surfacePressed = Color(0xFFDCE2EA),
    panel = Color(0xFFFFFFFF),

    border = Color(0xFFD8DDE5),
    borderSoft = Color(0xFFE6EAF0),
    borderStrong = Color(0xFFB9C1CD),
    scrollThumb = Color(0xFFC4CBD6),

    textPrimary = Color(0xFF1B1F27),
    textSecondary = Color(0xFF5A6270),
    textTertiary = Color(0xFF8A929E),

    success = Color(0xFF1F9D55),
    warning = Color(0xFFC77700),
    danger = Color(0xFFD93838),
    pending = Color(0xFF8A929E),
    successSoft = Color(0xFFE7F6EE),
    warningSoft = Color(0xFFFDF3E2),
    dangerSoft = Color(0xFFFDEBEB),

    handleFill = Color(0xFFEAF1FF),
    chrome = Color(0xF2FFFFFF),
    chromeBorder = Color(0xFFD8DDE5),
    chromeText = Color(0xFF1B1F27),

    paper = Color(0xFFF2EFE8),
    paperText = Color(0xFF1F1F1F),
    isDark = false,
)

/** 主题定义：Id 与 Windows 端 settings.json 的 ThemeName 保持一致。 */
data class RegionXTheme(
    val id: String,
    val displayName: String,
    val colors: RegionXColors,
    val preview: List<Color>,
)

object ThemeRegistry {
    val dark = RegionXTheme("dark", "深色", DarkColors, listOf(DarkColors.windowBackground, DarkColors.accent, DarkColors.borderStrong))
    val light = RegionXTheme("light", "浅色", LightColors, listOf(LightColors.windowBackground, LightColors.accent, LightColors.borderStrong))
    val system = RegionXTheme("system", "跟随系统", DarkColors, listOf(DarkColors.windowBackground, LightColors.accent, LightColors.borderStrong))

    val all: List<RegionXTheme> = listOf(system, dark, light)

    fun find(id: String?): RegionXTheme = all.firstOrNull { it.id == id } ?: system

    /** 实际生效的主题（system 按系统深浅色解析）。 */
    fun resolve(id: String?, systemDark: Boolean): RegionXTheme = when (find(id).id) {
        "light" -> light
        "dark" -> dark
        else -> if (systemDark) dark else light
    }
}

val LocalRegionXColors = staticCompositionLocalOf { DarkColors }

private val UiTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp),
)

@Composable
fun RegionXTheme(
    themeId: String,
    content: @Composable () -> Unit,
) {
    val theme = ThemeRegistry.resolve(themeId, isSystemInDarkTheme())
    val colors = theme.colors

    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.windowBackground,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceAlt,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.windowBackground,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceAlt,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(LocalRegionXColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = UiTypography, content = content)
    }
}

/** 简写：主题令牌。 */
val regionXColors: RegionXColors
    @Composable get() = LocalRegionXColors.current
