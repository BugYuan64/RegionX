using System.IO;
using System.Windows.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.App.ViewModels;

/// <summary>对话里的一条消息（详情页与识别框展开区共用）。</summary>
public sealed partial class MessageItemViewModel : ObservableObject
{
    public MessageItemViewModel(MessageRecord record, string? imageFullPath)
    {
        Role = record.Role;
        Content = record.Content;
        CreatedAt = record.CreatedAt;
        ImageFullPath = imageFullPath;
    }

    public ChatRole Role { get; }
    public DateTimeOffset CreatedAt { get; }
    public string? ImageFullPath { get; }

    [ObservableProperty]
    private string _content;

    public bool IsUser => Role == ChatRole.User;
    public bool IsAssistant => Role == ChatRole.Assistant;
    public string RoleName => Role.ToDisplayName();

    public string TimeText => CreatedAt.LocalDateTime.ToString("HH:mm");

    public ImageSource? Image => string.IsNullOrEmpty(ImageFullPath) || !File.Exists(ImageFullPath)
        ? null
        : ScreenAssistant.Platform.ScreenCaptureService.CreateBitmapSource(File.ReadAllBytes(ImageFullPath));
}
