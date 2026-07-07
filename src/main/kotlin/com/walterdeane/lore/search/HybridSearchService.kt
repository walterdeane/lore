package com.walterdeane.lore.search

import com.walterdeane.lore.model.ChunkingStrategy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Combines two independently-ranked chunk-id lists via Reciprocal Rank Fusion:
 * score(chunk) = sum of 1/(k + rank) across whichever lists it appears in.
 * Pure function so it's unit-testable without a Spring context.
 */
fun fuse(bm25: List<UUID>, vector: List<UUID>, k: Int = 60): List<Pair<UUID, Double>> {
    val scores = LinkedHashMap<UUID, Double>()
    bm25.forEachIndexed { i, id -> scores.merge(id, 1.0 / (k + i + 1), Double::plus) }
    vector.forEachIndexed { i, id -> scores.merge(id, 1.0 / (k + i + 1), Double::plus) }
    return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

@Service
class HybridSearchService(
    private val bm25SearchService: BM25SearchService,
    private val vectorSearchService: VectorSearchService,
    private val jdbcTemplate: JdbcTemplate,
    private val searchProperties: SearchProperties,
) {

    data class Result(
        val chunkId: UUID,
        val documentId: UUID,
        val domainId: UUID,
        val chunkIndex: Int,
        val chunkStrategy: ChunkingStrategy,
        val tagPaths: List<String>,
        val headline: String,
        // Fused RRF score, not a BM25 rank — named `rank` so search/index.html needs no changes.
        val rank: Double,
        val documentTitle: String,
        val documentAuthor: String?,
    )

    /**
     * total/pagination are bounded by the fused candidate pool (<= 2 * candidatePoolSize, deduped),
     * not an exhaustive count of the corpus — this is RAG-style top-K retrieval, not full search-engine
     * pagination, so results won't grow past the pool no matter how deep you page.
     */
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
        val poolSize = searchProperties.candidatePoolSize
        val bm25Ids = bm25SearchService.search(query, domainId, tags, size = poolSize, page = 0).results.map { it.chunkId }
        val vectorIds = vectorSearchService.search(query, domainId, tags, size = poolSize).map { it.chunkId }

        val fused = fuse(bm25Ids, vectorIds, searchProperties.rrfK)
        val total = fused.size.toLong()
        val pageIds = fused.drop(page * size).take(size)
        if (pageIds.isEmpty()) return SearchPage(emptyList(), total, page, size)

        val hydrated = hydrate(pageIds.map { it.first }, query)
        val results = pageIds.mapNotNull { (id, score) -> hydrated[id]?.copy(rank = score) }
        return SearchPage(results, total, page, size)
    }

    private fun hydrate(ids: List<UUID>, query: String): Map<UUID, Result> {
        val sql = """
            SELECT c.id, c.document_id, c.domain_id, c.chunk_index, c.chunk_strategy, c.tag_paths,
                   ts_headline('english', c.content, plainto_tsquery('english', ?), 'MaxWords=35, MinWords=15') AS headline,
                   d.title AS document_title, d.author AS document_author
            FROM chunk c
            JOIN document d ON d.id = c.document_id
            WHERE c.id = ANY(?)
        """.trimIndent()

        val rows = jdbcTemplate.query(
            sql,
            PreparedStatementSetter { ps ->
                ps.setString(1, query)
                ps.setArray(2, ps.connection.createArrayOf("uuid", ids.toTypedArray()))
            },
            RowMapper { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                id to Result(
                    chunkId = id,
                    documentId = rs.getObject("document_id", UUID::class.java),
                    domainId = rs.getObject("domain_id", UUID::class.java),
                    chunkIndex = rs.getInt("chunk_index"),
                    chunkStrategy = ChunkingStrategy.valueOf(rs.getString("chunk_strategy")),
                    tagPaths = (rs.getArray("tag_paths").array as Array<*>).map { it.toString() },
                    headline = rs.getString("headline"),
                    rank = 0.0,
                    documentTitle = rs.getString("document_title"),
                    documentAuthor = rs.getString("document_author"),
                )
            },
        )
        return rows.toMap()
    }
}
