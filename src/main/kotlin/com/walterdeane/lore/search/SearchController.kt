package com.walterdeane.lore.search

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * JSON API exposing the two retrieval strategies directly (as opposed to [SearchViewController],
 * which renders HTML). Useful for comparing lexical-only vs. hybrid results side by side while
 * developing or demoing the retrieval pipeline.
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

    /** Lexical + vector search fused with RRF — see [HybridSearchService]. */
    @GetMapping("/hybrid")
    fun hybrid(
        @RequestParam q: String,
        @RequestParam domainId: UUID,
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "0") page: Int,
    ): ResponseEntity<HybridSearchService.SearchPage> =
        ResponseEntity.ok(hybridSearchService.search(q, domainId, tags, size, page))
}
