package com.walterdeane.lore.search

import org.slf4j.LoggerFactory
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
 * Reads a passage ordering back out of the model's response text. The prompt asks for strict JSON
 * (e.g. `[2,0,1]`), but models don't always follow instructions exactly, so rather than parsing
 * JSON specifically, this just pulls out every number that appears anywhere in the response, in
 * the order it appears, drops any number that isn't a valid passage index, and removes duplicates.
 * Kept as its own plain function (no model call involved) so this parsing logic — the part most
 * likely to need tweaking as different models respond differently — can be tested on its own.
 */
internal fun parseRerankOrder(response: String, candidateCount: Int): List<Int> =
    Regex("\\d+").findAll(response)
        .map { it.value.toInt() }
        .filter { it in 0 until candidateCount }
        .distinct()
        .toList()

/**
 * Re-orders a list of search results by asking an LLM to judge relevance directly, rather than
 * relying only on the keyword/vector scores that found them in the first place. Some AI providers
 * offer a model built specifically for this ("reranking"); Ollama, which this project uses, does
 * not — so instead this shows the chat model all the candidate passages at once, numbered, and
 * asks it to return them in relevance order. If the model's response can't be understood as an
 * ordering (see [parseRerankOrder]), this falls back to the original order rather than failing the
 * search outright.
 */
@Service
class RerankerService(chatClientBuilder: ChatClient.Builder) {

    private val log = LoggerFactory.getLogger(RerankerService::class.java)
    private val chatClient = chatClientBuilder.build()

    /**
     * Narrows [candidates] (typically the results of a hybrid search) down to the best [topK], as
     * judged by the LLM, so only the most relevant passages get passed along to whatever generates
     * the final answer. Works with any type of candidate ([T]) — [contentOf] just needs to say how
     * to get the text to show the model from each one. If there are already [topK] or fewer
     * candidates, this does nothing and returns them unchanged.
     */
    fun <T> rerank(query: String, candidates: List<T>, topK: Int, contentOf: (T) -> String): List<T> {
        if (candidates.size <= topK) return candidates

        val listing = candidates.withIndex().joinToString("\n\n") { (i, candidate) ->
            "[$i] ${contentOf(candidate).take(MAX_PASSAGE_CHARS)}"
        }
        val (response, rerankMs) = timedStage(log, "rerank_llm", query) {
            chatClient.prompt()
                .user(RERANK_PROMPT.format(query, listing))
                .call()
                .content() ?: ""
        }

        val order = parseRerankOrder(response, candidates.size)
        val fallbackTriggered = order.isEmpty()
        // Logs the model's raw response and the parsed order, rather than a summary, because when
        // the model doesn't follow the "ONLY a JSON array" instruction, the raw text is exactly
        // what's needed to see what went wrong — a truncated or reformatted version would hide it.
        log.info(
            "rerank candidateCount={} topK={} ms={} fallbackTriggered={} parsedOrder={} rawResponse={}",
            candidates.size, topK, rerankMs, fallbackTriggered, order, response,
        )

        val ranked = order.map { candidates[it] }
        return ranked.ifEmpty { candidates }.take(topK)
    }
}
