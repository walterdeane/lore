package com.walterdeane.lore.chat

import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** `provider` picks the LLM backend (local "ollama" vs hosted "anthropic"); `rerankEnabled` toggles [com.walterdeane.lore.search.RerankerService]. */
@ConfigurationProperties(prefix = "lore.chat")
data class ChatProperties(val provider: String = "ollama", val rerankEnabled: Boolean = true)

/**
 * Spring AI's ChatClientAutoConfiguration wires a single ChatModel bean; with both
 * Ollama and Anthropic starters present there are two candidates, so we pick explicitly
 * here instead of relying on that autoconfiguration (excluded in LoreApplication).
 */
@Configuration
class ChatModelConfig(private val chatProperties: ChatProperties) {

    /** Builds the shared [ChatClient.Builder] injected into [ChatViewController] and [com.walterdeane.lore.search.RerankerService]. */
    @Bean
    fun chatClientBuilder(ollamaChatModel: OllamaChatModel, anthropicChatModel: AnthropicChatModel): ChatClient.Builder =
        ChatClient.builder(
            when (chatProperties.provider) {
                "anthropic" -> anthropicChatModel
                else -> ollamaChatModel
            }
        )
}
