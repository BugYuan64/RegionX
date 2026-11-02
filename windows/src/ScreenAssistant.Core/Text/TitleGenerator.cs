using System.Text;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Core.Text;

/// <summary>从 AI 结果本地生成任务标题（10 字以内）与简介（20 字以内），不额外调用模型。</summary>
public static class TitleGenerator
{
    public const int TitleMaxLength = 10;
    public const int SummaryMaxLength = 20;

    public static (string Title, string Summary) Generate(TaskKind kind, string? content)
    {
        var clean = Clean(content);

        if (string.IsNullOrWhiteSpace(clean))
        {
            return (kind.ToDisplayName(), FallbackSummary(kind));
        }

        // 三类操作的格式都是「第一行 = 答案 / 译文 / 提取正文」
        var firstLine = FirstLine(content);
        var title = Take(firstLine, TitleMaxLength);

        // 简介只取第一行，避免把解释部分混进来；第一行太短时补上后续内容
        var summary = Take(firstLine, SummaryMaxLength);
        if (summary.Length < 6)
        {
            summary = Take(clean.Replace('\n', ' '), SummaryMaxLength);
        }
        if (summary.Length == 0) summary = FallbackSummary(kind);
        return (title, summary);
    }

    /// <summary>回答正文的第一行（去掉 Markdown 标记和空行）。</summary>
    public static string FirstLine(string? content)
    {
        var clean = Clean(content);
        if (clean.Length == 0) return "";

        foreach (var line in clean.Split('\n'))
        {
            var text = StripMarkdown(line).Trim();
            if (text.Length > 0) return text;
        }
        return "";
    }

    /// <summary>去掉行首尾常见的 Markdown 修饰（列表符、标题符、加粗、行内代码）。</summary>
    private static string StripMarkdown(string line)
    {
        var text = line.Trim();
        if (text.Length == 0) return text;

        // 标题 ### / 列表 - * 1.
        text = text.TrimStart('#', '-', '*', '>', ' ');
        if (text.Length > 2 && char.IsDigit(text[0]) && (text[1] == '.' || text[1] == '、'))
        {
            text = text[2..].TrimStart();
        }

        text = text.Replace("**", "").Replace("__", "").Replace("`", "");
        return text.Trim();
    }

    private static string FallbackSummary(TaskKind kind) => kind switch
    {
        TaskKind.Answer => "已识别题目并作答",
        TaskKind.Translate => "已翻译框内文字",
        TaskKind.Extract => "已提取框内文字",
        _ => "任务"
    };

    public static string Clean(string? content)
    {
        if (string.IsNullOrWhiteSpace(content)) return "";
        var sb = new StringBuilder(content.Length);
        foreach (var line in content.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n'))
        {
            var trimmed = line.Trim();
            if (trimmed.Length == 0)
            {
                if (sb.Length > 0 && sb[^1] != '\n') sb.Append('\n');
                continue;
            }
            sb.Append(trimmed).Append('\n');
        }
        return sb.ToString().Trim();
    }

    /// <summary>按字符（而非 UTF-16 码元）截断，避免截断 emoji 或生僻字。</summary>
    public static string Take(string text, int max)
    {
        if (string.IsNullOrEmpty(text)) return "";
        var count = 0;
        var enumerator = text.EnumerateRunes();
        var length = 0;
        while (enumerator.MoveNext())
        {
            if (count >= max) break;
            length += enumerator.Current.Utf16SequenceLength;
            count++;
        }
        return text[..length];
    }
}
