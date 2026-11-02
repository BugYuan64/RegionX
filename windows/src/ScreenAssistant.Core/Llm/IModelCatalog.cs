using System.Text.Json.Nodes;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Core.Llm;

public sealed record UpstreamModel(string Id, string? DisplayName)
{
    public string Label => string.IsNullOrWhiteSpace(DisplayName) ? Id : $"{DisplayName}（{Id}）";
}

/// <summary>向上游查询该接口实际提供的模型列表（工具栏「模型」下拉框的数据来源）。</summary>
public interface IModelCatalog
{
    /// <summary>返回模型 id 列表，按上游顺序；失败时抛 LlmException 由调用方降级。</summary>
    Task<IReadOnlyList<UpstreamModel>> ListModelsAsync(ModelConfig config, CancellationToken cancellationToken);
}
