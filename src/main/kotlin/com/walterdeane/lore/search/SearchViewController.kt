package com.walterdeane.lore.search

import com.walterdeane.lore.document.ChunkRepository
import com.walterdeane.lore.document.DocumentsService
import com.walterdeane.lore.domain.DomainsService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

@Controller
@RequestMapping("/search")
class SearchViewController(
    private val bm25SearchService: BM25SearchService,
    private val domainsService: DomainsService,
    private val chunkRepository: ChunkRepository,
    private val documentsService: DocumentsService,
) {

    @GetMapping
    fun searchPage(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) domainId: UUID?,
        @RequestParam(required = false) tags: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        model: Model,
    ): String {
        model.addAttribute("domains", domainsService.getAllDomains())
        model.addAttribute("q", q)
        model.addAttribute("domainId", domainId)
        model.addAttribute("tags", tags)
        model.addAttribute("page", page)
        model.addAttribute("size", size)

        if (!q.isNullOrBlank() && domainId != null) {
            val tagList = tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            model.addAttribute("searchPage", bm25SearchService.search(q, domainId, tagList, size, page))
        }

        return "search/index"
    }

    @GetMapping("/chunks/{chunkId}")
    fun chunkDetail(
        @PathVariable chunkId: UUID,
        @RequestParam(required = false) q: String?,
        model: Model,
    ): String {
        val chunk = chunkRepository.findById(chunkId)
        model.addAttribute("chunk", chunk)
        model.addAttribute("document", chunk?.let { documentsService.getDocumentById(it.documentId) })
        model.addAttribute("q", q)
        return "search/chunk"
    }
}
