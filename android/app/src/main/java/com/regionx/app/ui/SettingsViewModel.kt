package com.regionx.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regionx.app.AppContainer
import com.regionx.app.core.llm.LlmException
import com.regionx.app.core.llm.UpstreamModel
import com.regionx.app.core.model.AppSettings
import com.regionx.app.core.model.CaptureMode
import com.regionx.app.core.model.LlmProvider
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.newId
import com.regionx.app.ui.theme.ThemeRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 设置页里一条模型配置的编辑态。 */
data class ModelEditor(
    val id: String,
    val name: String = "新上游配置",
    val provider: LlmProvider = LlmProvider.OpenAiCompatible,
    val baseUrl: String = ModelConfig.DefaultOpenAiBaseUrl,
    val apiKey: String = "",
    val modelName: String = "",
    val supportsImages: Boolean = true,
    val temperature: String = "",
    val maxTokens: String = "",
    val extraHeaders: String = "",
    val isNew: Boolean = true,
) {
    fun validate(): String? {
        if (name.isBlank()) return "请填写配置名称。"
        if (modelName.isBlank()) return "请填写模型名。"
        if (baseUrl.isNotBlank() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return "Base URL 必须是 http:// 或 https:// 开头的绝对地址。"
        }
        if (temperature.isNotBlank()) {
            val value = temperature.toDoubleOrNull() ?: return "温度需填数字。"
            if (value < 0 || value > 2) return "温度需在 0 到 2 之间。"
        }
        if (maxTokens.isNotBlank() && (maxTokens.toIntOrNull() ?: 0) <= 0) return "max_tokens 需为正整数。"
        return null
    }

    fun toConfig(): ModelConfig = ModelConfig(
        id = id,
        name = name.trim(),
        provider = provider,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        modelName = modelName.trim(),
        supportsImages = supportsImages,
        temperature = temperature.toDoubleOrNull(),
        maxTokens = maxTokens.toIntOrNull(),
        extraHeaders = extraHeaders.takeIf { it.isNotBlank() },
    )

    companion object {
        fun from(config: ModelConfig) = ModelEditor(
            id = config.id,
            name = config.name,
            provider = config.provider,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.modelName,
            supportsImages = config.supportsImages,
            temperature = config.temperature?.toString().orEmpty(),
            maxTokens = config.maxTokens?.toString().orEmpty(),
            extraHeaders = config.extraHeaders.orEmpty(),
            isNew = false,
        )
    }
}

/** 设置页（模型配置 / 提示词 / 通用 / 主题）。 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _models = MutableStateFlow<List<ModelConfig>>(emptyList())
    val models: StateFlow<List<ModelConfig>> = _models.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    /** 正在编辑的配置；null 表示显示配置列表。 */
    private val _editor = MutableStateFlow<ModelEditor?>(null)
    val editor: StateFlow<ModelEditor?> = _editor.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isError = MutableStateFlow(false)
    val isError: StateFlow<Boolean> = _isError.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /** 拉取到的上游模型列表与状态。 */
    private val _catalog = MutableStateFlow<List<UpstreamModel>>(emptyList())
    val catalog: StateFlow<List<UpstreamModel>> = _catalog.asStateFlow()

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    private var catalogJob: Job? = null
    private var catalogGeneration = 0L

    val themes get() = ThemeRegistry.all

    init {
        viewModelScope.launch { reload() }
        viewModelScope.launch {
            container.settings.collect { settings ->
                _settings.value = settings
                _activeModelId.value = settings.activeModelId ?: _models.value.firstOrNull()?.id
            }
        }
    }

    suspend fun reload() {
        container.initialize()
        _settings.value = container.settings.value
        _models.value = container.modelRepository.all()
        _activeModelId.value = container.settings.value.activeModelId ?: _models.value.firstOrNull()?.id
    }

    fun selectModel(config: ModelConfig) {
        invalidateCatalog()
        _editor.value = ModelEditor.from(config)
        setStatus("")
    }

    fun addModel() {
        invalidateCatalog()
        _editor.value = ModelEditor(id = newId())
        setStatus("")
    }

    fun closeEditor() {
        invalidateCatalog()
        _editor.value = null
        setStatus("")
    }

    fun updateEditor(mutate: (ModelEditor) -> ModelEditor) {
        val previous = _editor.value ?: return
        val next = mutate(previous)
        if (!sameCatalogSource(next, previous)) invalidateCatalog()
        _editor.value = next
    }

    private fun sameCatalogSource(current: ModelEditor?, snapshot: ModelEditor): Boolean =
        current != null && current.id == snapshot.id &&
            current.provider == snapshot.provider && current.baseUrl == snapshot.baseUrl &&
            current.apiKey == snapshot.apiKey && current.extraHeaders == snapshot.extraHeaders

    private fun invalidateCatalog() {
        // 先使旧回调失效，再取消请求，避免它的 finally 修改新请求的状态。
        catalogGeneration++
        catalogJob?.cancel()
        catalogJob = null
        _catalog.value = emptyList()
        _catalogError.value = null
        _catalogLoading.value = false
    }

    private fun setStatus(message: String, isError: Boolean = false) {
        _status.value = message
        _isError.value = isError
    }

    /** 拉取上游模型列表；失败时允许手填模型名。 */
    fun loadCatalog() {
        val editor = _editor.value ?: return
        val generation = ++catalogGeneration
        catalogJob?.cancel()
        _catalogLoading.value = true
        _catalogError.value = null
        catalogJob = viewModelScope.launch {
            fun isCurrent() = generation == catalogGeneration && sameCatalogSource(_editor.value, editor)
            try {
                val models = container.catalog.listModels(editor.toConfig())
                if (!isCurrent()) return@launch
                _catalog.value = models
                if (models.isEmpty()) _catalogError.value = "上游没有返回任何模型，可手动填写模型名。"
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: LlmException) {
                if (isCurrent()) {
                    _catalog.value = emptyList()
                    _catalogError.value = ex.message
                }
            } catch (ex: Exception) {
                if (isCurrent()) {
                    _catalog.value = emptyList()
                    _catalogError.value = "拉取失败：${ex.message}"
                }
            } finally {
                if (isCurrent()) _catalogLoading.value = false
            }
        }
    }

    /** 保存当前编辑的配置（新增或更新）。成功后清空编辑区，与 Windows 端行为一致。 */
    fun saveModel(alsoActivate: Boolean = false) {
        val editor = _editor.value ?: return
        val error = editor.validate()
        if (error != null) {
            setStatus(error, isError = true)
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val config = editor.toConfig()
                val previous = container.modelRepository.get(config.id)
                val currentActive = container.activeModel()?.id
                if (previous != null) container.modelRepository.update(config) else container.modelRepository.insert(config)

                if (currentActive == config.id && previous?.modelName != config.modelName) {
                    container.updateSettings {
                        if (it.activeModelId == config.id || (it.activeModelId == null && currentActive == config.id)) {
                            it.copy(activeUpstreamModelName = null)
                        } else it
                    }
                }
                if (alsoActivate || currentActive == null) {
                    container.selectUpstream(config.id)
                }

                closeEditor()
                reload()
                setStatus(if (alsoActivate) "已保存，当前使用「${config.name}」。" else "已保存模型「${config.name}」。")
            } catch (ex: Exception) {
                setStatus("保存失败：${ex.message}", isError = true)
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteModel(config: ModelConfig) {
        viewModelScope.launch {
            try {
                val active = container.activeModel()?.id
                container.modelRepository.delete(config.id)
                if (active == config.id) {
                    val next = container.modelRepository.all().firstOrNull()?.id
                    if (next != null) container.selectUpstream(next)
                    else container.updateSettings { it.copy(activeModelId = null, activeUpstreamModelName = null) }
                }
                closeEditor()
                reload()
                setStatus("已删除。")
            } catch (ex: Exception) {
                setStatus("删除失败：${ex.message}", isError = true)
            }
        }
    }

    fun activate(config: ModelConfig) {
        viewModelScope.launch {
            try {
                container.selectUpstream(config.id)
                setStatus("当前使用「${config.name}」。")
            } catch (ex: Exception) {
                setStatus("切换失败：${ex.message}", isError = true)
            }
        }
    }

    /** 通用设置保存：单次写入，避免多处同时改设置。 */
    fun saveGeneral(
        answerPrompt: String,
        translatePrompt: String,
        translateTarget: String,
        extractPrompt: String,
        followUpPrompt: String,
        includeImage: Boolean,
        proxyUrl: String,
        maxConcurrency: Int,
        timeoutSeconds: Int,
        captureDelayMs: Int,
        captureMode: CaptureMode = container.settings.value.captureMode,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                container.updateSettings {
                    it.copy(
                        answerPrompt = answerPrompt,
                        translatePrompt = translatePrompt,
                        translateTargetLanguage = translateTarget,
                        extractPrompt = extractPrompt,
                        followUpPrompt = followUpPrompt,
                        includeImageOnFollowUp = includeImage,
                        proxyUrl = proxyUrl.trim().ifEmpty { null },
                        maxConcurrency = maxConcurrency,
                        timeoutSeconds = timeoutSeconds,
                        captureDelayMs = captureDelayMs,
                        captureMode = captureMode,
                    )
                }
                _settings.value = container.settings.value
                setStatus("设置已保存。")
            } catch (ex: Exception) {
                setStatus("保存失败：${ex.message}", isError = true)
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun selectTheme(themeId: String) {
        viewModelScope.launch {
            container.updateSettings { it.copy(themeName = themeId) }
            _settings.value = container.settings.value
            setStatus("已切换主题。")
        }
    }
}
