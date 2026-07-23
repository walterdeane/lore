package com.walterdeane.lore.document

import com.walterdeane.lore.model.ChunkingStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `defaultStrategy` is the app-wide fallback used when neither the document nor its domain
 * specifies one. `tokenOverlapChars` controls how much trailing context (see [TokenOverlapChunker])
 * is carried into the next chunk for the TOKEN strategy; 0 disables overlap. `tokenChunkSize` is the
 * target size (in tokens) for [org.springframework.ai.transformer.splitter.TokenTextSplitter] —
 * used directly for TOKEN chunks and for SEMANTIC/STRUCTURAL's oversized-chunk fallback — chosen so
 * that the 5 chunks `ChatViewController` retrieves per RAG turn fit comfortably inside a local
 * model's context window alongside the system prompt and its answer; 600 was picked against
 * llama3.1:8b's actual 4096-token Ollama context (not its architectural max), leaving headroom for
 * 5 chunks plus prompt/response. `maxChunkChars` (`BoundaryConfig`/
 * `SemanticConfig`) stays a looser, chars-based outlier ceiling above this — it exists to catch rare
 * pathological spans (a table with no topic-shift signal, a chapter-only outline), not to define
 * everyday chunk size.
 */
@ConfigurationProperties(prefix = "lore.chunking")
data class ChunkingProperties(
    val defaultStrategy: ChunkingStrategy = ChunkingStrategy.TOKEN,
    val tokenOverlapChars: Int = 200,
    val tokenChunkSize: Int = 600,
)
