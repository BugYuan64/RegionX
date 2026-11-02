using Dapper;
using Microsoft.Data.Sqlite;

namespace ScreenAssistant.Data;

/// <summary>SQLite 连接与建表。两端共用同一套表结构（见技术文档 5.2）。</summary>
public sealed class Database
{
    private readonly string _connectionString;
    private readonly object _initGate = new();
    private bool _initialized;

    public Database(AppPaths paths)
    {
        Paths = paths;
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = paths.Database,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared
        }.ToString();
    }

    public AppPaths Paths { get; }

    public SqliteConnection Open()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        using var pragma = connection.CreateCommand();
        // WAL 下正常退出会把日志合并回主库；但进程被强制结束时最后一次提交可能只留在 -wal 里。
        // 这里再加 synchronous=FULL，保证 COMMIT 返回时数据已落盘。
        pragma.CommandText = "PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;";
        pragma.ExecuteNonQuery();
        return connection;
    }

    /// <summary>把 WAL 内容合并回主库，保证数据落到 .db 文件本身。</summary>
    public void Checkpoint()
    {
        try
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();
            using var cmd = connection.CreateCommand();
            cmd.CommandText = "PRAGMA wal_checkpoint(TRUNCATE);";
            cmd.ExecuteNonQuery();
        }
        catch (Exception)
        {
            // 检查点失败不影响主流程（下次写入或正常关闭时还会再合并）
        }
    }

    public void Initialize()
    {
        lock (_initGate)
        {
            if (_initialized) return;
            using var connection = Open();
            connection.Execute(Schema);
            Migrate(connection);
            _initialized = true;
        }
    }

    /// <summary>给老版本数据库补齐新增列（CREATE TABLE IF NOT EXISTS 不会修改已有表）。</summary>
    private static void Migrate(SqliteConnection connection)
    {
        var columns = connection.Query<string>("SELECT name FROM pragma_table_info('tasks')").ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (!columns.Contains("first_line"))
        {
            connection.Execute("ALTER TABLE tasks ADD COLUMN first_line TEXT NOT NULL DEFAULT ''");
        }
    }

    private const string Schema = """
        CREATE TABLE IF NOT EXISTS sessions (
            id          TEXT PRIMARY KEY,
            name        TEXT NOT NULL,
            created_at  TEXT NOT NULL,
            updated_at  TEXT NOT NULL
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
            created_at    TEXT NOT NULL,
            updated_at    TEXT NOT NULL,
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS ix_tasks_session ON tasks(session_id, created_at);

        CREATE TABLE IF NOT EXISTS messages (
            id          TEXT PRIMARY KEY,
            task_id     TEXT NOT NULL,
            role        INTEGER NOT NULL,
            content     TEXT NOT NULL,
            image_path  TEXT NULL,
            created_at  TEXT NOT NULL,
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
        """;
}
