namespace ScreenAssistant.Core.Models;

public sealed class SessionRecord
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.Now;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.Now;
}

public sealed class TaskRecord
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string SessionId { get; set; } = "";
    public TaskKind Kind { get; set; }

    /// <summary>标题（10 字以内）。</summary>
    public string Title { get; set; } = "";

    /// <summary>简介（20 字以内），回答结束后生成，不包含追问内容。</summary>
    public string Summary { get; set; } = "";

    /// <summary>回答正文第一行，用于识别框队列里的一行展示。</summary>
    public string FirstLine { get; set; } = "";

    /// <summary>截图文件名（相对于图片目录），不存绝对路径，便于导出。</summary>
    public string? ImagePath { get; set; }

    public int RegionX { get; set; }
    public int RegionY { get; set; }
    public int RegionWidth { get; set; }
    public int RegionHeight { get; set; }

    public TaskState Status { get; set; } = TaskState.Queued;
    public string ModelId { get; set; } = "";
    public string ModelName { get; set; } = "";
    public string? Error { get; set; }

    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.Now;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.Now;

    public TaskRecord Clone() => (TaskRecord)MemberwiseClone();
}

public sealed class MessageRecord
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string TaskId { get; set; } = "";
    public ChatRole Role { get; set; }
    public string Content { get; set; } = "";

    /// <summary>可选：与该消息一起发送的图片（例如最初带图的用户消息）。</summary>
    public string? ImagePath { get; set; }

    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.Now;

    public MessageRecord Clone() => (MessageRecord)MemberwiseClone();
}
