package com.regionx.app.data

import com.regionx.app.core.model.AppSettings
import com.regionx.app.core.model.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 模型配置仓库：读写 models.json，Key 与附加请求头用 Keystore 加密后落盘。
 * 语义与 Windows 端 ModelConfigRepository 对齐：
 * - 读取时按「模型名 → 配置名 → Id」排序，列表稳定；
 * - 写入时临时文件 + 原子替换，避免写一半断电导致配置丢失。
 */
class ModelConfigRepository(
    private val database: RegionXDatabase,
    private val secrets: SecretStore,
) {
    private val mutex = Mutex()

    suspend fun all(): List<ModelConfig> = mutex.withLock { readRows().map { it.toConfig() }.sortedForDisplay() }

    suspend fun get(id: String): ModelConfig? = all().firstOrNull { it.id == id }

    suspend fun insert(config: ModelConfig) = mutex.withLock {
        val rows = readRows().toMutableList()
        require(rows.none { it.id == config.id }) { "模型配置已存在。" }
        rows += config.toRow(sortOrder = rows.size)
        writeRows(rows)
    }

    suspend fun update(config: ModelConfig) = mutex.withLock {
        val rows = readRows().toMutableList()
        val index = rows.indexOfFirst { it.id == config.id }
        require(index >= 0) { "模型配置已删除，请重新新增。" }
        rows[index] = config.toRow(rows[index].sortOrder)
        writeRows(rows)
    }

    suspend fun delete(id: String) = mutex.withLock {
        writeRows(readRows().filterNot { it.id == id })
    }

    private suspend fun readRows(): List<ModelConfigRow> = withContext(Dispatchers.IO) {
        database.modelRows()
    }

    private suspend fun writeRows(rows: List<ModelConfigRow>) = withContext(Dispatchers.IO) {
        database.writeModelRows(rows)
    }

    private fun ModelConfigRow.toConfig(): ModelConfig = ModelConfig(
        id = id,
        name = name,
        provider = provider,
        baseUrl = baseUrl,
        // Windows 端的 dpapi: 密文在这里解不开：返回空串，让用户重新填写 Key
        apiKey = secrets.unprotect(apiKeyEnc),
        modelName = modelName,
        supportsImages = supportsImages,
        temperature = temperature,
        maxTokens = maxTokens,
        extraHeaders = secrets.unprotect(extraHeadersEnc).ifEmpty { null },
    )

    private fun ModelConfig.toRow(sortOrder: Int): ModelConfigRow = ModelConfigRow(
        id = id,
        name = name,
        provider = provider,
        baseUrl = baseUrl,
        apiKeyEnc = if (apiKey.isEmpty()) "" else secrets.protect(apiKey),
        modelName = modelName,
        supportsImages = supportsImages,
        temperature = temperature,
        maxTokens = maxTokens,
        extraHeadersEnc = if (extraHeaders.isNullOrEmpty()) "" else secrets.protect(extraHeaders),
        sortOrder = sortOrder,
    )

    private fun List<ModelConfig>.sortedForDisplay(): List<ModelConfig> = sortedWith(
        compareBy<ModelConfig, String>(String.CASE_INSENSITIVE_ORDER) { it.modelName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.id }
    )
}

/** 设置仓库：读写 settings.json，写入失败抛异常由调用方提示。 */
class SettingsRepository(private val database: RegionXDatabase) {
    private val mutex = Mutex()
    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun load(): AppSettings = mutex.withLock {
        val text = database.settingsFileText() ?: return@withLock AppSettings()
        runCatching { json.decodeFromString<AppSettings>(text) }
            .getOrElse { AppSettings() }
            .normalized()
    }

    suspend fun save(settings: AppSettings) = mutex.withLock {
        database.writeSettingsFile(json.encodeToString(settings.normalized()))
    }
}
