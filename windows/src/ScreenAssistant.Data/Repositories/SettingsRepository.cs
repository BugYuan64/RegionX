using System.Text.Json;
using Dapper;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Data.Repositories;

public interface ISettingsRepository
{
    AppSettings Load();
    void Save(AppSettings settings);
}

public sealed class SettingsRepository : ISettingsRepository
{
    private const string Key = "app_settings";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    private readonly Database _db;

    public SettingsRepository(Database db)
    {
        _db = db;
        DapperSetup.Register();
    }

    public AppSettings Load()
    {
        using (ConfigFile.Acquire(_db.Paths.SettingsFile))
        {
            if (File.Exists(_db.Paths.SettingsFile))
                return Normalize(ConfigFile.Read<AppSettings>(_db.Paths.SettingsFile));
            using var connection = _db.Open();
            var raw = connection.ExecuteScalar<string?>(
                "SELECT value FROM settings WHERE key = @Key", new { Key });
            if (string.IsNullOrWhiteSpace(raw)) return new AppSettings();

            try
            {
                var loaded = JsonSerializer.Deserialize<AppSettings>(raw, JsonOptions);
                if (loaded is null) return new AppSettings();
                ConfigFile.Write(_db.Paths.SettingsFile, loaded);
                return Normalize(loaded);
            }
            catch (JsonException)
            {
                return new AppSettings();
            }
        }
    }

    public void Save(AppSettings settings)
    {
        using (ConfigFile.Acquire(_db.Paths.SettingsFile))
        {
            ConfigFile.Write(_db.Paths.SettingsFile, settings);
        }
    }

    private static AppSettings Normalize(AppSettings settings)
    {
        settings.MaxConcurrency = Math.Clamp(settings.MaxConcurrency, 1, 5);
        if (settings.TimeoutSeconds is < 10 or > 600) settings.TimeoutSeconds = 120;
        settings.CaptureDelayMs = Math.Clamp(settings.CaptureDelayMs, 0, 2000);
        return settings;
    }
}
