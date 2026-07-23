package com.walterdeane.lore.document

import com.walterdeane.lore.model.SourceType
import com.walterdeane.lore.model.StructuralVariant
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.stereotype.Component

data class BoundaryConfig(
    val maxTitleLength: Int = 80,
    val minChunkChars: Int = 200,
    // Same default as SemanticConfig.maxChunkChars, so TOKEN/STRUCTURAL/SEMANTIC chunks land in a
    // comparable size range regardless of strategy — see StructuralTextSplitter.capOversizedChunks.
    val maxChunkChars: Int = 4000,
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
    private val tokenOverlapChunker: TokenOverlapChunker,
    private val chunkingProperties: ChunkingProperties,
) {

    private val log = LoggerFactory.getLogger(StructuralTextSplitter::class.java)
    private val oversizedChunkSplitter = TokenTextSplitter.builder().withChunkSize(chunkingProperties.tokenChunkSize).build()

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
     * or yields fewer than 3 chunks. [pages] is a supplier, not a value, so a caller whose primary
     * extraction (Tika, via [pages]) is expensive or fragile only pays for/risks it when the
     * markdown path actually needs the fallback — e.g. Tika's strict XML parser can reject a
     * malformed-but-otherwise-fine EPUB that the markdown path (Jsoup, lenient) handles just fine.
     */
    fun split(sourcePath: String, sourceType: SourceType, pages: () -> List<Document>, variant: StructuralVariant): List<Document> {
        val config = configFor(variant)

        val markdown = when (sourceType) {
            SourceType.EPUB -> epubMarkdownParser.parse(sourcePath)
            SourceType.PDF -> pdfMarkdownParser.parse(sourcePath)
        }

        val chunks = if (markdown.isNotBlank()) {
            val markdownChunks = markdownChunker.split(markdown, config.markdownHeadingLevel, config.minChunkChars, config.excludedHeaders)
            if (markdownChunks.size >= 3) {
                log.info("[{}] markdown chunking: {} chunks from {}", variant, markdownChunks.size, sourceType)
                markdownChunks
            } else {
                log.warn("[{}] markdown chunking produced only {} chunks for {}, falling back to heuristic",
                    variant, markdownChunks.size, sourceType)
                heuristicSplit(pages(), variant, config)
            }
        } else {
            log.warn("[{}] markdown parser returned empty for {}, falling back to heuristic", variant, sourceType)
            heuristicSplit(pages(), variant, config)
        }

        return capOversizedChunks(chunks, config.maxChunkChars).map { Document(it) }
    }

    /**
     * A heading-bounded chunk's size is bounded only by where headings happen to fall — a document
     * whose real structure is coarser than expected (chapter-level headings only, no per-recipe/
     * per-section markers) can produce a single chunk spanning tens of thousands of characters,
     * which embeds as one diluted vector no more useful for retrieval than not chunking at all.
     * Anything over [maxChars] is split further with the same plain token+overlap splitter the
     * TOKEN strategy uses — not [SemanticTextSplitter], which would give STRUCTURAL a new
     * embedding-model dependency it doesn't otherwise need — and each resulting piece is
     * re-prefixed with the original heading line so it doesn't lose which section it came from.
     */
    internal fun capOversizedChunks(chunks: List<String>, maxChars: Int): List<String> =
        chunks.flatMap { text -> if (text.length > maxChars) splitOversizedChunk(text) else listOf(text) }

    private fun splitOversizedChunk(text: String): List<String> {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val heading = firstLine.takeIf { it.startsWith("#") } ?: ""
        val body = if (heading.isBlank()) text else text.removePrefix(firstLine).trimStart('\n', ' ')

        val split = tokenOverlapChunker.applyOverlap(
            oversizedChunkSplitter.split(Document(body)),
            chunkingProperties.tokenOverlapChars,
        )
        return split.mapNotNull { it.text }.map { sub -> if (heading.isBlank()) sub else "$heading\n\n$sub" }
    }

    /** Legacy heuristic path — used as fallback when markdown parsing fails or under-chunks. */
    private fun heuristicSplit(pages: List<Document>, variant: StructuralVariant, config: BoundaryConfig): List<String> {
        val rawSegments = mutableListOf<String>()
        for (page in pages) {
            val text = (page.text ?: "").trim()
            if (text.isBlank()) continue
            rawSegments.addAll(segmentPage(text.lines(), config))
        }
        val merged = mergeShort(rawSegments, config.minChunkChars)
        log.info("[{}] heuristic split: {} pages → {} raw → {} chunks",
            variant, pages.size, rawSegments.size, merged.size)
        return merged.filter { it.isNotBlank() }
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
