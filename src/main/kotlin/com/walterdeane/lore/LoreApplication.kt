package com.walterdeane.lore

import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

/**
 * ChatClientAutoConfiguration is excluded because both Ollama and Anthropic starters are on the
 * classpath, which would give Spring AI two ChatModel candidates and no way to pick; instead
 * [com.walterdeane.lore.chat.ChatModelConfig] builds the ChatClient explicitly.
 * [EnableAsync] backs [com.walterdeane.lore.document.DocumentIngestionService]'s async ingestion.
 */
@SpringBootApplication(exclude = [ChatClientAutoConfiguration::class])
@EnableAsync
@ConfigurationPropertiesScan
class LoreApplication

fun main(args: Array<String>) {
	runApplication<LoreApplication>(*args)
}
