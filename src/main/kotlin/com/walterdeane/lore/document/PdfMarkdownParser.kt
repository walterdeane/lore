package com.walterdeane.lore.document

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.slf4j.LoggerFactory
import org.springframework.ai.reader.pdf.config.ParagraphManager
import org.springframework.ai.reader.pdf.layout.PDFLayoutTextStripperByArea
import org.springframework.stereotype.Component
import java.awt.Rectangle
import java.io.File

private data class PdfLine(val text: String, val fontSize: Float, val startsNewParagraph: Boolean)

private data class Glyph(val x: Float, val y: Float, val width: Float)

/** [yStart]/[yEnd] bound just the lines that actually corroborated the column gap — not the whole page/region. */
private data class ColumnBand(val splitX: Int, val yStart: Float, val yEnd: Float)

/**
 * Converts a PDF to markdown. Prefers the PDF's own embedded outline/TOC (real document structure —
 * see [ParagraphPdfDocumentReader]) when one exists, since that's far more reliable than guessing;
 * many PDFs have no outline, though, so this falls back to [FontTrackingStripper], which tracks the
 * rendered glyph height of each line and infers heading levels from font-size ratios relative to the
 * document's median line — rougher, but a PDF with no outline has no better signal to use. Either
 * way the output is markdown good enough for [StructuralTextSplitter]/[MarkdownChunker]/
 * [SemanticTextSplitter] to split on.
 */
@Component
class PdfMarkdownParser {

    private val log = LoggerFactory.getLogger(PdfMarkdownParser::class.java)

    companion object {
        /** Regions shorter than this (points) can't contain enough lines to confirm a recurring column gap. */
        private const val MIN_COLUMN_REGION_HEIGHT = 80
        private const val LINE_Y_TOLERANCE = 3f
        private const val MIN_GAP_WIDTH = 15f
        private const val MIN_CORROBORATING_LINES = 4
        private const val MIN_CORROBORATING_LINE_FRACTION = 0.15
        private const val GAP_BUCKET_SIZE = 10f
        // Margin added above/below a detected column band so its own boundary lines aren't clipped
        // by the sub-rectangle split — comfortably more than the ~3pt line-grouping tolerance but
        // well under a typical line's height, so it can't bleed into an adjacent real line.
        private const val BAND_Y_PADDING = 8f
    }

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
     * [ParagraphManager] throws at construction time when the PDF has no embedded outline/TOC
     * (`Assert.notNull` on `getDocumentOutline()`), so a null return here — not an exception — is
     * the normal, expected signal to fall back to the font-size heuristic.
     *
     * Text extraction is hand-rolled here (using [ParagraphManager] directly for section
     * boundaries) rather than delegating to Spring AI's `ParagraphPdfDocumentReader.get()`, because
     * that class's `getTextBetweenParagraphs` reads each section through a single full-page-width
     * `PDFLayoutTextStripperByArea` region. On a page with a genuine two-column layout (body text
     * beside a sidebar/callout box), that stripper has no notion of columns: it reconstructs lines
     * purely by y-position across the *entire* region width, so text from both columns at the same
     * height gets zippered onto one output line — a real cookbook page teaching a sous-vide setup
     * came out as "HOW TO COOK connective tissue, and/or tendons need low-temperature" instead of
     * two coherent sentences. [extractPageRegionColumnAware] detects a persistent horizontal gap
     * (the same gap recurring across many lines, not a one-off short line) and, only when confirmed,
     * extracts the two sides as separate regions instead of one — ordinary single-column pages are
     * extracted exactly as before.
     */
    internal fun parseFromOutline(pdfPath: String): String? {
        return try {
            Loader.loadPDF(File(pdfPath)).use { document ->
                val manager = ParagraphManager(document)
                val paragraphs = manager.flatten()
                if (paragraphs.isEmpty()) return null
                val sections = paragraphs.mapIndexed { i, p ->
                    val next = paragraphs.getOrElse(i + 1) { p }
                    p to extractSectionText(document, p, next)
                }.filter { (_, rawBody) -> rawBody.isNotBlank() }
                if (sections.isEmpty()) return null
                val runningHeaders = detectRunningHeaders(sections.map { (_, rawBody) -> rawBody })
                sections.joinToString("\n\n") { (p, rawBody) ->
                    val headingMarker = "#".repeat((p.level() + 2).coerceAtMost(6))
                    val body = cleanLayoutExtractedBody(rawBody, runningHeaders)
                    if (body.isBlank()) "$headingMarker ${p.title()}" else "$headingMarker ${p.title()}\n\n$body"
                }
            }
        } catch (e: Exception) {
            log.info("PDF {} has no usable outline/TOC ({}), falling back to font-size heuristic", pdfPath, e.message)
            null
        }
    }

    /** Replicates the page/y-range math `ParagraphPdfDocumentReader.getTextBetweenParagraphs` uses, per page. */
    private fun extractSectionText(document: PDDocument, from: ParagraphManager.Paragraph, to: ParagraphManager.Paragraph): String {
        if (from.startPageNumber() < 1) return ""
        val startPage = from.startPageNumber() - 1
        var endPage = to.startPageNumber() - 1
        if (from === to || endPage < startPage) endPage = startPage

        val sb = StringBuilder()
        for (pageNumber in startPage..endPage) {
            val page = document.getPage(pageNumber)
            val pageHeight = page.mediaBox.height

            val fromPos = from.position()
            val toPos = if (from !== to) to.position() else 0

            val x = page.mediaBox.lowerLeftX.toInt()
            val w = page.mediaBox.width.toInt()
            val (y, h) = when {
                pageNumber == startPage && pageNumber == endPage -> toPos to (fromPos - toPos)
                pageNumber == startPage -> 0 to fromPos
                pageNumber == endPage -> toPos to (pageHeight.toInt() - toPos)
                else -> 0 to pageHeight.toInt()
            }
            val safeH = h.coerceAtLeast(0)
            if (safeH == 0) continue
            val text = extractPageRegionColumnAware(document, pageNumber, x, y, w, safeH)
            if (text.isNotBlank()) sb.append(text)
        }
        return sb.toString()
    }

    /**
     * Extracts a page region as a single column unless [detectColumnBand] confirms a genuine,
     * recurring column gap somewhere within it — in which case only that band (not the whole region)
     * gets split into two narrower regions. A page can be full-width prose above and below a boxed
     * two-column diagram/callout; applying one split-x to the *entire* region would bisect the
     * full-width text too, at an x-coordinate that isn't a real boundary there — confirmed on a real
     * page where an intro paragraph above a callout box, previously extracted correctly, came out cut
     * into two disconnected halves once a page-wide split was applied. Content before and after the
     * band is extracted normally, in its original position; only the band itself reads left-column-
     * then-right-column.
     */
    private fun extractPageRegionColumnAware(document: PDDocument, pageNumber: Int, x: Int, y: Int, w: Int, h: Int): String {
        val page = document.getPage(pageNumber)
        val glyphs = collectGlyphs(document, pageNumber, y, h)
        val band = detectColumnBand(glyphs, x, w, h) ?: return extractRegion(page, Rectangle(x, y, w, h))

        val bandTop = (band.yStart - BAND_Y_PADDING).coerceAtLeast(y.toFloat())
        val bandBottom = (band.yEnd + BAND_Y_PADDING).coerceAtMost((y + h).toFloat())
        val bandHeight = (bandBottom - bandTop).toInt().coerceAtLeast(1)

        val pieces = mutableListOf<String>()
        if (bandTop > y) pieces += extractRegion(page, Rectangle(x, y, w, (bandTop - y).toInt()))
        pieces += extractRegion(page, Rectangle(x, bandTop.toInt(), band.splitX - x, bandHeight))
        pieces += extractRegion(page, Rectangle(band.splitX, bandTop.toInt(), x + w - band.splitX, bandHeight))
        if (bandBottom < y + h) pieces += extractRegion(page, Rectangle(x, bandBottom.toInt(), w, (y + h - bandBottom).toInt()))

        return pieces.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun extractRegion(page: PDPage, rect: Rectangle): String {
        val stripper = PDFLayoutTextStripperByArea()
        stripper.setSortByPosition(true)
        stripper.addRegion("region", rect)
        stripper.extractRegions(page)
        val text = stripper.getTextForRegion("region") ?: ""
        stripper.removeRegion("region")
        return text
    }

    /** Collects glyph positions for one page, restricted to the target y-range, in the same top-down coordinate space [PDFLayoutTextStripperByArea]'s regions use. */
    private fun collectGlyphs(document: PDDocument, pageNumber: Int, y: Int, h: Int): List<Glyph> {
        val glyphs = mutableListOf<Glyph>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                for (p in positions) {
                    val py = p.yDirAdj
                    if (py >= y && py <= y + h) glyphs.add(Glyph(p.xDirAdj, py, p.widthDirAdj))
                }
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageNumber + 1
        stripper.endPage = pageNumber + 1
        stripper.getText(document)
        return glyphs
    }

    /**
     * Looks for a vertical gap that recurs across many distinct lines within the region — a signature
     * of two side-by-side columns, as opposed to a one-off short line or a paragraph's ragged right
     * edge. Requires the gap to sit away from the region's own edges (10%-90% of its width) and to be
     * corroborated by enough lines that it's clearly structural, not coincidental; regions shorter
     * than [MIN_COLUMN_REGION_HEIGHT] are skipped since a handful of lines can't confirm a pattern.
     *
     * Buckets on the gap's right edge — the left-hand edge of whatever sits on the far side of the
     * gap — rather than the gap's midpoint. In left-to-right text, a text block's left edge is where
     * it starts a fresh line and stays put (a rigid column/box boundary); a block's right edge just
     * marks wherever that particular line happened to end (justified or ragged, so it wanders even
     * within a genuine column). A real cookbook page confirmed this: a numbered image-caption list
     * had its left edge locked at the same x on every line while its right edge varied by ~20pt —
     * bucketing on the midpoint diluted the vote below the corroboration threshold and missed a real
     * column; bucketing on the right-hand block's left edge instead catches it.
     */
    private fun detectColumnBand(glyphs: List<Glyph>, x: Int, w: Int, h: Int): ColumnBand? {
        if (h < MIN_COLUMN_REGION_HEIGHT || glyphs.size < 20) return null

        val lines = glyphs.sortedBy { it.y }
            .fold(mutableListOf<MutableList<Glyph>>()) { acc, glyph ->
                val current = acc.lastOrNull()
                if (current != null && glyph.y - current.last().y <= LINE_Y_TOLERANCE) current.add(glyph)
                else acc.add(mutableListOf(glyph))
                acc
            }
        if (lines.size < MIN_CORROBORATING_LINES) return null

        // rightEdge: this gap's right-hand edge (see doc above). lineY: which line the gap came from,
        // so once the winning bucket is picked, its line range becomes the band's y-extent — not the
        // whole region.
        data class Gap(val rightEdge: Float, val lineY: Float)
        val gaps = mutableListOf<Gap>()
        for (line in lines) {
            val sorted = line.sortedBy { it.x }
            val lineY = sorted.first().y
            for (i in 0 until sorted.size - 1) {
                val gapStart = sorted[i].x + sorted[i].width
                val gapEnd = sorted[i + 1].x
                if (gapEnd - gapStart >= MIN_GAP_WIDTH) gaps.add(Gap(gapEnd, lineY))
            }
        }

        val minX = x + w * 0.1f
        val maxX = x + w * 0.9f
        val buckets = gaps.filter { it.rightEdge in minX..maxX }
            .groupBy { (it.rightEdge / GAP_BUCKET_SIZE).toInt() }
        val bestBucket = buckets.maxByOrNull { (_, g) -> g.size } ?: return null
        val requiredLines = maxOf(MIN_CORROBORATING_LINES, (lines.size * MIN_CORROBORATING_LINE_FRACTION).toInt())
        if (bestBucket.value.size < requiredLines) return null

        val splitX = bestBucket.value.map { it.rightEdge }.average().toInt()
        val yStart = bestBucket.value.minOf { it.lineY }
        val yEnd = bestBucket.value.maxOf { it.lineY }
        return ColumnBand(splitX, yStart, yEnd)
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
     * lines downstream (see [SemanticTextSplitter.extractParagraphs]).
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
