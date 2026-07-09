package com.walterdeane.lore.model

import java.util.UUID
import java.time.Instant

/** Top-level scope for retrieval; documents, chunks, and tags each belong to exactly one. */
data class Domain(
    val id: UUID,
    val name: String,
    val description: String,
    val chunkStrategy: ChunkingStrategy? = null,
    val structuralVariant: StructuralVariant? = null,
)

/** A hierarchical label; [path] is a Postgres `ltree` materialized path (e.g. `cuisine.italian`) used to scope retrieval. */
data class Tag(
    val id: UUID,
    val domainId: UUID,
    val name: String,
    val description: String,
    val path: String,
)

/** An uploaded source file; [chunkStrategy]/[structuralVariant] override the domain's defaults for this file only. */
data class Document(
    val id: UUID,
    val title: String,
    val author: String? = null,
    val sourceFilename: String,
    val sourcePath: String,
    val sourceType: SourceType,
    val tags: List<String>,
    val domainId: UUID,
    val ingestionStatus: IngestionStatus,
    val ingestionError: String? = null,
    val ingestedAt: Instant? = null,
    val chunkStrategy: ChunkingStrategy? = null,
    val structuralVariant: StructuralVariant? = null,
)

/**
 * The unit of retrieval: a slice of a document's text plus its precomputed [embedding] vector.
 * Both [content] (for BM25/display) and [embedding] (for vector search) live on the same row so
 * hybrid search can hydrate either result type from one table.
 */
data class Chunk(
    val id: UUID,
    val documentId: UUID,
    val domainId: UUID,
    val tagPaths: List<String>,
    val content: String,
    val embedding: List<Float>,
    val chunkIndex: Int,
    val chunkStrategy: ChunkingStrategy = ChunkingStrategy.TOKEN,
    val pageNumber: Int? = null,
    val tokenCount: Int? = null,
    val createdAt: Instant,
)

/** TOKEN: fixed-size splitting ([org.springframework.ai.transformer.splitter.TokenTextSplitter]). STRUCTURAL: heading-aware (see [com.walterdeane.lore.document.StructuralTextSplitter]). SEMANTIC: not yet implemented, falls back to TOKEN. */
enum class ChunkingStrategy { TOKEN, SEMANTIC, STRUCTURAL }

/** Shapes the STRUCTURAL splitter's heuristics for a given document type (see [com.walterdeane.lore.document.BoundaryConfig]). */
enum class StructuralVariant { GENERIC, COOKBOOK, ACADEMIC }

enum class SourceType {
    PDF,
    EPUB,
}

enum class IngestionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

