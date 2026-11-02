package com.regionx.app.data

import android.content.Context
import com.regionx.app.core.model.ChatRole
import com.regionx.app.core.model.LlmProvider
import com.regionx.app.core.model.MessageRecord
import com.regionx.app.core.model.SessionRecord
import com.regionx.app.core.model.TaskKind
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import com.regionx.app.core.model.TaskConversation
import com.regionx.app.core.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地数据存储。表结构与 Windows 端一致（sessions / tasks / messages / model_configs / settings），
 * 用 SQLite 直连（不用 Room，避免注解处理与构建依赖），所有读写都在 IO 线程串行执行。
 */
class RegionXDatabase(context: Context) {

    private val appContext = context.applicationContext
    private val dbFile = File(appContext.filesDir, "screenassistant.db")
    private val imagesDir = File(appContext.filesDir, "images")
    private val logsDir = File(appContext.filesDir, "logs")
    private val gate = Mutex()

    private val helper: android.database.sqlite.SQLiteOpenHelper by lazy {
        object : android.database.sqlite.SQLiteOpenHelper(appContext, dbFile.absolutePath, null, 1) {
            override fun onConfigure(db: android.database.sqlite.SQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
                // execSQL 一次只执行一条语句：按分号拆开逐条执行建表脚本
                for (statement in SCHEMA.split(';')) {
                    val sql = statement.trim()
                    if (sql.isNotEmpty()) db.execSQL(sql)
                }
            }

            override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // 当前版本 1；以后新增列在这里用 ALTER TABLE 补齐
            }
        }
    }

    fun imagesDirectory(): File {
        if (!imagesDir.exists()) imagesDir.mkdirs()
        return imagesDir
    }

    fun imageFile(relativePath: String?): File? {
        if (relativePath.isNullOrBlank()) return null
        return File(imagesDir, relativePath)
    }

    fun logsDirectory(): File {
        if (!logsDir.exists()) logsDir.mkdirs()
        return logsDir
    }

    // ---------------------------------------------------------------- sessions

    suspend fun insertSession(session: SessionRecord) = write { db ->
        db.insertOrThrow("sessions", null, android.content.ContentValues().apply {
            put("id", session.id)
            put("name", session.name)
            put("created_at", session.createdAt)
            put("updated_at", session.updatedAt)
        })
    }

    suspend fun sessions(): List<SessionRow> = read { db ->
        val sql = """
            SELECT s.id, s.name, s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM tasks t WHERE t.session_id = s.id) AS task_count
            FROM sessions s ORDER BY s.created_at DESC
        """.trimIndent()
        db.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SessionRow(
                            SessionRecord(
                                id = cursor.getString(0),
                                name = cursor.getString(1),
                                createdAt = cursor.getLong(2),
                                updatedAt = cursor.getLong(3),
                            ),
                            taskCount = cursor.getInt(4),
                        )
                    )
                }
            }
        }
    }

    suspend fun session(id: String): SessionRecord? = read { db ->
        db.rawQuery("SELECT id, name, created_at, updated_at FROM sessions WHERE id = ?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) null else SessionRecord(
                id = cursor.getString(0),
                name = cursor.getString(1),
                createdAt = cursor.getLong(2),
                updatedAt = cursor.getLong(3),
            )
        }
    }

    suspend fun touchSession(id: String, updatedAt: Long = System.currentTimeMillis()) = write { db ->
        db.execSQL("UPDATE sessions SET updated_at = ? WHERE id = ?", arrayOf<Any>(updatedAt, id))
    }

    suspend fun renameSession(id: String, name: String) = write { db ->
        db.execSQL("UPDATE sessions SET name = ? WHERE id = ?", arrayOf<Any>(name, id))
    }

    suspend fun deleteSession(id: String) = write { db ->
        db.delete("sessions", "id = ?", arrayOf(id))
    }

    // ------------------------------------------------------------------- tasks

    suspend fun insertTask(task: TaskRecord) = write { db ->
        db.insertOrThrow("tasks", null, taskValues(task))
    }

    suspend fun updateTask(task: TaskRecord) = write { db ->
        val values = android.content.ContentValues().apply {
            put("title", task.title)
            put("summary", task.summary)
            put("first_line", task.firstLine)
            put("status", task.status.ordinal)
            put("error", task.error)
            put("model_id", task.modelId)
            put("model_name", task.modelName)
            put("updated_at", task.updatedAt)
        }
        db.update("tasks", values, "id = ?", arrayOf(task.id))
    }

    suspend fun tasks(sessionId: String): List<TaskRecord> = read { db ->
        db.rawQuery("$SELECT_TASK WHERE session_id = ? ORDER BY created_at ASC", arrayOf(sessionId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTask()) }
        }
    }

    suspend fun task(id: String): TaskRecord? = read { db ->
        db.rawQuery("$SELECT_TASK WHERE id = ?", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTask() else null
        }
    }

    suspend fun deleteTask(id: String) = write { db ->
        db.delete("tasks", "id = ?", arrayOf(id))
    }

    /** 启动时把上次异常退出留下的「处理中 / 排队中」任务标为已取消，避免界面永远转圈。 */
    suspend fun recoverInterruptedTasks() = write { db ->
        db.execSQL(
            "UPDATE tasks SET status = ?, error = ? WHERE status IN (?, ?)",
            arrayOf<Any>(
                TaskStatus.Cancelled.ordinal,
                "应用上次退出时任务未完成，可点击重试。",
                TaskStatus.Queued.ordinal,
                TaskStatus.Running.ordinal,
            )
        )
    }

    // ---------------------------------------------------------------- messages

    suspend fun insertMessage(message: MessageRecord) = write { db ->
        db.insertOrThrow("messages", null, android.content.ContentValues().apply {
            put("id", message.id)
            put("task_id", message.taskId)
            put("role", message.role.ordinal)
            put("content", message.content)
            put("image_path", message.imagePath)
            put("created_at", message.createdAt)
        })
    }

    /** Retry replaces the original answer in-place and leaves follow-up history untouched. */
    suspend fun savePrimaryAnswer(task: TaskRecord, content: String) = write { db ->
        val conversation = TaskConversation.from(task.id, readMessages(db, task.id))
        val previous = conversation.answer
        if (previous != null) {
            db.execSQL("UPDATE messages SET content = ? WHERE id = ?", arrayOf(content, previous.id))
        } else {
            db.insertOrThrow("messages", null, android.content.ContentValues().apply {
                put("id", TaskConversation.answerId(task.id))
                put("task_id", task.id)
                put("role", ChatRole.Assistant.ordinal)
                put("content", content)
                put("created_at", conversation.initial?.createdAt ?: task.createdAt)
            })
        }
    }

    suspend fun messages(taskId: String): List<MessageRecord> = read { db -> readMessages(db, taskId) }

    private fun readMessages(db: android.database.sqlite.SQLiteDatabase, taskId: String): List<MessageRecord> =
        db.rawQuery(
            "SELECT id, task_id, role, content, image_path, created_at FROM messages WHERE task_id = ? ORDER BY created_at ASC, rowid ASC",
            arrayOf(taskId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MessageRecord(
                            id = cursor.getString(0),
                            taskId = cursor.getString(1),
                            role = if (cursor.getInt(2) == ChatRole.Assistant.ordinal) ChatRole.Assistant else ChatRole.User,
                            content = cursor.getString(3),
                            imagePath = cursor.getStringOrNull(4),
                            createdAt = cursor.getLong(5),
                        )
                    )
                }
            }
        }

    // --------------------------------------------------------- model configs

    private val modelsFile by lazy { File(appContext.filesDir, "models.json") }
    private val settingsFile by lazy { File(appContext.filesDir, "settings.json") }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val modelFileMutex = Mutex()

    suspend fun modelRows(): List<ModelConfigRow> = withContext(Dispatchers.IO) {
        modelFileMutex.withLock {
            if (!modelsFile.exists()) {
                // 首次运行：从数据库迁移（旧版本或跨端复制来的库）
                val migrated = migrateModelsFromDatabase()
                writeModelFile(migrated)
                return@withLock migrated
            }
            runCatching { json.decodeFromString<List<ModelConfigRow>>(modelsFile.readText()) }.getOrDefault(emptyList())
        }
    }

    suspend fun writeModelRows(rows: List<ModelConfigRow>) = withContext(Dispatchers.IO) {
        modelFileMutex.withLock { writeModelFile(rows) }
    }

    private fun writeModelFile(rows: List<ModelConfigRow>) {
        val temp = File(modelsFile.parentFile, modelsFile.name + ".tmp")
        temp.writeText(json.encodeToString(rows))
        if (!temp.renameTo(modelsFile)) {
            modelsFile.delete()
            temp.renameTo(modelsFile)
        }
    }

    private suspend fun migrateModelsFromDatabase(): List<ModelConfigRow> = read { db ->
        db.rawQuery(
            """SELECT id, name, provider, base_url, api_key_enc, model_name, supports_images,
                      temperature, max_tokens, extra_headers, sort_order
               FROM model_configs ORDER BY sort_order, name""",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ModelConfigRow(
                            id = cursor.getString(0),
                            name = cursor.getString(1),
                            provider = LlmProvider.entries.getOrElse(cursor.getInt(2)) { LlmProvider.OpenAiCompatible },
                            baseUrl = cursor.getString(3),
                            // 旧库里的 Key 可能是 DPAPI 密文（Windows 端），无法在本机解开：
                            // 保留原值让上层识别为「需要重新填写」，而不是静默丢失。
                            apiKeyEnc = cursor.getString(4),
                            modelName = cursor.getString(5),
                            supportsImages = cursor.getInt(6) != 0,
                            temperature = cursor.getDoubleOrNull(7),
                            maxTokens = cursor.getIntOrNull(8),
                            extraHeadersEnc = cursor.getStringOrNull(9),
                            sortOrder = cursor.getInt(10),
                        )
                    )
                }
            }
        }
    }

    suspend fun settingsFileText(): String? = withContext(Dispatchers.IO) {
        if (settingsFile.exists()) settingsFile.readText() else null
    }

    suspend fun writeSettingsFile(text: String) = withContext(Dispatchers.IO) {
        val temp = File(settingsFile.parentFile, settingsFile.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(settingsFile)) {
            settingsFile.delete()
            temp.renameTo(settingsFile)
        }
    }

    fun settingsPath(): String = settingsFile.absolutePath
    fun modelsPath(): String = modelsFile.absolutePath
    fun dataPath(): String = dbFile.parentFile?.absolutePath.orEmpty()

    // --------------------------------------------------------------- internals

    private suspend fun <T> read(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            gate.withLock { block(helper.readableDatabase) }
        }

    private suspend fun <T> write(block: (android.database.sqlite.SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val result = block(db)
                    db.setTransactionSuccessful()
                    result
                } finally {
                    db.endTransaction()
                }
            }
        }

    private fun taskValues(task: TaskRecord) = android.content.ContentValues().apply {
        put("id", task.id)
        put("session_id", task.sessionId)
        put("kind", task.kind.ordinal)
        put("title", task.title)
        put("summary", task.summary)
        put("first_line", task.firstLine)
        put("image_path", task.imagePath)
        put("region_x", task.regionX)
        put("region_y", task.regionY)
        put("region_w", task.regionWidth)
        put("region_h", task.regionHeight)
        put("status", task.status.ordinal)
        put("model_id", task.modelId)
        put("model_name", task.modelName)
        put("error", task.error)
        put("created_at", task.createdAt)
        put("updated_at", task.updatedAt)
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.getDoubleOrNull(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun android.database.Cursor.toTask(): TaskRecord = TaskRecord(
        id = getString(0),
        sessionId = getString(1),
        kind = TaskKind.entries.getOrElse(getInt(2)) { TaskKind.Answer },
        title = getString(3),
        summary = getString(4),
        firstLine = getString(5),
        imagePath = getStringOrNull(6),
        regionX = getInt(7),
        regionY = getInt(8),
        regionWidth = getInt(9),
        regionHeight = getInt(10),
        status = TaskStatus.entries.getOrElse(getInt(11)) { TaskStatus.Queued },
        modelId = getString(12),
        modelName = getString(13),
        error = getStringOrNull(14),
        createdAt = getLong(15),
        updatedAt = getLong(16),
    )

    data class SessionRow(val session: SessionRecord, val taskCount: Int)

    private companion object {
        const val SELECT_TASK =
            "SELECT id, session_id, kind, title, summary, first_line, image_path, region_x, region_y, " +
                "region_w, region_h, status, model_id, model_name, error, created_at, updated_at FROM tasks"

        val SCHEMA = """
            CREATE TABLE IF NOT EXISTS sessions (
                id          TEXT PRIMARY KEY,
                name        TEXT NOT NULL,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS tasks (
                id            TEXT PRIMARY KEY,
                session_id    TEXT NOT NULL,
                kind          INTEGER NOT NULL,
                title         TEXT NOT NULL DEFAULT '',
                summary       TEXT NOT NULL DEFAULT '',
                first_line    TEXT NOT NULL DEFAULT '',
                image_path    TEXT NULL,
                region_x      INTEGER NOT NULL DEFAULT 0,
                region_y      INTEGER NOT NULL DEFAULT 0,
                region_w      INTEGER NOT NULL DEFAULT 0,
                region_h      INTEGER NOT NULL DEFAULT 0,
                status        INTEGER NOT NULL DEFAULT 0,
                model_id      TEXT NOT NULL DEFAULT '',
                model_name    TEXT NOT NULL DEFAULT '',
                error         TEXT NULL,
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS ix_tasks_session ON tasks(session_id, created_at);

            CREATE TABLE IF NOT EXISTS messages (
                id          TEXT PRIMARY KEY,
                task_id     TEXT NOT NULL,
                role        INTEGER NOT NULL,
                content     TEXT NOT NULL,
                image_path  TEXT NULL,
                created_at  INTEGER NOT NULL,
                FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS ix_messages_task ON messages(task_id, created_at);

            CREATE TABLE IF NOT EXISTS model_configs (
                id              TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                provider        INTEGER NOT NULL,
                base_url        TEXT NOT NULL DEFAULT '',
                api_key_enc     TEXT NOT NULL DEFAULT '',
                model_name      TEXT NOT NULL DEFAULT '',
                supports_images INTEGER NOT NULL DEFAULT 1,
                temperature     REAL NULL,
                max_tokens      INTEGER NULL,
                extra_headers   TEXT NULL,
                sort_order      INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
        """.trimIndent()
    }
}

/** 本地模型配置行（models.json 里的元素）。字段名与 Windows 端一致。 */
@Serializable
data class ModelConfigRow(
    @SerialName("Id") val id: String = newId(),
    @SerialName("Name") val name: String = "",
    @SerialName("Provider") val provider: LlmProvider = LlmProvider.OpenAiCompatible,
    @SerialName("BaseUrl") val baseUrl: String = "",
    /** 加密后的 Key（aes-gcm: 前缀）。 */
    @SerialName("ApiKeyEnc") val apiKeyEnc: String = "",
    @SerialName("ModelName") val modelName: String = "",
    @SerialName("SupportsImages") val supportsImages: Boolean = true,
    @SerialName("Temperature") val temperature: Double? = null,
    @SerialName("MaxTokens") val maxTokens: Int? = null,
    @SerialName("ExtraHeadersEnc") val extraHeadersEnc: String? = null,
    @SerialName("SortOrder") val sortOrder: Int = 0,
)
