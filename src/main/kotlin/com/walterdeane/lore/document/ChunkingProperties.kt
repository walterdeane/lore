package com.walterdeane.lore.document

import com.walterdeane.lore.model.ChunkingStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "lore.chunking")
data class ChunkingProperties(
    val defaultStrategy: ChunkingStrategy = ChunkingStrategy.TOKEN,
)
