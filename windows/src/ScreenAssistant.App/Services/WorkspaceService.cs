using System.Collections.ObjectModel;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Core.Tasks;
using ScreenAssistant.Data;
using ScreenAssistant.Data.Repositories;

namespace ScreenAssistant.App.Services;

/// <summary>
/// 会话与任务的应用层门面：创建会话、派发截图任务、追问、重试、取消，
/// 并把流式结果推送回界面层维护的集合。
/// </summary>
public sealed class WorkspaceService
{
    private readonly ISessionRepository _sessions;
    private readonly ITaskRepository _tasks;
    private readonly IMessageRepository _messages;
    private readonly IImageStore _images;
    private readonly TaskQueue _scheduler;
    private readonly IChatModelClientFactory _factory;
    private readonly SettingsService _settings;
    private readonly IModelConfigRepository _models;

    public WorkspaceService(
        ISessionRepository sessions,
        ITaskRepository tasks,
        IMessageRepository messages,
        IImageStore images,
        TaskQueue scheduler,
        IChatModelClientFactory factory,
        SettingsService settings,
        IModelConfigRepository models)
    {
        _sessions = sessions;
        _tasks = tasks;
        _messages = messages;
        _images = images;
        _scheduler = scheduler;
        _factory = factory;
        _settings = settings;
        _models = models;
    }

    public TaskQueue Scheduler => _scheduler;

    /// <summary>任务状态变化（completed / failed / cancelled / running）。</summary>
    public event EventHandler<TaskRecord>? TaskUpdated;

    /// <summary>流式文本增量（已节流）。</summary>
    public event EventHandler<(string TaskId, string Accumulated)>? TaskStreaming;

    public void WireScheduler()
    {
        _scheduler.Runner.StateChanged += (_, e) => TaskUpdated?.Invoke(this, e.Task);
        _scheduler.Runner.Progress += (_, e) => TaskStreaming?.Invoke(this, (e.TaskId, e.Accumulated));
    }

    public async Task<SessionRecord> CreateSessionAsync()
    {
        var now = DateTimeOffset.Now;
        var session = new SessionRecord
        {
            Id = Guid.NewGuid().ToString("N"),
            Name = now.ToString("yyyy-MM-dd HH:mm"),
            CreatedAt = now,
            UpdatedAt = now
        };
        await _sessions.InsertAsync(session).ConfigureAwait(false);
        return session;
    }

    public async Task<IReadOnlyList<SessionRow>> LoadSessionsAsync() =>
        await _sessions.GetAllAsync().ConfigureAwait(false);

    public Task<TaskRecord?> LoadTaskAsync(string taskId) => _tasks.GetAsync(taskId);

    public Task<SessionRecord?> LoadSessionAsync(string sessionId) => _sessions.GetAsync(sessionId);

    /// <summary>追问时是否附带原截图（详情页开关直接读写设置）。</summary>
    public bool IncludeImageOnFollowUp
    {
        get => _settings.Settings.IncludeImageOnFollowUp;
        set => _settings.Update(s => s.IncludeImageOnFollowUp = value);
    }

    public async Task<IReadOnlyList<TaskRecord>> LoadTasksAsync(string sessionId) =>
        await _sessions.GetAsync(sessionId) is null
            ? Array.Empty<TaskRecord>()
            : await _tasks.GetBySessionAsync(sessionId).ConfigureAwait(false);

    public async Task<IReadOnlyList<MessageRecord>> LoadMessagesAsync(string taskId) =>
        await _messages.GetByTaskAsync(taskId).ConfigureAwait(false);

    public Task<ModelConfig?> GetModelAsync(string id) => _models.GetAsync(id);

    public Task<IReadOnlyList<ModelConfig>> LoadModelConfigsAsync() => _models.GetAllAsync();

    public Task SaveModelConfigAsync(ModelConfig config) => _models.UpdateAsync(config);

    public async Task<string> SaveCaptureAsync(byte[] png, CancellationToken cancellationToken) =>
        await _images.SavePngAsync(png, cancellationToken).ConfigureAwait(false);

    public async Task<ModelConfig?> GetActiveModelAsync()
    {
        var id = _settings.Settings.ActiveModelId;
        if (!string.IsNullOrEmpty(id))
        {
            var model = await _models.GetAsync(id).ConfigureAwait(false);
            if (model is not null) return model;
        }

        var all = await _models.GetAllAsync().ConfigureAwait(false);
        return all.FirstOrDefault();
    }

    /// <summary>
    /// 派发一个任务：图片已在调用前截好并保存为文件，这里只负责建记录、写消息、入队。
    /// </summary>
    public async Task<TaskRecord> EnqueueTaskAsync(
        SessionRecord session,
        TaskKind kind,
        string imageRelativePath,
        CapturedRegionInfo region,
        ModelConfig model,
        CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.Now;
        var task = new TaskRecord
        {
            Id = Guid.NewGuid().ToString("N"),
            SessionId = session.Id,
            Kind = kind,
            Title = kind.ToDisplayName(),
            Summary = "已提交，等待处理",
            ImagePath = imageRelativePath,
            RegionX = region.X,
            RegionY = region.Y,
            RegionWidth = region.Width,
            RegionHeight = region.Height,
            Status = TaskState.Queued,
            ModelId = model.Id,
            ModelName = model.Name,
            CreatedAt = now,
            UpdatedAt = now
        };

        await _tasks.InsertAsync(task).ConfigureAwait(false);
        await _messages.InsertAsync(new MessageRecord
        {
            TaskId = task.Id,
            Role = ChatRole.User,
            Content = kind switch
            {
                TaskKind.Answer => "回答框内内容",
                TaskKind.Translate => $"翻译框内文字（目标：{_settings.Settings.TranslateTargetLanguage}）",
                _ => "提取框内文字"
            },
            ImagePath = imageRelativePath
        }).ConfigureAwait(false);
        await _sessions.TouchAsync(session.Id, now).ConfigureAwait(false);

        _scheduler.Enqueue(task, model);
        return task;
    }

    public async Task CancelAsync(string taskId)
    {
        _scheduler.Cancel(taskId);
        await Task.CompletedTask;
    }

    /// <summary>重试：沿用原任务与截图，清空旧错误后重新排队。</summary>
    public async Task<TaskRecord?> RetryAsync(string taskId)
    {
        var task = await _tasks.GetAsync(taskId).ConfigureAwait(false);
        if (task is null) return null;

        var model = await GetModelAsync(task.ModelId).ConfigureAwait(false)
                    ?? await GetActiveModelAsync().ConfigureAwait(false);
        if (model is null) return null;

        task.Status = TaskState.Queued;
        task.Error = null;
        task.Summary = "已重新提交";
        task.UpdatedAt = DateTimeOffset.Now;
        task.ModelId = model.Id;
        task.ModelName = model.Name;
        await _tasks.UpdateAsync(task).ConfigureAwait(false);

        _scheduler.Enqueue(task, model);
        return task;
    }

    /// <summary>把一个任务的消息列表转成可绑定对象（首条用户消息的图片由详情页顶部单独展示）。</summary>
    public async Task<IReadOnlyList<MessageItemViewModel>> LoadMessageItemsAsync(string taskId, bool hideFirstImage)
    {
        var records = await _messages.GetByTaskAsync(taskId).ConfigureAwait(false);
        var items = new List<MessageItemViewModel>(records.Count);
        for (var i = 0; i < records.Count; i++)
        {
            var record = records[i];
            var imagePath = hideFirstImage && i == 0 ? null : ResolveImagePath(record.ImagePath);
            items.Add(new MessageItemViewModel(record, imagePath));
        }
        return items;
    }

    /// <summary>追问：把新的用户消息追加到该任务的会话历史中，流式返回后写入最后的助手消息。</summary>
    public async Task<string> FollowUpAsync(
        TaskRecord task,
        string userText,
        Action<string> onDelta,
        CancellationToken cancellationToken)
    {
        var model = await GetModelAsync(task.ModelId).ConfigureAwait(false)
                    ?? await GetActiveModelAsync().ConfigureAwait(false)
                    ?? throw new InvalidOperationException("没有可用的模型配置。");

        var settings = _settings.Settings;
        var history = await _messages.GetByTaskAsync(task.Id).ConfigureAwait(false);

        var userMessage = new MessageRecord
        {
            TaskId = task.Id,
            Role = ChatRole.User,
            Content = userText,
            ImagePath = settings.IncludeImageOnFollowUp ? task.ImagePath : null
        };
        await _messages.InsertAsync(userMessage).ConfigureAwait(false);

        var llmMessages = new List<LlmMessage>();
        LlmImage[]? image = null;
        if (settings.IncludeImageOnFollowUp && task.ImagePath is not null)
        {
            var png = await _images.ReadAsync(task.ImagePath, cancellationToken).ConfigureAwait(false);
            if (png is { Length: > 0 }) image = new[] { LlmImage.FromPng(png) };
        }

        foreach (var message in history.Append(userMessage))
        {
            if (message.Role == ChatRole.User && message.Id == userMessage.Id)
            {
                // 只在追问这条消息上附带一次图片，历史消息不再重复带图，节省 token
                var text = $"{settings.FollowUpPrompt}\n\n{userText}";
                llmMessages.Add(LlmMessage.User(text, image ?? Array.Empty<LlmImage>()));
            }
            else
            {
                llmMessages.Add(message.Role == ChatRole.Assistant
                    ? LlmMessage.Assistant(message.Content)
                    : LlmMessage.User(message.Content));
            }
        }

        var request = new LlmChatRequest
        {
            Model = model,
            Messages = llmMessages,
            SystemPrompt = TaskRunner.SystemPromptFor(task.Kind, settings)
        };

        var client = _factory.Create(model);
        var builder = new System.Text.StringBuilder();
        var lastReport = DateTime.UtcNow;

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(settings.TimeoutSeconds));

        try
        {
            await foreach (var delta in client.StreamAsync(request, timeout.Token).ConfigureAwait(false))
            {
                builder.Append(delta);
                var now = DateTime.UtcNow;
                if (now - lastReport >= TimeSpan.FromMilliseconds(70))
                {
                    lastReport = now;
                    onDelta(builder.ToString());
                }
            }
        }
        finally
        {
            onDelta(builder.ToString());
        }

        var answer = builder.ToString();
        await _messages.InsertAsync(new MessageRecord
        {
            TaskId = task.Id,
            Role = ChatRole.Assistant,
            Content = answer
        }).ConfigureAwait(false);

        // 追问不改动标题与简介：只刷新会话的更新时间
        await _sessions.TouchAsync(task.SessionId, DateTimeOffset.Now).ConfigureAwait(false);

        return answer;
    }

    public async Task DeleteTaskAsync(string taskId)
    {
        var task = await _tasks.GetAsync(taskId).ConfigureAwait(false);
        if (task is null) return;
        _scheduler.Cancel(taskId);
        await _tasks.DeleteAsync(taskId).ConfigureAwait(false);
        _images.Delete(task.ImagePath);
    }

    public async Task DeleteSessionAsync(string sessionId)
    {
        var tasks = await _tasks.GetBySessionAsync(sessionId).ConfigureAwait(false);
        foreach (var task in tasks)
        {
            _scheduler.Cancel(task.Id);
            _images.Delete(task.ImagePath);
        }
        await _sessions.DeleteAsync(sessionId).ConfigureAwait(false);
    }

    public async Task RenameSessionAsync(string sessionId, string name) =>
        await _sessions.RenameAsync(sessionId, name).ConfigureAwait(false);

    public string ResolveImagePath(string? relativePath) => _images.Resolve(relativePath);

    public async Task SendModelSettingsAsync(AppSettings settings)
    {
        _settings.Save(settings);
        _scheduler.UpdateConcurrency(settings.MaxConcurrency);
        await Task.CompletedTask;
    }
}

/// <summary>截图区域信息（屏幕像素）。</summary>
public readonly record struct CapturedRegionInfo(int X, int Y, int Width, int Height);
