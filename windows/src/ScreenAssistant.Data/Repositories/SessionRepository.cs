using Dapper;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Data.Repositories;

public sealed class SessionRow
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
    public int TaskCount { get; set; }
}

public interface ISessionRepository
{
    Task<IReadOnlyList<SessionRow>> GetAllAsync();
    Task<SessionRecord?> GetAsync(string id);
    Task InsertAsync(SessionRecord session);
    Task TouchAsync(string id, DateTimeOffset updatedAt);
    Task RenameAsync(string id, string name);
    Task DeleteAsync(string id);
}

public sealed class SessionRepository : ISessionRepository
{
    private readonly Database _db;

    public SessionRepository(Database db)
    {
        _db = db;
        DapperSetup.Register();
    }

    public async Task<IReadOnlyList<SessionRow>> GetAllAsync()
    {
        using var connection = _db.Open();
        var rows = await connection.QueryAsync<SessionRow>(new CommandDefinition("""
            SELECT s.id, s.name, s.created_at AS CreatedAt, s.updated_at AS UpdatedAt,
                   (SELECT COUNT(*) FROM tasks t WHERE t.session_id = s.id) AS TaskCount
            FROM sessions s
            ORDER BY s.created_at DESC
            """)).ConfigureAwait(false);
        return rows.AsList();
    }

    public async Task<SessionRecord?> GetAsync(string id)
    {
        using var connection = _db.Open();
        return await connection.QuerySingleOrDefaultAsync<SessionRecord>(new CommandDefinition("""
            SELECT id, name, created_at AS CreatedAt, updated_at AS UpdatedAt
            FROM sessions WHERE id = @id
            """, new { id })).ConfigureAwait(false);
    }

    public async Task InsertAsync(SessionRecord session)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO sessions (id, name, created_at, updated_at)
            VALUES (@Id, @Name, @CreatedAt, @UpdatedAt)
            """, session)).ConfigureAwait(false);
    }

    public async Task TouchAsync(string id, DateTimeOffset updatedAt)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition(
            "UPDATE sessions SET updated_at = @updatedAt WHERE id = @id",
            new { id, updatedAt })).ConfigureAwait(false);
    }

    public async Task RenameAsync(string id, string name)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition(
            "UPDATE sessions SET name = @name WHERE id = @id",
            new { id, name })).ConfigureAwait(false);
    }

    public async Task DeleteAsync(string id)
    {
        using var connection = _db.Open();
        await connection.ExecuteAsync(new CommandDefinition(
            "DELETE FROM sessions WHERE id = @id", new { id })).ConfigureAwait(false);
    }
}
