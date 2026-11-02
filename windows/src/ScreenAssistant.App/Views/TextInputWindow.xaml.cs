using System.Windows;
using System.Windows.Input;

namespace ScreenAssistant.App.Views;

/// <summary>轻量单行输入对话框（重命名会话等）。</summary>
public partial class TextInputWindow : Window
{
    public TextInputWindow(string title, string initialValue)
    {
        InitializeComponent();
        TitleText.Text = title;
        InputBox.Text = initialValue;
        Loaded += (_, _) =>
        {
            InputBox.Focus();
            InputBox.SelectAll();
        };
    }

    public string Value => InputBox.Text;

    private void Ok_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = true;
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }

    private void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            DialogResult = true;
        }
        else if (e.Key == Key.Escape)
        {
            DialogResult = false;
        }
    }
}
