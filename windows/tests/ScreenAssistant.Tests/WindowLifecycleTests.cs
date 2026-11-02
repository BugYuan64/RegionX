using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Extensions.DependencyInjection;
using ScreenAssistant.App;
using ScreenAssistant.App.Services;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.App.Views;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Core.Tasks;
using ScreenAssistant.Data;
using ScreenAssistant.Data.Repositories;
using ScreenAssistant.Platform;
using ScreenAssistant.App.Themes;
using ScreenAssistant.Llm;
using RegionApp = ScreenAssistant.App.App;

namespace ScreenAssistant.Tests;

[Collection("WPF")]
public sealed class WindowLifecycleTests
{
    [Fact]
    public Task ModelPicker_DeactivationDuringClose_DoesNotCloseTwice() => WpfTestHost.RunAsync(() =>
    {
        var picker = new ModelPickerWindow("test", () => Task.FromResult<IReadOnlyList<ModelChoice>>([]), _ => Task.CompletedTask);
        var closed = 0;
        picker.Closed += (_, _) => closed++;
        picker.Closing += (_, _) => typeof(Window).GetMethod("OnDeactivated", BindingFlags.Instance | BindingFlags.NonPublic)!
            .Invoke(picker, [EventArgs.Empty]);
        picker.Show();
        try { picker.Close(); }
        finally { if (picker.IsVisible) picker.Hide(); }
        Assert.Equal(1, closed);
        return Task.CompletedTask;
    });

    [Fact]
    public Task ModelPicker_LoadCompletesAfterOwnerClosed_DoesNotReopenOrPosition() => WpfTestHost.RunAsync(async () =>
    {
        var loaded = new TaskCompletionSource<IReadOnlyList<ModelChoice>>();
        var anchor = new Button { Content = "模型" };
        var owner = new Window { Content = anchor, Width = 200, Height = 100, ShowInTaskbar = false };
        owner.Show();
        var picker = new ModelPickerWindow("test", () => loaded.Task, _ => Task.CompletedTask) { Owner = owner };
        var showing = picker.ShowBelowAsync(owner, anchor);
        owner.Close();
        loaded.SetResult([new ModelChoice { ModelId = "a", ModelName = "test", ProviderName = "测试" }]);
        await showing;
        Assert.False(picker.IsVisible);
    });

    [Fact]
    public Task ModelPicker_SelectsOnce_ThenClosesWithoutReentry() => WpfTestHost.RunAsync(async () =>
    {
        var anchor = new Button { Content = "模型" };
        var owner = new Window { Content = anchor, Width = 200, Height = 100, ShowInTaskbar = false };
        owner.Show();
        var selected = 0;
        var picker = new ModelPickerWindow("test", () => Task.FromResult<IReadOnlyList<ModelChoice>>(
            [new ModelChoice { ModelId = "a", ModelName = "deepseek-chat", ProviderName = "测试" }]),
            _ => { selected++; return Task.CompletedTask; })
        { Owner = owner };
        try
        {
            await picker.ShowBelowAsync(owner, anchor);
            var panel = (StackPanel)((ScrollViewer)((Border)picker.Content).Child).Content;
            var row = (Border)panel.Children[0];
            row.RaiseEvent(new System.Windows.Input.MouseButtonEventArgs(System.Windows.Input.Mouse.PrimaryDevice,
                Environment.TickCount, System.Windows.Input.MouseButton.Left)
            { RoutedEvent = UIElement.MouseLeftButtonUpEvent });
            Assert.Equal(1, selected);
            Assert.False(picker.IsVisible);
        }
        finally { owner.Close(); }
    });

    [Fact]
    public Task SettingsCloseDuringExit_DoesNotUseDisposedProvider() => WpfTestHost.RunAsync(async () =>
    {
        using var temp = new TempWorkspace();
        var services = new ServiceCollection();
        services.AddSingleton(temp.Paths);
        services.AddSingleton(new SettingsService(new SettingsRepository(temp.Database)));
        services.AddSingleton<IModelConfigRepository>(new ModelConfigRepository(temp.Database, new DpapiSecretProtector(), temp.Paths));
        services.AddSingleton<IHttpClientProvider, HttpClientProvider>();
        services.AddSingleton<IModelCatalog, ModelCatalog>();
        services.AddSingleton<IThemeRegistry, ThemeRegistry>();
        var provider = services.BuildServiceProvider();
        var app = (RegionApp)Application.Current;
        var servicesField = typeof(RegionApp).GetField("_services", BindingFlags.Instance | BindingFlags.NonPublic)!;
        var exitingField = typeof(RegionApp).GetField("_isExiting", BindingFlags.Instance | BindingFlags.NonPublic);
        var previousServices = servicesField.GetValue(app);
        var wasExiting = exitingField?.GetValue(app);
        servicesField.SetValue(app, provider);
        var errors = new List<Exception>();
        System.Windows.Threading.DispatcherUnhandledExceptionEventHandler onError = (_, e) =>
        { errors.Add(e.Exception); e.Handled = true; };
        app.Dispatcher.UnhandledException += onError;
        try
        {
            var closing = app.Dispatcher.InvokeAsync(async () =>
            {
                exitingField?.SetValue(app, true);
                await provider.DisposeAsync();
                Assert.Throws<ObjectDisposedException>(() => provider.GetRequiredService<SettingsService>());
                Field<SettingsWindow>(app, "_settingsWindow").Close();
            });
            await RegionApp.OpenSettingsAsync(null);
            await closing.Task.Unwrap();
            await System.Windows.Threading.Dispatcher.Yield(System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            Assert.Empty(errors);
        }
        finally
        {
            servicesField.SetValue(app, previousServices);
            app.Dispatcher.UnhandledException -= onError;
            exitingField?.SetValue(app, wasExiting);
            await provider.DisposeAsync();
        }
    });

    [Fact]
    public Task OverlayWindows_HaveSharedOwner_AndUnpinTogether() => WpfTestHost.RunAsync(async () =>
    {
        await using var fixture = new OverlayFixture();
        var coordinator = fixture.Coordinator;
        try
        {
            await coordinator.StartNewSessionAsync(row => new SessionItemViewModel(new SessionRow
            { Id = row.Id, Name = row.Name, CreatedAt = row.CreatedAt, UpdatedAt = row.UpdatedAt }));
            var overlay = Field<OverlayWindow>(coordinator, "_overlay");
            var toolbar = Field<ToolbarWindow>(coordinator, "_toolbar");
            var queue = Field<TaskQueueWindow>(coordinator, "_queue");
            Assert.Same(overlay, toolbar.Owner);
            Assert.Same(overlay, queue.Owner);
            coordinator.ViewModel!.ToggleTopmostCommand.Execute(null);
            Assert.False(overlay.Topmost);
            Assert.False(toolbar.Topmost);
            Assert.False(queue.Topmost);
            Assert.True(overlay.IsVisible && toolbar.IsVisible && queue.IsVisible);
            overlay.Activate();
            Assert.True(toolbar.IsVisible && queue.IsVisible);
        }
        finally { coordinator.Shutdown(); coordinator.Shutdown(); }
    });

    [Fact]
    public Task ReusedOverlay_UsesNewViewModelForTopmostChanges() => WpfTestHost.RunAsync(async () =>
    {
        await using var fixture = new OverlayFixture();
        var coordinator = fixture.Coordinator;
        SessionItemViewModel Create(SessionRecord row) => new(new SessionRow
        { Id = row.Id, Name = row.Name, CreatedAt = row.CreatedAt, UpdatedAt = row.UpdatedAt });
        try
        {
            await coordinator.StartNewSessionAsync(Create);
            var previous = coordinator.ViewModel!;
            coordinator.HideOverlay();
            await coordinator.StartNewSessionAsync(Create);
            var current = coordinator.ViewModel!;
            Assert.NotSame(previous, current);
            current.ToggleTopmostCommand.Execute(null);
            Assert.Equal(current.IsTopmost, Field<OverlayWindow>(coordinator, "_overlay").Topmost);
            Assert.Equal(current.IsTopmost, Field<ToolbarWindow>(coordinator, "_toolbar").Topmost);
            Assert.Equal(current.IsTopmost, Field<TaskQueueWindow>(coordinator, "_queue").Topmost);
        }
        finally { coordinator.Shutdown(); }
    });

    [Fact]
    public Task Exit_DisposesAsyncOnlyServices_ExactlyOnce() => WpfTestHost.RunAsync(async () =>
    {
        await using var fixture = new OverlayFixture();
        var probe = new AsyncProbe();
        var services = new ServiceCollection();
        services.AddSingleton(_ => fixture.Workspace);
        services.AddSingleton(_ => fixture.Scheduler);
        services.AddSingleton(_ => probe);
        var provider = services.BuildServiceProvider();
        provider.GetRequiredService<TaskQueue>();
        provider.GetRequiredService<AsyncProbe>();
        var app = (RegionApp)Application.Current;
        var field = typeof(RegionApp).GetField("_services", BindingFlags.Instance | BindingFlags.NonPublic)!;
        var previous = field.GetValue(app);
        var exitField = typeof(RegionApp).GetField("_isExiting", BindingFlags.Instance | BindingFlags.NonPublic)!;
        var disposalField = typeof(RegionApp).GetField("_disposeServicesTask", BindingFlags.Instance | BindingFlags.NonPublic)!;
        var previousExit = exitField.GetValue(app);
        var previousDisposal = disposalField.GetValue(app);
        field.SetValue(app, provider);
        disposalField.SetValue(app, null);
        try
        {
            var exitArgs = (ExitEventArgs)Activator.CreateInstance(typeof(ExitEventArgs),
                BindingFlags.Instance | BindingFlags.NonPublic, null, [0], null)!;
            typeof(RegionApp).GetMethod("OnExit", BindingFlags.Instance | BindingFlags.NonPublic)!
                .Invoke(app, [exitArgs]);
            typeof(RegionApp).GetMethod("OnExit", BindingFlags.Instance | BindingFlags.NonPublic)!
                .Invoke(app, [exitArgs]);
            Assert.Equal(1, probe.DisposalCount);
        }
        finally
        {
            field.SetValue(app, previous);
            exitField.SetValue(app, previousExit);
            disposalField.SetValue(app, previousDisposal);
            await provider.DisposeAsync();
        }
    });

    [Fact]
    public Task QueueCard_ShowsSmallCreationTime_AndKeepsItAfterStatusUpdates() => WpfTestHost.RunAsync(async () =>
    {
        await using var fixture = new OverlayFixture();
        var model = new OverlayViewModel(fixture.Workspace, fixture.Settings, (_, _) => Task.FromResult(Array.Empty<byte>()));
        var queue = new TaskQueueWindow(model);
        var created = new DateTimeOffset(2026, 10, 29, 8, 12, 34, TimeSpan.Zero);
        var record = new TaskRecord { CreatedAt = created, UpdatedAt = created, Title = "测试任务" };
        var item = new TaskItemViewModel(record, null);
        var template = (DataTemplate)queue.Resources["QueueTaskTemplate"];
        var card = (FrameworkElement)template.LoadContent();
        card.DataContext = item;
        card.Measure(new Size(280, 440));
        card.Arrange(new Rect(0, 0, 280, 440));
        card.UpdateLayout();
        var time = FindText(card, "CreatedTime");
        Assert.NotNull(time);
        Assert.InRange(time.FontSize, 9, 10);
        Assert.Equal(created.ToLocalTime().ToString("HH:mm:ss"), time.Text);
        record.Status = TaskState.Completed;
        record.UpdatedAt = created.AddMinutes(20);
        item.UpdateFromRecord();
        Assert.Equal(created.ToLocalTime().ToString("HH:mm:ss"), time.Text);
        var screenshot = Environment.GetEnvironmentVariable("REGIONX_QUEUE_SCREENSHOT");
        if (!string.IsNullOrWhiteSpace(screenshot))
        {
            model.SessionName = "今天 16:12";
            record.FirstLine = "答案：B";
            item.UpdateFromRecord();
            model.Tasks.Add(item);
            model.Tasks.Add(new TaskItemViewModel(new TaskRecord
            {
                Title = "正在翻译",
                Kind = TaskKind.Translate,
                Status = TaskState.Running,
                CreatedAt = created.AddSeconds(30)
            }, null));
            var content = (FrameworkElement)queue.Content;
            content.Measure(new Size(304, 440));
            content.Arrange(new Rect(0, 0, 304, 440));
            content.UpdateLayout();
            var bitmap = new System.Windows.Media.Imaging.RenderTargetBitmap(304, 440, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(content);
            var png = new System.Windows.Media.Imaging.PngBitmapEncoder();
            png.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bitmap));
            using var stream = File.Create(screenshot);
            png.Save(stream);
        }
        queue.Close();
    });

    private static T Field<T>(object value, string field) =>
        (T)value.GetType().GetField(field, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(value)!;

    private static TextBlock? FindText(DependencyObject root, string name)
    {
        if (root is TextBlock text && text.Name == name) return text;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            if (FindText(VisualTreeHelper.GetChild(root, i), name) is { } found) return found;
        return null;
    }

    private sealed class AsyncProbe : IAsyncDisposable
    {
        public int DisposalCount { get; private set; }
        public async ValueTask DisposeAsync() { await Task.Delay(10); DisposalCount++; }
    }

    private sealed class OverlayFixture : IAsyncDisposable
    {
        private readonly TempWorkspace _temp = new();
        public OverlayFixture()
        {
            Settings = new SettingsService(new SettingsRepository(_temp.Database));
            var factory = new FakeFactory();
            Scheduler = new TaskQueue(new TaskRunner(factory, new MemoryPersistence(), new NoImages(), Settings), 1);
            Workspace = new WorkspaceService(new SessionRepository(_temp.Database), new TaskRepository(_temp.Database),
                new MessageRepository(_temp.Database), new FileImageStore(_temp.Paths), Scheduler, factory, Settings,
                new ModelConfigRepository(_temp.Database, new DpapiSecretProtector(), _temp.Paths));
            Coordinator = new OverlaySessionCoordinator(Settings, Workspace, new NoCapture());
        }
        public SettingsService Settings { get; }
        public TaskQueue Scheduler { get; }
        public WorkspaceService Workspace { get; }
        public OverlaySessionCoordinator Coordinator { get; }
        public async ValueTask DisposeAsync() { Coordinator.Shutdown(); await Scheduler.DisposeAsync(); _temp.Dispose(); }
    }

    private sealed class NoCapture : IScreenCaptureService
    {
        public Task<CapturedRegion> CaptureAsync(Int32Rect bounds, int delayMs, CancellationToken cancellationToken = default) =>
            throw new InvalidOperationException("此测试不应截图或调用上游模型。");
    }
}
