using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Core.Tasks;
using ScreenAssistant.Core.Text;

namespace ScreenAssistant.Tests;

public class TitleGeneratorTests
{
    [Fact]
    public void Take_RespectsCharacterCount_ForSurrogatePairs()
    {
        // emoji 占 2 个 UTF-16 码元，按字符截断不应切坏
        var title = TitleGenerator.Take("😀😀😀😀😀😀😀😀😀😀😀😀", 10);
        Assert.Equal(10, title.EnumerateRunes().Count());
        Assert.Equal(20, title.Length);
    }

    [Fact]
    public void Generate_UsesFirstLineForTitle_AndStripsMarkdownNoise()
    {
        var (title, summary) = TitleGenerator.Generate(
            TaskKind.Answer,
            "\n\n答案是 B。\n解析：因为 ……\n");

        Assert.Equal("答案是 B。", title);
        Assert.StartsWith("答案是 B。", summary);
        Assert.True(summary.Length <= TitleGenerator.SummaryMaxLength);
    }

    [Fact]
    public void Generate_FallsBackWhenModelReturnsNothing()
    {
        var (title, summary) = TitleGenerator.Generate(TaskKind.Translate, "   \n ");
        Assert.Equal("翻译", title);
        Assert.Equal("已翻译框内文字", summary);
    }
}

public class AdjustableGateTests
{
    [Fact]
    public async Task Gate_LimitsConcurrency_AndReleasesOnCapacityIncrease()
    {
        var gate = new AdjustableGate(1);
        await gate.WaitAsync(CancellationToken.None);

        var second = gate.WaitAsync(CancellationToken.None);
        Assert.False(second.IsCompleted);

        // 缩小容量不会让排队者通过
        gate.SetCapacity(1);
        Assert.False(second.IsCompleted);

        gate.SetCapacity(2);
        await second.WaitAsync(TimeSpan.FromSeconds(1));
    }

    [Fact]
    public async Task Gate_CancelledWaiterDoesNotConsumeSlot()
    {
        var gate = new AdjustableGate(1);
        await gate.WaitAsync(CancellationToken.None);

        using var cts = new CancellationTokenSource();
        var waiter = gate.WaitAsync(cts.Token);
        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => waiter);

        gate.Release();
        var next = gate.WaitAsync(CancellationToken.None);
        await next.WaitAsync(TimeSpan.FromSeconds(1));
    }
}

/// <summary>假的网关客户端：按脚本返回文本，并可观察并发峰值。</summary>
internal sealed class FakeChatClient : IChatModelClient
{
    private static int _active;
    private static int _peak;

    public static int Peak => _peak;
    public static TimeSpan Delay { get; set; } = TimeSpan.FromMilliseconds(60);

    public static void Reset()
    {
        _active = 0;
        _peak = 0;
    }

    public async IAsyncEnumerable<string> StreamAsync(
        LlmChatRequest request,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var now = Interlocked.Increment(ref _active);
        InterlockedMax(ref _peak, now);
        try
        {
            await Task.Delay(Delay, cancellationToken);
            yield return "答案";
            await Task.Delay(Delay, cancellationToken);
            yield return "：B";
        }
        finally
        {
            Interlocked.Decrement(ref _active);
        }
    }

    private static void InterlockedMax(ref int target, int value)
    {
        int current;
        while (value > (current = Volatile.Read(ref target)) &&
               Interlocked.CompareExchange(ref target, value, current) != current)
        {
        }
    }
}

internal sealed class FakeFactory : IChatModelClientFactory
{
    public IChatModelClient Create(ModelConfig config) => new FakeChatClient();
}

internal sealed class MemoryPersistence : ITaskPersistence
{
    public List<TaskRecord> Updates { get; } = new();
    public List<string> AssistantMessages { get; } = new();

    public Task UpdateTaskAsync(TaskRecord task, CancellationToken cancellationToken)
    {
        Updates.Add(task.Clone());
        return Task.CompletedTask;
    }

    public Task SaveAssistantMessageAsync(TaskRecord task, string content, CancellationToken cancellationToken)
    {
        AssistantMessages.Add(content);
        return Task.CompletedTask;
    }
}

internal sealed class NoImages : ITaskImageSource
{
    public Task<byte[]> LoadAsync(string? imagePath, CancellationToken cancellationToken) =>
        Task.FromResult(Array.Empty<byte>());
}

internal sealed class FixedSettings : IAppSettingsAccessor
{
    public AppSettings Settings { get; } = new() { MaxConcurrency = 2, TimeoutSeconds = 30 };
}

public class TaskQueueTests
{
    private static (TaskQueue Queue, MemoryPersistence Persistence) Create(int concurrency = 2)
    {
        var persistence = new MemoryPersistence();
        var runner = new TaskRunner(new FakeFactory(), persistence, new NoImages(), new FixedSettings());
        return (new TaskQueue(runner, concurrency), persistence);
    }

    private static TaskRecord NewTask(string id = "t1") => new()
    {
        Id = id,
        SessionId = "s1",
        Kind = TaskKind.Answer,
        ImagePath = "a.png"
    };

    private static readonly ModelConfig Model = new()
    {
        Id = "m1",
        Name = "测试模型",
        ModelName = "fake",
        ApiKey = "k"
    };

    [Fact]
    public async Task Queue_NeverExceedsConcurrencyLimit()
    {
        FakeChatClient.Reset();
        FakeChatClient.Delay = TimeSpan.FromMilliseconds(40);
        var (queue, _) = Create(concurrency: 2);

        var tasks = Enumerable.Range(0, 6).Select(i => NewTask($"t{i}")).ToArray();
        foreach (var task in tasks) queue.Enqueue(task, Model);

        await WaitUntilAsync(() => tasks.All(t => t.Status == TaskState.Completed), TimeSpan.FromSeconds(10));
        await queue.DisposeAsync();

        Assert.True(FakeChatClient.Peak <= 2, $"并发峰值 {FakeChatClient.Peak} 超过上限 2");
        Assert.All(tasks, t =>
        {
            Assert.Equal(TaskState.Completed, t.Status);
            Assert.Equal("答案：B", t.Title);
        });
    }

    [Fact]
    public async Task Queue_CancelKeepsTaskInCancelledState()
    {
        FakeChatClient.Reset();
        FakeChatClient.Delay = TimeSpan.FromMilliseconds(500);
        var (queue, _) = Create(concurrency: 1);

        var task = NewTask();
        queue.Enqueue(task, Model);
        await Task.Delay(80);

        Assert.True(queue.Cancel(task.Id));
        await WaitUntilAsync(() => task.Status == TaskState.Cancelled, TimeSpan.FromSeconds(5));
        await queue.DisposeAsync();

        Assert.Equal(TaskState.Cancelled, task.Status);
    }

    [Fact]
    public async Task Runner_OnSuccess_SetsTitleFromResult()
    {
        FakeChatClient.Reset();
        FakeChatClient.Delay = TimeSpan.Zero;
        var persistence = new MemoryPersistence();
        var runner = new TaskRunner(new FakeFactory(), persistence, new NoImages(), new FixedSettings());
        var task = NewTask();

        await runner.RunAsync(task, Model, CancellationToken.None);

        Assert.Equal(TaskState.Completed, task.Status);
        Assert.Equal("答案：B", task.Title);
        Assert.Equal("答案：B", Assert.Single(persistence.AssistantMessages));
        Assert.Equal(TaskState.Running, persistence.Updates.First().Status);
    }

    [Fact]
    public async Task Runner_OnClientError_MarksFailedWithRetryHint()
    {
        var runner = new TaskRunner(new FailingFactory(), new MemoryPersistence(), new NoImages(), new FixedSettings());
        var task = NewTask();

        await runner.RunAsync(task, Model, CancellationToken.None);

        Assert.Equal(TaskState.Failed, task.Status);
        Assert.Contains("429", task.Error);
    }

    private static async Task WaitUntilAsync(Func<bool> condition, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            if (condition()) return;
            await Task.Delay(20);
        }
        Assert.True(condition(), "等待条件超时");
    }

    private sealed class FailingFactory : IChatModelClientFactory
    {
        public IChatModelClient Create(ModelConfig config) => new FailingClient();
    }

    private sealed class FailingClient : IChatModelClient
    {
        public async IAsyncEnumerable<string> StreamAsync(
            LlmChatRequest request,
            [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
        {
            await Task.Yield();
            throw new LlmException(LlmErrorKind.RateLimited, "请求过于频繁（429）");
#pragma warning disable CS0162
            yield break;
#pragma warning restore CS0162
        }
    }
}
