using System.Windows;
using System.Windows.Threading;

namespace ScreenAssistant.Tests;

[CollectionDefinition("WPF", DisableParallelization = true)]
public sealed class WpfCollection;

internal static class WpfTestHost
{
    private static readonly Lazy<Task<Dispatcher>> Host = new(() =>
    {
        var ready = new TaskCompletionSource<Dispatcher>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try
            {
                var app = new ScreenAssistant.App.App();
                app.InitializeComponent();
                SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext());
                ready.SetResult(Dispatcher.CurrentDispatcher);
                Dispatcher.Run();
            }
            catch (Exception ex) { ready.TrySetException(ex); }
        })
        { IsBackground = true, Name = "RegionX WPF tests" };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return ready.Task;
    });

    internal static async Task RunAsync(Func<Task> action)
    {
        var dispatcher = await Host.Value.WaitAsync(TimeSpan.FromSeconds(15));
        await dispatcher.InvokeAsync(async () =>
        {
            var errors = new List<Exception>();
            DispatcherUnhandledExceptionEventHandler onError = (_, e) => { errors.Add(e.Exception); e.Handled = true; };
            dispatcher.UnhandledException += onError;
            try
            {
                await action();
                await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
                if (errors.Count > 0) throw new AggregateException("WPF 窗口回调发生未处理异常。", errors);
            }
            finally { dispatcher.UnhandledException -= onError; }
        }).Task.Unwrap().WaitAsync(TimeSpan.FromSeconds(20));
    }
}
