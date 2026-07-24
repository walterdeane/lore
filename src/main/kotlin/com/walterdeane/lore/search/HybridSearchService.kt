package com.walterdeane.lore.search

import com.walterdeane.lore.model.ChunkingStrategy
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
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
    private val embeddingModel: EmbeddingModel,
) {

    private val log = LoggerFactory.getLogger(HybridSearchService::class.java)

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
     * Post-03 Phase 2.1: what used to be three round trips (lexical SQL, vector SQL, Kotlin [fuse],
     * hydrate SQL) is now one statement — two CTEs ranking each leg's top [SearchProperties.candidatePoolSize]
     * candidates by `ROW_NUMBER()`, a `FULL OUTER JOIN` computing the same RRF formula [fuse] uses
     * (`1/(k+pos)` per leg, summed), and the chunk/document hydration columns joined in directly.
     * Query embedding still happens in Kotlin ([EmbeddingModel.embed]) and is passed in as a
     * parameter, same as [VectorSearchService] does standalone — that part isn't SQL and doesn't
     * need to be. [LexicalSearchService]/[VectorSearchService]/[fuse]/[hydrate] are unchanged and
     * still used by [explain], which deliberately keeps the legs visible for debugging/capture
     * rather than adopting this single-statement path.
     *
     * Stage timing note: `lexical_sql`/`vector_sql`/`fuse`/`hydrate` (the four steps this replaces)
     * collapse into one `hybrid_sql` stage; `vector_embed` is unchanged since embedding is still a
     * separate Kotlin-side call. Comparing a before capture (four stages) against an after capture
     * (`vector_embed` + `hybrid_sql`) means summing the four old stages against the one new one.
     *
     * Tie-break note: [fuse]'s scores can tie (verified against real data — same score, different
     * chunks) whenever two candidates each rank #1 in exactly one leg and are absent from the other.
     * The old code broke ties by `LinkedHashMap` insertion order (lexical scored before vector) —
     * an accident of implementation, not a relevance signal (see post-03 capture notes on this
     * exact behavior). SQL has no equivalent accident to inherit, so ties are broken explicitly by
     * `c.id` instead: arbitrary, but deterministic and documented, which the old behavior wasn't.
     */
    fun search(query: String, domainId: UUID, tags: List<String>? = null, size: Int = 20, page: Int = 0): SearchPage {
        val poolSize = searchProperties.candidatePoolSize
        val k = searchProperties.rrfK

        val (vectorParam, _) = timedStage(log, "vector_embed", query) { toVectorParam(query) }
        val tagClause = tagFilterClause(tags)
        val useOrFallback = shouldUseLexicalOrFallback(jdbcTemplate, query, domainId, tags, searchProperties.lexicalFallbackEnabled)
        val tsqueryFromItem = lexicalTsqueryFromItem(useOrFallback)
        val tsqueryExpr = lexicalTsqueryExpr(useOrFallback)

        val sql = """
            WITH ${candidateCtesSql(tagClause, tsqueryFromItem)}
            SELECT c.id, c.document_id, c.domain_id, c.chunk_index, c.chunk_strategy, c.tag_paths,
                   ts_headline('english', c.content, $tsqueryExpr, 'MaxWords=35, MinWords=15') AS headline,
                   fused.rrf_score, fused.search_type,
                   d.title AS document_title, d.author AS document_author,
                   COUNT(*) OVER() AS total_count
            FROM fused
            JOIN chunk c ON c.id = fused.id
            JOIN document d ON d.id = c.document_id
            ORDER BY fused.rrf_score DESC, c.id
            LIMIT ? OFFSET ?
        """.trimIndent()

        val args = candidateCteArgs(query, vectorParam, domainId, tags, poolSize, k) + listOf(query, size, page * size)

        var total = 0L
        val (results, _) = timedStage(log, "hybrid_sql", query) {
            jdbcTemplate.query(sql, { rs, rowNum ->
                if (rowNum == 0) total = rs.getLong("total_count")
                Result(
                    chunkId = rs.getObject("id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    domainId = rs.getObject("domain_id", UUID::class.java),
                    chunkIndex = rs.getInt("chunk_index"),
                    chunkStrategy = ChunkingStrategy.valueOf(rs.getString("chunk_strategy")),
                    tagPaths = (rs.getArray("tag_paths").array as Array<*>).map { it.toString() },
                    headline = rs.getString("headline"),
                    rank = rs.getDouble("rrf_score"),
                    documentTitle = rs.getString("document_title"),
                    documentAuthor = rs.getString("document_author"),
                    searchType = SearchType.valueOf(rs.getString("search_type")),
                )
            }, *args.toTypedArray())
        }

        // COUNT(*) OVER() rides on the returned rows, so a page past the end (LIMIT/OFFSET yields
        // zero rows) leaves no row to read a total from — matches old behavior (fuse()'s full size
        // was known before slicing to the page) via a direct count of the same CTEs, only when needed.
        if (results.isEmpty()) {
            total = countFused(query, vectorParam, domainId, tags, poolSize, tsqueryFromItem)
        }

        return SearchPage(results, total, page, size)
    }

    private fun toVectorParam(query: String): PGobject = PGobject().apply {
        type = "vector"
        value = embeddingModel.embed(query).joinToString(",", "[", "]")
    }

    /** Shared `lex`/`vec` candidate-ranking CTEs for [search] and [countFused] — see [search]'s KDoc for the mechanism. */
    private fun candidateCtesSql(tagClause: String, tsqueryFromItem: String): String = """
        lex_ranked AS (
            SELECT c.id, ROW_NUMBER() OVER (ORDER BY ts_rank_cd(c.search_vector, q.q) DESC) AS pos
            FROM chunk c, $tsqueryFromItem q
            WHERE c.search_vector @@ q.q
              AND c.domain_id = ?
              $tagClause
        ),
        lex AS (
            SELECT id, pos FROM lex_ranked WHERE pos <= ?
        ),
        vec_ranked AS (
            SELECT c.id, ROW_NUMBER() OVER (ORDER BY c.embedding <=> ? ASC) AS pos
            FROM chunk c
            WHERE c.domain_id = ?
              AND c.embedding IS NOT NULL
              $tagClause
        ),
        vec AS (
            SELECT id, pos FROM vec_ranked WHERE pos <= ?
        ),
        fused AS (
            SELECT COALESCE(lex.id, vec.id) AS id,
                   COALESCE(1.0 / (? + lex.pos), 0) + COALESCE(1.0 / (? + vec.pos), 0) AS rrf_score,
                   CASE
                       WHEN lex.id IS NOT NULL AND vec.id IS NOT NULL THEN 'BOTH'
                       WHEN vec.id IS NOT NULL THEN 'EMBEDDING'
                       ELSE 'LEXICAL'
                   END AS search_type
            FROM lex
            FULL OUTER JOIN vec ON lex.id = vec.id
        )
    """.trimIndent()

    /** Positional args matching [candidateCtesSql]'s `?` placeholders, in order. */
    private fun candidateCteArgs(query: String, vectorParam: PGobject, domainId: UUID, tags: List<String>?, poolSize: Int, k: Int): List<Any?> =
        buildList {
            add(query); add(domainId)
            if (!tags.isNullOrEmpty()) addAll(tags)
            add(poolSize)
            add(vectorParam); add(domainId)
            if (!tags.isNullOrEmpty()) addAll(tags)
            add(poolSize)
            add(k); add(k)
        }

    /** Fallback total when [search]'s main query returns zero rows (an out-of-range page) — see the comment at its call site. */
    private fun countFused(query: String, vectorParam: PGobject, domainId: UUID, tags: List<String>?, poolSize: Int, tsqueryFromItem: String): Long {
        val tagClause = tagFilterClause(tags)
        val sql = "WITH ${candidateCtesSql(tagClause, tsqueryFromItem)} SELECT COUNT(*) FROM fused"
        val args = candidateCteArgs(query, vectorParam, domainId, tags, poolSize, searchProperties.rrfK)
        return jdbcTemplate.queryForObject(sql, Long::class.java, *args.toTypedArray()) ?: 0L
    }

    /**
     * The post-03 capture harness (`post-03-data-capture-spec.md`): returns per-leg candidate lists
     * with positions/scores, the fused list, per-stage timings, and the resolved tsquery text for
     * [query] — everything [search] computes and discards, made visible instead of hidden behind
     * one fused [SearchPage]. Deliberately a separate method rather than a flag on [search] so the
     * normal path's return type/behavior can't drift by accident.
     *
     * [ExplainResult.tsqueryText] reflects Phase 2.2: whatever [search] would actually run —
     * `websearch_to_tsquery`, or the OR-fallback form when the AND form finds nothing and
     * [SearchProperties.lexicalFallbackEnabled] is on — not the plain `plainto_tsquery` output. A
     * capture wanting to see *why* the fallback triggered can diff this against
     * `websearch_to_tsquery('english', ?)::text` directly.
     */
    fun explain(query: String, domainId: UUID, tags: List<String>? = null): ExplainResult {
        val poolSize = searchProperties.candidatePoolSize
        val timings = LinkedHashMap<String, Long>()

        // searchTimed already logs "lexical_sql"/"vector_embed"/"vector_sql" internally — call it
        // directly rather than wrapping again here, so the explain response and the log trail agree
        // on one number per stage instead of two similar-but-not-identical ones.
        val lexicalTimed = lexicalSearchService.searchTimed(query, domainId, tags, size = poolSize, page = 0)
        timings["lexical_sql"] = lexicalTimed.sqlMs

        val vectorTimed = vectorSearchService.searchTimed(query, domainId, tags, size = poolSize)
        timings["vector_embed"] = vectorTimed.embedMs
        timings["vector_sql"] = vectorTimed.sqlMs

        val lexicalIds = lexicalTimed.page.results.map { it.chunkId }
        val vectorIds = vectorTimed.results.map { it.chunkId }
        val lexicalIdSet = lexicalIds.toSet()
        val vectorIdSet = vectorIds.toSet()

        val (fused, fuseMs) = timedStage(log, "fuse", query) { fuse(lexicalIds, vectorIds, searchProperties.rrfK) }
        timings["fuse"] = fuseMs

        val (_, hydrateMs) = timedStage(log, "hydrate", query) {
            hydrate(fused.map { it.first }, query, lexicalIdSet, vectorIdSet)
        }
        timings["hydrate"] = hydrateMs

        val useOrFallback = shouldUseLexicalOrFallback(jdbcTemplate, query, domainId, tags, searchProperties.lexicalFallbackEnabled)
        val tsqueryText = jdbcTemplate.queryForObject(
            "SELECT ${lexicalTsqueryExpr(useOrFallback)}::text", String::class.java, query,
        ) ?: ""

        return ExplainResult(
            lexical = lexicalTimed.page.results.mapIndexed { i, r -> LegHit(r.chunkId, i, r.rank) },
            vector = vectorTimed.results.mapIndexed { i, r -> LegHit(r.chunkId, i, r.similarity) },
            fused = fused.mapIndexed { i, (id, score) ->
                val searchType = when {
                    id in lexicalIdSet && id in vectorIdSet -> SearchType.BOTH
                    id in vectorIdSet -> SearchType.EMBEDDING
                    else -> SearchType.LEXICAL
                }
                FusedHit(id, score, i, searchType)
            },
            timingsMs = timings,
            tsqueryText = tsqueryText,
        )
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

/** One candidate from a single retrieval leg, before fusion — [score] is `ts_rank_cd` for lexical, cosine similarity for vector. */
data class LegHit(val chunkId: UUID, val position: Int, val score: Double)

/** One entry in the post-fusion ranking, with the RRF score that produced its position. */
data class FusedHit(val chunkId: UUID, val rrfScore: Double, val position: Int, val searchType: SearchType)

/** Full output of [HybridSearchService.explain] — see its KDoc. */
data class ExplainResult(
    val lexical: List<LegHit>,
    val vector: List<LegHit>,
    val fused: List<FusedHit>,
    val timingsMs: Map<String, Long>,
    val tsqueryText: String,
)
