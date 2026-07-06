package com.walterdeane.lore.search

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/search")
class SearchController(private val bm25SearchService: BM25SearchService) {

    @GetMapping("/bm25")
    fun bm25(
        @RequestParam q: String,
        @RequestParam domainId: UUID,
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<BM25SearchService.Result>> =
        ResponseEntity.ok(bm25SearchService.search(q, domainId, tags, limit))
}
