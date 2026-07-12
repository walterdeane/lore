package com.walterdeane.lore.document

import com.walterdeane.lore.model.SourceType
import com.walterdeane.lore.model.StructuralVariant
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuralTextSplitterTest {

    private val splitter = StructuralTextSplitter(EpubMarkdownParser(), PdfMarkdownParser(), MarkdownChunker())

    // --- configFor: per-variant boundary tuning -----------------------------------------------

    @Test
    fun `GENERIC config allows numbered sections and has no excluded headers`() {
        val config = StructuralTextSplitter.configFor(StructuralVariant.GENERIC)
        assertTrue(config.allowNumberedSections)
        assertEquals(emptySet(), config.excludedHeaders)
        assertEquals(2, config.markdownHeadingLevel)
    }

    @Test
    fun `COOKBOOK config disallows numbered sections and excludes recipe sub-headers`() {
        val config = StructuralTextSplitter.configFor(StructuralVariant.COOKBOOK)
        assertFalse(config.allowNumberedSections)
        assertTrue("ingredients" in config.excludedHeaders)
        assertTrue("method" in config.excludedHeaders)
    }

    @Test
    fun `ACADEMIC config raises title length and minimum chunk size`() {
        val config = StructuralTextSplitter.configFor(StructuralVariant.ACADEMIC)
        assertEquals(120, config.maxTitleLength)
        assertEquals(300, config.minChunkChars)
        assertTrue(config.allowNumberedSections)
        assertEquals(emptySet(), config.excludedHeaders)
    }

    // --- couldBeHeading: cheap shape-based heuristic ------------------------------------------

    private val genericConfig = StructuralTextSplitter.configFor(StructuralVariant.GENERIC)
    private val cookbookConfig = StructuralTextSplitter.configFor(StructuralVariant.COOKBOOK)

    @Test
    fun `couldBeHeading rejects lines that are too short or too long`() {
        assertFalse(splitter.couldBeHeading("Hi", genericConfig))
        assertFalse(splitter.couldBeHeading("A".repeat(genericConfig.maxTitleLength + 1), genericConfig))
    }

    @Test
    fun `couldBeHeading rejects lines ending in a period, comma, semicolon, or colon`() {
        assertFalse(splitter.couldBeHeading("This looks like a sentence.", genericConfig))
        assertFalse(splitter.couldBeHeading("A trailing clause,", genericConfig))
        assertFalse(splitter.couldBeHeading("A subtitle:", genericConfig))
    }

    @Test
    fun `couldBeHeading does not reject trailing question or exclamation marks`() {
        // Only ./,/;/: are treated as sentence-ending for this heuristic — a heading-shaped line
        // ending in ? or ! still qualifies.
        assertTrue(splitter.couldBeHeading("Is This A Heading?", genericConfig))
    }

    @Test
    fun `couldBeHeading rejects excluded headers, exact or as a prefix`() {
        assertFalse(splitter.couldBeHeading("ingredients", cookbookConfig))
        assertFalse(splitter.couldBeHeading("ingredients for the sauce", cookbookConfig))
    }

    @Test
    fun `couldBeHeading accepts a numbered section only when allowNumberedSections is true`() {
        assertTrue(splitter.couldBeHeading("1.2 Background", genericConfig))
        assertFalse(splitter.couldBeHeading("1.2 background", cookbookConfig)) // lowercase, not capitalized either
    }

    @Test
    fun `couldBeHeading accepts a short capitalized line`() {
        assertTrue(splitter.couldBeHeading("Yakitori Grilled Chicken", genericConfig))
        assertFalse(splitter.couldBeHeading("lowercase title", genericConfig))
    }

    // --- isHeadingInContext: couldBeHeading plus surrounding-line context ---------------------

    @Test
    fun `isHeadingInContext accepts a heading-shaped line preceded by a blank line`() {
        val lines = listOf("", "A Heading")
        assertTrue(splitter.isHeadingInContext("A Heading", lines, 1, genericConfig))
    }

    @Test
    fun `isHeadingInContext accepts a heading-shaped line preceded by a completed sentence`() {
        val lines = listOf("This is a full preceding sentence that ends properly.", "A Heading")
        assertTrue(splitter.isHeadingInContext("A Heading", lines, 1, genericConfig))
    }

    @Test
    fun `isHeadingInContext rejects a heading-shaped line preceded by a short unfinished line`() {
        val lines = listOf("Continued", "A Heading")
        assertFalse(splitter.isHeadingInContext("A Heading", lines, 1, genericConfig))
    }

    @Test
    fun `isHeadingInContext rejects a line that doesn't even look like a heading`() {
        val lines = listOf("", "this is not heading-shaped.")
        assertFalse(splitter.isHeadingInContext("this is not heading-shaped.", lines, 1, genericConfig))
    }

    // --- split(): heuristic fallback end-to-end --------------------------------------------
    // A nonexistent source path makes EpubMarkdownParser/PdfMarkdownParser return "" (both catch
    // their own I/O errors and return blank), forcing split() down the heuristic path over the
    // raw `pages` text — the "harder to get right" path the class's own doc comment calls out.

    @Test
    fun `split falls back to heuristic segmentation when markdown parsing yields nothing`() {
        val pageText = """
            Introduction

            This chapter introduces the topic in a few sentences of plain prose.

            Chapter One

            This is the body of chapter one, long enough to be a real chunk of its own.
        """.trimIndent()

        val result = splitter.split(
            sourcePath = "/nonexistent/path/does-not-exist.epub",
            sourceType = SourceType.EPUB,
            pages = { listOf(Document(pageText)) },
            variant = StructuralVariant.GENERIC,
        )

        assertTrue(result.isNotEmpty())
        assertTrue(result.any { (it.text ?: "").contains("Introduction") })
        assertTrue(result.any { (it.text ?: "").contains("Chapter One") })
    }

    @Test
    fun `split returns nothing for entirely blank pages via the heuristic path`() {
        val result = splitter.split(
            sourcePath = "/nonexistent/path/does-not-exist.pdf",
            sourceType = SourceType.PDF,
            pages = { listOf(Document("   "), Document("")) },
            variant = StructuralVariant.GENERIC,
        )

        assertEquals(emptyList(), result.map { it.text })
    }
}