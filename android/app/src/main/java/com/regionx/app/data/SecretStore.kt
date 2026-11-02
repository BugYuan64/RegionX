package com.regionx.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-GCM 密钥加密 API Key 与自定义请求头。
 * 密文格式：`aes-gcm:` + base64(iv || ciphertext)。
 * 与 Windows 端 `dpapi:` 前缀对应；解析到无法识别的前缀时返回空串，而不是崩溃。
 */
class SecretStore(private val context: Context) {

    private val alias = "regionx_config_key"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun protect(plain: String?): String {
        if (plain.isNullOrEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        } catch (ex: Exception) {
            // 密钥不可用时不写入明文：返回空串让调用方提示用户重新填写
            ""
        }
    }

    fun unprotect(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        if (!value.startsWith(PREFIX)) return ""
        return try {
            val combined = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (ex: Exception) {
            ""
        }
    }

    private companion object {
        const val PREFIX = "aes-gcm:"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
