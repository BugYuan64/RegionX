namespace ScreenAssistant.Core.Tasks;

/// <summary>
/// 可调整容量的异步闸门（替代 SemaphoreSlim 不支持缩容的缺陷）。
/// 缩小容量时不打断正在运行的任务，只影响后续进入者。
/// </summary>
public sealed class AdjustableGate
{
    private readonly object _gate = new();
    private readonly Queue<TaskCompletionSource<bool>> _waiters = new();
    private int _capacity;
    private int _running;

    public AdjustableGate(int capacity)
    {
        _capacity = Math.Max(1, capacity);
    }

    public int Capacity
    {
        get { lock (_gate) return _capacity; }
    }

    public void SetCapacity(int capacity)
    {
        lock (_gate)
        {
            _capacity = Math.Max(1, capacity);
            DrainWaiters();
        }
    }

    public Task WaitAsync(CancellationToken cancellationToken)
    {
        lock (_gate)
        {
            if (_running < _capacity)
            {
                _running++;
                return Task.CompletedTask;
            }

            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            if (cancellationToken.CanBeCanceled)
            {
                var registration = cancellationToken.Register(() =>
                {
                    lock (_gate)
                    {
                        if (_waiters.Contains(tcs)) tcs.TrySetCanceled(cancellationToken);
                    }
                });
                _ = registration;
            }
            _waiters.Enqueue(tcs);
            return tcs.Task;
        }
    }

    public void Release()
    {
        lock (_gate)
        {
            if (_running > 0) _running--;
            DrainWaiters();
        }
    }

    private void DrainWaiters()
    {
        while (_running < _capacity && _waiters.Count > 0)
        {
            var waiter = _waiters.Dequeue();
            if (waiter.Task.IsCompleted) continue;
            _running++;
            waiter.TrySetResult(true);
        }
    }
}
