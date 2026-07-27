package com.walterdeane.lore.search

import com.walterdeane.lore.model.ChunkingStrategy
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Meaning-based ("semantic") search over document chunks. When a chunk is first stored, it's
 * converted into a vector — a list of numbers that captures its meaning — using an AI embedding
 * model. This class converts the search query into a vector the same way (via the same
 * [EmbeddingModel]), then uses the pgvector database extension to find the chunks whose stored
 * vectors are closest to it. This finds matches based on meaning even when the wording is
 * completely different, but has no notion of exact keyword/acronym matches — that's what
 * [LexicalSearchService] contributes, and [HybridSearchService] combines the two.
 */
@Service
class VectorSearchService(
    private val jdbcTemplate: JdbcTemplate,
    private val embeddingModel: EmbeddingModel,
) {

    private val log = LoggerFactory.getLogger(VectorSearchService::class.java)

    /** Same as a plain result list, but also reports how long each step took: [embedMs] for converting the query into a vector, [sqlMs] for the database search. Used by [HybridSearchService.explain] to show per-stage timing. */
    data class TimedResult(val results: List<Result>, val embedMs: Long, val sqlMs: Long)

    data class Result(
        val chunkId: UUID,
        val documentId: UUID,
        val domainId: UUID,
        val chunkIndex: Int,
        val chunkStrategy: ChunkingStrategy,
        val tagPaths: List<String>,
        val headline: String,
        // How closely this chunk's meaning matches the query, from 0 (unrelated) to 1 (identical).
        // Computed as 1 minus the vectors' cosine distance, so — like the keyword search's rank —
        // a higher number always means a better match.
        val similarity: Double,
        val documentTitle: String,
        val documentAuthor: String?,
    )

    /**
     * Converts [query] into a vector, then finds the chunks in [domainId] (optionally restricted
     * to [tags]) whose stored vectors are the closest match, using Postgres's `<=>` operator to
     * compute the distance between vectors. Unlike keyword search, there's no natural way to
     * highlight "the matching words" here — a meaning-based match doesn't need to share any words
     * with the query at all — so the excerpt returned is just the start of the chunk's text (see
     * [plainExcerpt]).
     */
    fun search(query: String, domainId: UUID, tags: List<String>? = null, size: Int = 20): List<Result> =
        searchTimed(query, domainId, tags, size).results

    /** Same as [search], but also returns the embed/SQL split timing — see [TimedResult]. */
    fun searchTimed(query: String, domainId: UUID, tags: List<String>? = null, size: Int = 20): TimedResult {
        val (vectorParam, embedMs) = timedStage(log, "vector_embed", query) {
            PGobject().apply {
                type = "vector"
                value = embeddingModel.embed(query).joinToString(",", "[", "]")
            }
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

        val (results, sqlMs) = timedStage(log, "vector_sql", query) {
            jdbcTemplate.query(sql, { rs, _ ->
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

        return TimedResult(results, embedMs, sqlMs)
    }
}

/** Builds a short display excerpt from [content]: collapses repeated whitespace and cuts it off at [maxLen] characters. Doesn't highlight anything, unlike the keyword-search excerpt. */
internal fun plainExcerpt(content: String, maxLen: Int = 240): String {
    val stripped = content.trim().replace(Regex("\\s+"), " ")
    return if (stripped.length <= maxLen) stripped else stripped.take(maxLen).substringBeforeLast(' ') + "…"
}
