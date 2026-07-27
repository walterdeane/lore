package com.walterdeane.lore.search

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Tuning knobs for search, set under `lore.search` in application config.
 *
 * - [candidatePoolSize]: how many top results each search method (keyword and semantic) considers
 *   before the two are combined. A bigger pool gives a good match more chances to survive into the
 *   final results, at the cost of a slower query.
 * - [rrfK]: a smoothing constant used when combining the keyword and semantic rankings — see
 *   [fuse]. Bigger values make the combined ranking less sensitive to small differences in
 *   position between the two methods. 60 is a commonly used default.
 * - [lexicalFallbackEnabled]: keyword search normally requires every significant word in the
 *   query to appear in a matching chunk. That's strict enough that a natural-language question
 *   like "what is the difference between cooking with gas versus charcoal" can match nothing at
 *   all, just because of the word "versus" — even though every other word is common. When this is
 *   on, a search that strictly matches nothing is retried requiring only one word to match instead
 *   of all of them (see [shouldUseLexicalOrFallback]).
 */
@ConfigurationProperties(prefix = "lore.search")
data class SearchProperties(
    val candidatePoolSize: Int = 50,
    val rrfK: Int = 60,
    val lexicalFallbackEnabled: Boolean = true,
)
