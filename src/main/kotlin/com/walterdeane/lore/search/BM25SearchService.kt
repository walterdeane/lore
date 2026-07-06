package com.walterdeane.lore.search

import com.walterdeane.lore.model.ChunkingStrategy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

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
    )

    fun search(query: String, domainId: UUID, tags: List<String>? = null, limit: Int = 20): List<Result> {
        val tagClause = if (!tags.isNullOrEmpty())
            "AND c.tag_paths && ARRAY[${tags.joinToString(",") { "?" }}]::ltree[]"
        else ""

        val sql = """
            SELECT c.id, c.document_id, c.domain_id, c.chunk_index, c.chunk_strategy, c.tag_paths,
                   ts_rank_cd(c.search_vector, q) AS rank,
                   ts_headline('english', c.content, q, 'MaxWords=35, MinWords=15') AS headline
            FROM chunk c, plainto_tsquery('english', ?) q
            WHERE c.search_vector @@ q
              AND c.domain_id = ?
              $tagClause
            ORDER BY rank DESC
            LIMIT ?
        """.trimIndent()

        val args = buildList<Any?> {
            add(query)
            add(domainId)
            if (!tags.isNullOrEmpty()) addAll(tags)
            add(limit)
        }.toTypedArray()

        return jdbcTemplate.query(sql, { rs, _ ->
            Result(
                chunkId = rs.getObject("id", UUID::class.java),
                documentId = rs.getObject("document_id", UUID::class.java),
                domainId = rs.getObject("domain_id", UUID::class.java),
                chunkIndex = rs.getInt("chunk_index"),
                chunkStrategy = ChunkingStrategy.valueOf(rs.getString("chunk_strategy")),
                tagPaths = (rs.getArray("tag_paths").array as Array<*>).map { it.toString() },
                headline = rs.getString("headline"),
                rank = rs.getDouble("rank"),
            )
        }, *args)
    }
}
