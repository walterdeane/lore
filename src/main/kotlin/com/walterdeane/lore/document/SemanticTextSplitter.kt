package com.walterdeane.lore.document

import com.walterdeane.lore.model.SourceType
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.stereotype.Component
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * `windowSize`/`breakpointPercentile` defaults come from sweeping a real cookbook EPUB (see
 * `SemanticTextSplitterSmokeTest`, `WINDOW_SIZES`/`PERCENTILES` env vars): p=0.95 badly
 * under-segmented (5 chunks over 200 paragraphs, one 24K-char chunk); p=0.85 with windowSize=2 gave
 * the best count/size balance (14 chunks, avg 2550 chars) across all three window sizes tried.
 * `maxChunkChars` exists because no threshold eliminated one failure mode: a long span of
 * internally-uniform content (e.g. a unit-conversion table) never triggers a topic-shift signal, so
 * breakpoint detection alone let a single chunk run to 24-29K chars regardless of tuning — that's
 * handled by falling back to [TokenTextSplitter] on any chunk over the cap, not by the percentile.
 */
data class SemanticConfig(
    val windowSize: Int = 2,
    val minChunkChars: Int = 300,
    val maxChunkChars: Int = 4000,
    val breakpointPercentile: Double = 0.85,
)

/**
 * Entry point for the SEMANTIC chunking strategy: converts a source file (EPUB/PDF) to markdown via
 * [EpubMarkdownParser]/[PdfMarkdownParser] for its paragraph structure (both now emit blank-line-
 * delimited paragraphs), embeds a sliding window around each paragraph with [embeddingModel], and
 * cuts wherever consecutive-window cosine distance spikes above this document's own
 * [SemanticConfig.breakpointPercentile]. Unlike [StructuralTextSplitter], there's no separate
 * fallback algorithm when markdown parsing fails — the same paragraph+embedding logic just runs
 * over paragraphs split from Tika's raw per-page text instead, which is one reason semantic chunking
 * is simpler to fall back safely than heading-detection heuristics are.
 */
@Component
class SemanticTextSplitter(
    private val epubMarkdownParser: EpubMarkdownParser,
    private val pdfMarkdownParser: PdfMarkdownParser,
    private val embeddingModel: EmbeddingModel,
    private val chunkingProperties: ChunkingProperties,
) {

    private val log = LoggerFactory.getLogger(SemanticTextSplitter::class.java)
    private val oversizedChunkSplitter = TokenTextSplitter.builder().withChunkSize(chunkingProperties.tokenChunkSize).build()

    /**
     * [pages] is a supplier, not a value — see [StructuralTextSplitter.split]'s doc for why: it's
     * only invoked in the fallback branch below, so a Tika extraction that would fail on this file
     * (e.g. malformed XHTML Tika's strict parser rejects) never runs unless the markdown path
     * (Jsoup, lenient) actually needs the fallback.
     */
    fun split(
        sourcePath: String,
        sourceType: SourceType,
        pages: () -> List<Document>,
        config: SemanticConfig = SemanticConfig(),
    ): List<Document> {
        val markdown = when (sourceType) {
            SourceType.EPUB -> epubMarkdownParser.parse(sourcePath)
            SourceType.PDF -> pdfMarkdownParser.parse(sourcePath)
        }

        val paragraphs = if (markdown.isNotBlank()) {
            extractParagraphs(markdown)
        } else {
            log.warn("markdown parser returned empty for {}, splitting paragraphs from raw page text", sourceType)
            extractParagraphs(pages().joinToString("\n\n") { (it.text ?: "").trim() })
        }

        if (paragraphs.size < 3) {
            log.info("only {} paragraph(s) found, returning as a single chunk", paragraphs.size)
            return if (paragraphs.isEmpty()) emptyList() else listOf(Document(paragraphs.joinToString("\n\n")))
        }

        val windows = buildWindows(paragraphs, config.windowSize)
        // One embed() call per window rather than a single batched embed(List<String>): the local
        // Ollama runner serves embeddings from a single-slot process, and handing it a whole book's
        // worth of windows in one request timed out mid-processing during testing (see
        // SemanticTextSplitterSmokeTest). Sequential calls are slower but reliable at book scale.
        val embeddings = windows.map { embeddingModel.embed(it) }
        val distances = (0 until embeddings.size - 1).map { i -> 1.0 - cosineSimilarity(embeddings[i], embeddings[i + 1]) }
        val threshold = percentile(distances, config.breakpointPercentile)

        val chunks = groupAtBreakpoints(paragraphs, distances, threshold)
        val merged = mergeShort(chunks, config.minChunkChars)
        val sized = capOversizedChunks(merged, config.maxChunkChars)
        log.info("semantic split: {} paragraphs → {} breakpoints (threshold {}) → {} chunks → {} after size cap",
            paragraphs.size, distances.count { it > threshold }, threshold, merged.size, sized.size)
        return sized.map { Document(it) }
    }

    /**
     * Breakpoint detection alone can't bound chunk size — a long span of internally-similar content
     * (e.g. a conversion table) never triggers a topic shift. Any chunk over [maxChars] gets handed
     * to [TokenTextSplitter] instead, the same splitter the TOKEN strategy uses, as a size backstop.
     */
    internal fun capOversizedChunks(chunks: List<String>, maxChars: Int): List<String> =
        chunks.flatMap { text ->
            if (text.length > maxChars) {
                oversizedChunkSplitter.split(Document(text)).mapNotNull { it.text }
            } else {
                listOf(text)
            }
        }

    /** Splits on blank lines — both markdown parsers now emit `\n\n` between paragraphs and before headings. */
    internal fun extractParagraphs(text: String): List<String> =
        text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotBlank() }

    /** Each paragraph's embedding target is itself plus [windowSize] neighbors on each side, joined by a space. */
    internal fun buildWindows(paragraphs: List<String>, windowSize: Int): List<String> =
        paragraphs.indices.map { i ->
            val from = (i - windowSize).coerceAtLeast(0)
            val to = (i + windowSize).coerceAtMost(paragraphs.size - 1)
            paragraphs.subList(from, to + 1).joinToString(" ")
        }

    internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    /** Nearest-rank percentile over [values]; a document with near-identical paragraphs throughout yields no breakpoints. */
    internal fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.MAX_VALUE
        val sorted = values.sorted()
        val index = (p * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** Cuts after paragraph [i] wherever the window-to-window distance crosses [threshold]. */

    internal fun groupAtBreakpoints(paragraphs: List<String>, distances: List<Double>, threshold: Double): List<String> {
        val chunks = mutableListOf<String>()
        var current = mutableListOf(paragraphs[0])
        for (i in distances.indices) {
            if (distances[i] > threshold) {
                chunks.add(current.joinToString("\n\n"))
                current = mutableListOf(paragraphs[i + 1])
            } else {
                current.add(paragraphs[i + 1])
            }
        }
        chunks.add(current.joinToString("\n\n"))
        return chunks
    }

    /** Same under-sized-chunk merge as [StructuralTextSplitter.mergeShort] and [MarkdownChunker.mergeShort]. */
    internal fun mergeShort(segments: List<String>, minChars: Int): List<String> {
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