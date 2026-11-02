using ScreenAssistant.App.Services;
using ScreenAssistant.App.Themes;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.App.Converters;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Data;
using ScreenAssistant.Data.Repositories;
using ScreenAssistant.Llm;

namespace ScreenAssistant.Tests;

[Collection("WPF")]
public class SettingsFeedbackTests
{
    [Fact]
    public void InvalidProxy_IsRejectedOnEverySaveAttempt()
    {
        using var http = new HttpClientProvider();
        Assert.Throws<ArgumentException>(() => http.SetProxy("invalid proxy"));
        Assert.Throws<ArgumentException>(() => http.SetProxy("invalid proxy"));
    }

    [Fact]
    public async Task FailedSettingsSave_RestoresRuntimeProxyAndKeepsInput()
    {
        using var temp = new TempWorkspace();
        using var http = new RecordingHttp();
        var service = new SettingsService(new SettingsRepository(temp.Database));
        service.Update(s => s.ProxyUrl = "http://localhost:18080");
        http.SetProxy(service.Settings.ProxyUrl);
        var vm = new SettingsViewModel(service,
            new ModelConfigRepository(temp.Database, new DpapiSecretProtector(), temp.Paths),
            http, new ModelCatalog(http), new ThemeRegistry(), temp.Paths);
        vm.ProxyUrl = "http://localhost:18081";
        File.SetAttributes(temp.Paths.SettingsFile, FileAttributes.ReadOnly);
        try
        {
            await vm.SaveSettingsCommand.ExecuteAsync(null);
            Assert.True(vm.IsStatusError);
            Assert.Equal("http://localhost:18080", service.Settings.ProxyUrl);
            Assert.Equal("http://localhost:18080", http.ProxyUrl);
            Assert.Equal("http://localhost:18081", vm.ProxyUrl);
        }
        finally { File.SetAttributes(temp.Paths.SettingsFile, FileAttributes.Normal); }
    }

    private sealed class RecordingHttp : IHttpClientProvider, IDisposable
    {
        public HttpClient Client { get; } = new();
        public string? ProxyUrl { get; private set; }
        public void SetProxy(string? proxyUrl) => ProxyUrl = proxyUrl;
        public void Dispose() => Client.Dispose();
    }

    [Fact]
    public Task SettingsWindow_LoadsResourcesAndRendersDeepSeekIcon() => WpfTestHost.RunAsync(() =>
    {
        var app = System.Windows.Application.Current;
        using var temp = new TempWorkspace();
        using var http = new HttpClientProvider();
        var vm = new SettingsViewModel(new SettingsService(new SettingsRepository(temp.Database)),
            new ModelConfigRepository(temp.Database, new DpapiSecretProtector(), temp.Paths),
            http, new ModelCatalog(http), new ThemeRegistry(), temp.Paths);
        var window = new ScreenAssistant.App.Views.SettingsWindow(vm);
        Assert.True(vm.AddModelCommand.ExecuteAsync(null).IsCompletedSuccessfully);
        vm.SelectedModel!.Name = "DeepSeek 示例";
        vm.SelectedModel.ModelName = "deepseek-chat";
        vm.SelectedModel.ApiKey = "local-render-fixture";
        Assert.True(vm.SaveSettingsCommand.ExecuteAsync(null).IsCompletedSuccessfully);
        var content = (System.Windows.FrameworkElement)window.Content;
        content.Measure(new System.Windows.Size(960, 720));
        content.Arrange(new System.Windows.Rect(0, 0, 960, 720));
        content.UpdateLayout();
        Assert.NotNull(BrandIconConverter.ResolveGeometry("deepseek-chat"));
        Assert.Equal(BrandIconConverter.ResolveGeometry("gpt-4o"), BrandIconConverter.ResolveGeometry("unrecognized"));
        // 所有有映射的品牌都应显示图标，即使某个品牌资源缺失。
        Assert.NotNull(BrandIconConverter.ResolveGeometry("claude-sonnet"));
        foreach (var (name, key) in new[] { ("mimo-v2-flash", "BrandMimo"), ("gemini-2.5-pro", "BrandGemini"), ("glm-5", "BrandGlm") })
        {
            var geometry = BrandIconConverter.ResolveGeometry(name);
            Assert.Same(app.TryFindResource(key), geometry);
            Assert.NotNull(geometry);
            Assert.True(geometry.Bounds.Width > 0 && geometry.Bounds.Height > 0);
            Assert.InRange(geometry.Bounds.Width / geometry.Bounds.Height, 0.8, 1.3);
        }
        var bitmap = new System.Windows.Media.Imaging.RenderTargetBitmap(960, 720, 96, 96,
            System.Windows.Media.PixelFormats.Pbgra32);
        var visual = new System.Windows.Media.DrawingVisual();
        using (var draw = visual.RenderOpen())
        {
            draw.DrawRectangle(window.Background, null, new System.Windows.Rect(0, 0, 960, 720));
            draw.DrawRectangle(new System.Windows.Media.VisualBrush(content), null, new System.Windows.Rect(0, 0, 960, 720));
        }
        bitmap.Render(visual);
        var screenshot = Environment.GetEnvironmentVariable("REGIONX_TEST_SCREENSHOT");
        if (!string.IsNullOrEmpty(screenshot))
        {
            var png = new System.Windows.Media.Imaging.PngBitmapEncoder();
            png.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bitmap));
            using var stream = File.Create(screenshot);
            png.Save(stream);
        }
        window.Close();
        return Task.CompletedTask;
    });

    [Fact]
    public async Task SaveSettings_PersistsModelAndClearsEditor_AfterReopening()
    {
        using var temp = new TempWorkspace();
        using var http = new HttpClientProvider();
        var models = new ModelConfigRepository(temp.Database, new DpapiSecretProtector(), temp.Paths);
        var vm = new SettingsViewModel(new SettingsService(new SettingsRepository(temp.Database)),
            models, http, new ModelCatalog(http), new ThemeRegistry(), temp.Paths);
        await vm.AddModelCommand.ExecuteAsync(null);
        vm.SelectedModel!.Name = "测试配置";
        vm.SelectedModel.ModelName = "deepseek-chat";
        vm.SelectedModel.ApiKey = "test-secret-for-roundtrip";
        vm.SelectedModel.ExtraHeaders = "X-Test: private-header";
        await vm.SaveSettingsCommand.ExecuteAsync(null);
        Assert.Null(vm.SelectedModel);
        Assert.Contains("已保存", vm.StatusMessage);
        var file = Path.Combine(temp.Paths.Root, "models.json");
        Assert.True(File.Exists(file));
        var json = File.ReadAllText(file);
        Assert.DoesNotContain("test-secret-for-roundtrip", json);
        Assert.DoesNotContain("private-header", json);
        // 配置文件本身足够恢复配置，不依赖原来的 SQLite。
        using var restored = new TempWorkspace();
        File.Copy(file, Path.Combine(restored.Paths.Root, "models.json"));
        var reopened = new ModelConfigRepository(restored.Database, new DpapiSecretProtector(), restored.Paths);
        var saved = Assert.Single(await reopened.GetAllAsync());
        Assert.Equal("deepseek-chat", saved.ModelName);
        Assert.Equal("test-secret-for-roundtrip", saved.ApiKey);
        Assert.Equal("X-Test: private-header", saved.ExtraHeaders);
    }

    [Fact]
    public async Task InvalidModel_RemainsInEditor_WhenSaveSettingsClicked()
    {
        using var temp = new TempWorkspace();
        using var http = new HttpClientProvider();
        var vm = new SettingsViewModel(new SettingsService(new SettingsRepository(temp.Database)),
            new ModelConfigRepository(temp.Database, new DpapiSecretProtector(), temp.Paths),
            http, new ModelCatalog(http), new ThemeRegistry(), temp.Paths);
        await vm.AddModelCommand.ExecuteAsync(null);
        var draft = vm.SelectedModel;
        await vm.SaveSettingsCommand.ExecuteAsync(null);
        Assert.Same(draft, vm.SelectedModel);
        Assert.Contains("模型名", vm.StatusMessage);
    }

    [Fact]
    public void GeneralSettings_RestoreFromFileWithoutOriginalDatabase()
    {
        using var temp = new TempWorkspace();
        new SettingsRepository(temp.Database).Save(new AppSettings { TranslateTargetLanguage = "English" });
        var file = Path.Combine(temp.Paths.Root, "settings.json");
        Assert.True(File.Exists(file));
        using var restored = new TempWorkspace();
        File.Copy(file, Path.Combine(restored.Paths.Root, "settings.json"));
        Assert.Equal("English", new SettingsRepository(restored.Database).Load().TranslateTargetLanguage);
    }

    [Fact]
    public async Task WriteFailure_PreservesEditorAndPreviousConfig_ThenRetrySucceeds()
    {
        using var temp = new TempWorkspace();
        using var http = new HttpClientProvider();
        var repository = new ModelConfigRepository(temp.Database, new DpapiSecretProtector(), temp.Paths);
        var vm = new SettingsViewModel(new SettingsService(new SettingsRepository(temp.Database)),
            repository, http, new ModelCatalog(http), new ThemeRegistry(), temp.Paths);
        await vm.AddModelCommand.ExecuteAsync(null);
        vm.SelectedModel!.ModelName = "gpt-test";
        vm.SelectedModel.ApiKey = "local-test-secret";
        var draft = vm.SelectedModel;
        var previous = File.ReadAllText(temp.Paths.ModelsFile);
        File.SetAttributes(temp.Paths.ModelsFile, FileAttributes.ReadOnly);
        try
        {
            await vm.SaveModelCommand.ExecuteAsync(null);
            Assert.Same(draft, vm.SelectedModel);
            Assert.True(vm.IsStatusError);
            Assert.Equal(previous, File.ReadAllText(temp.Paths.ModelsFile));
        }
        finally { File.SetAttributes(temp.Paths.ModelsFile, FileAttributes.Normal); }
        await vm.SaveModelCommand.ExecuteAsync(null);
        Assert.Null(vm.SelectedModel);
        Assert.False(vm.IsStatusError);
        Assert.Single(await repository.GetAllAsync());
    }

    [Fact]
    public void FailedSettingsWrite_DoesNotChangeInMemoryState()
    {
        using var temp = new TempWorkspace();
        var service = new SettingsService(new SettingsRepository(temp.Database));
        service.Update(s => s.TranslateTargetLanguage = "English");
        File.SetAttributes(temp.Paths.SettingsFile, FileAttributes.ReadOnly);
        try
        {
            Assert.ThrowsAny<Exception>(() => service.Update(s => s.TranslateTargetLanguage = "French"));
            Assert.Equal("English", service.Settings.TranslateTargetLanguage);
        }
        finally { File.SetAttributes(temp.Paths.SettingsFile, FileAttributes.Normal); }
    }

    [Fact]
    public async Task LegacyDatabaseAndMaskedSnapshot_MigrateWithoutLosingEncryptedFields()
    {
        using var temp = new TempWorkspace();
        var protector = new DpapiSecretProtector();
        using (var connection = temp.Database.Open())
        {
            using var command = connection.CreateCommand();
            command.CommandText = """
                INSERT INTO model_configs (id,name,provider,model_name,api_key_enc,extra_headers)
                VALUES ('legacy','旧配置',0,'deepseek-chat',@key,'X-Test: private-header');
                INSERT INTO settings (key,value) VALUES ('app_settings','{"TranslateTargetLanguage":"English","ActiveModelId":"legacy"}');
                """;
            command.Parameters.AddWithValue("@key", protector.Protect("legacy-private-key"));
            command.ExecuteNonQuery();
        }
        File.WriteAllText(temp.Paths.ModelsFile, """[{"Id":"legacy","ApiKeyMasked":"****"}]""");
        var repository = new ModelConfigRepository(temp.Database, protector, temp.Paths);
        var model = Assert.Single(await repository.GetAllAsync());
        Assert.Equal("legacy-private-key", model.ApiKey);
        Assert.Equal("X-Test: private-header", model.ExtraHeaders);
        var persisted = File.ReadAllText(temp.Paths.ModelsFile);
        Assert.DoesNotContain("legacy-private-key", persisted);
        Assert.DoesNotContain("private-header", persisted);
        Assert.DoesNotContain("ApiKeyMasked", persisted);
        Assert.Equal("legacy", new SettingsRepository(temp.Database).Load().ActiveModelId);
        Assert.True(File.Exists(temp.Paths.SettingsFile));
        await repository.DeleteAsync(model.Id);
        Assert.Empty(await new ModelConfigRepository(temp.Database, protector, temp.Paths).GetAllAsync());
    }

    [Fact]
    public async Task ConfigList_SortsAfterAddRenameAndDelete()
    {
        using var temp = new TempWorkspace();
        var repository = new ModelConfigRepository(temp.Database, new DpapiSecretProtector(), temp.Paths);
        var zeta = new ModelConfig { Name = "Z", ModelName = "zeta" };
        await repository.InsertAsync(zeta);
        await repository.InsertAsync(new ModelConfig { Name = "A", ModelName = "Alpha" });
        Assert.Equal(new[] { "Alpha", "zeta" }, (await repository.GetAllAsync()).Select(m => m.ModelName));
        zeta.ModelName = "aardvark";
        await repository.UpdateAsync(zeta);
        Assert.Equal(zeta.Id, (await repository.GetAllAsync())[0].Id);
        await repository.DeleteAsync(zeta.Id);
        Assert.Equal("Alpha", Assert.Single(await repository.GetAllAsync()).ModelName);
    }

    [Theory]
    [InlineData("deepseek-chat", "BrandDeepSeek")]
    [InlineData("xiaomi/mimo-v2-flash", "BrandMimo")]
    [InlineData("MiMo-V2-Pro", "BrandMimo")]
    [InlineData("小米MiMo", "BrandMimo")]
    [InlineData("z-ai/glm-5", "BrandGlm")]
    [InlineData("custom-unknown", "BrandGpt")]
    public void BrandMapping(string model, string expected) => Assert.Equal(expected, BrandIconConverter.ResolveKey(model));
}
