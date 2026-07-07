package com.walterdeane.lore.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class MarkdownChunker {

    private val log = LoggerFactory.getLogger(MarkdownChunker::class.java)

    /**
     * Split markdown text into chunks at heading boundaries.
     *
     * When [excludedHeaders] is provided (e.g. "ingredients", "method") the effective
     * split level is auto-detected by finding which heading level directly parents those
     * sub-headers — so a cookbook structured as `### Recipe / #### Ingredients` splits
     * at ### rather than the default ##. Excluded headings within a chunk are kept as
     * body content rather than used as split boundaries.
     *
     * Falls back to trying adjacent levels if the detected level yields fewer than 3 chunks.
     */
    fun split(
        markdown: String,
        preferredLevel: Int = 2,
        minChunkChars: Int = 200,
        excludedHeaders: Set<String> = emptySet(),
    ): List<String> {
        if (markdown.isBlank()) return emptyList()

        val effectiveLevel = if (excludedHeaders.isNotEmpty()) {
            detectRecipeLevel(markdown, excludedHeaders)?.also {
                if (it != preferredLevel) log.info("detected recipe level h{} (preferred was h{})", it, preferredLevel)
            } ?: preferredLevel
        } else {
            preferredLevel
        }

        var chunks = splitAtLevel(markdown, effectiveLevel, excludedHeaders)

        if (chunks.size < 3) {
            val fallbacks = listOf(effectiveLevel - 1, effectiveLevel + 1, effectiveLevel + 2)
                .filter { it in 1..5 }
            for (level in fallbacks) {
                val candidate = splitAtLevel(markdown, level, excludedHeaders)
                if (candidate.size > chunks.size) {
                    log.info("h{} produced only {} chunks; using h{} ({} chunks) instead",
                        effectiveLevel, chunks.size, level, candidate.size)
                    chunks = candidate
                    break
                }
            }
        }

        return mergeShort(chunks, minChunkChars)
    }

    /**
     * Infer the recipe heading level by finding the first excluded sub-header
     * (e.g. "### Ingredients" at level 3 → recipes are at level 2).
     */
    private fun detectRecipeLevel(markdown: String, excludedHeaders: Set<String>): Int? {
        val headingLine = Regex("""^(#{1,6}) (.+)""")
        for (line in markdown.lines()) {
            val m = headingLine.matchEntire(line.trimEnd()) ?: continue
            val level = m.groupValues[1].length
            val title = m.groupValues[2].trim().lowercase()
            if (excludedHeaders.any { ex -> title == ex || title.startsWith("$ex ") }) {
                if (level > 1) return level - 1
            }
        }
        return null
    }

    private fun splitAtLevel(markdown: String, level: Int, excludedHeaders: Set<String> = emptySet()): List<String> {
        val headingLine = Regex("""^(#{1,6}) (.+)""")
        val lines = markdown.lines()
        val segments = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()

        for (line in lines) {
            val m = headingLine.matchEntire(line.trimEnd())
            // A heading at or shallower than the configured level starts a new chunk
            // (e.g. a book's h1 chapter/back-matter break must end a chunk even when
            // splitting at h2 recipe level) — deeper headings stay inside the chunk body.
            if (m != null && m.groupValues[1].length <= level) {
                val title = m.groupValues[2].trim().lowercase()
                val excluded = excludedHeaders.any { ex -> title == ex || title.startsWith("$ex ") }
                if (excluded) {
                    current.add(line)
                } else {
                    if (current.any { it.isNotBlank() }) segments.add(current)
                    current = mutableListOf(line)
                }
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
