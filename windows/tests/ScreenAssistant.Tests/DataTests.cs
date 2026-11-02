using ScreenAssistant.Core.Models;
using ScreenAssistant.Data;
using ScreenAssistant.Data.Repositories;

namespace ScreenAssistant.Tests;

/// <summary>每个测试用独立的临时目录，避免互相干扰。</summary>
public sealed class TempWorkspace : IDisposable
{
    private readonly AppPaths _paths;

    public TempWorkspace()
    {
        _paths = new AppPaths(Path.Combine(Path.GetTempPath(), "ScreenAssistantTests", Guid.NewGuid().ToString("N")));
        Database = new Database(_paths);
        Database.Initialize();
    }

    public Database Database { get; }
    public AppPaths Paths => _paths;

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_paths.Root)) Directory.Delete(_paths.Root, recursive: true);
        }
        catch (IOException)
        {
            // SQLite 连接释放有延迟时忽略
        }
    }
}

public class DataTests
{
    [Fact]
    public async Task SessionAndTask_RoundTrip_PreservesFields()
    {
        using var workspace = new TempWorkspace();
        var sessions = new SessionRepository(workspace.Database);
        var tasks = new TaskRepository(workspace.Database);

        var created = new DateTimeOffset(2026, 10, 28, 9, 30, 0, TimeSpan.FromHours(8));
        var session = new SessionRecord { Name = "2026-10-28 09:30", CreatedAt = created, UpdatedAt = created };
        await sessions.InsertAsync(session);

        var task = new TaskRecord
        {
            SessionId = session.Id,
            Kind = TaskKind.Translate,
            Title = "翻译结果",
            Summary = "这是一段简介",
            ImagePath = "abc.png",
            RegionX = 10,
            RegionY = 20,
            RegionWidth = 300,
            RegionHeight = 200,
            Status = TaskState.Completed,
            ModelId = "m1",
            ModelName = "GPT",
            CreatedAt = created,
            UpdatedAt = created
        };
        await tasks.InsertAsync(task);

        var loaded = await tasks.GetAsync(task.Id);
        Assert.NotNull(loaded);
        Assert.Equal(TaskKind.Translate, loaded!.Kind);
        Assert.Equal(TaskState.Completed, loaded.Status);
        Assert.Equal(created, loaded.CreatedAt);
        Assert.Equal((10, 20, 300, 200), (loaded.RegionX, loaded.RegionY, loaded.RegionWidth, loaded.RegionHeight));

        var rows = await sessions.GetAllAsync();
        Assert.Equal(1, Assert.Single(rows).TaskCount);
    }

    [Fact]
    public async Task DeletingSession_CascadesToTasksAndMessages()
    {
        using var workspace = new TempWorkspace();
        var sessions = new SessionRepository(workspace.Database);
        var tasks = new TaskRepository(workspace.Database);
        var messages = new MessageRepository(workspace.Database);

        var session = new SessionRecord { Name = "s" };
        await sessions.InsertAsync(session);
        var task = new TaskRecord { SessionId = session.Id };
        await tasks.InsertAsync(task);
        await messages.InsertAsync(new MessageRecord { TaskId = task.Id, Role = ChatRole.User, Content = "hi" });

        await sessions.DeleteAsync(session.Id);

        Assert.Null(await tasks.GetAsync(task.Id));
        Assert.Empty(await messages.GetByTaskAsync(task.Id));
    }

    [Fact]
    public async Task ReplaceAssistant_UpdatesInPlace_InsteadOfDuplicating()
    {
        using var workspace = new TempWorkspace();
        var sessions = new SessionRepository(workspace.Database);
        var tasks = new TaskRepository(workspace.Database);
        var messages = new MessageRepository(workspace.Database);

        var session = new SessionRecord { Name = "s" };
        await sessions.InsertAsync(session);
        var task = new TaskRecord { SessionId = session.Id };
        await tasks.InsertAsync(task);
        await messages.InsertAsync(new MessageRecord { TaskId = task.Id, Role = ChatRole.User, Content = "问题" });

        await messages.ReplaceAssistantAsync(task.Id, "第一版回答");
        await messages.ReplaceAssistantAsync(task.Id, "重试后的回答");

        var all = await messages.GetByTaskAsync(task.Id);
        Assert.Equal(2, all.Count);
        Assert.Equal("问题", all[0].Content);
        Assert.Equal("重试后的回答", all[1].Content);
    }

    [Fact]
    public async Task ModelConfig_ApiKeyIsEncryptedAtRest()
    {
        using var workspace = new TempWorkspace();
        var repository = new ModelConfigRepository(
            workspace.Database, new DpapiSecretProtector(), workspace.Paths);

        var config = new ModelConfig
        {
            Name = "测试",
            Provider = LlmProvider.Claude,
            BaseUrl = "https://api.anthropic.com",
            ApiKey = "sk-ant-secret-value",
            ModelName = "claude-x"
        };
        await repository.InsertAsync(config);

        // 持久配置文件里不能出现明文
        var stored = File.ReadAllText(workspace.Paths.ModelsFile);
        Assert.DoesNotContain("sk-ant-secret-value", stored);
        Assert.Contains("dpapi:", stored);

        // 读回来是明文，供网关使用
        var loaded = await repository.GetAsync(config.Id);
        Assert.Equal("sk-ant-secret-value", loaded!.ApiKey);
        Assert.Equal(LlmProvider.Claude, loaded.Provider);
    }

    [Fact]
    public void Settings_RoundTrip_ClampsInvalidConcurrency()
    {
        using var workspace = new TempWorkspace();
        var repository = new SettingsRepository(workspace.Database);

        repository.Save(new AppSettings
        {
            TranslateTargetLanguage = "English",
            MaxConcurrency = 99,
            TimeoutSeconds = 5
        });

        var loaded = repository.Load();
        Assert.Equal("English", loaded.TranslateTargetLanguage);
        Assert.InRange(loaded.MaxConcurrency, 1, 5);
        Assert.InRange(loaded.TimeoutSeconds, 10, 600);
    }

    [Fact]
    public async Task ImageStore_SavesAndReadsRelativeName()
    {
        using var workspace = new TempWorkspace();
        var store = new FileImageStore(workspace.Paths);
        var png = new byte[] { 1, 2, 3, 4 };

        var name = await store.SavePngAsync(png);
        Assert.False(Path.IsPathRooted(name));
        Assert.True(File.Exists(Path.Combine(workspace.Paths.Images, name)));

        var read = await store.ReadAsync(name);
        Assert.Equal(png, read);

        store.Delete(name);
        Assert.Null(await store.ReadAsync(name));
    }
}
