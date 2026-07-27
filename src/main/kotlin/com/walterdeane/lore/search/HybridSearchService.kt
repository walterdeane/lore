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
 * Merges two separately-ranked lists of chunk IDs into one ranking, using a technique called
 * Reciprocal Rank Fusion (RRF). Each chunk gets a score of `1 / (k + its position in the list)`
 * for every list it appears in (position 0 scores highest); a chunk that appears in both lists has
 * both scores added together. The returned list is sorted by that combined score, highest first.
 * [k] is a smoothing constant — see [SearchProperties.rrfK].
 *
 * This is a plain function with no database or Spring dependency, so it can be unit-tested on its
 * own, without needing a running application.
 */
fun fuse(lexical: List<UUID>, vector: List<UUID>, k: Int = 60): List<Pair<UUID, Double>> {
    val scores = LinkedHashMap<UUID, Double>()
    lexical.forEachIndexed { i, id -> scores.merge(id, 1.0 / (k + i + 1), Double::plus) }
    vector.forEachIndexed { i, id -> scores.merge(id, 1.0 / (k + i + 1), Double::plus) }
    return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/**
 * Runs a search two different ways and combines the results, so the outcome benefits from both:
 *
 * - Keyword search ([LexicalSearchService]) is good at exact matches — names, acronyms, specific
 *   phrases.
 * - Meaning-based/"semantic" search ([VectorSearchService]) is good at matching what a query
 *   *means*, even when the wording is completely different.
 *
 * The two ranked lists are merged into one combined ranking using Reciprocal Rank Fusion — see
 * [fuse]. That combined ranking is what other features use as the set of passages to show — for
 * example, [com.walterdeane.lore.chat.ChatViewController] (the chat feature) feeds these results to
 * an LLM as context so it can answer questions grounded in the user's own documents.
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
        // The combined score computed by fusing the keyword and vector rankings together (see
        // [fuse]) — not a raw position from either individual method. Still called `rank` (rather
        // than `score`) so the search results page doesn't need any changes.
        val rank: Double,
        val documentTitle: String,
        val documentAuthor: String?,
        // Which search method actually found this chunk for this query: keyword, semantic, or
        // both. This isn't about what's stored — every chunk always has both a full-text search
        // entry and an embedding — it's computed at query time from which candidate list(s) this
        // chunk showed up in before fusion. See [hydrate].
        val searchType: SearchType,
    )

    /**
     * A page of search results, plus pagination info. Note that [total] isn't a count of every
     * matching chunk across the whole document collection — it's the size of the combined
     * candidate pool that keyword and semantic search produced (at most
     * `2 * `[SearchProperties.candidatePoolSize], after removing duplicates). This search is meant
     * to find the best handful of passages for an LLM to use as context, not to behave like a
     * general-purpose search engine, so paging deep into the results won't ever surface more than
     * that pool contains.
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
     * Runs a hybrid search: looks up [query] both by keyword and by meaning, combines the two
     * rankings with Reciprocal Rank Fusion, and returns one page of the combined, de-duplicated
     * results — each with its chunk text, its document, and which method(s) found it.
     *
     * This all happens in a single SQL query. In plain terms, that query:
     * 1. Ranks the top [SearchProperties.candidatePoolSize] chunks by keyword match.
     * 2. Separately ranks the top [SearchProperties.candidatePoolSize] chunks by how close their
     *    stored vector is to the query's vector.
     * 3. Combines those two ranked lists (a chunk found by only one method still appears once),
     *    computing the same Reciprocal Rank Fusion score that [fuse] computes — `1/(k + position)`
     *    from each list, added together for any chunk that appears in both.
     * 4. Joins in the chunk text and document details needed to display each result, sorts by the
     *    combined score, and returns one page of it.
     *
     * Turning the query into a vector still has to happen in Kotlin first (via
     * [EmbeddingModel.embed], the same call [VectorSearchService] makes on its own) — that step
     * isn't something SQL can do — and the resulting vector is passed into the query as a
     * parameter.
     *
     * [LexicalSearchService], [VectorSearchService], and [fuse] are separate, reusable pieces that
     * this method doesn't call directly, since it does the equivalent work in one query instead.
     * [explain] uses those separate pieces rather than this method, specifically so each stage's
     * inputs and outputs stay visible for debugging.
     *
     * One detail worth knowing: two chunks can end up with exactly the same combined score — for
     * example, two chunks that each rank #1 in one method's list but don't appear in the other's
     * list at all. When that happens, this method breaks the tie by chunk ID, which is an arbitrary
     * choice but gives a consistent order from one run to the next.
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

        // The total result count above rides along with each returned row (a SQL trick:
        // COUNT(*) OVER()). That breaks down if the requested page is past the end of the
        // results — then there are no rows at all to read a total from — so in that case, run a
        // separate query just to get the count.
        if (results.isEmpty()) {
            total = countFused(query, vectorParam, domainId, tags, poolSize, tsqueryFromItem)
        }

        return SearchPage(results, total, page, size)
    }

    private fun toVectorParam(query: String): PGobject = PGobject().apply {
        type = "vector"
        value = embeddingModel.embed(query).joinToString(",", "[", "]")
    }

    /**
     * The reusable SQL building blocks — Postgres calls these "CTEs" (`WITH ... AS (...)`), named
     * temporary result sets you can query like tables within one larger query — that rank and
     * combine the keyword and vector candidates. Shared by [search] and [countFused] so the two
     * stay in sync. See [search]'s comment for what each step does.
     */
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

    /** The parameter values to fill in [candidateCtesSql]'s `?` placeholders, in the same order they appear in the SQL. */
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

    /** Counts how many chunks are in the combined candidate pool — used as a fallback when [search]'s main query returns no rows to read a total from (see the comment at its call site). */
    private fun countFused(query: String, vectorParam: PGobject, domainId: UUID, tags: List<String>?, poolSize: Int, tsqueryFromItem: String): Long {
        val tagClause = tagFilterClause(tags)
        val sql = "WITH ${candidateCtesSql(tagClause, tsqueryFromItem)} SELECT COUNT(*) FROM fused"
        val args = candidateCteArgs(query, vectorParam, domainId, tags, poolSize, searchProperties.rrfK)
        return jdbcTemplate.queryForObject(sql, Long::class.java, *args.toTypedArray()) ?: 0L
    }

    /**
     * A diagnostic version of [search] for developers: instead of returning only the final combined
     * results, this exposes every intermediate step — the keyword-only results, the vector-only
     * results, the combined ranking, how long each stage took, and the exact full-text search
     * pattern that was used. It's meant for understanding *why* a particular chunk did or didn't
     * show up — for example, whether it was found by keyword search, vector search, or both.
     *
     * This is a separate method rather than a flag on [search], so that [search]'s normal, simpler
     * return type can't accidentally change just to support this debugging path.
     *
     * [ExplainResult.tsqueryText] is the actual full-text search pattern used for the query,
     * including whether the "match any word" fallback kicked in (see
     * [SearchProperties.lexicalFallbackEnabled]).
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
     * Looks up the full details — document title/author, chunk text, a highlighted excerpt — for a
     * list of chunk IDs that have already gone through fusion. [lexicalIds] and [vectorIds] are the
     * pre-fusion candidate sets, passed in only so each result can be labeled with
     * [Result.searchType]: a chunk found by both methods is exactly the case Reciprocal Rank Fusion
     * is designed to reward.
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

/** Which search method found a given result: keyword search, semantic search, or both (finding a result both ways is the case Reciprocal Rank Fusion rewards most). */
enum class SearchType {
    LEXICAL,
    EMBEDDING,
    BOTH,
}

/** One candidate from a single search method, before combining with the other. [score] is the keyword-match score (Postgres's `ts_rank_cd`) for keyword search, or similarity for vector search. */
data class LegHit(val chunkId: UUID, val position: Int, val score: Double)

/** One entry in the combined ranking, with the score (from [fuse]) that determined its position. */
data class FusedHit(val chunkId: UUID, val rrfScore: Double, val position: Int, val searchType: SearchType)

/** Full output of [HybridSearchService.explain] — see its KDoc. */
data class ExplainResult(
    val lexical: List<LegHit>,
    val vector: List<LegHit>,
    val fused: List<FusedHit>,
    val timingsMs: Map<String, Long>,
    val tsqueryText: String,
)
