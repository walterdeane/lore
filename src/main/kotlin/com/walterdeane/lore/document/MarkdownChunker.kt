package com.walterdeane.lore.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class MarkdownChunker {

    private val log = LoggerFactory.getLogger(MarkdownChunker::class.java)

    /**
     * Split markdown text into chunks at heading boundaries.
     *
     * Tries [preferredLevel] first (e.g. ## = level 2). If that produces fewer than
     * 3 chunks it tries adjacent levels so a book whose recipes are ### still splits
     * correctly without manual config.
     */
    fun split(markdown: String, preferredLevel: Int = 2, minChunkChars: Int = 200): List<String> {
        if (markdown.isBlank()) return emptyList()

        var chunks = splitAtLevel(markdown, preferredLevel)

        if (chunks.size < 3) {
            // Try h1, h3, h4 in order of likely usefulness
            val fallbacks = listOf(preferredLevel - 1, preferredLevel + 1, preferredLevel + 2)
                .filter { it in 1..4 }
            for (level in fallbacks) {
                val candidate = splitAtLevel(markdown, level)
                if (candidate.size > chunks.size) {
                    log.info("heading level {} produced only {} chunks; using h{} ({} chunks) instead",
                        preferredLevel, chunks.size, level, candidate.size)
                    chunks = candidate
                    break
                }
            }
        }

        return mergeShort(chunks, minChunkChars)
    }

    private fun splitAtLevel(markdown: String, level: Int): List<String> {
        val prefix = "#".repeat(level) + " "
        // A line is a boundary only if it starts exactly at this level (not a sub-heading).
        val lines = markdown.lines()
        val segments = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith(prefix) && !line.startsWith(prefix + "#")) {
                if (current.any { it.isNotBlank() }) segments.add(current)
                current = mutableListOf(line)
            } else {
                current.add(line)
            }
        }
        if (current.any { it.isNotBlank() }) segments.add(current)

        return segments.map { it.joinToString("\n").trim() }.filter { it.isNotBlank() }
    }

    private fun mergeShort(segments: List<String>, minChars: Int): List<String> {
        if (segments.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var acc = segments[0]
        for (i in 1 until segments.size) {
            acc = if (acc.trim().length < minChars) "$acc\n\n${segments[i]}" else { result.add(acc); segments[i] }
        }
        result.add(acc)
        return result
    }
}
