package com.walterdeane.lore.document

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownChunkerTest {

    private val chunker = MarkdownChunker()

    @Test
    fun `blank markdown produces no chunks`() {
        assertEquals(emptyList(), chunker.split(""))
        assertEquals(emptyList(), chunker.split("   \n  "))
    }

    @Test
    fun `splits at the preferred heading level`() {
        val markdown = """
            ## Chapter One
            Some intro text about chapter one, long enough to not be merged away.

            ## Chapter Two
            Some intro text about chapter two, long enough to not be merged away.

            ## Chapter Three
            Some intro text about chapter three, long enough to not be merged away.
        """.trimIndent()

        val chunks = chunker.split(markdown, preferredLevel = 2, minChunkChars = 10)

        assertEquals(3, chunks.size)
        assertTrue(chunks[0].startsWith("## Chapter One"))
        assertTrue(chunks[1].startsWith("## Chapter Two"))
        assertTrue(chunks[2].startsWith("## Chapter Three"))
    }

    @Test
    fun `excluded sub-headers stay inside the chunk body instead of starting a new chunk`() {
        val markdown = """
            ## Recipe One
            ### Ingredients
            Flour, sugar, eggs.
            ### Method
            Mix and bake.

            ## Recipe Two
            ### Ingredients
            Butter, salt.
            ### Method
            Melt and pour.

            ## Recipe Three
            ### Ingredients
            Water.
            ### Method
            Boil.
        """.trimIndent()

        val chunks = chunker.split(
            markdown,
            preferredLevel = 2,
            minChunkChars = 10,
            excludedHeaders = setOf("ingredients", "method"),
        )

        assertEquals(3, chunks.size)
        assertTrue(chunks[0].contains("### Ingredients"))
        assertTrue(chunks[0].contains("### Method"))
        assertTrue(chunks[0].startsWith("## Recipe One"))
    }

    @Test
    fun `splitAtLevel keeps an excluded heading at the split level itself as body, not a new chunk`() {
        // Deeper excluded headers (tested above) are already kept as body simply because they're
        // deeper than the split level — that doesn't exercise the exclusion check itself. This puts
        // the excluded header at the *same* level as the split point, which does.
        val markdown = """
            ## Recipe One
            content here

            ## Notes
            these notes should stay attached to Recipe One

            ## Recipe Two
            content here
        """.trimIndent()

        val chunks = chunker.splitAtLevel(markdown, level = 2, excludedHeaders = setOf("notes"))

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].contains("## Notes"))
        assertTrue(chunks[0].startsWith("## Recipe One"))
        assertTrue(chunks[1].startsWith("## Recipe Two"))
    }

    @Test
    fun `auto-detects the recipe heading level from an excluded sub-header one level deeper`() {
        // Recipes are at h3 here, with "Ingredients" at h4 — excludedHeaders should make the
        // chunker detect h3 as the effective split level even though preferredLevel is 2.
        val markdown = """
            ### Recipe One
            #### Ingredients
            Flour.

            ### Recipe Two
            #### Ingredients
            Butter.

            ### Recipe Three
            #### Ingredients
            Water.
        """.trimIndent()

        val chunks = chunker.split(
            markdown,
            preferredLevel = 2,
            minChunkChars = 5,
            excludedHeaders = setOf("ingredients"),
        )

        assertEquals(3, chunks.size)
        assertTrue(chunks[0].startsWith("### Recipe One"))
        assertTrue(chunks[0].contains("#### Ingredients"))
    }

    @Test
    fun `merges segments shorter than minChunkChars into the following segment`() {
        val markdown = """
            ## A
            hi

            ## B
            This section is long enough on its own to exceed the minimum chunk size easily.

            ## C
            This one too is long enough on its own to exceed the minimum chunk size easily.
        """.trimIndent()

        val chunks = chunker.split(markdown, preferredLevel = 2, minChunkChars = 30)

        // "## A" (very short) should have been merged forward into "## B"'s chunk.
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].contains("## A"))
        assertTrue(chunks[0].contains("## B"))
        assertTrue(chunks[1].startsWith("## C"))
    }

    @Test
    fun `falls back to an adjacent heading level when the preferred level yields too few chunks`() {
        // Only one h2 in the whole document, but three h3s underneath it — splitting at h2 alone
        // gives 1 chunk, so the chunker should fall back to h3, which gives 3.
        val markdown = """
            ## Book

            ### Section One
            Enough content here to matter for this section of the book.

            ### Section Two
            Enough content here to matter for this section of the book.

            ### Section Three
            Enough content here to matter for this section of the book.
        """.trimIndent()

        val chunks = chunker.split(markdown, preferredLevel = 2, minChunkChars = 10)

        assertEquals(3, chunks.size)
        assertTrue(chunks[0].startsWith("## Book\n\n### Section One"))
    }
}
