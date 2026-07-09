package com.walterdeane.lore.search

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * JSON API exposing the two retrieval strategies directly (as opposed to [SearchViewController],
 * which renders HTML). Useful for comparing BM25-only vs. hybrid results side by side while
 * developing or demoing the retrieval pipeline.
 */
@RestController
@RequestMapping("/api/search")
class SearchController(
    private val bm25SearchService: BM25SearchService,
    private val hybridSearchService: HybridSearchService,
) {

    /** Keyword-only search — see [BM25SearchService]. */
    @GetMapping("/bm25")
    fun bm25(
        @RequestParam q: String,
        @RequestParam domainId: UUID,
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "0") page: Int,
    ): ResponseEntity<BM25SearchService.SearchPage> =
        ResponseEntity.ok(bm25SearchService.search(q, domainId, tags, size, page))

    /** BM25 + vector search fused with RRF — see [HybridSearchService]. */
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
