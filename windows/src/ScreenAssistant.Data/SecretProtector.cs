using System.Security.Cryptography;
using System.Text;

namespace ScreenAssistant.Data;

/// <summary>API Key 加密保存。Windows 使用 DPAPI（当前用户范围），不落盘明文。</summary>
public interface ISecretProtector
{
    string Protect(string plainText);
    string Unprotect(string protectedText);
}

public sealed class DpapiSecretProtector : ISecretProtector
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("ScreenAssistant.ApiKey.v1");

    public string Protect(string plainText)
    {
        if (string.IsNullOrEmpty(plainText)) return "";
        var data = Encoding.UTF8.GetBytes(plainText);
        try
        {
            var protectedData = ProtectedData.Protect(data, Entropy, DataProtectionScope.CurrentUser);
            return "dpapi:" + Convert.ToBase64String(protectedData);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(data);
        }
    }

    public string Unprotect(string protectedText)
    {
        if (string.IsNullOrEmpty(protectedText)) return "";
        if (!protectedText.StartsWith("dpapi:", StringComparison.Ordinal))
        {
            // 兼容早期明文数据（仅读取，不做迁移写回）
            return protectedText;
        }

        var raw = Convert.FromBase64String(protectedText["dpapi:".Length..]);
        var data = ProtectedData.Unprotect(raw, Entropy, DataProtectionScope.CurrentUser);
        try
        {
            return Encoding.UTF8.GetString(data);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(data);
        }
    }
}
