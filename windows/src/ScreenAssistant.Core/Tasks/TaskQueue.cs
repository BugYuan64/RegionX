using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Core.Tasks;

public sealed class TaskQueuedEventArgs : EventArgs
{
    public required TaskRecord Task { get; init; }
    public required ModelConfig Model { get; init; }
}

/// <summary>
/// 任务队列：限制同时运行的任务数，每个任务独立持有 CancellationTokenSource，
/// 可取消、可重试。单个任务失败不影响其他任务。
/// </summary>
public sealed class TaskQueue : IAsyncDisposable
{
    private readonly TaskRunner _runner;
    private readonly AdjustableGate _gate;
    private readonly object _lock = new();
    private readonly Dictionary<string, RunningEntry> _running = new();
    private readonly Dictionary<string, Task> _workers = new();

    public TaskQueue(TaskRunner runner, int maxConcurrency)
    {
        _runner = runner;
        _gate = new AdjustableGate(maxConcurrency);
    }

    public TaskRunner Runner => _runner;

    public event EventHandler<TaskQueuedEventArgs>? TaskQueued;

    public int MaxConcurrency => _gate.Capacity;

    public void UpdateConcurrency(int value) => _gate.SetCapacity(value);

    /// <summary>提交任务，立即返回，不等待模型。</summary>
    public void Enqueue(TaskRecord task, ModelConfig model)
    {
        var cts = new CancellationTokenSource();
        lock (_lock)
        {
            _running[task.Id] = new RunningEntry(cts);
        }

        TaskQueued?.Invoke(this, new TaskQueuedEventArgs { Task = task, Model = model });
        StartWorker(task, model, cts);
    }

    /// <summary>重试：沿用原任务（保留标题与截图），重新排队。</summary>
    public void Retry(TaskRecord task, ModelConfig model) => Enqueue(task, model);

    private void StartWorker(TaskRecord task, ModelConfig model, CancellationTokenSource cts)
    {
        var worker = Task.Run(async () =>
        {
            var acquired = false;
            try
            {
                await _gate.WaitAsync(cts.Token).ConfigureAwait(false);
                acquired = true;
                await _runner.RunAsync(task, model, cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // 排队阶段被取消：TaskRunner 未接管状态，这里补上
                if (!acquired)
                {
                    task.Status = TaskState.Cancelled;
                    task.UpdatedAt = DateTimeOffset.Now;
                }
            }
            catch (Exception ex)
            {
                task.Status = TaskState.Failed;
                task.Error = $"任务执行异常：{ex.Message}";
                task.UpdatedAt = DateTimeOffset.Now;
            }
            finally
            {
                if (acquired) _gate.Release();
                cts.Dispose();
                lock (_lock)
                {
                    _running.Remove(task.Id);
                    _workers.Remove(task.Id);
                }
            }
        });

        lock (_lock)
        {
            _workers[task.Id] = worker;
        }
    }

    public bool Cancel(string taskId)
    {
        lock (_lock)
        {
            if (!_running.TryGetValue(taskId, out var entry)) return false;
            try
            {
                entry.Cts.Cancel();
                return true;
            }
            catch (ObjectDisposedException)
            {
                return false;
            }
        }
    }

    public bool IsRunning(string taskId)
    {
        lock (_lock) return _running.ContainsKey(taskId);
    }

    public async ValueTask DisposeAsync()
    {
        Task[] workers;
        lock (_lock)
        {
            foreach (var entry in _running.Values)
            {
                try
                {
                    entry.Cts.Cancel();
                }
                catch (ObjectDisposedException)
                {
                }
            }
            workers = _workers.Values.ToArray();
        }

        try
        {
            await Task.WhenAll(workers).WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        }
        catch
        {
            // 退出时不为残留任务阻塞
        }
    }

    private sealed class RunningEntry
    {
        public RunningEntry(CancellationTokenSource cts) => Cts = cts;
        public CancellationTokenSource Cts { get; }
    }
}
