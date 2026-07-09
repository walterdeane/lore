package com.walterdeane.lore.search

import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

private const val MAX_PASSAGE_CHARS = 1000

private const val RERANK_PROMPT = """
Rank the passages below by relevance to the query. Respond with ONLY a JSON array of
passage numbers ordered from most to least relevant, e.g. [2,0,1]. Do not include any
other text.

Query: %s

Passages:
%s
"""

/**
 * LLM-scored reranking: Ollama has no cross-encoder rerank endpoint, so relevance
 * ordering is done listwise by asking the chat model to rank numbered passages in a
 * single call. Falls back to the original order if the model's response can't be parsed.
 */
@Service
class RerankerService(chatClientBuilder: ChatClient.Builder) {

    private val chatClient = chatClientBuilder.build()

    /**
     * Reranks [candidates] (typically the top-N fused hybrid-search hits) down to the best [topK]
     * for feeding into the answering LLM's context window. Generic over T so both raw search
     * results and already-hydrated chunks can be reranked; [contentOf] extracts the text to show
     * the model. No-ops if there are already <= topK candidates.
     */
    fun <T> rerank(query: String, candidates: List<T>, topK: Int, contentOf: (T) -> String): List<T> {
        if (candidates.size <= topK) return candidates

        val listing = candidates.withIndex().joinToString("\n\n") { (i, candidate) ->
            "[$i] ${contentOf(candidate).take(MAX_PASSAGE_CHARS)}"
        }
        val response = chatClient.prompt()
            .user(RERANK_PROMPT.format(query, listing))
            .call()
            .content() ?: ""

        val order = Regex("\\d+").findAll(response)
            .map { it.value.toInt() }
            .filter { it in candidates.indices }
            .distinct()
            .toList()

        val ranked = order.map { candidates[it] }
        return ranked.ifEmpty { candidates }.take(topK)
    }
}
