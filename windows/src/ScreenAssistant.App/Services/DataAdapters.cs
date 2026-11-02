using ScreenAssistant.Core.Models;
using ScreenAssistant.Core.Tasks;
using ScreenAssistant.Data;
using ScreenAssistant.Data.Repositories;
using ScreenAssistant.Llm;

namespace ScreenAssistant.App.Services;

/// <summary>把数据层适配成 Core 需要的接口，避免 Core 依赖 Data。</summary>
public sealed class TaskPersistenceAdapter : ITaskPersistence
{
    private readonly ITaskRepository _tasks;
    private readonly IMessageRepository _messages;
    private readonly ISessionRepository _sessions;

    public TaskPersistenceAdapter(
        ITaskRepository tasks,
        IMessageRepository messages,
        ISessionRepository sessions)
    {
        _tasks = tasks;
        _messages = messages;
        _sessions = sessions;
    }

    public async Task UpdateTaskAsync(TaskRecord task, CancellationToken cancellationToken)
    {
        task.UpdatedAt = DateTimeOffset.Now;
        await _tasks.UpdateAsync(task).ConfigureAwait(false);
        await _sessions.TouchAsync(task.SessionId, task.UpdatedAt).ConfigureAwait(false);
    }

    public async Task SaveAssistantMessageAsync(TaskRecord task, string content, CancellationToken cancellationToken)
    {
        _ = cancellationToken;
        // 重试时覆盖旧回复，避免出现两份
        await _messages.ReplaceAssistantAsync(task.Id, content).ConfigureAwait(false);
    }
}

public sealed class ImageStoreAdapter : ITaskImageSource
{
    private readonly IImageStore _images;

    public ImageStoreAdapter(IImageStore images) => _images = images;

    public async Task<byte[]> LoadAsync(string? imagePath, CancellationToken cancellationToken) =>
        await _images.ReadAsync(imagePath, cancellationToken).ConfigureAwait(false) ?? Array.Empty<byte>();
}
