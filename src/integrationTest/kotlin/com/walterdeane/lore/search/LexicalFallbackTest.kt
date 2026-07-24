package com.walterdeane.lore.search

import com.walterdeane.lore.AbstractIntegrationTest
import com.walterdeane.lore.document.ChunkRepository
import com.walterdeane.lore.document.DocumentRepository
import com.walterdeane.lore.domain.DomainRepository
import com.walterdeane.lore.model.Chunk
import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.Domain
import com.walterdeane.lore.model.IngestionStatus
import com.walterdeane.lore.model.SourceType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Post-03 Phase 2.2: `plainto_tsquery`/`websearch_to_tsquery` AND every term, so a natural-language
 * question with one uncommon incidental word returns nothing even though every other term is
 * common (post 02's exact finding — see `HybridSearchService`'s lexical-OR-fallback doc). Tests
 * insert chunks directly (bypassing full ingestion — this is FTS-only behavior, no embeddings or
 * chunking logic involved) so the scenario is controlled rather than dependent on which words a
 * particular fixture book happens to contain.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LexicalFallbackTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var domainRepository: DomainRepository

    @Autowired
    private lateinit var documentRepository: DocumentRepository

    @Autowired
    private lateinit var chunkRepository: ChunkRepository

    @Autowired
    private lateinit var lexicalSearchService: LexicalSearchService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var domainId: UUID
    private lateinit var documentId: UUID

    @BeforeAll
    fun setUpFixtureChunks() {
        val domain = domainRepository.save(
            Domain(id = UUID.randomUUID(), name = "Lexical fallback test domain", description = "created by LexicalFallbackTest")
        )
        domainId = domain.id
        val document = documentRepository.save(
            Document(
                id = UUID.randomUUID(),
                title = "synthetic fixture",
                sourceFilename = "synthetic.txt",
                sourcePath = "/dev/null",
                sourceType = SourceType.PDF,
                tags = emptyList(),
                domainId = domainId,
                ingestionStatus = IngestionStatus.COMPLETED,
            )
        )
        documentId = document.id

        // Contains "gas" and "cook" (and separately "charcoal" elsewhere in the corpus below) but
        // never the word "versus" or "differ" anywhere — the AND query for the natural-language
        // question below can never match this chunk (or any other in this fixture) as a result.
        saveChunk(0, "You want high heat on a gas grill? Turn the knob and start cooking right away.")
        saveChunk(1, "The charcoal grill takes longer to get going but gives a different smoky flavor.")

        // Exact-phrase regression fixture: both chunks satisfy the AND query for "rooftop ribs",
        // but chunk 2 repeats both terms together twice, so ts_rank_cd should rank it first.
        saveChunk(2, "Rooftop ribs are the best ribs you can make on a rooftop. Rooftop ribs, every time.")
        saveChunk(3, "I once made ribs on a rooftop. It was fine, nothing special about rooftop cooking.")
    }

    private fun saveChunk(index: Int, content: String) {
        chunkRepository.save(
            Chunk(
                id = UUID.randomUUID(),
                documentId = documentId,
                domainId = domainId,
                tagPaths = emptyList(),
                content = content,
                embedding = List(768) { 0.001f },
                chunkIndex = index,
                chunkStrategy = ChunkingStrategy.TOKEN,
                createdAt = Instant.now(),
            )
        )
    }

    @Test
    fun `known zero-result natural-language query returns non-empty via OR fallback`() {
        val page = lexicalSearchService.search(
            "what is the difference between cooking with gas versus charcoal",
            domainId,
        )
        assertTrue(page.results.isNotEmpty(), "OR-fallback should find chunks containing at least one of the common terms")
        assertTrue(page.total > 0)
    }

    @Test
    fun `disabling the fallback flag reproduces the pre-2_2 zero-result behavior`() {
        // Exercises the flag path directly rather than relying on config wiring — confirms the
        // "one config switch away" property code-instructions asked for.
        val fallbackDisabled = SearchProperties(lexicalFallbackEnabled = false)
        val service = LexicalSearchService(jdbcTemplate, fallbackDisabled)
        val page = service.search(
            "what is the difference between cooking with gas versus charcoal",
            domainId,
        )
        assertEquals(0, page.results.size)
        assertEquals(0L, page.total)
    }

    @Test
    fun `exact-phrase query still ranks the exact match first`() {
        val page = lexicalSearchService.search("rooftop ribs", domainId)
        assertTrue(page.results.size >= 2, "both fixture chunks should satisfy the AND query")
        assertEquals(2, page.results.first().chunkIndex, "the chunk repeating both terms together should rank first by ts_rank_cd")
    }
}
