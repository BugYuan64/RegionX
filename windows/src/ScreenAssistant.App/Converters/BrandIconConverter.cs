using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using ScreenAssistant.App.ViewModels;

namespace ScreenAssistant.App.Converters;

/// <summary>
/// 模型名 → 品牌标识几何图形。按关键字匹配，做不到时回退到 GPT 标识。
/// 只影响展示，不影响调用。
/// </summary>
public sealed class BrandIconConverter : IValueConverter
{
    /// <summary>按顺序匹配，越具体的放越前面。</summary>
    private static readonly (string[] Keywords, string ResourceKey)[] Rules =
    {
        (new[] { "deepseek" }, "BrandDeepSeek"),
        (new[] { "mimo", "小米" }, "BrandMimo"),
        (new[] { "claude", "anthropic", "sonnet", "opus", "haiku" }, "BrandClaude"),
        (new[] { "gemini", "gemma", "google" }, "BrandGemini"),
        (new[] { "qwen", "tongyi", "通义", "qwq" }, "BrandQwen"),
        (new[] { "kimi", "moonshot", "月之暗面" }, "BrandKimi"),
        // GLM / 智谱 / ChatGLM
        (new[] { "glm", "chatglm", "zhipu", "智谱" }, "BrandGlm"),
        (new[] { "llama", "meta", "mistral", "mixtral" }, "BrandLlama"),
        (new[] { "grok", "x-ai", "xai" }, "BrandGrok"),
        (new[] { "ollama" }, "BrandOllama"),
        (new[] { "ernie", "文心", "baidu" }, "BrandErnie"),
        // 豆包 / 火山方舟
        (new[] { "doubao", "豆包", "volc", "ark" }, "BrandDoubao"),
        // MiniMax（含 abab / MiniMax-M 系列）
        (new[] { "minimax", "abab" }, "BrandMiniMax"),
        // 阶跃星辰 Step
        (new[] { "step-", "stepfun", "step1", "step2", "step3", "阶跃" }, "BrandStep"),
        // 腾讯混元
        (new[] { "hunyuan", "tencent", "混元", "hy-" }, "BrandHunyuan"),
        (new[] { "mistral" }, "BrandMistral")
    };

    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var name = value switch
        {
            string s => s,
            ModelChoice choice => choice.ModelName,
            null => "",
            _ => value.ToString() ?? ""
        };

        var key = ResolveKey(name);
        return FindGeometry(key);
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;

    /// <summary>直接拿到品牌图标几何（供代码构造菜单项使用）。</summary>
    public static Geometry? ResolveGeometry(string? modelName) =>
        FindGeometry(ResolveKey(modelName));

    private static Geometry? FindGeometry(string key) =>
        Application.Current?.TryFindResource(key) as Geometry
        ?? Application.Current?.TryFindResource("BrandGpt") as Geometry;

    /// <summary>模型名对应的品牌资源键；未识别时返回 GPT。</summary>
    public static string ResolveKey(string? modelName)
    {
        var name = (modelName ?? "").ToLowerInvariant();
        if (name.Length == 0) return "BrandGpt";

        foreach (var (keywords, key) in Rules)
        {
            foreach (var keyword in keywords)
            {
                if (name.Contains(keyword, StringComparison.Ordinal)) return key;
            }
        }

        // GPT 系列（gpt-4o / o1 / o3 / o4-mini / chatgpt 等）与所有未知模型
        return "BrandGpt";
    }
}
