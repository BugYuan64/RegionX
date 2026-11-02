using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using ScreenAssistant.App.ViewModels;

namespace ScreenAssistant.App.Views;

/// <summary>
/// 任务详情视图：主窗口内嵌使用（不再为每个任务弹独立窗口）。
/// 左侧是同会话的任务列表，右侧是截图、AI 回复与追问。
/// </summary>
public partial class TaskDetailView : UserControl
{
    private TaskDetailViewModel? _bound;

    public TaskDetailView()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
    }

    private void OnDataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (_bound is not null)
        {
            _bound.ScrollToEndRequested -= OnScrollToEnd;
        }

        _bound = e.NewValue as TaskDetailViewModel;
        if (_bound is not null)
        {
            _bound.ScrollToEndRequested -= OnScrollToEnd;
            _bound.ScrollToEndRequested += OnScrollToEnd;
        }
    }

    private void OnScrollToEnd(object? sender, EventArgs e) =>
        Dispatcher.BeginInvoke(new Action(() => ContentScroll.ScrollToEnd()));

    private async void SiblingTask_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: SiblingTaskItem item } && _bound is not null)
        {
            await _bound.OpenSiblingAsync(item.Id);
        }
    }

    private void BackToMain_Click(object sender, RoutedEventArgs e)
    {
        _bound?.CloseRequested?.Invoke();
    }

    private async void Retry_Click(object sender, RoutedEventArgs e)
    {
        if (_bound is not null) await _bound.RetryAsync();
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        var text = _bound?.AnswerText;
        if (string.IsNullOrWhiteSpace(text)) return;

        try
        {
            Clipboard.SetText(text);
        }
        catch (Exception)
        {
            // 剪贴板被其他程序占用时忽略
        }
    }

    /// <summary>Enter 发送，Shift+Enter 换行。</summary>
    private void FollowUpBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        if (Keyboard.Modifiers.HasFlag(ModifierKeys.Shift)) return;

        e.Handled = true;
        if (_bound?.SendFollowUpCommand.CanExecute(null) == true)
        {
            _bound.SendFollowUpCommand.Execute(null);
        }
    }

    private void IncludeImage_Click(object sender, RoutedEventArgs e) => _bound?.ApplyFollowUpOption();
}
