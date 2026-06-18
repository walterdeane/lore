package com.walterdeane.lore.model

import java.util.UUID
import java.time.Instant

data class Domain(
    val id: UUID,
    val name: String,
    val description: String,
)

data class Tag(
    val id: UUID,
    val domainId: UUID,
    val name: String,
    val description: String,
    val path: String, //materialized path for hierarchical tags, e.g. "Science/Physics/Quantum Mechanics"
)

data class Document(
    val id: UUID,
    val title: String,
    val author: String?,
    val sourceFilename: String,
    val sourcePath: String,
    val sourceType: SourceType,
    val tags: List<String>,
    val domainId: UUID,
    val ingestionStatus: IngestionStatus,
    val ingestionError: String?,
    val ingestedAt: Instant?
)

data class Chunk(
    val id: UUID,
    val documentId: UUID,
    val domainId: UUID,
    val tagPaths: List<String>,
    val content: String,
    val embedding: List<Float>, //(pgvector `vector` type)
    val chunkIndex: Int,
    val pageNumber: Int?,
    val tokenCount: Int?,
    val createdAt: Instant,
)

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

