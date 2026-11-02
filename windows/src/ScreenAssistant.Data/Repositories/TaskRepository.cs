using Dapper;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Data.Repositories;

public sealed class TaskRow
{
    public string Id { get; set; } = "";
    public string SessionId { get; set; } = "";
    public TaskKind Kind { get; set; }
    public string Title { get; set; } = "";
    public string Summary { get; set; } = "";
    public string FirstLine { get; set; } = "";
    public string? ImagePath { get; set; }
    public int RegionX { get; set; }
    public int RegionY { get; set; }
    public int RegionWidth { get; set; }
    public int RegionHeight { get; set; }
    public TaskState Status { get; set; }
    public string ModelId { get; set; } = "";
    public string ModelName { get; set; } = "";
    public string? Error { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }

    public TaskRecord ToRecord() => new()
    {
        Id = Id,
        SessionId = SessionId,
        Kind = Kind,
        Title = Title,
        Summary = Summary,
        FirstLine = FirstLine,
        ImagePath = ImagePath,
        RegionX = RegionX,
        RegionY = RegionY,
        RegionWidth = RegionWidth,
        RegionHeight = RegionHeight,
        Status = Status,
        ModelId = ModelId,
        ModelName = ModelName,
        Error = Error,
        CreatedAt = CreatedAt,
        UpdatedAt = UpdatedAt
    };
}

public interface ITaskRepository
{
    Task InsertAsync(TaskRecord task);
    Task UpdateAsync(TaskRecord task);
    Task<TaskRecord?> GetAsync(string id);
    Task<IReadOnlyList<TaskRecord>> GetBySessionAsync(string sessionId);
    Task DeleteAsync(string id);
}

public sealed class TaskRepository : ITaskRepository
{
    private const string SelectColumns = """
        SELECT id, session_id AS SessionId, kind, title, summary, first_line AS FirstLine,
               image_path AS ImagePath,
               region_x AS RegionX, region_y AS RegionY, region_w AS RegionWidth, region_h AS RegionHeight,
               status, model_id AS ModelId, model_name AS ModelName, error,
               created_at AS CreatedAt, updated_at AS UpdatedAt
        FROM tasks
        """;

    private readonly Database _db;

    public TaskRepository(Database db)
    {
        _db = db;
        DapperSetup.Register();
    }

    public async Task InsertAsync(TaskRecord task)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO tasks
                (id, session_id, kind, title, summary, first_line, image_path, region_x, region_y, region_w, region_h,
                 status, model_id, model_name, error, created_at, updated_at)
            VALUES
                (@Id, @SessionId, @Kind, @Title, @Summary, @FirstLine, @ImagePath, @RegionX, @RegionY, @RegionWidth, @RegionHeight,
                 @Status, @ModelId, @ModelName, @Error, @CreatedAt, @UpdatedAt)
            """, task)).ConfigureAwait(false);
    }

    public async Task UpdateAsync(TaskRecord task)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition("""
            UPDATE tasks SET
                title = @Title, summary = @Summary, first_line = @FirstLine,
                status = @Status, error = @Error,
                model_id = @ModelId, model_name = @ModelName, updated_at = @UpdatedAt
            WHERE id = @Id
            """, task)).ConfigureAwait(false);
    }

    public async Task<TaskRecord?> GetAsync(string id)
    {
        using var connection = _db.Open();
        var row = await connection.QuerySingleOrDefaultAsync<TaskRow>(new CommandDefinition(
            SelectColumns + " WHERE id = @id", new { id })).ConfigureAwait(false);
        return row?.ToRecord();
    }

    public async Task<IReadOnlyList<TaskRecord>> GetBySessionAsync(string sessionId)
    {
        using var connection = _db.Open();
        var rows = await connection.QueryAsync<TaskRow>(new CommandDefinition(
            SelectColumns + " WHERE session_id = @sessionId ORDER BY created_at ASC",
            new { sessionId })).ConfigureAwait(false);
        return rows.Select(r => r.ToRecord()).ToList();
    }

    public async Task DeleteAsync(string id)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition(
            "DELETE FROM tasks WHERE id = @id", new { id })).ConfigureAwait(false);
    }
}
