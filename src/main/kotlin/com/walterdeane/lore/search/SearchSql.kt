package com.walterdeane.lore.search

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Post-03 2.2: `plainto_tsquery`/`websearch_to_tsquery` AND every extracted term together, so a
 * natural-language question that happens to include one uncommon incidental word (post 02's
 * "what is the difference between cooking with gas versus charcoal" — killed by "versus" alone,
 * despite every other term being common) returns zero rows. When [fallbackEnabled] and the normal
 * AND query would find nothing, callers should OR the same lexemes together instead
 * (`replace(websearch_to_tsquery(...)::text, ' & ', ' | ')::tsquery` — Postgres accepts a tsquery's
 * own text representation as input, so swapping the operator is enough to build the OR form from
 * the AND form without re-deriving lexemes separately).
 *
 * Only a cheap `EXISTS` probe (GIN-indexed, no ranking work) — not a full second ranking pass — so
 * the common case (the AND query finds something) pays a small fixed cost, not a doubled query.
 */
internal fun shouldUseLexicalOrFallback(
    jdbcTemplate: JdbcTemplate,
    query: String,
    domainId: UUID,
    tags: List<String>?,
    fallbackEnabled: Boolean,
): Boolean {
    if (!fallbackEnabled) return false
    val tagClause = tagFilterClause(tags)
    val sql = """
        SELECT EXISTS (
            SELECT 1 FROM chunk c
            WHERE c.domain_id = ?
              AND c.search_vector @@ websearch_to_tsquery('english', ?)
              $tagClause
        )
    """.trimIndent()
    val args = buildList<Any?> {
        add(domainId)
        add(query)
        if (!tags.isNullOrEmpty()) addAll(tags)
    }.toTypedArray()
    val hasAnyMatch = jdbcTemplate.queryForObject(sql, Boolean::class.java, *args) ?: true
    return !hasAnyMatch
}

/** The tsquery SQL value expression given [useOrFallback] — always consumes exactly one `?` (the query text). Safe anywhere a plain value expression is expected (e.g. inside `ts_headline(...)`), but *not* as a bare FROM-clause item — see [lexicalTsqueryFromItem]. */
internal fun lexicalTsqueryExpr(useOrFallback: Boolean): String =
    if (useOrFallback) "replace(websearch_to_tsquery('english', ?)::text, ' & ', ' | ')::tsquery"
    else "websearch_to_tsquery('english', ?)"

/**
 * [lexicalTsqueryExpr] wrapped for use as a `FROM`-clause cross-join item (`FROM chunk c, <this> q`,
 * matching the existing `plainto_tsquery(...) q` pattern). The plain AND-case expression is already
 * a bare function call, valid directly in a FROM list — but the OR-fallback expression ends in a
 * `::tsquery` cast, and Postgres's FROM-list grammar doesn't accept a cast trailing a function call
 * there (`ERROR: syntax error at or near "::"`, confirmed against real Postgres). Wrapping *both*
 * forms identically as `(SELECT <expr> AS q)` — not just the one that needs it — means every caller
 * references the column the same way (`q.q`) regardless of which branch is active, rather than two
 * different reference styles depending on a runtime condition.
 */
internal fun lexicalTsqueryFromItem(useOrFallback: Boolean): String =
    "(SELECT ${lexicalTsqueryExpr(useOrFallback)} AS q)"

/**
 * For each selected tag, match chunks whose tag_paths contain that tag or any descendant.
 * tp <@ ANY(...) is true when tp is a descendant of (or equal to) any element in the array,
 * so cookbook.cuisine matches cookbook.cuisine.american but not cookbook or cookbook.american.
 * Multiple selected tags are OR: a chunk matches if any tag_path falls under any selected tag.
 */
internal fun tagFilterClause(tags: List<String>?): String =
    if (!tags.isNullOrEmpty())
        "AND EXISTS (SELECT 1 FROM unnest(c.tag_paths) AS tp WHERE tp <@ ANY(ARRAY[${tags.joinToString(",") { "?" }}]::ltree[]))"
    else ""
