package com.walterdeane.lore.search

import com.walterdeane.lore.model.ChunkingStrategy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Classic lexical/keyword search using Postgres full-text search (`tsvector`/`ts_rank_cd`).
 * This is the "sparse" retriever in the hybrid pipeline: strong on exact term/acronym matches,
 * weak on paraphrases and synonyms — [VectorSearchService] covers that gap, and
 * [HybridSearchService] merges the two.
 */
@Service
class BM25SearchService(private val jdbcTemplate: JdbcTemplate) {

    data class Result(
        val chunkId: UUID,
        val documentId: UUID,
        val domainId: UUID,
        val chunkIndex: Int,
        val chunkStrategy: ChunkingStrategy,
        val tagPaths: List<String>,
        val headline: String,
        val rank: Double,
        val documentTitle: String,
        val documentAuthor: String?,
    )

    data class SearchPage(
        val results: List<Result>,
        val total: Long,
        val page: Int,
        val size: Int,
    ) {
        val totalPages: Int get() = if (total == 0L) 1 else ((total + size - 1) / size).toInt()
        val hasNext: Boolean get() = (page + 1).toLong() * size < total
        val hasPrevious: Boolean get() = page > 0
    }

    /**
     * Ranks chunks in [domainId] by Postgres's `ts_rank_cd` against a `plainto_tsquery` built from
     * [query], optionally restricted to chunks under [tags] (see [tagFilterClause]). Also returns a
     * highlighted excerpt (`ts_headline`) so result lists can show why a chunk matched.
     */
    fun search(query: String, domainId: UUID, tags: List<String>? = null, size: Int = 20, page: Int = 0): SearchPage {
        val tagClause = tagFilterClause(tags)

        val sql = """
            SELECT c.id, c.document_id, c.domain_id, c.chunk_index, c.chunk_strategy, c.tag_paths,
                   ts_rank_cd(c.search_vector, q) AS rank,
                   ts_headline('english', c.content, q, 'MaxWords=35, MinWords=15') AS headline,
                   COUNT(*) OVER() AS total_count,
                   d.title AS document_title, d.author AS document_author
            FROM chunk c
            JOIN document d ON d.id = c.document_id,
            plainto_tsquery('english', ?) q
            WHERE c.search_vector @@ q
              AND c.domain_id = ?
              $tagClause
            ORDER BY rank DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        val args = buildList<Any?> {
            add(query)
            add(domainId)
            if (!tags.isNullOrEmpty()) addAll(tags)
            add(size)
            add(page * size)
        }.toTypedArray()

        var total = 0L
        val results = jdbcTemplate.query(sql, { rs, rowNum ->
            if (rowNum == 0) total = rs.getLong("total_count")
            Result(
                chunkId = rs.getObject("id", UUID::class.java),
                documentId = rs.getObject("document_id", UUID::class.java),
                domainId = rs.getObject("domain_id", UUID::class.java),
                chunkIndex = rs.getInt("chunk_index"),
                chunkStrategy = ChunkingStrategy.valueOf(rs.getString("chunk_strategy")),
                tagPaths = (rs.getArray("tag_paths").array as Array<*>).map { it.toString() },
                headline = rs.getString("headline"),
                rank = rs.getDouble("rank"),
                documentTitle = rs.getString("document_title"),
                documentAuthor = rs.getString("document_author"),
            )
        }, *args)

        return SearchPage(results, total, page, size)
    }
}
