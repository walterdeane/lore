package com.walterdeane.lore.document

import com.walterdeane.lore.model.StructuralVariant
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.stereotype.Component

data class BoundaryConfig(
    val maxTitleLength: Int = 80,
    val minChunkChars: Int = 200,
    val allowNumberedSections: Boolean = true,
    val excludedHeaders: Set<String> = emptySet(),
)

@Component
class StructuralTextSplitter {

    private val log = LoggerFactory.getLogger(StructuralTextSplitter::class.java)

    companion object {
        // "1 Title", "1.1 Title", "2.3.1 Title"
        private val NUMBERED_SECTION = Regex("""^\d+(\.\d+)*\.?\s+[A-ZÀ-ÿ]""")

        fun configFor(variant: StructuralVariant): BoundaryConfig = when (variant) {
            StructuralVariant.GENERIC -> BoundaryConfig(
                allowNumberedSections = true,
            )
            StructuralVariant.COOKBOOK -> BoundaryConfig(
                allowNumberedSections = false,
                excludedHeaders = setOf(
                    "ingredients", "instructions", "directions", "method", "methods",
                    "notes", "note", "preparation", "serves", "makes", "servings",
                    "prep time", "cook time", "yield", "equipment", "to serve",
                    "to finish", "to make", "for serving", "for the", "tips",
                    "variations", "storage", "make ahead", "do ahead",
                ),
            )
            StructuralVariant.ACADEMIC -> BoundaryConfig(
                maxTitleLength = 120,
                minChunkChars = 300,
                allowNumberedSections = true,
                excludedHeaders = emptySet(),
            )
        }
    }

    fun split(pages: List<Document>, variant: StructuralVariant = StructuralVariant.GENERIC): List<Document> {
        val config = configFor(variant)
        // Process each Tika page independently so page boundaries are always structural breaks,
        // then detect heading boundaries within each page's text.
        val rawSegments = mutableListOf<String>()
        for (page in pages) {
            val text = (page.text ?: "").trim()
            if (text.isBlank()) continue
            rawSegments.addAll(segmentPage(text.lines(), config))
        }

        val merged = mergeShortChunks(rawSegments, config.minChunkChars)
        log.info("[{}] structural split: {} Tika pages → {} raw segments → {} chunks after merge",
            variant, pages.size, rawSegments.size, merged.size)

        return merged.filter { it.isNotBlank() }.map { Document(it) }
    }

    private fun segmentPage(lines: List<String>, config: BoundaryConfig): List<String> {
        val segments = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var seenContent = false

        for (i in lines.indices) {
            val trimmed = lines[i].trim()

            val boundary = when {
                // First non-blank line of this Tika page: treat as boundary candidate
                // without needing context (page boundary is itself a structural signal).
                !seenContent && trimmed.isNotBlank() -> {
                    seenContent = true
                    couldBeHeading(trimmed, config)
                }
                else -> {
                    if (trimmed.isNotBlank()) seenContent = true
                    isHeadingInContext(trimmed, lines, i, config)
                }
            }

            if (boundary) {
                if (current.any { it.isNotBlank() }) segments.add(current)
                current = mutableListOf(lines[i])
            } else {
                current.add(lines[i])
            }
        }
        if (current.any { it.isNotBlank() }) segments.add(current)
        return segments.map { it.joinToString("\n").trim() }.filter { it.isNotBlank() }
    }

    // Basic shape check — no context required.
    private fun couldBeHeading(trimmed: String, config: BoundaryConfig): Boolean {
        if (trimmed.length < 3 || trimmed.length > config.maxTitleLength) return false
        // Headings don't end like sentences or list items.
        if (trimmed.endsWith(".") || trimmed.endsWith(",") || trimmed.endsWith(";") || trimmed.endsWith(":")) return false
        val lower = trimmed.lowercase()
        if (lower in config.excludedHeaders) return false
        if (config.excludedHeaders.any { lower.startsWith(it) }) return false
        val isNumbered = config.allowNumberedSections && NUMBERED_SECTION.containsMatchIn(trimmed)
        return isNumbered || trimmed[0].isUpperCase()
    }

    // Context-aware check: the line must pass couldBeHeading AND be preceded by
    // a blank line OR a sentence-ending line (e.g. the last instruction of a recipe).
    private fun isHeadingInContext(trimmed: String, lines: List<String>, index: Int, config: BoundaryConfig): Boolean {
        if (!couldBeHeading(trimmed, config)) return false

        val prevLine = if (index > 0) lines[index - 1].trim() else ""

        // Classic signal: blank line before the heading.
        if (prevLine.isBlank()) return true

        // Sentence-end signal: previous line closes a paragraph/instruction.
        // Minimum length guard avoids single-word lines triggering this.
        val prevEndsSentence = prevLine.length >= 20 &&
            (prevLine.endsWith(".") || prevLine.endsWith("!") || prevLine.endsWith("?"))
        return prevEndsSentence
    }

    private fun mergeShortChunks(segments: List<String>, minChars: Int): List<String> {
        if (segments.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var acc = segments[0]
        for (i in 1 until segments.size) {
            if (acc.trim().length < minChars) {
                acc = "$acc\n\n${segments[i]}"
            } else {
                result.add(acc)
                acc = segments[i]
            }
        }
        result.add(acc)
        return result
    }
}
