package com.walterdeane.lore.document

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File

private data class PdfLine(val text: String, val fontSize: Float)

/**
 * Converts a PDF to markdown despite PDFs having no semantic heading tags: it tracks the rendered
 * glyph height of each line via [FontTrackingStripper] and infers heading levels from font-size
 * ratios relative to the document's median line. Rough, but good enough for
 * [StructuralTextSplitter]/[MarkdownChunker] to split on the result.
 */
@Component
class PdfMarkdownParser {

    private val log = LoggerFactory.getLogger(PdfMarkdownParser::class.java)

    fun parse(pdfPath: String): String {
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

    private inner class FontTrackingStripper : PDFTextStripper() {

        val lines = mutableListOf<PdfLine>()
        private val buf = StringBuilder()
        private var maxSize = 0f

        override fun writeString(text: String, positions: List<TextPosition>) {
            for (p in positions) {
                // getHeight() returns the actual rendered glyph height in points —
                // a reliable proxy for visual font size across PDF encodings.
                val h = p.height
                if (h > maxSize) maxSize = h
            }
            buf.append(text)
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
            if (text.isNotBlank()) lines.add(PdfLine(text, maxSize))
            buf.clear()
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
                    else -> sb.append("${line.text}\n")
                }
            }
            return sb.toString().trim()
        }
    }
}
