package com.walterdeane.lore.search

import com.walterdeane.lore.model.ChunkingStrategy
import org.postgresql.util.PGobject
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class VectorSearchService(
    private val jdbcTemplate: JdbcTemplate,
    private val embeddingModel: EmbeddingModel,
) {

    data class Result(
        val chunkId: UUID,
        val documentId: UUID,
        val domainId: UUID,
        val chunkIndex: Int,
        val chunkStrategy: ChunkingStrategy,
        val tagPaths: List<String>,
        val headline: String,
        // 1 - cosine distance; higher means more similar, matching BM25's "higher rank is better" direction.
        val similarity: Double,
        val documentTitle: String,
        val documentAuthor: String?,
    )

    fun search(query: String, domainId: UUID, tags: List<String>? = null, size: Int = 20): List<Result> {
        val vectorParam = PGobject().apply {
            type = "vector"
            value = embeddingModel.embed(query).joinToString(",", "[", "]")
        }
        val tagClause = tagFilterClause(tags)

        val sql = """
            SELECT c.id, c.document_id, c.domain_id, c.chunk_index, c.chunk_strategy, c.tag_paths, c.content,
                   1 - (c.embedding <=> ?) AS similarity,
                   d.title AS document_title, d.author AS document_author
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE c.domain_id = ?
              AND c.embedding IS NOT NULL
              $tagClause
            ORDER BY c.embedding <=> ?
            LIMIT ?
        """.trimIndent()

        val args = buildList<Any?> {
            add(vectorParam)
            add(domainId)
            if (!tags.isNullOrEmpty()) addAll(tags)
            add(vectorParam)
            add(size)
        }.toTypedArray()

        return jdbcTemplate.query(sql, { rs, _ ->
            Result(
                chunkId = rs.getObject("id", UUID::class.java),
                documentId = rs.getObject("document_id", UUID::class.java),
                domainId = rs.getObject("domain_id", UUID::class.java),
                chunkIndex = rs.getInt("chunk_index"),
                chunkStrategy = ChunkingStrategy.valueOf(rs.getString("chunk_strategy")),
                tagPaths = (rs.getArray("tag_paths").array as Array<*>).map { it.toString() },
                headline = plainExcerpt(rs.getString("content")),
                similarity = rs.getDouble("similarity"),
                documentTitle = rs.getString("document_title"),
                documentAuthor = rs.getString("document_author"),
            )
        }, *args)
    }
}

internal fun plainExcerpt(content: String, maxLen: Int = 240): String {
    val stripped = content.trim().replace(Regex("\\s+"), " ")
    return if (stripped.length <= maxLen) stripped else stripped.take(maxLen).substringBeforeLast(' ') + "…"
}
