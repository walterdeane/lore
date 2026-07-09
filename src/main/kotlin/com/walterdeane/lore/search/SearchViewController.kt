package com.walterdeane.lore.search

import com.walterdeane.lore.document.ChunkRepository
import com.walterdeane.lore.document.DocumentsService
import com.walterdeane.lore.document.MarkdownRenderer
import com.walterdeane.lore.domain.DomainsService
import com.walterdeane.lore.tags.TagsService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * Human-facing search UI: renders the retrieval side of the RAG pipeline on its own, without an
 * LLM in the loop, so you can inspect what hybrid search actually returns before it gets fed to
 * the chat model in [com.walterdeane.lore.chat.ChatViewController].
 */
@Controller
@RequestMapping("/search")
class SearchViewController(
    private val hybridSearchService: HybridSearchService,
    private val domainsService: DomainsService,
    private val tagsService: TagsService,
    private val chunkRepository: ChunkRepository,
    private val documentsService: DocumentsService,
    private val markdownRenderer: MarkdownRenderer,
) {

    /** Renders the search page; runs hybrid search only once a query and domain are both present. */
    @GetMapping
    fun searchPage(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) domainId: UUID?,
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        model: Model,
    ): String {
        val selectedTags = tags?.filter { it.isNotBlank() } ?: emptyList()
        val domains = domainsService.getAllDomains()
        val effectiveDomainId = domainId ?: domains.firstOrNull()?.id

        model.addAttribute("domains", domains)
        model.addAttribute("q", q)
        model.addAttribute("domainId", effectiveDomainId)
        model.addAttribute("selectedTags", selectedTags)
        model.addAttribute("page", page)
        model.addAttribute("size", size)
        model.addAttribute("availableTags",
            if (effectiveDomainId != null) tagsService.getDomainTags(effectiveDomainId).sortedBy { it.path }
            else emptyList<Any>()
        )

        if (!q.isNullOrBlank() && effectiveDomainId != null) {
            model.addAttribute("searchPage", hybridSearchService.search(q, effectiveDomainId, selectedTags.ifEmpty { null }, size, page))
        }

        return "search/index"
    }

    /** Drill-down view showing the full text of a single retrieved chunk, e.g. for auditing why it matched. */
    @GetMapping("/chunks/{chunkId}")
    fun chunkDetail(
        @PathVariable chunkId: UUID,
        @RequestParam(required = false) q: String?,
        model: Model,
    ): String {
        val chunk = chunkRepository.findById(chunkId)
        model.addAttribute("chunk", chunk)
        model.addAttribute("document", chunk?.let { documentsService.getDocumentById(it.documentId) })
        model.addAttribute("contentHtml", chunk?.let { markdownRenderer.toHtml(it.content) } ?: "")
        model.addAttribute("q", q)
        model.addAttribute("previousChunkId", chunk?.let { chunkRepository.findIdByDocumentIdAndChunkIndex(it.documentId, it.chunkIndex - 1) })
        model.addAttribute("nextChunkId", chunk?.let { chunkRepository.findIdByDocumentIdAndChunkIndex(it.documentId, it.chunkIndex + 1) })
        return "search/chunk"
    }
}
