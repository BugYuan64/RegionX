namespace ScreenAssistant.Core.Models;

public enum TaskKind
{
    Answer = 0,
    Translate = 1,
    Extract = 2
}

public enum TaskState
{
    Queued = 0,
    Running = 1,
    Completed = 2,
    Failed = 3,
    Cancelled = 4
}

public enum LlmProvider
{
    OpenAiCompatible = 0,
    Claude = 1,
    Gemini = 2
}

public enum ChatRole
{
    User = 0,
    Assistant = 1
}

public static class DisplayNames
{
    public static string ToDisplayName(this TaskKind kind) => kind switch
    {
        TaskKind.Answer => "回答",
        TaskKind.Translate => "翻译",
        TaskKind.Extract => "提取图文",
        _ => "任务"
    };

    public static string ToDisplayName(this TaskState state) => state switch
    {
        TaskState.Queued => "排队中",
        TaskState.Running => "处理中",
        TaskState.Completed => "已完成",
        TaskState.Failed => "失败",
        TaskState.Cancelled => "已取消",
        _ => "未知"
    };

    public static string ToDisplayName(this LlmProvider provider) => provider switch
    {
        LlmProvider.OpenAiCompatible => "OpenAI 兼容",
        LlmProvider.Claude => "Claude",
        LlmProvider.Gemini => "Gemini",
        _ => "未知"
    };

    public static string ToDisplayName(this ChatRole role) => role switch
    {
        ChatRole.User => "我",
        ChatRole.Assistant => "AI",
        _ => "未知"
    };
}
