package com.regionx.app.core.llm

import com.regionx.app.core.model.ModelConfig

/** 按模型配置里的类型创建对应的适配器。 */
class DefaultChatModelClientFactory(
    private val provider: HttpClientProvider,
) : ChatModelClientFactory {
    override fun create(config: ModelConfig): ChatModelClient {
        // 每次都取当前客户端：用户改代理后新任务立即使用新设置
        val client = provider.client
        return when (config.provider) {
            com.regionx.app.core.model.LlmProvider.OpenAiCompatible -> OpenAiCompatibleClient(config, client)
            com.regionx.app.core.model.LlmProvider.Claude -> ClaudeClient(config, client)
            com.regionx.app.core.model.LlmProvider.Gemini -> GeminiClient(config, client)
        }
    }
}
