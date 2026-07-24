package com.walterdeane.lore.search

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `lexicalFallbackEnabled` (post-03 2.2): `plainto_tsquery`/`websearch_to_tsquery` AND every term,
 * so a natural-language question with one uncommon incidental word (e.g. "versus") can return zero
 * rows even though every other term is common — see `HybridSearchService`'s lexical-OR-fallback
 * doc. Default on; set false to reproduce the pre-2.2 AND-only behavior for comparison captures.
 */
@ConfigurationProperties(prefix = "lore.search")
data class SearchProperties(
    val candidatePoolSize: Int = 50,
    val rrfK: Int = 60,
    val lexicalFallbackEnabled: Boolean = true,
)
