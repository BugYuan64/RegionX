using System.Windows;
using System.Windows.Input;
using ScreenAssistant.App.ViewModels;

namespace ScreenAssistant.App.Views;

public partial class MainWindow : Window
{
    private readonly MainViewModel _viewModel;

    /// <summary>
    /// 关闭按钮默认只是隐藏到托盘（程序仍在后台跑识别框）。
    /// 只有从托盘「退出」或系统关机时，App 才把这里置为 true 允许真正关闭。
    /// </summary>
    public static bool AllowClose { get; set; }

    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        _viewModel = viewModel;
        DataContext = viewModel;

        // 主程序也会出现在截图里：同样排除，避免框内拍到自己的界面
        SourceInitialized += (_, _) =>
        {
            ScreenAssistant.Platform.WindowHelper.SetExcludeFromCapture(this);
        };

        Closing += (_, e) =>
        {
            if (AllowClose) return;
            // 隐藏而不是销毁：再次点托盘图标时直接 Show 回来
            e.Cancel = true;
            Hide();
        };
    }

    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2)
        {
            ToggleMaximize();
            return;
        }
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void Maximize_Click(object sender, RoutedEventArgs e) => ToggleMaximize();

    private void ToggleMaximize() =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private async void TaskRow_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: TaskItemViewModel item })
        {
            await _viewModel.OpenTaskAsync(item);
        }
    }

    private async void RenameSession_Click(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
        if (sender is FrameworkElement { DataContext: SessionItemViewModel session })
        {
            await _viewModel.RenameSessionPromptAsync(session);
        }
    }

    private async void DeleteSession_Click(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
        if (sender is FrameworkElement { DataContext: SessionItemViewModel session })
        {
            await _viewModel.DeleteSessionConfirmAsync(session);
        }
    }
}
