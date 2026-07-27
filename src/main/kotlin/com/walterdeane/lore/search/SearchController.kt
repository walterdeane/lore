package com.walterdeane.lore.search

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A JSON API for running searches directly (as opposed to [SearchViewController], which renders an
 * HTML page). Useful for comparing keyword-only and combined ("hybrid") results side by side while
 * developing or demoing search.
 */
@RestController
@RequestMapping("/api/search")
class SearchController(
    private val lexicalSearchService: LexicalSearchService,
    private val hybridSearchService: HybridSearchService,
) {

    /** Keyword-only search — see [LexicalSearchService]. */
    @GetMapping("/lexical")
    fun lexical(
        @RequestParam q: String,
        @RequestParam domainId: UUID,
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "0") page: Int,
    ): ResponseEntity<LexicalSearchService.SearchPage> =
        ResponseEntity.ok(lexicalSearchService.search(q, domainId, tags, size, page))

    /**
     * Keyword and vector search combined via Reciprocal Rank Fusion — see [HybridSearchService].
     * `explain=true` switches to the diagnostic version ([HybridSearchService.explain]): per-method
     * candidate lists, fusion detail, and stage timings, instead of the normal combined
     * [HybridSearchService.SearchPage]. Meant for development/debugging only — no UI reads this.
     */
    @GetMapping("/hybrid")
    fun hybrid(
        @RequestParam q: String,
        @RequestParam domainId: UUID,
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "false") explain: Boolean,
    ): ResponseEntity<Any> =
        if (explain) {
            ResponseEntity.ok(hybridSearchService.explain(q, domainId, tags))
        } else {
            ResponseEntity.ok(hybridSearchService.search(q, domainId, tags, size, page))
        }
}
