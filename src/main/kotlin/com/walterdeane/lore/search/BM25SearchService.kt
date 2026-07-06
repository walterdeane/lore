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

    fun search(query: String, domainId: UUID, tags: List<String>? = null, size: Int = 20, page: Int = 0): SearchPage {
        // For each selected tag, match chunks whose tag_paths contain that tag or any descendant.
        // tp <@ ANY(...) is true when tp is a descendant of (or equal to) any element in the array,
        // so cookbook.cuisine matches cookbook.cuisine.american but not cookbook or cookbook.american.
        // Multiple selected tags are OR: a chunk matches if any tag_path falls under any selected tag.
        val tagClause = if (!tags.isNullOrEmpty())
            "AND EXISTS (SELECT 1 FROM unnest(c.tag_paths) AS tp WHERE tp <@ ANY(ARRAY[${tags.joinToString(",") { "?" }}]::ltree[]))"
        else ""

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
