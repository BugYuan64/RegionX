using System.Text.Json;
using Dapper;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Data.Repositories;

public sealed class ModelConfigRow
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public LlmProvider Provider { get; set; }
    public string BaseUrl { get; set; } = "";
    public string ApiKeyEnc { get; set; } = "";
    public string ModelName { get; set; } = "";
    public bool SupportsImages { get; set; } = true;
    public double? Temperature { get; set; }
    public int? MaxTokens { get; set; }
    public string? ExtraHeadersEnc { get; set; }
    [System.Text.Json.Serialization.JsonIgnore]
    public string? ExtraHeaders { get; set; }
    public int SortOrder { get; set; }
}

public interface IModelConfigRepository
{
    /// <summary>返回包含明文 Key 的配置，仅供内部调用，不应写日志。</summary>
    Task<IReadOnlyList<ModelConfig>> GetAllAsync();
    Task<ModelConfig?> GetAsync(string id);
    Task InsertAsync(ModelConfig config);
    Task UpdateAsync(ModelConfig config);
    Task DeleteAsync(string id);
    /// <summary>兼容旧调用：确保旧数据库配置已迁移到配置文件。</summary>
    Task ExportAsync();
}

public sealed class ModelConfigRepository : IModelConfigRepository
{
    private readonly Database _db;
    private readonly ISecretProtector _protector;
    private readonly AppPaths _paths;

    public ModelConfigRepository(Database db, ISecretProtector protector, AppPaths paths)
    {
        _db = db;
        _protector = protector;
        _paths = paths;
        DapperSetup.Register();
    }

    private List<ModelConfigRow> ReadRows()
    {
        if (File.Exists(_paths.ModelsFile))
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(_paths.ModelsFile));
            // 旧版 models.json 只是脱敏快照，不能覆盖数据库中的完整配置。
            var legacy = doc.RootElement.ValueKind == JsonValueKind.Array &&
                         doc.RootElement.EnumerateArray().Any(e => e.TryGetProperty("ApiKeyMasked", out _));
            if (!legacy) return ConfigFile.Read<List<ModelConfigRow>>(_paths.ModelsFile);
        }

        using var connection = _db.Open();
        var rows = connection.Query<ModelConfigRow>("""
            SELECT id, name, provider, base_url AS BaseUrl, api_key_enc AS ApiKeyEnc,
                   model_name AS ModelName, supports_images AS SupportsImages, temperature,
                   max_tokens AS MaxTokens, extra_headers AS ExtraHeaders, sort_order AS SortOrder
            FROM model_configs ORDER BY sort_order, name
            """).ToList();
        foreach (var row in rows)
        {
            if (!row.ApiKeyEnc.StartsWith("dpapi:", StringComparison.Ordinal))
                row.ApiKeyEnc = _protector.Protect(row.ApiKeyEnc);
            row.ExtraHeadersEnc = _protector.Protect(row.ExtraHeaders ?? "");
            row.ExtraHeaders = null;
        }
        ConfigFile.Write(_paths.ModelsFile, rows);
        return rows;
    }

    public Task<IReadOnlyList<ModelConfig>> GetAllAsync()
    {
        using (ConfigFile.Acquire(_paths.ModelsFile))
        {
            IReadOnlyList<ModelConfig> configs = ReadRows()
                .OrderBy(r => r.ModelName, StringComparer.OrdinalIgnoreCase)
                .ThenBy(r => r.Name, StringComparer.OrdinalIgnoreCase)
                .ThenBy(r => r.Id, StringComparer.Ordinal)
                .Select(ToConfig).ToList();
            return Task.FromResult(configs);
        }
    }

    public async Task<ModelConfig?> GetAsync(string id) =>
        (await GetAllAsync().ConfigureAwait(false)).FirstOrDefault(c => c.Id == id);

    public Task InsertAsync(ModelConfig config) => Write(config, insert: true);
    public Task UpdateAsync(ModelConfig config) => Write(config, insert: false);

    private Task Write(ModelConfig config, bool insert)
    {
        using (ConfigFile.Acquire(_paths.ModelsFile))
        {
            var rows = ReadRows();
            var index = rows.FindIndex(r => r.Id == config.Id);
            if (insert && index >= 0) throw new InvalidOperationException("模型配置已存在。");
            if (!insert && index < 0) throw new InvalidOperationException("模型配置已删除，请重新新增。");
            var row = new ModelConfigRow
            {
                Id = config.Id,
                Name = config.Name,
                Provider = config.Provider,
                BaseUrl = config.BaseUrl,
                ApiKeyEnc = _protector.Protect(config.ApiKey),
                ModelName = config.ModelName,
                SupportsImages = config.SupportsImages,
                Temperature = config.Temperature,
                MaxTokens = config.MaxTokens,
                ExtraHeadersEnc = _protector.Protect(config.ExtraHeaders ?? ""),
                SortOrder = index >= 0 ? rows[index].SortOrder : rows.Count
            };
            if (index >= 0) rows[index] = row;
            else rows.Add(row);
            ConfigFile.Write(_paths.ModelsFile, rows);
            return Task.CompletedTask;
        }
    }

    public Task DeleteAsync(string id)
    {
        using (ConfigFile.Acquire(_paths.ModelsFile))
        {
            var rows = ReadRows();
            rows.RemoveAll(r => r.Id == id);
            ConfigFile.Write(_paths.ModelsFile, rows);
            return Task.CompletedTask;
        }
    }

    public Task ExportAsync()
    {
        using (ConfigFile.Acquire(_paths.ModelsFile)) ReadRows();
        return Task.CompletedTask;
    }

    private ModelConfig ToConfig(ModelConfigRow row) => new()
    {
        Id = row.Id,
        Name = row.Name,
        Provider = row.Provider,
        BaseUrl = row.BaseUrl,
        ApiKey = SafeUnprotect(row.ApiKeyEnc),
        ModelName = row.ModelName,
        SupportsImages = row.SupportsImages,
        Temperature = row.Temperature,
        MaxTokens = row.MaxTokens,
        ExtraHeaders = string.IsNullOrEmpty(row.ExtraHeadersEnc) ? null : SafeUnprotect(row.ExtraHeadersEnc)
    };

    private string SafeUnprotect(string encrypted)
    {
        try { return _protector.Unprotect(encrypted); }
        catch (System.Security.Cryptography.CryptographicException) { return ""; }
        catch (FormatException) { return ""; }
    }
}
