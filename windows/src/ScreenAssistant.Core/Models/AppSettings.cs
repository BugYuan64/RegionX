namespace ScreenAssistant.Core.Models;

/// <summary>应用设置（键值对持久化，这里做类型化封装）。</summary>
public sealed class AppSettings
{
    /// <summary>
    /// 问答模板：第一行只给答案（界面标题与队列首行都取这一行），换行后给解释。
    /// </summary>
    public string AnswerPrompt { get; set; } =
        "请仔细查看图片，完成其中的题目或回答其中提出的问题。输出格式必须严格遵守：\n" +
        "第一行只写最终答案本身，简洁直接，不要加「答案：」「答：」之类的前缀，也不要换行；\n" +
        "从第二行开始写解释、推导过程或理由，可以分段。\n" +
        "使用简体中文回答。";

    /// <summary>
    /// 翻译模板：第一行给完整译文，之后对重点短语 / 字词逐个换行解释。
    /// </summary>
    public string TranslatePrompt { get; set; } =
        "请把图片中的全部文字翻译成目标语言。输出格式必须严格遵守：\n" +
        "第一行只写完整译文，不要加任何前缀；\n" +
        "另起一段，对重点短语或字词逐个换行解释，每行格式为「原词 —— 释义」，不要合并成一段。";

    public string TranslateTargetLanguage { get; set; } = "简体中文";

    /// <summary>提取图文模板：只输出提取到的内容。</summary>
    public string ExtractPrompt { get; set; } =
        "请提取图片中的全部文字，只输出文字本身，保持原有换行与顺序。" +
        "不要添加任何解释、前缀、标题，也不要用代码块包裹。";

    public string FollowUpPrompt { get; set; } =
        "下面是对之前内容的追问，请结合图片和对话历史回答。";

    /// <summary>追问时是否附带当时的截图。</summary>
    public bool IncludeImageOnFollowUp { get; set; } = true;

    /// <summary>HTTP 代理地址，如 http://127.0.0.1:7890；为空表示直连。</summary>
    public string? ProxyUrl { get; set; }

    /// <summary>同时运行的任务数（2 到 3）。</summary>
    public int MaxConcurrency { get; set; } = 3;

    /// <summary>请求超时（秒）。</summary>
    public int TimeoutSeconds { get; set; } = 120;

    public string? ActiveModelId { get; set; }

    /// <summary>识别框最后一次的位置与大小（设备无关像素）。</summary>
    public string? OverlayBounds { get; set; }

    public bool OverlayTopmost { get; set; } = true;

    /// <summary>截取前延迟（毫秒），用于等待界面稳定。</summary>
    public int CaptureDelayMs { get; set; } = 120;

    /// <summary>主题标识（见 Themes/ThemeRegistry），默认深色。</summary>
    public string ThemeName { get; set; } = "dark";
}
