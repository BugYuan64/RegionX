using System.Text.Json;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Core.Tasks;
using ScreenAssistant.Data.Repositories;

namespace ScreenAssistant.App.Services;

/// <summary>设置的单例门面：先写入配置文件，成功后更新内存并通知界面。</summary>
public sealed class SettingsService : IAppSettingsAccessor
{
    private static readonly JsonSerializerOptions CloneOptions = new();

    private readonly ISettingsRepository _repository;
    private readonly object _gate = new();
    private AppSettings _settings;

    public SettingsService(ISettingsRepository repository)
    {
        _repository = repository;
        _settings = repository.Load();
    }

    public AppSettings Settings
    {
        get { lock (_gate) return _settings; }
    }

    public event EventHandler? Changed;

    public void Save(AppSettings settings)
    {
        lock (_gate)
        {
            _repository.Save(settings);
            _settings = settings;
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>以副本方式修改后保存，避免多处共享同一个可变对象。</summary>
    public void Update(Action<AppSettings> mutate)
    {
        AppSettings copy;
        lock (_gate)
        {
            var json = JsonSerializer.Serialize(_settings, CloneOptions);
            copy = JsonSerializer.Deserialize<AppSettings>(json, CloneOptions) ?? new AppSettings();
        }
        mutate(copy);
        Save(copy);
    }
}
