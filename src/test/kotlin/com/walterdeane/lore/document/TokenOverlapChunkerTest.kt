package com.walterdeane.lore.document

import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import kotlin.test.assertEquals

class TokenOverlapChunkerTest {

    private val chunker = TokenOverlapChunker()

    private fun docs(vararg texts: String) = texts.map { Document(it) }
    private fun texts(docs: List<Document>): List<String> = docs.map { it.text ?: "" }

    @Test
    fun `fewer than two chunks are returned unchanged`() {
        assertEquals(emptyList(), chunker.applyOverlap(emptyList(), 50))
        val single = docs("only chunk")
        assertEquals(listOf("only chunk"), texts(chunker.applyOverlap(single, 50)))
    }

    @Test
    fun `zero or negative overlapChars disables overlap`() {
        val chunks = docs("First chunk.", "Second chunk.")
        assertEquals(listOf("First chunk.", "Second chunk."), texts(chunker.applyOverlap(chunks, 0)))
        assertEquals(listOf("First chunk.", "Second chunk."), texts(chunker.applyOverlap(chunks, -5)))
    }

    @Test
    fun `prepends trailing sentence of the previous chunk, trimmed to a sentence boundary`() {
        val chunks = docs(
            "This is the first sentence. This is the second sentence.",
            "This continues on.",
        )

        val result = texts(chunker.applyOverlap(chunks, 40))

        // Window is the trailing 40 chars of chunk 0; it should be trimmed forward to start right
        // after the nearest sentence-ending punctuation within that window, not mid-sentence.
        assertEquals("This is the first sentence. This is the second sentence.", result[0])
        assertEquals("This is the second sentence. This continues on.", result[1])
    }

    @Test
    fun `uses the whole previous chunk when it's shorter than the overlap window`() {
        val chunks = docs("Short.", "Next chunk here.")

        val result = texts(chunker.applyOverlap(chunks, 100))

        assertEquals("Short. Next chunk here.", result[1])
    }

    @Test
    fun `falls back to the raw trailing window when no sentence boundary is found`() {
        val chunks = docs("one two three four five six seven eight nine ten", "next")

        val result = texts(chunker.applyOverlap(chunks, 10))

        // No '.', '?', '!', or '\n' anywhere — trailingContext should just return the raw last
        // 10 characters of the previous chunk, mid-word cut and all.
        assertEquals("t nine ten next", result[1])
    }

    @Test
    fun `each chunk's overlap is based on the original previous chunk, not an already-modified one`() {
        val chunks = docs("Alpha sentence. ", "Bravo sentence. ", "Charlie sentence.")

        val result = texts(chunker.applyOverlap(chunks, 20))

        // Chunk 2's overlap must come from the ORIGINAL chunk 1 text, not chunk 1 with chunk 0's
        // overlap already prepended to it (which would duplicate "Alpha").
        assertEquals(false, result[2]!!.contains("Alpha"))
        assertEquals(true, result[2]!!.contains("Bravo"))
    }
}
