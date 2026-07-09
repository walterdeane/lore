package com.walterdeane.lore.document

import org.springframework.ai.document.Document
import org.springframework.stereotype.Component

private val SENTENCE_BOUNDARY = setOf('.', '?', '!', '\n')

/**
 * Adds overlap between consecutive TOKEN-strategy chunks so a fact split across a chunk boundary
 * still appears whole in at least one chunk. Spring AI's [org.springframework.ai.transformer.splitter.TokenTextSplitter]
 * has no overlap concept — it walks the token stream once with no stride — so this runs as a
 * post-processing step over its output: each chunk (after the first) gets the trailing sentence(s)
 * of the previous chunk prepended, using the same punctuation-based boundary Spring AI's splitter
 * already uses internally to keep truncation from landing mid-sentence.
 */
@Component
class TokenOverlapChunker {

    fun applyOverlap(chunks: List<Document>, overlapChars: Int): List<Document> {
        if (chunks.size < 2 || overlapChars <= 0) return chunks

        val texts = chunks.map { it.text ?: "" }
        val result = mutableListOf(texts[0])
        for (i in 1 until texts.size) {
            val overlap = trailingContext(texts[i - 1], overlapChars)
            result.add(if (overlap.isBlank()) texts[i] else "$overlap ${texts[i]}")
        }
        return result.map { Document(it) }
    }

    /**
     * Takes the trailing [maxChars] of [text], then trims forward to the nearest sentence boundary
     * within that window so the overlap starts cleanly rather than mid-sentence. Falls back to the
     * raw window if no boundary is found (e.g. one long unpunctuated line).
     */
    private fun trailingContext(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed

        val window = trimmed.takeLast(maxChars)
        val boundary = window.indexOfFirst { it in SENTENCE_BOUNDARY }
        return if (boundary != -1 && boundary < window.length - 1) window.substring(boundary + 1).trim() else window
    }
}
