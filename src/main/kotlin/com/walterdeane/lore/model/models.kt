package com.walterdeane.lore.model

import java.util.UUID
import java.time.Instant

data class Domain(
    val id: UUID,
    val name: String,
    val description: String,
    val chunkStrategy: ChunkingStrategy? = null,
)

data class Tag(
    val id: UUID,
    val domainId: UUID,
    val name: String,
    val description: String,
    val path: String,
)

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
)

data class Chunk(
    val id: UUID,
    val documentId: UUID,
    val domainId: UUID,
    val tagPaths: List<String>,
    val content: String,
    val embedding: List<Float>,
    val chunkIndex: Int,
    val chunkStrategy: ChunkingStrategy = ChunkingStrategy.TOKEN,
    val parentChunkId: UUID? = null,
    val chunkLevel: String? = null,
    val pageNumber: Int? = null,
    val tokenCount: Int? = null,
    val createdAt: Instant,
)

enum class ChunkingStrategy { TOKEN, SEMANTIC, STRUCTURAL }

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

