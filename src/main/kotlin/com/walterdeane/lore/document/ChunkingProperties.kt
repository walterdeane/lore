package com.walterdeane.lore.document

import com.walterdeane.lore.model.ChunkingStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `defaultStrategy` is the app-wide fallback used when neither the document nor its domain
 * specifies one. `tokenOverlapChars` controls how much trailing context (see [TokenOverlapChunker])
 * is carried into the next chunk for the TOKEN strategy; 0 disables overlap.
 */
@ConfigurationProperties(prefix = "lore.chunking")
data class ChunkingProperties(
    val defaultStrategy: ChunkingStrategy = ChunkingStrategy.TOKEN,
    val tokenOverlapChars: Int = 200,
)
