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
        // Matches "1 Title", "1.1 Title", "2.3.1 Title" — numbered section headings
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
                // academic section names (Abstract, Introduction, etc.) are valid
                // boundaries, not exclusions — leave excludedHeaders empty
                excludedHeaders = emptySet(),
            )
        }
    }

    fun split(pages: List<Document>, variant: StructuralVariant = StructuralVariant.GENERIC): List<Document> {
        val config = configFor(variant)
        val fullText = pages.joinToString("\n\n") { it.text ?: "" }
        val lines = fullText.lines()

        val segments = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()

        for (i in lines.indices) {
            if (isBoundary(lines[i], lines, i, config)) {
                if (current.any { it.isNotBlank() }) segments.add(current)
                current = mutableListOf(lines[i])
            } else {
                current.add(lines[i])
            }
        }
        if (current.any { it.isNotBlank() }) segments.add(current)

        val merged = mergeShortSegments(segments, config.minChunkChars)
        log.info("[{}] structural split: {} boundaries, {} chunks after merge",
            variant, segments.size, merged.size)

        return merged
            .map { it.joinToString("\n").trim() }
            .filter { it.isNotBlank() }
            .map { Document(it) }
    }

    private fun isBoundary(line: String, lines: List<String>, index: Int, config: BoundaryConfig): Boolean {
        val trimmed = line.trim()

        if (trimmed.length < 3 || trimmed.length > config.maxTitleLength) return false
        if (trimmed.endsWith(".") || trimmed.endsWith(",") || trimmed.endsWith(";") || trimmed.endsWith(":")) return false

        val lower = trimmed.lowercase()
        if (lower in config.excludedHeaders) return false
        if (config.excludedHeaders.any { lower.startsWith(it) }) return false

        val isNumbered = config.allowNumberedSections && NUMBERED_SECTION.containsMatchIn(trimmed)
        if (!isNumbered && !trimmed[0].isUpperCase()) return false

        // Must be preceded by a blank line
        val prevBlank = index == 0 || lines[index - 1].isBlank()
        if (!prevBlank) return false

        // Must be immediately followed by content
        val nextLine = if (index + 1 < lines.size) lines[index + 1].trim() else ""
        if (nextLine.isBlank()) return false

        return true
    }

    private fun mergeShortSegments(segments: List<MutableList<String>>, minChars: Int): List<List<String>> {
        if (segments.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<String>>()
        var acc = segments[0].toMutableList()

        for (i in 1 until segments.size) {
            if (acc.joinToString("").trim().length < minChars) {
                acc.addAll(segments[i])
            } else {
                result.add(acc)
                acc = segments[i].toMutableList()
            }
        }
        result.add(acc)
        return result
    }
}
