using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace ScreenAssistant.Llm;

public static class Json
{
    public static readonly JsonSerializerOptions Options = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        WriteIndented = false
    };

    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);

    public static JsonNode? Parse(string json)
    {
        try
        {
            return JsonNode.Parse(json);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>按路径读取嵌套字符串，路径元素可以是属性名（string）或数组下标（int）。</summary>
    public static string? StringAt(JsonNode? node, params object[] path)
    {
        node = At(node, path);
        return node is JsonValue value && value.TryGetValue<string>(out var s) ? s : null;
    }

    public static JsonNode? At(JsonNode? node, params object[] path)
    {
        foreach (var segment in path)
        {
            switch (segment)
            {
                case int index:
                    if (node is not JsonArray array || index < 0 || index >= array.Count) return null;
                    node = array[index];
                    break;
                case string name:
                    if (node is not JsonObject obj || !obj.TryGetPropertyValue(name, out node)) return null;
                    break;
                default:
                    return null;
            }
        }
        return node;
    }
}
