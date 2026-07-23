package com.walterdeane.lore.document

import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpubMarkdownParserTest {

    private val parser = EpubMarkdownParser()

    // --- parse(): full EPUB round trip, to exercise the CSS-resolution + heading-detection wiring ---

    /** Builds a minimal single-chapter EPUB with an optional stylesheet, and returns its path. */
    private fun epubFile(chapterHtml: String, css: String? = null): String {
        val file = File.createTempFile("epub-test", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                   <container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                   <package><manifest><item id="ch1" href="chapter1.html"/></manifest>
                   <spine><itemref idref="ch1"/></spine></package>""",
            )
            entry("OEBPS/chapter1.html", chapterHtml)
            if (css != null) entry("OEBPS/stylesheet.css", css)
        }
        return file.absolutePath
    }

    @Test
    fun `real heading tags still become markdown headings`() {
        val html = """<html><body><h2>The Characters of the Story</h2><p>Some body text.</p></body></html>"""
        val markdown = parser.parse(epubFile(html))
        assertTrue(markdown.contains("## The Characters of the Story"))
    }

    @Test
    fun `a paragraph entirely wrapped in a bold CSS class is promoted to a heading`() {
        val css = ".bold1 { font-weight: bold }"
        val html = """
            <html><head><link rel="stylesheet" href="stylesheet.css"/></head>
            <body>
            <h2>Two Systems</h2>
            <p>Ordinary paragraph text that should stay as body text.</p>
            <p class="calibre25"><span class="bold1">Speaking of System 1 and System 2</span></p>
            <p>More ordinary body text after the subhead.</p>
            </body></html>
        """.trimIndent()

        val markdown = parser.parse(epubFile(html, css))

        assertTrue(
            markdown.contains("## Speaking of System 1 and System 2"),
            "expected the bold-only paragraph to be promoted to a heading, got:\n$markdown",
        )
        assertTrue(markdown.contains("Ordinary paragraph text that should stay as body text."))
    }

    @Test
    fun `a paragraph wrapped in a real bold tag is promoted even with no stylesheet`() {
        val html = """<html><body><h2>Chapter</h2><p><strong>Speaking of System 1 and System 2</strong></p></body></html>"""
        val markdown = parser.parse(epubFile(html))
        assertTrue(markdown.contains("## Speaking of System 1 and System 2"))
    }

    @Test
    fun `bold detection actually moves the STRUCTURAL chunk boundary`() {
        val css = ".bold1 { font-weight: bold }"
        val html = """
            <html><head><link rel="stylesheet" href="stylesheet.css"/></head>
            <body>
            <h2>The Characters of the Story</h2>
            <p>Psychologists have been intensely interested for several decades in two systems of thinking.</p>
            <p class="calibre25"><span class="bold1">Speaking of System 1 and System 2</span></p>
            <p>System 1 operates automatically and quickly, with little or no effort.</p>
            </body></html>
        """.trimIndent()

        val markdown = parser.parse(epubFile(html, css))
        val chunks = MarkdownChunker().split(markdown, preferredLevel = 2, minChunkChars = 10)

        assertEquals(2, chunks.size, "expected the bold subhead to start its own chunk, got:\n$chunks")
        assertTrue(chunks[0].startsWith("## The Characters of the Story"))
        assertTrue(chunks[1].startsWith("## Speaking of System 1 and System 2"))
    }

    // --- extractBoldClassNames: CSS scanning in isolation ------------------------------------------

    @Test
    fun `extractBoldClassNames finds simple bold class rules`() {
        val css = """
            .bold1 { font-weight: bold }
            .bold2 { font-weight:bold; }
            .italic1 { font-style: italic }
            .heavy { font-weight: 700 }
        """.trimIndent()
        assertEquals(setOf("bold1", "bold2", "heavy"), parser.extractBoldClassNames(css))
    }

    @Test
    fun `extractBoldClassNames ignores non-bold weights and non-class selectors`() {
        val css = """
            .normal { font-weight: normal }
            .light { font-weight: 300 }
            p { font-weight: bold }
        """.trimIndent()
        assertEquals(emptySet(), parser.extractBoldClassNames(css))
    }

    @Test
    fun `extractBoldClassNames handles a shared selector list`() {
        val css = ".bold1, .bold2, .bold3 { font-weight: bold }"
        assertEquals(setOf("bold1", "bold2", "bold3"), parser.extractBoldClassNames(css))
    }

    // --- looksLikeBoldHeading: the shape heuristic in isolation ------------------------------------

    @Test
    fun `looksLikeBoldHeading rejects a full sentence even if entirely bold`() {
        val p = Jsoup.parse("<p><strong>This is a full sentence that happens to be bold.</strong></p>").selectFirst("p")!!
        assertFalse(parser.looksLikeBoldHeading(p, p.text().trim(), emptySet()))
    }

    @Test
    fun `looksLikeBoldHeading rejects a paragraph that is only partially bold`() {
        val p = Jsoup.parse("<p><strong>Partly bold</strong> and partly not</p>").selectFirst("p")!!
        assertFalse(parser.looksLikeBoldHeading(p, p.text().trim(), emptySet()))
    }

    @Test
    fun `looksLikeBoldHeading accepts a short bold title-like phrase`() {
        val p = Jsoup.parse("<p><span class=\"bold1\">Neglect of Ambiguity and Suppression of Doubt</span></p>").selectFirst("p")!!
        assertTrue(parser.looksLikeBoldHeading(p, p.text().trim(), setOf("bold1")))
    }

    @Test
    fun `looksLikeBoldHeading rejects an oversized bold block`() {
        val longText = "Word ".repeat(30).trim()
        val p = Jsoup.parse("<p><strong>$longText</strong></p>").selectFirst("p")!!
        assertFalse(parser.looksLikeBoldHeading(p, p.text().trim(), emptySet()))
    }
}