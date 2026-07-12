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
fun fuse(lexical: List<UUID>, vector: List<UUID>, k: Int = 60): List<Pair<UUID, Double>> {
    val scores = LinkedHashMap<UUID, Double>()
    lexical.forEachIndexed { i, id -> scores.merge(id, 1.0 / (k + i + 1), Double::plus) }
    vector.forEachIndexed { i, id -> scores.merge(id, 1.0 / (k + i + 1), Double::plus) }
    return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/**
 * Orchestrates hybrid retrieval: run lexical (keyword) and vector (semantic) search independently,
 * then fuse their rankings with Reciprocal Rank Fusion so the result set benefits from both —
 * exact term matches the lexical leg is good at, and paraphrase/semantic matches vector search is
 * good at. This is the "R" (retrieval) half of RAG; [com.walterdeane.lore.chat.ChatViewController]
 * is the consumer that feeds these results into an LLM as grounding context.
 */
@Service
class HybridSearchService(
    private val lexicalSearchService: LexicalSearchService,
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
        // Fused RRF score, not a lexical-leg rank — named `rank` so search/index.html needs no changes.
        val rank: Double,
        val documentTitle: String,
        val documentAuthor: String?,
        // Which retrieval leg(s) surfaced this chunk for this query — not a stored chunk property
        // (every chunk always has both a tsvector and an embedding), so it's derived at query time
        // from membership in the two pre-fusion candidate lists. See [hydrate].
        val searchType: SearchType,
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

    /**
     * Retrieves candidatePoolSize chunk ids from each of the lexical and vector search legs, fuses
     * them into one ranked list via [fuse], then hydrates only the ids needed for the requested page
     * with a single follow-up SQL query (avoids re-running both searches per page).
     */
    fun search(query: String, domainId: UUID, tags: List<String>? = null, size: Int = 20, page: Int = 0): SearchPage {
        val poolSize = searchProperties.candidatePoolSize
        val lexicalIds = lexicalSearchService.search(query, domainId, tags, size = poolSize, page = 0).results.map { it.chunkId }
        val vectorIds = vectorSearchService.search(query, domainId, tags, size = poolSize).map { it.chunkId }

        val fused = fuse(lexicalIds, vectorIds, searchProperties.rrfK)
        val total = fused.size.toLong()
        val pageIds = fused.drop(page * size).take(size)
        if (pageIds.isEmpty()) return SearchPage(emptyList(), total, page, size)

        val hydrated = hydrate(pageIds.map { it.first }, query, lexicalIds.toSet(), vectorIds.toSet())
        val results = pageIds.mapNotNull { (id, score) -> hydrated[id]?.copy(rank = score) }
        return SearchPage(results, total, page, size)
    }

    /**
     * Loads chunk/document metadata plus a query-highlighted headline for the given fused-result ids.
     * [lexicalIds]/[vectorIds] are the pre-fusion candidate sets, used only to classify each result's
     * [Result.searchType] — a chunk in both is where RRF's fusion actually pays off.
     */
    private fun hydrate(ids: List<UUID>, query: String, lexicalIds: Set<UUID>, vectorIds: Set<UUID>): Map<UUID, Result> {
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
                    searchType = when {
                        id in lexicalIds && id in vectorIds -> SearchType.BOTH
                        id in vectorIds -> SearchType.EMBEDDING
                        else -> SearchType.LEXICAL
                    },
                )
            },
        )
        return rows.toMap()
    }
}

/** Which retrieval leg(s) surfaced a result: keyword search, semantic search, or both (the case RRF rewards). */
enum class SearchType {
    LEXICAL,
    EMBEDDING,
    BOTH,
}
