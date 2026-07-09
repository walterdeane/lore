package com.walterdeane.lore.chat

import com.walterdeane.lore.document.ChunkRepository
import com.walterdeane.lore.document.MarkdownRenderer
import com.walterdeane.lore.domain.DomainsService
import com.walterdeane.lore.search.HybridSearchService
import com.walterdeane.lore.search.RerankerService
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

private const val CONTEXT_CHUNK_COUNT = 5
private const val RERANK_CANDIDATE_COUNT = 15

private const val SYSTEM_PROMPT = """
You are answering questions using only the CONTEXT below, which was retrieved from the user's own document library.
If the context doesn't contain the answer, say so plainly instead of guessing.
Cite sources inline using their titles.

CONTEXT:
%s
"""

/**
 * The "generation" half of RAG, tying together retrieval and the LLM in one request: hybrid search
 * finds candidate chunks, an optional LLM-based rerank narrows them to the best few (see
 * [RerankerService]), then those chunks are stuffed into a system prompt as grounding CONTEXT before
 * asking the chat model to answer. This is the same corpus [SearchViewController]/[HybridSearchService]
 * expose for browsing, but here the retrieved chunks become model input instead of a results page.
 */
@Controller
@RequestMapping("/chat")
class ChatViewController(
    chatClientBuilder: ChatClient.Builder,
    private val markdownRenderer: MarkdownRenderer,
    private val hybridSearchService: HybridSearchService,
    private val chunkRepository: ChunkRepository,
    private val domainsService: DomainsService,
    private val rerankerService: RerankerService,
    private val chatProperties: ChatProperties,
) {

    private val chatClient = chatClientBuilder.build()

    /**
     * Runs one full RAG turn for [q] scoped to [domainId]:
     * 1. hybrid search fetches [RERANK_CANDIDATE_COUNT] candidate chunks;
     * 2. optionally rerank them down to the top [CONTEXT_CHUNK_COUNT] most relevant;
     * 3. concatenate their content into the CONTEXT block of [SYSTEM_PROMPT];
     * 4. call the chat model and render its answer, alongside the sources used, so the user can verify grounding.
     */
    @GetMapping
    fun chatPage(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) domainId: UUID?,
        model: Model,
    ): String {
        val domains = domainsService.getAllDomains()
        val effectiveDomainId = domainId ?: domains.firstOrNull()?.id

        model.addAttribute("domains", domains)
        model.addAttribute("q", q)
        model.addAttribute("domainId", effectiveDomainId)

        if (!q.isNullOrBlank() && effectiveDomainId != null) {
            val candidates = hybridSearchService.search(q, effectiveDomainId, size = RERANK_CANDIDATE_COUNT).results
            val contentByChunk = candidates.associate { it.chunkId to (chunkRepository.findById(it.chunkId)?.content ?: it.headline) }
            val sources = if (chatProperties.rerankEnabled) {
                rerankerService.rerank(q, candidates, CONTEXT_CHUNK_COUNT) { contentByChunk.getValue(it.chunkId) }
            } else {
                candidates.take(CONTEXT_CHUNK_COUNT)
            }
            val context = sources.joinToString("\n\n") { result ->
                "### ${result.documentTitle}\n${contentByChunk.getValue(result.chunkId)}"
            }
            val answer = chatClient.prompt()
                .system(SYSTEM_PROMPT.format(context))
                .user(q)
                .call()
                .content() ?: ""
            model.addAttribute("contentHtml", markdownRenderer.toHtml(answer))
            model.addAttribute("sources", sources)
        }

        return "chat/index"
    }
}