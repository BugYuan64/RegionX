using System.Net;
using System.Text;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Core.Text;
using ScreenAssistant.Llm;

namespace ScreenAssistant.Tests;

public class FirstLineTests
{
    [Theory]
    [InlineData("最大值为 f(2) = 5\n\n解释：配方得 ……", "最大值为 f(2) = 5")]
    [InlineData("**答案：B**\n解析……", "答案：B")]
    [InlineData("\n\n## 译文\n第二段……", "译文")]
    [InlineData("1. 第一行内容\n第二行", "第一行内容")]
    public void FirstLine_StripsMarkdownAndBlankLines(string content, string expected)
    {
        Assert.Equal(expected, TitleGenerator.FirstLine(content));
    }

    [Fact]
    public void Generate_UsesFirstLineAsTitle_AndSummaryIsShort()
    {
        var (title, summary) = TitleGenerator.Generate(
            TaskKind.Answer,
            "最大值为 f(2) = 5\n\n解释：把函数配方得 f(x) = -(x-2)² + 5，对称轴 x = 2 在区间内。");

        // 标题受 10 字限制，会截断首行
        Assert.Equal("最大值为 f(2) ", title);
        Assert.True(title.EnumerateRunes().Count() <= TitleGenerator.TitleMaxLength);
        Assert.True(summary.Length <= TitleGenerator.SummaryMaxLength);
        Assert.DoesNotContain("配方", summary);
        Assert.DoesNotContain("解释", summary);
    }

    [Fact]
    public void Generate_Translation_UsesFirstLineAsSummary()
    {
        var (title, summary) = TitleGenerator.Generate(
            TaskKind.Translate,
            "气候变化正在影响全球。\n\nclimate —— 气候\nchange —— 变化");

        Assert.Equal("气候变化正在影响全球。", summary);
    }
}

public class ModelCatalogTests
{
    [Theory]
    [InlineData(LlmProvider.OpenAiCompatible)]
    [InlineData(LlmProvider.Claude)]
    [InlineData(LlmProvider.Gemini)]
    public async Task ListsModels_SortedAndDeduplicated(LlmProvider provider)
    {
        var body = provider == LlmProvider.Gemini
            ? """{"models":[{"name":"models/zeta"},{"name":"models/Alpha"},{"name":"models/beta"},{"name":"models/Alpha"}]}"""
            : """{"data":[{"id":"zeta"},{"id":"Alpha"},{"id":"beta"},{"id":"Alpha"}]}""";
        var (catalog, _) = Create(provider, body);
        var models = await catalog.ListModelsAsync(Config(provider, "https://example.com"), CancellationToken.None);
        Assert.Equal(new[] { "Alpha", "beta", "zeta" }, models.Select(m => m.Id));
    }

    private static (ModelCatalog Catalog, CapturingHandler Handler) Create(
        LlmProvider provider, string body, HttpStatusCode status = HttpStatusCode.OK)
    {
        var handler = new CapturingHandler(body, status, "application/json");
        var http = new StubHttpClientProvider(handler);
        return (new ModelCatalog(http), handler);
    }

    private static ModelConfig Config(LlmProvider provider, string baseUrl) => new()
    {
        Provider = provider,
        BaseUrl = baseUrl,
        ApiKey = "k",
        ModelName = "x"
    };

    [Fact]
    public async Task OpenAi_ListsModelsFromDataArray()
    {
        var (catalog, handler) = Create(LlmProvider.OpenAiCompatible,
            """{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"}]}""");

        var models = await catalog.ListModelsAsync(
            Config(LlmProvider.OpenAiCompatible, "https://example.com/v1"), CancellationToken.None);

        Assert.Equal(new[] { "gpt-4o", "gpt-4o-mini" }, models.Select(m => m.Id));
        Assert.Equal("https://example.com/v1/models", handler.LastRequest!.RequestUri!.ToString());
        Assert.Equal("Bearer", handler.LastRequest.Headers.Authorization!.Scheme);
    }

    [Fact]
    public async Task Claude_ListsModelsWithDisplayName()
    {
        var (catalog, handler) = Create(LlmProvider.Claude,
            """{"data":[{"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"}]}""");

        var models = await catalog.ListModelsAsync(
            Config(LlmProvider.Claude, "https://api.anthropic.com"), CancellationToken.None);

        var model = Assert.Single(models);
        Assert.Equal("claude-sonnet-4-5", model.Id);
        Assert.Contains("Claude Sonnet 4.5", model.Label);
        Assert.Equal("https://api.anthropic.com/v1/models", handler.LastRequest!.RequestUri!.ToString());
        Assert.Equal("k", Assert.Single(handler.LastRequest.Headers.GetValues("x-api-key")));
    }

    [Fact]
    public async Task Gemini_SkipsEmbeddingModelsAndStripsPrefix()
    {
        var (catalog, _) = Create(LlmProvider.Gemini,
            """
            {"models":[
              {"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}
            ]}
            """);

        var models = await catalog.ListModelsAsync(
            Config(LlmProvider.Gemini, "https://generativelanguage.googleapis.com"), CancellationToken.None);

        var model = Assert.Single(models);
        Assert.Equal("gemini-2.0-flash", model.Id);
    }

    [Fact]
    public async Task Unauthorized_ThrowsAuthenticationError()
    {
        var (catalog, _) = Create(LlmProvider.OpenAiCompatible, "{\"error\":\"bad key\"}",
            HttpStatusCode.Unauthorized);

        var ex = await Assert.ThrowsAsync<ScreenAssistant.Core.Llm.LlmException>(() =>
            catalog.ListModelsAsync(Config(LlmProvider.OpenAiCompatible, "https://example.com/v1"),
                CancellationToken.None));

        Assert.Equal(ScreenAssistant.Core.Llm.LlmErrorKind.Authentication, ex.Kind);
    }

    [Fact]
    public async Task OpenAi_HostOnlyBaseUrl_AppendsV1()
    {
        var (catalog, handler) = Create(LlmProvider.OpenAiCompatible, """{"data":[{"id":"m1"}]}""");

        // 用户只填主机名（没有 /v1）也应能取到列表
        await catalog.ListModelsAsync(
            Config(LlmProvider.OpenAiCompatible, "https://api.example.com"), CancellationToken.None);

        Assert.Equal("https://api.example.com/v1/models", handler.LastRequest!.RequestUri!.ToString());
    }

    [Fact]
    public async Task OpenAi_PastedEndpoint_IsStrippedBackToBase()
    {
        var (catalog, handler) = Create(LlmProvider.OpenAiCompatible, """{"data":[{"id":"m1"}]}""");

        await catalog.ListModelsAsync(
            Config(LlmProvider.OpenAiCompatible, "https://api.example.com/v1/chat/completions"),
            CancellationToken.None);

        Assert.Equal("https://api.example.com/v1/models", handler.LastRequest!.RequestUri!.ToString());
    }

    [Fact]
    public async Task OpenAi_FallsBackToOtherPaths_WhenFirstReturns404()
    {
        // 只有 /models 可用（没有 /v1 前缀的网关）
        var handler = new CapturingHandler(request =>
            request.RequestUri!.AbsolutePath.EndsWith("/v1/models", StringComparison.Ordinal)
                ? new HttpResponseMessage(HttpStatusCode.NotFound)
                : new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"data":[{"id":"m2"}]}""",
                        System.Text.Encoding.UTF8, "application/json")
                });
        var catalog = new ModelCatalog(new StubHttpClientProvider(handler));

        var models = await catalog.ListModelsAsync(
            Config(LlmProvider.OpenAiCompatible, "https://api.example.com"), CancellationToken.None);

        Assert.Equal("m2", Assert.Single(models).Id);
        Assert.Equal("https://api.example.com/models", handler.LastRequest!.RequestUri!.ToString());
    }
}
