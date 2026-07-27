package com.walterdeane.lore.search

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Checks whether keyword search should relax from requiring every word in [query] to match (the
 * normal, strict behavior) down to requiring just one. Postgres's `websearch_to_tsquery` turns
 * [query] into search terms that must *all* be present by default, so a single uncommon word in an
 * otherwise-ordinary question (e.g. "versus" in "what is the difference between cooking with gas
 * versus charcoal") can make the whole search match nothing, even though every other word is
 * common.
 *
 * When [fallbackEnabled] is on, this runs a quick existence check first — "does the strict,
 * match-everything search find anything at all?" — and tells the caller to switch to the looser
 * "match anything" version only if the answer is no. The check just asks yes/no rather than
 * ranking anything, and Postgres can answer it straight from an index, so it stays cheap even
 * though it runs before every keyword search.
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

/**
 * Builds the SQL expression that turns a query string into a Postgres full-text search pattern
 * (called a "tsquery"). It always consumes exactly one `?` placeholder for the query text, and is
 * safe to use anywhere a plain value is expected — e.g. inside `ts_headline(...)`. It can *not* be
 * used directly as a `FROM`-clause item; see [lexicalTsqueryFromItem] for that.
 *
 * When [useOrFallback] is false (the normal case), this builds a pattern requiring every word in
 * the query to match ("AND"). When true, it takes that same pattern and swaps every `&` for a `|`,
 * turning "match every word" into "match any word" — this works because Postgres can print a
 * pattern back out as text and read it back in as a new pattern, so swapping the operator in the
 * text is enough; there's no need to re-derive the words separately.
 */
internal fun lexicalTsqueryExpr(useOrFallback: Boolean): String =
    if (useOrFallback) "replace(websearch_to_tsquery('english', ?)::text, ' & ', ' | ')::tsquery"
    else "websearch_to_tsquery('english', ?)"

/**
 * The same expression as [lexicalTsqueryExpr], wrapped so it can be used as a table in a SQL
 * `FROM` clause (`FROM chunk c, <this> q`), matching the existing `plainto_tsquery(...) q` pattern
 * elsewhere in this codebase. This wrapping is needed because the "match any word" version ends
 * with a type cast (`::tsquery`), and Postgres doesn't allow a `FROM`-clause item to end that way.
 * Both versions are wrapped identically — not just the one that needs it — so callers can always
 * refer to the result as `q.q`, regardless of which version is actually running.
 */
internal fun lexicalTsqueryFromItem(useOrFallback: Boolean): String =
    "(SELECT ${lexicalTsqueryExpr(useOrFallback)} AS q)"

/**
 * Builds a SQL clause that restricts results to chunks tagged with any of [tags], including
 * chunks tagged with something more specific underneath one of those tags. Tags are stored as
 * dot-separated paths (e.g. `cookbook.cuisine.american`); the `<@` operator means "this path is
 * equal to, or nested under, that path", so filtering on `cookbook.cuisine` matches
 * `cookbook.cuisine.american` but not `cookbook` or `cookbook.american`. If more than one tag is
 * given, a chunk matches if it falls under *any* of them.
 */
internal fun tagFilterClause(tags: List<String>?): String =
    if (!tags.isNullOrEmpty())
        "AND EXISTS (SELECT 1 FROM unnest(c.tag_paths) AS tp WHERE tp <@ ANY(ARRAY[${tags.joinToString(",") { "?" }}]::ltree[]))"
    else ""
