using Dapper;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Data.Repositories;

public sealed class MessageRow
{
    public string Id { get; set; } = "";
    public string TaskId { get; set; } = "";
    public ChatRole Role { get; set; }
    public string Content { get; set; } = "";
    public string? ImagePath { get; set; }
    public DateTimeOffset CreatedAt { get; set; }

    public MessageRecord ToRecord() => new()
    {
        Id = Id,
        TaskId = TaskId,
        Role = Role,
        Content = Content,
        ImagePath = ImagePath,
        CreatedAt = CreatedAt
    };
}

public interface IMessageRepository
{
    Task InsertAsync(MessageRecord message);
    Task<IReadOnlyList<MessageRecord>> GetByTaskAsync(string taskId);
    Task DeleteByTaskAsync(string taskId);

    /// <summary>写入或覆盖任务的 AI 回复（重试时不产生重复消息）。</summary>
    Task ReplaceAssistantAsync(string taskId, string content);
}

public sealed class MessageRepository : IMessageRepository
{
    private readonly Database _db;

    public MessageRepository(Database db)
    {
        _db = db;
        DapperSetup.Register();
    }

    public async Task InsertAsync(MessageRecord message)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO messages (id, task_id, role, content, image_path, created_at)
            VALUES (@Id, @TaskId, @Role, @Content, @ImagePath, @CreatedAt)
            """, message)).ConfigureAwait(false);
    }

    public async Task<IReadOnlyList<MessageRecord>> GetByTaskAsync(string taskId)
    {
        using var connection = _db.Open();
        var rows = await connection.QueryAsync<MessageRow>(new CommandDefinition("""
            SELECT id, task_id AS TaskId, role, content, image_path AS ImagePath, created_at AS CreatedAt
            FROM messages WHERE task_id = @taskId ORDER BY created_at ASC
            """, new { taskId })).ConfigureAwait(false);
        return rows.Select(r => r.ToRecord()).ToList();
    }

    public async Task DeleteByTaskAsync(string taskId)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition(
            "DELETE FROM messages WHERE task_id = @taskId", new { taskId })).ConfigureAwait(false);
    }

    public async Task ReplaceAssistantAsync(string taskId, string content)
    {
        using var connection = _db.Open();
        // 保留最早那条助手消息的 id 与创建时间，只更新内容
        var affected = await connection.ExecuteAsync(new CommandDefinition("""
            UPDATE messages SET content = @content
            WHERE id = (
                SELECT id FROM messages
                WHERE task_id = @taskId AND role = @role
                ORDER BY created_at ASC LIMIT 1
            )
            """, new { taskId, content, role = (int)ChatRole.Assistant })).ConfigureAwait(false);

        if (affected == 0)
        {
            await connection.ExecuteAsync(new CommandDefinition("""
                INSERT INTO messages (id, task_id, role, content, image_path, created_at)
                VALUES (@Id, @TaskId, @Role, @Content, NULL, @CreatedAt)
                """, new MessageRecord
            {
                TaskId = taskId,
                Role = ChatRole.Assistant,
                Content = content
            })).ConfigureAwait(false);
        }
    }
}
