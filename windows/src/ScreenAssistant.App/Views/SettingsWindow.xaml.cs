using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using ScreenAssistant.App.ViewModels;

namespace ScreenAssistant.App.Views;

public partial class SettingsWindow : Window
{
    public SettingsWindow(SettingsViewModel viewModel)
    {
        InitializeComponent();
        DataContext = viewModel;
        viewModel.CloseRequested += (_, _) => Close();
    }

    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    /// <summary>从已拉取的上游模型列表里选一个填进模型名。</summary>
    private void PickModel_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { DataContext: ModelConfigViewModel model } button) return;

        if (model.AvailableModels.Count == 0)
        {
            MessageBox.Show(this, "还没有可选列表。请先点击「拉取模型列表」，或直接在输入框里填写模型名。",
                "选择模型", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var picker = new ModelNamePickerWindow(model.ModelName, model.AvailableModels.ToList());
        picker.Owner = this;

        // 默认放在「选择」按钮正下方，贴右边缘时向左收
        if (button is not null)
        {
            var point = button.PointToScreen(new Point(0, button.ActualHeight + 6));
            var dpi = VisualTreeHelper.GetDpi(button);
            picker.WindowStartupLocation = WindowStartupLocation.Manual;
            picker.Left = point.X / dpi.DpiScaleX;
            picker.Top = point.Y / dpi.DpiScaleY;
        }
        else
        {
            picker.WindowStartupLocation = WindowStartupLocation.CenterOwner;
        }

        if (picker.ShowDialog() == true && picker.Selected is { Length: > 0 } selected)
        {
            model.ModelName = selected;
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
