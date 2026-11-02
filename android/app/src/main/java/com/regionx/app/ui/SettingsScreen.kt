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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regionx.app.core.model.AppSettings
import com.regionx.app.core.model.CaptureMode
import com.regionx.app.core.model.LlmProvider
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.ui.components.CaptionText
import com.regionx.app.ui.components.IconAction
import com.regionx.app.ui.components.PrimaryButton
import com.regionx.app.ui.components.RegionXTextField
import com.regionx.app.ui.components.SectionTitle
import com.regionx.app.ui.components.SubtleButton
import com.regionx.app.ui.icons.BrandBadge
import com.regionx.app.ui.icons.RegionXIcons
import com.regionx.app.ui.theme.ThemeRegistry
import com.regionx.app.ui.theme.regionXColors

/** 设置页：模型配置 / 提示词 / 通用 / 主题（对应需求 7.2 / 7.3 / 8.10 / 8.11）。 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    models: List<ModelConfig>,
    activeModelId: String?,
    settings: AppSettings,
    status: String,
    isError: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
) {
    val colors = regionXColors
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("上游", "提示词", "通用", "主题")

    Column(Modifier.fillMaxSize().background(colors.windowBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(RegionXIcons.ChevronLeft, "返回", onBack)
            Spacer(Modifier.width(4.dp))
            Text("设置", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = index == tab
                Box(
                    Modifier
                        .background(
                            if (selected) colors.accentSoft else colors.surfaceAlt,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable { tab = index }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        color = if (selected) colors.accent else colors.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                color = if (isError) colors.danger else colors.success,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ModelsTab(viewModel, models, activeModelId, isSaving)
                1 -> PromptsTab(viewModel, settings, isSaving)
                2 -> GeneralTab(viewModel, settings, isSaving)
                else -> ThemeTab(viewModel, settings.themeName)
            }
        }
    }
}

@Composable
private fun ModelsTab(
    viewModel: SettingsViewModel,
    models: List<ModelConfig>,
    activeModelId: String?,
    isSaving: Boolean,
) {
    val colors = regionXColors
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<ModelConfig?>(null) }
    var upstreamMenu by remember { mutableStateOf(false) }
    val currentEditor = editor

    if (currentEditor != null) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                ModelEditorPanel(viewModel, currentEditor, isSaving)
            }
            if (!currentEditor.isNew) {
                TextButton(
                    onClick = { deleteTarget = models.firstOrNull { it.id == currentEditor.id } },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text("删除此配置", color = colors.danger) }
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SectionTitle("上游配置")
            Spacer(Modifier.height(6.dp))
            CaptionText("每次只启用一个上游。点选配置即可切换，右侧「编辑」修改该配置；API Key 在本机加密保存。")
            Spacer(Modifier.height(12.dp))
            val active = models.firstOrNull { it.id == activeModelId }
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(10.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .clickable { upstreamMenu = true }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            active?.name ?: "请选择或添加上游配置",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        active?.let { CaptionText("${it.provider.displayName} · ${it.modelName}") }
                    }
                    Icon(RegionXIcons.ChevronDown, contentDescription = "选择上游配置", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = upstreamMenu,
                    onDismissRequest = { upstreamMenu = false },
                    containerColor = colors.panel,
                    modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 360.dp),
                ) {
                    models.forEach { config ->
                        val selected = config.id == activeModelId
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        config.name,
                                        color = if (selected) colors.accent else colors.textPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    CaptionText(if (selected) "当前启用 · ${config.modelName}" else config.modelName)
                                }
                            },
                            trailingIcon = {
                                TextButton(onClick = {
                                    upstreamMenu = false
                                    viewModel.selectModel(config)
                                }) { Text("编辑", color = colors.accent) }
                            },
                            onClick = {
                                upstreamMenu = false
                                viewModel.activate(config)
                            },
                        )
                    }
                    if (models.isNotEmpty()) HorizontalDivider(color = colors.borderSoft)
                    DropdownMenuItem(
                        text = { Text("添加配置", color = colors.accent, fontSize = 14.sp) },
                        leadingIcon = { Icon(RegionXIcons.Plus, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            upstreamMenu = false
                            viewModel.addModel()
                        },
                    )
                }
            }
            if (models.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                CaptionText("还没有上游配置，展开下拉菜单并点「添加配置」。")
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除上游配置", fontSize = 16.sp) },
            text = { Text("删除「${target.name}」？此操作无法恢复。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModel(target)
                    deleteTarget = null
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            containerColor = colors.panel,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
        )
    }
}

@Composable
private fun ModelEditorPanel(
    viewModel: SettingsViewModel,
    editor: ModelEditor,
    isSaving: Boolean,
) {
    val colors = regionXColors
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val catalogLoading by viewModel.catalogLoading.collectAsStateWithLifecycle()
    val catalogError by viewModel.catalogError.collectAsStateWithLifecycle()
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(if (editor.isNew) "添加上游配置" else "编辑上游配置")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::closeEditor) { Text("返回列表", color = colors.textSecondary) }
        }

        Spacer(Modifier.height(8.dp))
        RegionXTextField(editor.name, { value -> viewModel.updateEditor { it.copy(name = value) } }, label = "配置名称")
        Spacer(Modifier.height(10.dp))

        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .clickable { providerMenu = true }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("接口类型", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(editor.provider.displayName, color = colors.textPrimary, fontSize = 14.sp)
                Icon(
                    RegionXIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.padding(start = 6.dp).size(14.dp),
                )
            }
            DropdownMenu(
                expanded = providerMenu,
                onDismissRequest = { providerMenu = false },
                containerColor = colors.panel,
            ) {
                LlmProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                provider.displayName,
                                color = if (provider == editor.provider) colors.accent else colors.textPrimary,
                                fontSize = 14.sp,
                            )
                        },
                        onClick = {
                            providerMenu = false
                            viewModel.updateEditor {
                                it.copy(
                                    provider = provider,
                                    baseUrl = when (provider) {
                                        LlmProvider.OpenAiCompatible -> ModelConfig.DefaultOpenAiBaseUrl
                                        LlmProvider.Claude -> ModelConfig.DefaultClaudeBaseUrl
                                        LlmProvider.Gemini -> ModelConfig.DefaultGeminiBaseUrl
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        RegionXTextField(
            editor.baseUrl,
            { value -> viewModel.updateEditor { it.copy(baseUrl = value) } },
            label = "Base URL",
            placeholder = "https://api.example.com/v1（可留空用官方地址）",
        )
        Spacer(Modifier.height(10.dp))
        RegionXTextField(
            editor.apiKey,
            { value -> viewModel.updateEditor { it.copy(apiKey = value) } },
            label = "API Key",
            placeholder = "不会明文保存",
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                RegionXTextField(
                    editor.modelName,
                    { value -> viewModel.updateEditor { it.copy(modelName = value) } },
                    label = "模型名",
                    placeholder = "如 gpt-4o-mini",
                )
            }
            Spacer(Modifier.width(8.dp))
            SubtleButton(if (catalogLoading) "拉取中…" else "拉取列表", {
                if (!catalogLoading) viewModel.loadCatalog()
            })
        }
        if (catalogLoading) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                CaptionText("正在读取上游模型列表…")
            }
        }
        catalogError?.let {
            Text(it, color = colors.danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }

        if (catalog.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box {
                SubtleButton("选择上游模型（${catalog.size}）", { modelMenu = true })
                DropdownMenu(
                    expanded = modelMenu,
                    onDismissRequest = { modelMenu = false },
                    containerColor = colors.panel,
                    modifier = Modifier.height(280.dp),
                ) {
                    catalog.forEach { item ->
                        DropdownMenuItem(
                            leadingIcon = { BrandBadge(item.id, 16.dp, colors.textSecondary) },
                            text = {
                                Text(
                                    item.label,
                                    color = if (item.id == editor.modelName) colors.accent else colors.textPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                if (item.id == editor.modelName) {
                                    Icon(
                                        RegionXIcons.Check,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                            onClick = {
                                modelMenu = false
                                viewModel.updateEditor { it.copy(modelName = item.id) }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clickable { viewModel.updateEditor { it.copy(supportsImages = !it.supportsImages) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = editor.supportsImages,
                    onCheckedChange = { value -> viewModel.updateEditor { it.copy(supportsImages = value) } },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accent,
                        uncheckedColor = colors.borderStrong,
                        checkmarkColor = colors.onAccent,
                    ),
                )
                Text("支持图片输入", color = colors.textPrimary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RegionXTextField(
                editor.temperature,
                { value -> viewModel.updateEditor { it.copy(temperature = value) } },
                label = "温度（可空）",
                modifier = Modifier.weight(1f),
            )
            RegionXTextField(
                editor.maxTokens,
                { value -> viewModel.updateEditor { it.copy(maxTokens = value) } },
                label = "max_tokens（可空）",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        RegionXTextField(
            editor.extraHeaders,
            { value -> viewModel.updateEditor { it.copy(extraHeaders = value) } },
            label = "附加请求头（每行 Name: Value）",
            singleLine = false,
            minLines = 2,
            maxLines = 4,
        )

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                "保存",
                onClick = { viewModel.saveModel(alsoActivate = false) },
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                "保存并设为当前",
                onClick = { viewModel.saveModel(alsoActivate = true) },
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PromptsTab(viewModel: SettingsViewModel, settings: AppSettings, isSaving: Boolean) {
    var answer by remember(settings.answerPrompt) { mutableStateOf(settings.answerPrompt) }
    var followUp by remember(settings.followUpPrompt) { mutableStateOf(settings.followUpPrompt) }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        CaptionText("问答结果的第一行作为任务标题与队列首行，修改模板时请保留「第一行给结果」的约定。")
        Spacer(Modifier.height(12.dp))

        PromptField("问答模板", answer) { answer = it }
        Spacer(Modifier.height(12.dp))
        PromptField("追问模板", followUp) { followUp = it }

        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            "保存提示词",
            onClick = {
                viewModel.saveGeneral(
                    answerPrompt = answer,
                    translatePrompt = settings.translatePrompt,
                    translateTarget = settings.translateTargetLanguage,
                    extractPrompt = settings.extractPrompt,
                    followUpPrompt = followUp,
                    includeImage = settings.includeImageOnFollowUp,
                    proxyUrl = settings.proxyUrl.orEmpty(),
                    maxConcurrency = settings.maxConcurrency,
                    timeoutSeconds = settings.timeoutSeconds,
                    captureDelayMs = settings.captureDelayMs,
                )
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PromptField(label: String, value: String, onChange: (String) -> Unit) {
    RegionXTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        singleLine = false,
        minLines = 3,
        maxLines = 8,
    )
}

@Composable
private fun GeneralTab(viewModel: SettingsViewModel, settings: AppSettings, isSaving: Boolean) {
    val colors = regionXColors
    var proxy by remember(settings.proxyUrl) { mutableStateOf(settings.proxyUrl.orEmpty()) }
    var concurrency by remember(settings.maxConcurrency) { mutableStateOf(settings.maxConcurrency.toString()) }
    var timeout by remember(settings.timeoutSeconds) { mutableStateOf(settings.timeoutSeconds.toString()) }
    var delayMs by remember(settings.captureDelayMs) { mutableStateOf(settings.captureDelayMs.toString()) }
    var includeImage by remember(settings.includeImageOnFollowUp) { mutableStateOf(settings.includeImageOnFollowUp) }
    var captureMode by remember(settings.captureMode) { mutableStateOf(settings.captureMode) }
    var captureMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        CaptionText("截图只会发送到当前启用的上游接口。")
        Spacer(Modifier.height(12.dp))

        SectionTitle("截图方式")
        Spacer(Modifier.height(8.dp))
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .clickable { captureMenu = true }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (captureMode == CaptureMode.FullScreen) "全屏截图（默认）" else "区域截图（可调整识别框）", color = colors.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(RegionXIcons.ChevronDown, contentDescription = "选择截图方式", tint = colors.textTertiary, modifier = Modifier.size(14.dp))
            }
            DropdownMenu(expanded = captureMenu, onDismissRequest = { captureMenu = false }, containerColor = colors.panel) {
                CaptureMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(if (mode == CaptureMode.FullScreen) "全屏截图（默认）" else "区域截图（可调整识别框）", color = if (mode == captureMode) colors.accent else colors.textPrimary) },
                        onClick = { captureMode = mode; captureMenu = false },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        CaptionText("全屏模式直接截取屏幕；区域模式使用可拖动、缩放的识别框。保存后生效。")
        Spacer(Modifier.height(16.dp))

        RegionXTextField(proxy, { proxy = it }, label = "HTTP 代理（可空）", placeholder = "http://127.0.0.1:7890")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RegionXTextField(
                concurrency,
                { concurrency = it.filter(Char::isDigit) },
                label = "并发任务数（1-5）",
                modifier = Modifier.weight(1f),
            )
            RegionXTextField(
                timeout,
                { timeout = it.filter(Char::isDigit) },
                label = "超时（秒）",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        RegionXTextField(
            delayMs,
            { delayMs = it.filter(Char::isDigit) },
            label = "截图前延迟（毫秒，等待画面稳定）",
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("追问时默认附带原截图", color = colors.textPrimary, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = includeImage,
                onCheckedChange = { includeImage = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.textTertiary,
                    uncheckedTrackColor = colors.surfaceAlt,
                ),
            )
        }

        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            "保存通用设置",
            onClick = {
                viewModel.saveGeneral(
                    answerPrompt = settings.answerPrompt,
                    translatePrompt = settings.translatePrompt,
                    translateTarget = settings.translateTargetLanguage,
                    extractPrompt = settings.extractPrompt,
                    followUpPrompt = settings.followUpPrompt,
                    includeImage = includeImage,
                    proxyUrl = proxy,
                    maxConcurrency = concurrency.toIntOrNull() ?: settings.maxConcurrency,
                    timeoutSeconds = timeout.toIntOrNull() ?: settings.timeoutSeconds,
                    captureDelayMs = delayMs.toIntOrNull() ?: settings.captureDelayMs,
                    captureMode = captureMode,
                )
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ThemeTab(viewModel: SettingsViewModel, themeId: String) {
    val colors = regionXColors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        CaptionText("主题通过语义令牌统一控制，切换后立即生效。")
        Spacer(Modifier.height(12.dp))
        ThemeRegistry.all.forEach { theme ->
            val selected = theme.id == themeId
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(10.dp))
                    .border(1.dp, if (selected) colors.accent else colors.border, RoundedCornerShape(10.dp))
                    .clickable { viewModel.selectTheme(theme.id) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                theme.preview.forEach { color ->
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(color, RoundedCornerShape(4.dp))
                            .border(1.dp, colors.borderSoft, RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(theme.displayName, color = colors.textPrimary, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(RegionXIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.borderSoft)
        Spacer(Modifier.height(12.dp))
        SectionTitle("关于")
        Spacer(Modifier.height(6.dp))
        CaptionText("RegionX Android · 数据全部保存在本机应用私有目录，不含任何统计或遥测。")
    }
}
