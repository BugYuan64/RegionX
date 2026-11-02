using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace ScreenAssistant.App.Controls;

/// <summary>
/// 图标控件：<c>&lt;c:Icon Geometry="{StaticResource IconScan}" Size="18" /&gt;</c>
/// 用描边绘制，颜色跟随 Foreground。
/// </summary>
public sealed class Icon : Control
{
    static Icon()
    {
        DefaultStyleKeyProperty.OverrideMetadata(typeof(Icon), new FrameworkPropertyMetadata(typeof(Icon)));
    }

    public static readonly DependencyProperty DataProperty = DependencyProperty.Register(
        nameof(Data), typeof(Geometry), typeof(Icon),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty SizeProperty = DependencyProperty.Register(
        nameof(Size), typeof(double), typeof(Icon), new FrameworkPropertyMetadata(18.0));

    public static readonly DependencyProperty ThicknessProperty = DependencyProperty.Register(
        nameof(Thickness), typeof(double), typeof(Icon), new FrameworkPropertyMetadata(2.0));

    public static readonly DependencyProperty FillIconProperty = DependencyProperty.Register(
        nameof(FillIcon), typeof(bool), typeof(Icon), new FrameworkPropertyMetadata(false));

    public Geometry? Data
    {
        get => (Geometry?)GetValue(DataProperty);
        set => SetValue(DataProperty, value);
    }

    public double Size
    {
        get => (double)GetValue(SizeProperty);
        set => SetValue(SizeProperty, value);
    }

    public double Thickness
    {
        get => (double)GetValue(ThicknessProperty);
        set => SetValue(ThicknessProperty, value);
    }

    public bool FillIcon
    {
        get => (bool)GetValue(FillIconProperty);
        set => SetValue(FillIconProperty, value);
    }
}
