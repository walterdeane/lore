package com.walterdeane.lore.model

import java.util.UUID


data class QueryRequest(
    val question: String,
    val collectionIds: List<UUID>,
    val tagPaths: List<String>,
    val limit: Int,
   )

data class QueryResponse(
    val answer: String,
    val sourceDocuments: List<SourceChunk>,

)

data class SourceChunk(
    val chunkId: UUID,
    val documentTitle: String,
    val collectionName: String,
    val pageNumber: Int?,
    val tagPaths: List<String>,
    val content: String,
    val similarityScore: Float,
    val documentId: UUID,
)
