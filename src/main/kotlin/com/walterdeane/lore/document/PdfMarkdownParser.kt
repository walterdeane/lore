package com.walterdeane.lore.document

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.slf4j.LoggerFactory
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader
import org.springframework.core.io.FileSystemResource
import org.springframework.stereotype.Component
import java.io.File

private data class PdfLine(val text: String, val fontSize: Float, val startsNewParagraph: Boolean)

/**
 * Converts a PDF to markdown. Prefers the PDF's own embedded outline/TOC (real document structure —
 * see [ParagraphPdfDocumentReader]) when one exists, since that's far more reliable than guessing;
 * many PDFs have no outline, though, so this falls back to [FontTrackingStripper], which tracks the
 * rendered glyph height of each line and infers heading levels from font-size ratios relative to the
 * document's median line — rougher, but a PDF with no outline has no better signal to use. Either
 * way the output is markdown good enough for [StructuralTextSplitter]/[MarkdownChunker]/
 * [SymanticTextSplitter] to split on.
 */
@Component
class PdfMarkdownParser {

    private val log = LoggerFactory.getLogger(PdfMarkdownParser::class.java)

    fun parse(pdfPath: String): String {
        parseFromOutline(pdfPath)?.let { return it }

        return try {
            Loader.loadPDF(File(pdfPath)).use { doc ->
                val stripper = FontTrackingStripper()
                stripper.setSortByPosition(true)
                stripper.getText(doc) // drives the write* callbacks
                stripper.buildMarkdown()
            }
        } catch (e: Exception) {
            log.warn("PDF markdown parsing failed for {}: {}", pdfPath, e.message)
            ""
        }
    }

    /**
     * [ParagraphPdfDocumentReader] throws at construction time when the PDF has no embedded
     * outline/TOC (`Assert.notNull` on `getDocumentOutline()`), so a null return here — not an
     * exception — is the normal, expected signal to fall back to the font-size heuristic.
     */
    internal fun parseFromOutline(pdfPath: String): String? {
        return try {
            val paragraphs = ParagraphPdfDocumentReader(FileSystemResource(pdfPath)).get()
            if (paragraphs.isEmpty()) return null
            val runningHeaders = detectRunningHeaders(paragraphs.map { it.text ?: "" })
            paragraphs.joinToString("\n\n") { doc ->
                val level = (doc.metadata["level"] as? Int) ?: 0
                val headingMarker = "#".repeat((level + 2).coerceAtMost(6))
                val title = doc.metadata["title"] as? String ?: ""
                val body = cleanLayoutExtractedBody(doc.text ?: "", runningHeaders)
                if (body.isBlank()) "$headingMarker $title" else "$headingMarker $title\n\n$body"
            }
        } catch (e: Exception) {
            log.info("PDF {} has no usable outline/TOC ({}), falling back to font-size heuristic", pdfPath, e.message)
            null
        }
    }

    /**
     * A running header/footer (book title, chapter label — e.g. "Great Meat", "chapter 1 Beef")
     * gets reprinted on nearly every page, so it shows up in nearly every outline section's
     * extracted text; real content doesn't repeat verbatim across a large fraction of the book's
     * distinct sections the way page furniture does. [minSectionFraction] is deliberately high so a
     * coincidentally-repeated short phrase (e.g. "Serves 4" across a few recipes) isn't mistaken for
     * one — a true running header should clear it by a wide margin since it's on every single page.
     *
     * Some books bake the page number directly into the header line (e.g. "THE MEAT HOOK MEAT BOOK
     * 14" on page 14, "...15" on page 15) — every occurrence is then a distinct string, so none of
     * them individually clear the frequency bar and the header leaks straight into body text
     * mid-sentence. [normalizeForHeaderDetection] strips a trailing page number before counting, but
     * only when the remaining phrase is substantial (3+ words); "Serves 4" isn't collapsed to
     * "Serves" and confused with a title, since that's short enough to be real content.
     */
    internal fun detectRunningHeaders(
        bodies: List<String>,
        maxHeaderLength: Int = 60,
        minSectionFraction: Double = 0.3,
    ): Set<String> = sectionCounts(bodies, maxHeaderLength)
        .filterValues { it >= (bodies.size * minSectionFraction).coerceAtLeast(3.0) }
        .keys

    /** Exposed separately from [detectRunningHeaders] so tests can inspect raw counts, not just the final filtered set. */
    internal fun sectionCounts(bodies: List<String>, maxHeaderLength: Int = 60): Map<String, Int> {
        val sectionCounts = mutableMapOf<String, Int>()
        for (body in bodies) {
            val linesInSection = body.lines().map { normalizeForHeaderDetection(it.trim()) }
                .filter { it.isNotBlank() && it.length <= maxHeaderLength }
                .toSet()
            for (line in linesInSection) sectionCounts.merge(line, 1, Int::plus)
        }
        return sectionCounts
    }

    /**
     * The layout stripper's column-based extraction leaves irregular internal spacing on the same
     * logical line — e.g. "THE MEAT  HOOK  MEAT  BOOK" vs "THE  MEAT  HOOK  MEAT  BOOK" — which,
     * left uncollapsed, fragments one running header into multiple distinct count keys and can keep
     * every variant individually under the frequency threshold. Collapsing runs of spaces first
     * (before the trailing-page-number strip) is what makes those variants compare equal.
     */
    internal fun normalizeForHeaderDetection(line: String): String {
        val collapsed = line.replace(Regex(" {2,}"), " ").trim()
        val stripped = collapsed.replace(Regex("""\s+\d{1,4}$"""), "").trim()
        val wordCount = stripped.split(Regex("""\s+""")).count { it.isNotBlank() }
        return if (stripped.length >= 12 && wordCount >= 3) stripped else collapsed
    }

    /**
     * [ParagraphPdfDocumentReader] extracts text via PDFBox's region-based
     * `PDFLayoutTextStripperByArea`, which (unlike [FontTrackingStripper]'s line-by-line callbacks)
     * carries over print-production page furniture — bare page numbers, InDesign export filenames
     * (`012-015_30591.indd`), press-run stamps (`(Fogra 39)Job:05-30591...`), and repeated running
     * headers ([detectRunningHeaders]) — and pads short lines with runs of spaces to preserve column
     * alignment. Left alone, each of those becomes its own spurious "paragraph" once split on blank
     * lines downstream (see [SymanticTextSplitter.extractParagraphs]).
     */
    private fun cleanLayoutExtractedBody(text: String, runningHeaders: Set<String> = emptySet()): String =
        text.lines()
            .filterNot { isPageFurniture(it) || normalizeForHeaderDetection(it.trim()) in runningHeaders }
            .joinToString("\n") { it.replace(Regex(" {2,}"), " ").trim() }
            .trim()

    private fun isPageFurniture(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.matches(Regex("""\d{1,4}"""))) return true
        if (trimmed.contains(".indd", ignoreCase = true)) return true
        if (trimmed.contains("Fogra", ignoreCase = true)) return true
        // Press-run stamps like "0 6 - C 6 9 5 0 6 #175 Dtp:225 Page:11" — letter-tracked job
        // codes followed by a "Dtp:"/"Page:" plate reference.
        if (Regex("""(?i)\bDtp:\d+\b""").containsMatchIn(trimmed)) return true
        if (Regex("""(?i)\bPage:\d+\b""").containsMatchIn(trimmed)) return true
        // Chapter-label running headers (e.g. "chapter 1 Beef") repeat on every page within their
        // own chapter but not the rest of the book, so detectRunningHeaders' book-wide frequency
        // threshold misses them — this pattern is specific enough that real content is very
        // unlikely to open a line with it.
        if (Regex("""(?i)^chapter\s+\d+\b""").containsMatchIn(trimmed)) return true
        return false
    }

    private inner class FontTrackingStripper : PDFTextStripper() {

        val lines = mutableListOf<PdfLine>()
        private val buf = StringBuilder()
        private var maxSize = 0f
        private var pendingParagraphBreak: Boolean = false

        override fun writeString(text: String, positions: List<TextPosition>) {
            for (p in positions) {
                // getHeight() returns the actual rendered glyph height in points —
                // a reliable proxy for visual font size across PDF encodings.
                val h = p.height
                if (h > maxSize) maxSize = h
            }
            buf.append(text)
        }

        override fun getParagraphStart(): String {
            pendingParagraphBreak = true
            return super.getParagraphStart()
        }

        override fun writeLineSeparator() {
            flush()
            super.writeLineSeparator()
        }

        override fun writePageEnd() {
            flush()
            super.writePageEnd()
        }

        private fun flush() {
            val text = buf.toString().trim()
            if (text.isNotBlank()) lines.add(PdfLine(text, maxSize, this.pendingParagraphBreak))
            buf.clear()
            this.pendingParagraphBreak = false
            maxSize = 0f
        }

        /** Maps each line's font-size ratio to the document's median size onto a heading level (or body text). */
        fun buildMarkdown(): String {
            if (lines.isEmpty()) return ""
            val sizes = lines.map { it.fontSize }.filter { it > 0f }.sorted()
            if (sizes.isEmpty()) return lines.joinToString("\n") { it.text }
            val median = sizes[sizes.size / 2]
            val sb = StringBuilder()
            for (line in lines) {
                val ratio = if (median > 0f) line.fontSize / median else 1f
                when {
                    ratio > 1.8f -> sb.append("\n\n# ${line.text}\n\n")
                    ratio > 1.4f -> sb.append("\n\n## ${line.text}\n\n")
                    ratio > 1.15f -> sb.append("\n\n### ${line.text}\n\n")
                    else -> if (line.startsNewParagraph) sb.append("\n\n${line.text}\n")
                            else  sb.append("${line.text}\n")
                }
            }
            return sb.toString().trim()
        }
    }
}
