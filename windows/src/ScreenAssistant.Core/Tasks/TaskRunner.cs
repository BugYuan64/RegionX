using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Core.Text;

namespace ScreenAssistant.Core.Tasks;

public sealed class TaskProgressEventArgs : EventArgs
{
    public required string TaskId { get; init; }
    /// <summary>增量文本（本次新增部分）。</summary>
    public string Delta { get; init; } = "";
    /// <summary>当前累计的完整文本。</summary>
    public string Accumulated { get; init; } = "";
}

public sealed class TaskStateChangedEventArgs : EventArgs
{
    public required TaskRecord Task { get; init; }
}

public interface ITaskImageSource
{
    Task<byte[]> LoadAsync(string? imagePath, CancellationToken cancellationToken);
}

/// <summary>
/// 单个任务的执行器：调用 LLM 网关、节流推送流式文本、写回数据库。
/// 任务之间互不影响；由 TaskQueue 控制并发。
/// </summary>
public sealed class TaskRunner
{
    private readonly IChatModelClientFactory _factory;
    private readonly ITaskPersistence _persistence;
    private readonly ITaskImageSource _images;
    private readonly IAppSettingsAccessor _settings;

    public TaskRunner(
        IChatModelClientFactory factory,
        ITaskPersistence persistence,
        ITaskImageSource images,
        IAppSettingsAccessor settings)
    {
        _factory = factory;
        _persistence = persistence;
        _images = images;
        _settings = settings;
    }

    public event EventHandler<TaskProgressEventArgs>? Progress;
    public event EventHandler<TaskStateChangedEventArgs>? StateChanged;

    /// <summary>50 到 100ms 合并一次，避免流式刷新过于频繁。</summary>
    public TimeSpan ProgressThrottle { get; set; } = TimeSpan.FromMilliseconds(70);

    public async Task RunAsync(
        TaskRecord task,
        ModelConfig model,
        CancellationToken cancellationToken)
    {
        var settings = _settings.Settings;
        var content = new System.Text.StringBuilder();
        var lastReport = DateTime.UtcNow;

        SetState(task, TaskState.Running);
        task.Error = null;
        task.ModelId = model.Id;
        task.ModelName = model.Name;
        await _persistence.UpdateTaskAsync(task, cancellationToken).ConfigureAwait(false);

        try
        {
            var messages = await BuildInitialMessagesAsync(task, model, settings, cancellationToken)
                .ConfigureAwait(false);

            var request = new LlmChatRequest
            {
                Model = model,
                Messages = messages,
                SystemPrompt = SystemPromptFor(task.Kind, settings)
            };

            var client = _factory.Create(model);

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(settings.TimeoutSeconds));

            await foreach (var delta in client.StreamAsync(request, timeout.Token).ConfigureAwait(false))
            {
                content.Append(delta);
                var now = DateTime.UtcNow;
                if (now - lastReport >= ProgressThrottle)
                {
                    lastReport = now;
                    Progress?.Invoke(this, new TaskProgressEventArgs
                    {
                        TaskId = task.Id,
                        Delta = delta,
                        Accumulated = content.ToString()
                    });
                }
            }

            var finalText = content.ToString();
            if (finalText.Length == 0)
            {
                throw new LlmException(LlmErrorKind.Protocol, "模型没有返回任何内容。");
            }

            // 收尾时补发一次完整文本，保证界面与最终结果一致
            Progress?.Invoke(this, new TaskProgressEventArgs
            {
                TaskId = task.Id,
                Delta = "",
                Accumulated = finalText
            });

            var (title, summary) = TitleGenerator.Generate(task.Kind, finalText);
            task.Title = title;
            task.Summary = summary;
            task.FirstLine = TitleGenerator.FirstLine(finalText);
            task.Status = TaskState.Completed;

            await _persistence.SaveAssistantMessageAsync(task, finalText, cancellationToken).ConfigureAwait(false);
            await _persistence.UpdateTaskAsync(task, cancellationToken).ConfigureAwait(false);
            StateChanged?.Invoke(this, new TaskStateChangedEventArgs { Task = task });
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // 用户取消：保留已生成的内容
            await FinishAsCancelledAsync(task, content.ToString(), null).ConfigureAwait(false);
        }
        catch (OperationCanceledException ex)
        {
            // 超时（链接令牌触发）
            var message = $"请求超时（{settings.TimeoutSeconds} 秒），已保留部分内容。";
            await FinishAsCancelledAsync(task, content.ToString(), message).ConfigureAwait(false);
            _ = ex;
        }
        catch (LlmException ex)
        {
            await FinishAsFailedAsync(task, content.ToString(), Describe(ex)).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            await FinishAsFailedAsync(task, content.ToString(), $"发生未知错误：{ex.Message}").ConfigureAwait(false);
        }
    }

    private async Task FinishAsCancelledAsync(TaskRecord task, string partial, string? error)
    {
        var hasContent = !string.IsNullOrWhiteSpace(partial);
        task.Status = TaskState.Cancelled;
        task.Error = error;
        if (hasContent)
        {
            var (title, summary) = TitleGenerator.Generate(task.Kind, partial);
            task.Title = title;
            task.Summary = summary;
            task.FirstLine = TitleGenerator.FirstLine(partial);
            await _persistence.SaveAssistantMessageAsync(task, partial, CancellationToken.None).ConfigureAwait(false);
        }
        await _persistence.UpdateTaskAsync(task, CancellationToken.None).ConfigureAwait(false);
        StateChanged?.Invoke(this, new TaskStateChangedEventArgs { Task = task });
    }

    private async Task FinishAsFailedAsync(TaskRecord task, string partial, string error)
    {
        task.Status = TaskState.Failed;
        task.Error = error;
        if (!string.IsNullOrWhiteSpace(partial))
        {
            // 流式中途断开：保留已收到的内容并标记未完成
            task.Summary = "生成中断，" + TitleGenerator.Take(error, 14);
            task.FirstLine = TitleGenerator.FirstLine(partial);
            await _persistence.SaveAssistantMessageAsync(task, partial, CancellationToken.None).ConfigureAwait(false);
        }
        await _persistence.UpdateTaskAsync(task, CancellationToken.None).ConfigureAwait(false);
        StateChanged?.Invoke(this, new TaskStateChangedEventArgs { Task = task });
    }

    private void SetState(TaskRecord task, TaskState state)
    {
        task.Status = state;
        task.UpdatedAt = DateTimeOffset.Now;
        StateChanged?.Invoke(this, new TaskStateChangedEventArgs { Task = task });
    }

    private async Task<IReadOnlyList<LlmMessage>> BuildInitialMessagesAsync(
        TaskRecord task,
        ModelConfig model,
        AppSettings settings,
        CancellationToken cancellationToken)
    {
        var images = new List<LlmImage>();
        if (model.SupportsImages && task.ImagePath is not null)
        {
            var png = await _images.LoadAsync(task.ImagePath, cancellationToken).ConfigureAwait(false);
            if (png.Length > 0) images.Add(LlmImage.FromPng(png));
        }

        var prompt = task.Kind switch
        {
            TaskKind.Answer => "请回答图片中的题目或问题。",
            TaskKind.Translate => $"请把图片中的文字翻译成{settings.TranslateTargetLanguage}。",
            TaskKind.Extract => "请提取图片中的全部文字。",
            _ => "请处理图片内容。"
        };

        if (!model.SupportsImages)
        {
            prompt += "\n（当前模型不支持图片输入，请基于文字尽力作答；如果无法判断，请直接说明。）";
        }

        return new[] { LlmMessage.User(prompt, images.ToArray()) };
    }

    public static string SystemPromptFor(TaskKind kind, AppSettings settings) => kind switch
    {
        TaskKind.Answer => settings.AnswerPrompt,
        TaskKind.Translate => $"{settings.TranslatePrompt}\n目标语言：{settings.TranslateTargetLanguage}",
        TaskKind.Extract => settings.ExtractPrompt,
        _ => ""
    };

    private static string Describe(LlmException ex) => ex.Kind switch
    {
        LlmErrorKind.Authentication => ex.Message,
        LlmErrorKind.RateLimited => "请求过于频繁或额度不足（429），请稍后重试。",
        LlmErrorKind.Server => "模型服务暂时不可用（5xx），请稍后重试。",
        LlmErrorKind.Network => ex.Message,
        LlmErrorKind.Timeout => "网络请求超时，请检查网络或代理。",
        LlmErrorKind.BadRequest => ex.Message,
        LlmErrorKind.Protocol => ex.Message,
        LlmErrorKind.Cancelled => "已取消",
        _ => ex.Message
    };
}

public interface IAppSettingsAccessor
{
    AppSettings Settings { get; }
}

public interface ITaskPersistence
{
    Task UpdateTaskAsync(TaskRecord task, CancellationToken cancellationToken);
    Task SaveAssistantMessageAsync(TaskRecord task, string content, CancellationToken cancellationToken);
}
