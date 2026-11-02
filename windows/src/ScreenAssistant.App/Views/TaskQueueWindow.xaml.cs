using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.Platform;

namespace ScreenAssistant.App.Views;

/// <summary>贴在识别框左侧的任务队列。</summary>
public partial class TaskQueueWindow : Window
{
    public TaskQueueWindow(OverlayViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;

        SourceInitialized += (_, _) =>
        {
            WindowHelper.SetExcludeFromCapture(this);
            WindowHelper.SetToolWindow(this);
        };

        // 允许点击任务队列时获得键盘焦点（否则透明浮层里的文本框无法输入）
        PreviewMouseDown += OnPreviewMouseDown;
    }

    /// <summary>
    /// 这个窗口是 WS_EX_NOACTIVATE 的浮层，默认拿不到键盘焦点，
    /// 点进追问框时手动激活窗口并把焦点交给文本框。
    /// </summary>
    private void OnPreviewMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is not DependencyObject source) return;
        if (FindAncestor<TextBox>(source) is not { } box) return;

        if (!IsActive) Activate();
        if (!box.IsKeyboardFocused)
        {
            box.Focus();
            Keyboard.Focus(box);
            box.CaretIndex = box.Text.Length;
        }
    }

    private static T? FindAncestor<T>(DependencyObject current) where T : DependencyObject
    {
        while (current is not null)
        {
            if (current is T match) return match;
            current = System.Windows.Media.VisualTreeHelper.GetParent(current)!;
        }
        return null;
    }

    /// <summary>识别框复用到新会话时换绑 ViewModel。</summary>
    public void SetViewModel(OverlayViewModel viewModel) => DataContext = viewModel;

    /// <summary>由识别框位置驱动；左边放不下时翻到右侧。</summary>
    public void FollowOverlay(OverlayWindow overlay)
    {
        if (!IsLoaded) return;

        const double gap = 10;
        var area = WindowHelper.GetMonitorWorkArea(overlay);

        // 高度不超出识别框太多
        var height = Math.Max(240, Math.Min(Height, overlay.ActualHeight));
        Height = Math.Min(height, area.Height - 8);

        var leftCandidate = overlay.Left - Width - gap;
        var left = leftCandidate >= area.Left + 4
            ? leftCandidate
            : Math.Min(overlay.Left + overlay.ActualWidth + gap, area.Right - Width - 4);
        left = Math.Max(area.Left + 4, left);

        var top = Math.Max(area.Top + 4, Math.Min(overlay.Top, area.Bottom - Height - 4));

        Left = left;
        Top = top;
    }

    /// <summary>回车发送、Shift+回车换行。</summary>
    private void FollowUpBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        if (Keyboard.Modifiers.HasFlag(ModifierKeys.Shift)) return;

        e.Handled = true;
        if (sender is FrameworkElement { DataContext: TaskItemViewModel item } &&
            item.SendFollowUpCommand.CanExecute(null))
        {
            item.SendFollowUpCommand.Execute(null);
        }
    }
}
