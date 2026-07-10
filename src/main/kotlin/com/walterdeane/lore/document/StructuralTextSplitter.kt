package com.walterdeane.lore.document

import com.walterdeane.lore.model.SourceType
import com.walterdeane.lore.model.StructuralVariant
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.stereotype.Component

data class BoundaryConfig(
    val maxTitleLength: Int = 80,
    val minChunkChars: Int = 200,
    val allowNumberedSections: Boolean = true,
    val excludedHeaders: Set<String> = emptySet(),
    // Preferred markdown heading level to split on (## = 2).
    val markdownHeadingLevel: Int = 2,
)

/**
 * Entry point for the STRUCTURAL chunking strategy: converts a source file (EPUB/PDF) to markdown
 * via [EpubMarkdownParser]/[PdfMarkdownParser], then delegates to [MarkdownChunker] to cut it at
 * heading boundaries. [BoundaryConfig] tunes that split per [StructuralVariant] (e.g. a cookbook's
 * "Ingredients"/"Method" sub-headers shouldn't themselves start new chunks). If markdown parsing
 * fails or produces too few chunks, falls back to a heuristic line-based splitter over the raw
 * per-page text Tika already extracted — a good illustration of why structural/semantic chunking
 * is harder to get right than naive fixed-size token splitting ([DocumentIngestionService]'s
 * TOKEN strategy).
 */
@Component
class StructuralTextSplitter(
    private val epubMarkdownParser: EpubMarkdownParser,
    private val pdfMarkdownParser: PdfMarkdownParser,
    private val markdownChunker: MarkdownChunker,
) {

    private val log = LoggerFactory.getLogger(StructuralTextSplitter::class.java)

    companion object {
        private val NUMBERED_SECTION = Regex("""^\d+(\.\d+)*\.?\s+[A-ZÀ-ÿ]""")

        fun configFor(variant: StructuralVariant): BoundaryConfig = when (variant) {
            StructuralVariant.GENERIC -> BoundaryConfig(
                allowNumberedSections = true,
            )
            StructuralVariant.COOKBOOK -> BoundaryConfig(
                allowNumberedSections = false,
                markdownHeadingLevel = 2,
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
                markdownHeadingLevel = 2,
                excludedHeaders = emptySet(),
            )
        }
    }

    /**
     * Primary entry point: parses the source file to markdown then splits on
     * heading markers. Falls back to heuristic text analysis if parsing fails
     * or yields fewer than 3 chunks.
     */
    fun split(sourcePath: String, sourceType: SourceType, pages: List<Document>, variant: StructuralVariant): List<Document> {
        val config = configFor(variant)

        val markdown = when (sourceType) {
            SourceType.EPUB -> epubMarkdownParser.parse(sourcePath)
            SourceType.PDF -> pdfMarkdownParser.parse(sourcePath)
        }

        if (markdown.isNotBlank()) {
            val chunks = markdownChunker.split(markdown, config.markdownHeadingLevel, config.minChunkChars, config.excludedHeaders)
            if (chunks.size >= 3) {
                log.info("[{}] markdown chunking: {} chunks from {}", variant, chunks.size, sourceType)
                return chunks.map { Document(it) }
            }
            log.warn("[{}] markdown chunking produced only {} chunks for {}, falling back to heuristic",
                variant, chunks.size, sourceType)
        } else {
            log.warn("[{}] markdown parser returned empty for {}, falling back to heuristic", variant, sourceType)
        }

        return heuristicSplit(pages, variant, config)
    }

    /** Legacy heuristic path — used as fallback when markdown parsing fails or under-chunks. */
    private fun heuristicSplit(pages: List<Document>, variant: StructuralVariant, config: BoundaryConfig): List<Document> {
        val rawSegments = mutableListOf<String>()
        for (page in pages) {
            val text = (page.text ?: "").trim()
            if (text.isBlank()) continue
            rawSegments.addAll(segmentPage(text.lines(), config))
        }
        val merged = mergeShort(rawSegments, config.minChunkChars)
        log.info("[{}] heuristic split: {} pages → {} raw → {} chunks",
            variant, pages.size, rawSegments.size, merged.size)
        return merged.filter { it.isNotBlank() }.map { Document(it) }
    }

    /** Groups raw page lines into segments, starting a new one wherever [isHeadingInContext]/[couldBeHeading] fires. */
    private fun segmentPage(lines: List<String>, config: BoundaryConfig): List<String> {
        val segments = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var seenContent = false

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            val boundary = when {
                !seenContent && trimmed.isNotBlank() -> { seenContent = true; couldBeHeading(trimmed, config) }
                else -> { if (trimmed.isNotBlank()) seenContent = true; isHeadingInContext(trimmed, lines, i, config) }
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

    /** Cheap shape-based heading heuristic: short, capitalized/numbered, no trailing punctuation. */
    internal fun couldBeHeading(trimmed: String, config: BoundaryConfig): Boolean {
        if (trimmed.length < 3 || trimmed.length > config.maxTitleLength) return false
        if (trimmed.endsWith(".") || trimmed.endsWith(",") || trimmed.endsWith(";") || trimmed.endsWith(":")) return false
        val lower = trimmed.lowercase()
        if (lower in config.excludedHeaders) return false
        if (config.excludedHeaders.any { lower.startsWith(it) }) return false
        val isNumbered = config.allowNumberedSections && NUMBERED_SECTION.containsMatchIn(trimmed)
        return isNumbered || trimmed[0].isUpperCase()
    }

    /** [couldBeHeading] plus a context check: a heading is usually preceded by a blank line or sentence end. */
    internal fun isHeadingInContext(trimmed: String, lines: List<String>, index: Int, config: BoundaryConfig): Boolean {
        if (!couldBeHeading(trimmed, config)) return false
        val prevLine = if (index > 0) lines[index - 1].trim() else ""
        if (prevLine.isBlank()) return true
        val prevEndsSentence = prevLine.length >= 20 &&
            (prevLine.endsWith(".") || prevLine.endsWith("!") || prevLine.endsWith("?"))
        return prevEndsSentence
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
