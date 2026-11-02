using System.IO;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Extensions.DependencyInjection;
using ScreenAssistant.App.Services;
using ScreenAssistant.App.Themes;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.App.Views;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Tasks;
using ScreenAssistant.Data;
using ScreenAssistant.Data.Repositories;
using ScreenAssistant.Llm;
using ScreenAssistant.Platform;

namespace ScreenAssistant.App;

public partial class App : Application
{
    private IServiceProvider _services = null!;
    private OverlaySessionCoordinator? _coordinator;
    private MainWindow? _mainWindow;
    private SettingsWindow? _settingsWindow;
    private Hardcodet.Wpf.TaskbarNotification.TaskbarIcon? _trayIcon;
    private bool _isExiting;
    private Task? _disposeServicesTask;

    public static IServiceProvider Services => ((App)Current)._services;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;

        // 系统关机 / 注销时不能被「隐藏到托盘」拦住
        SessionEnding += (_, _) => Views.MainWindow.AllowClose = true;

        _services = ConfigureServices();

        // 数据库在构建容器时已建表，这里确保设置已加载
        var settings = _services.GetRequiredService<SettingsService>();

        // 启动即应用用户选择的主题（必须在任何窗口创建之前）
        ApplyTheme(settings.Settings.ThemeName);

        // 首次启动且没有模型时，直接引导用户去配置
        InitializeTray();

        var mainViewModel = _services.GetRequiredService<MainViewModel>();
        _mainWindow = new MainWindow(mainViewModel);
        MainWindow = _mainWindow;

        _coordinator = _services.GetRequiredService<OverlaySessionCoordinator>();
        mainViewModel.StartOverlayHandler = async () =>
            await _coordinator.StartNewSessionAsync(row => mainViewModel.CreateSessionItem(row));

        _mainWindow.Show();
        _ = mainViewModel.LoadAsync();

        // 首次启动还没有模型时，直接打开设置页引导配置
        _ = PromptForModelSetupAsync();
    }

    /// <summary>按主题标识应用主题；找不到时回退默认主题。</summary>
    public static void ApplyTheme(string? themeId)
    {
        var registry = (Current as App)?._services.GetService(typeof(IThemeRegistry)) as IThemeRegistry
                       ?? new ThemeRegistry();
        var theme = registry.Find(themeId) ?? registry.Default;
        ThemeApplier.Apply(theme);
    }

    private async Task PromptForModelSetupAsync()
    {
        var models = await _services.GetRequiredService<IModelConfigRepository>().GetAllAsync();
        if (models.Count == 0)
        {
            await OpenSettingsAsync(_mainWindow);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _isExiting = true;
        // 退出流程里主窗口必须能真正关闭
        Views.MainWindow.AllowClose = true;
        if (_mainWindow?.DataContext is MainViewModel mainViewModel) mainViewModel.Detach();
        _trayIcon?.Dispose();
        _coordinator?.Shutdown();
        // 托盘退出会提前异步释放。系统退出直接进入此处时，仍使用异步释放接口收尾。
        try { Task.Run(DisposeServicesAsync).GetAwaiter().GetResult(); }
        catch (Exception ex) { LogException(ex); }
        base.OnExit(e);
    }

    private Task DisposeServicesAsync() => _disposeServicesTask ??= DisposeServicesCoreAsync();

    private async Task DisposeServicesCoreAsync()
    {
        if (_services is null) return;
        var database = _services.GetService<Database>();
        if (_services is IAsyncDisposable asyncServices)
            await asyncServices.DisposeAsync().ConfigureAwait(false);
        else if (_services is IDisposable services)
            services.Dispose();
        database?.Checkpoint();
    }

    private IServiceProvider ConfigureServices()
    {
        var services = new ServiceCollection();

        // 数据层：先建表，任何读取设置的代码都必须在 Initialize 之后
        services.AddSingleton(_ => new AppPaths());
        services.AddSingleton(sp =>
        {
            var database = new Database(sp.GetRequiredService<AppPaths>());
            database.Initialize();
            return database;
        });
        services.AddSingleton<ISecretProtector, DpapiSecretProtector>();
        services.AddSingleton<IImageStore, FileImageStore>();
        services.AddSingleton<ISessionRepository, SessionRepository>();
        services.AddSingleton<ITaskRepository, TaskRepository>();
        services.AddSingleton<IMessageRepository, MessageRepository>();
        services.AddSingleton<IModelConfigRepository, ModelConfigRepository>();
        services.AddSingleton<ISettingsRepository, SettingsRepository>();

        // 设置与主题
        services.AddSingleton<SettingsService>();
        services.AddSingleton<IThemeRegistry, ThemeRegistry>();

        // LLM
        services.AddSingleton<IHttpClientProvider, HttpClientProvider>();
        services.AddSingleton<IChatModelClientFactory, ChatModelClientFactory>();
        services.AddSingleton<IModelCatalog, ModelCatalog>();

        // 核心任务
        services.AddSingleton<ITaskPersistence, TaskPersistenceAdapter>();
        services.AddSingleton<ITaskImageSource, ImageStoreAdapter>();
        services.AddSingleton(sp => new TaskRunner(
            sp.GetRequiredService<IChatModelClientFactory>(),
            sp.GetRequiredService<ITaskPersistence>(),
            sp.GetRequiredService<ITaskImageSource>(),
            sp.GetRequiredService<SettingsService>()));

        services.AddSingleton(sp => new TaskQueue(
            sp.GetRequiredService<TaskRunner>(),
            sp.GetRequiredService<SettingsService>().Settings.MaxConcurrency));

        services.AddSingleton<WorkspaceService>();
        services.AddSingleton<IScreenCaptureService, ScreenCaptureService>();

        // UI
        services.AddSingleton(sp => new MainViewModel(
            sp.GetRequiredService<WorkspaceService>(),
            openSettings: () => OpenSettingsAsync(null)));
        services.AddSingleton(sp => new OverlaySessionCoordinator(
            sp.GetRequiredService<SettingsService>(),
            sp.GetRequiredService<WorkspaceService>(),
            sp.GetRequiredService<IScreenCaptureService>()));

        var provider = services.BuildServiceProvider();

        // 加载设置后应用代理
        var settings = provider.GetRequiredService<SettingsService>().Settings;
        if (!string.IsNullOrWhiteSpace(settings.ProxyUrl))
        {
            try
            {
                provider.GetRequiredService<IHttpClientProvider>().SetProxy(settings.ProxyUrl);
            }
            catch (ArgumentException)
            {
                // 代理格式错误时忽略，直连
            }
        }

        var workspace = provider.GetRequiredService<WorkspaceService>();
        workspace.WireScheduler();

        return provider;
    }

    private void InitializeTray()
    {
        _trayIcon = new Hardcodet.Wpf.TaskbarNotification.TaskbarIcon
        {
            ToolTipText = "RegionX",
            Icon = LoadAppIcon()
        };

        var menu = new System.Windows.Controls.ContextMenu();
        var showItem = new System.Windows.Controls.MenuItem { Header = "显示主窗口" };
        showItem.Click += (_, _) => ShowMainWindow();
        var exitItem = new System.Windows.Controls.MenuItem { Header = "退出" };
        exitItem.Click += (_, _) => ExitApplication();
        menu.Items.Add(showItem);
        menu.Items.Add(new System.Windows.Controls.Separator());
        menu.Items.Add(exitItem);
        _trayIcon.ContextMenu = menu;
        // 单击 / 双击托盘都唤回主窗口（窗口关闭按钮只是隐藏到托盘）
        _trayIcon.TrayLeftMouseUp += (_, _) => ShowMainWindow();
        _trayIcon.TrayMouseDoubleClick += (_, _) => ShowMainWindow();
    }

    private static System.Drawing.Icon? LoadAppIcon()
    {
        try
        {
            // 优先用嵌入在 exe 里的图标，找不到再退回默认系统图标
            var exePath = Environment.ProcessPath;
            if (exePath is { Length: > 0 } && File.Exists(exePath))
            {
                var icon = System.Drawing.Icon.ExtractAssociatedIcon(exePath);
                if (icon is not null) return icon;
            }
            return System.Drawing.SystemIcons.Application;
        }
        catch (Exception)
        {
            return System.Drawing.SystemIcons.Application;
        }
    }

    private void ShowMainWindow()
    {
        if (_isExiting) return;
        if (_mainWindow is null) return;

        // 窗口可能已被销毁（理论上只在退出流程中出现），这里只做防御性判断
        if (_mainWindow.Dispatcher.HasShutdownStarted) return;

        _mainWindow.Show();
        if (_mainWindow.WindowState == WindowState.Minimized)
        {
            _mainWindow.WindowState = WindowState.Normal;
        }

        // 置顶一小段时间再恢复，确保盖过当前前台窗口（立即取消往往还没完成激活）
        _mainWindow.Topmost = true;
        _mainWindow.Activate();
        ScreenAssistant.Platform.WindowHelper.TrySetForeground(_mainWindow);

        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            if (_mainWindow is not null) _mainWindow.Topmost = false;
        };
        timer.Start();
    }

    public static void ExitApplication()
    {
        var app = (App)Current;
        _ = app.ExitApplicationAsync();
    }

    private async Task ExitApplicationAsync()
    {
        if (_isExiting) return;
        _isExiting = true;
        // 允许主窗口真正关闭，否则会一直被「隐藏到托盘」拦截
        Views.MainWindow.AllowClose = true;
        try
        {
            if (_mainWindow?.DataContext is MainViewModel mainViewModel) mainViewModel.Detach();
            if (_mainWindow is not null) _mainWindow.IsEnabled = false;
            _coordinator?.Shutdown();
            _settingsWindow?.Close();
            _trayIcon?.Dispose();
            _trayIcon = null;
            await DisposeServicesAsync();
        }
        catch (Exception ex) { LogException(ex); }
        finally { Shutdown(); }
    }

    public static async Task OpenSettingsAsync(Window? owner)
    {
        var app = (App)Current;
        if (app._isExiting) return;
        // 只复用真正可见的窗口；已关闭的引用必须丢弃，否则设置页再也打不开
        if (app._settingsWindow is { IsVisible: true })
        {
            app._settingsWindow.Activate();
            return;
        }
        app._settingsWindow = null;

        var viewModel = new SettingsViewModel(
            app._services.GetRequiredService<SettingsService>(),
            app._services.GetRequiredService<IModelConfigRepository>(),
            app._services.GetRequiredService<IHttpClientProvider>(),
            app._services.GetRequiredService<IModelCatalog>(),
            app._services.GetRequiredService<IThemeRegistry>(),
            app._services.GetRequiredService<AppPaths>());

        var window = new SettingsWindow(viewModel);
        if (owner is not null && owner.IsVisible) window.Owner = owner;
        else if (app._mainWindow is not null) window.Owner = app._mainWindow;

        app._settingsWindow = window;
        window.Closed += (_, _) =>
        {
            app._settingsWindow = null;
            if (app._isExiting) return;
            var mainViewModel = app._services.GetRequiredService<MainViewModel>();
            _ = mainViewModel.RefreshModelNameAsync();
        };
        window.ShowDialog();
        await Task.CompletedTask;
    }

    /// <summary>
    /// 打开任务详情：统一在主窗口内展示（不再为每个任务弹独立窗口）。
    /// 从识别框或别处调用时，先把主窗口带到前台。
    /// </summary>
    public static async Task OpenTaskDetailAsync(string taskId)
    {
        var app = (App)Current;
        if (app._isExiting) return;
        var main = app._mainWindow;
        if (main is null) return;

        if (!main.IsVisible) main.Show();
        if (main.WindowState == WindowState.Minimized) main.WindowState = WindowState.Normal;
        main.Activate();

        var viewModel = app._services.GetRequiredService<MainViewModel>();
        var item = viewModel.FindTask(taskId);
        if (item is not null)
        {
            await viewModel.ShowTaskDetailAsync(item);
            return;
        }

        await viewModel.ShowTaskDetailByIdAsync(taskId);
    }

    public static TaskItemActions CreateTaskActions()
    {
        var app = (App)Current;
        var workspace = app._services.GetRequiredService<WorkspaceService>();

        return new TaskItemActions
        {
            Open = async item => await OpenTaskDetailAsync(item.Id),
            Retry = async item =>
            {
                var retried = await workspace.RetryAsync(item.Id);
                if (retried is null)
                {
                    MessageBox.Show("没有可用的模型配置，请先在设置中添加模型。", "无法重试",
                        MessageBoxButton.OK, MessageBoxImage.Warning);
                    return;
                }
                item.UpdateFromRecord();
                item.SetStreaming("");
            },
            Cancel = async item => await workspace.CancelAsync(item.Id),
            Delete = async item =>
            {
                var result = MessageBox.Show(
                    $"删除任务「{item.Title}」及其截图？此操作无法恢复。",
                    "删除任务", MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel);
                if (result != MessageBoxResult.OK) return;
                await workspace.DeleteTaskAsync(item.Id);
            }
        };
    }

    private bool _reportingError;

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        // 单个界面异常不应让整个程序退出
        e.Handled = true;
        LogException(e.Exception);

        // 弹窗本身也会触发布局：若弹窗过程中再次异常，只记日志，避免无限递归
        if (_reportingError) return;
        _reportingError = true;
        try
        {
            var separator = Environment.NewLine + Environment.NewLine;
            MessageBox.Show($"发生错误：{e.Exception.Message}{separator}{e.Exception}",
                "RegionX", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            _reportingError = false;
        }
    }

    /// <summary>把异常写到 %LOCALAPPDATA%\ScreenAssistant\logs\error.log，便于排查。</summary>
    private static void LogException(Exception exception)
    {
        try
        {
            var paths = ((App)Current)._services.GetRequiredService<ScreenAssistant.Data.AppPaths>();
            var dir = paths.EnsureLogsDirectory();
            File.AppendAllText(Path.Combine(dir, "error.log"),
                $"[{DateTime.Now:O}] {exception}{Environment.NewLine}{Environment.NewLine}");
        }
        catch
        {
            // 日志失败不影响主流程
        }
    }

    private void OnDomainUnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        try
        {
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ScreenAssistant", "logs", "crash.log");
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.AppendAllText(path, $"[{DateTime.Now:O}] {e.ExceptionObject}\n");
        }
        catch
        {
            // 记录崩溃日志失败时不做任何事
        }
    }
}
