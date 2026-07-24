package com.walterdeane.lore.search

import com.walterdeane.lore.AbstractIntegrationTest
import com.walterdeane.lore.document.DocumentIngestionService
import com.walterdeane.lore.document.DocumentRepository
import com.walterdeane.lore.domain.DomainRepository
import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.Domain
import com.walterdeane.lore.model.IngestionStatus
import com.walterdeane.lore.model.SourceType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Post-03 Phase 2.1 acceptance: the single-statement SQL fusion in [HybridSearchService.search]
 * must produce the same ranking [fuse] (the original, still-used-elsewhere pure function) would —
 * verified against real data rather than a synthetic candidate list, since the whole point of the
 * refactor is the real lexical/vector legs feeding real RRF math. Ties are the one place they're
 * allowed to differ (see [HybridSearchService.search]'s KDoc: the old code's tie-break was an
 * accident of `LinkedHashMap` insertion order, not a rule worth preserving) — this test asserts
 * score equality and non-tied ordering exactly, and only checks *set* membership within a tie group.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridSearchFusionParityTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var documentIngestionService: DocumentIngestionService

    @Autowired
    private lateinit var documentRepository: DocumentRepository

    @Autowired
    private lateinit var domainRepository: DomainRepository

    @Autowired
    private lateinit var lexicalSearchService: LexicalSearchService

    @Autowired
    private lateinit var vectorSearchService: VectorSearchService

    @Autowired
    private lateinit var hybridSearchService: HybridSearchService

    @Autowired
    private lateinit var searchProperties: SearchProperties

    private lateinit var domainId: UUID

    @BeforeAll
    fun ingestFixtureOnce() {
        val domain = domainRepository.save(
            Domain(
                id = UUID.randomUUID(),
                name = "Fusion parity test domain",
                description = "created by HybridSearchFusionParityTest",
                chunkStrategy = ChunkingStrategy.STRUCTURAL,
            )
        )
        domainId = domain.id
        val fixturePath = javaClass.classLoader.getResource("fixtures/jekyll-and-hyde.epub")
            ?.let { java.io.File(it.toURI()).path }
            ?: fail("fixture not found on classpath: fixtures/jekyll-and-hyde.epub")

        val document = documentRepository.save(
            Document(
                id = UUID.randomUUID(),
                title = "jekyll-and-hyde.epub",
                sourceFilename = "jekyll-and-hyde.epub",
                sourcePath = fixturePath,
                sourceType = SourceType.EPUB,
                tags = emptyList(),
                domainId = domain.id,
                ingestionStatus = IngestionStatus.PENDING,
            )
        )
        documentIngestionService.ingest(document)
        awaitTerminalStatus(document.id)
    }

    @Test
    fun `SQL fusion ordering matches Kotlin fuse() ordering, ties aside`() {
        val query = "Mr Hyde"
        val poolSize = searchProperties.candidatePoolSize

        val lexicalIds = lexicalSearchService.search(query, domainId, size = poolSize, page = 0).results.map { it.chunkId }
        val vectorIds = vectorSearchService.search(query, domainId, size = poolSize).map { it.chunkId }
        val kotlinFused = fuse(lexicalIds, vectorIds, searchProperties.rrfK)

        val sqlPage = hybridSearchService.search(query, domainId, size = kotlinFused.size, page = 0)

        assertEquals(kotlinFused.size.toLong(), sqlPage.total, "SQL fusion should find the same total candidate count as Kotlin fuse()")
        assertEquals(kotlinFused.size, sqlPage.results.size)

        // Group both rankings by score, in descending-score order, and compare group-by-group:
        // within a group of size 1 (no tie), the chunk id must match exactly; within a larger group
        // (a real tie), only the *set* of ids must match — internal order is allowed to differ.
        val kotlinGroups = kotlinFused.groupBy({ it.second }, { it.first }).toList().sortedByDescending { it.first }
        val sqlGroups = sqlPage.results.groupBy({ it.rank }, { it.chunkId }).toList().sortedByDescending { it.first }

        assertEquals(kotlinGroups.size, sqlGroups.size, "same number of distinct score levels")
        kotlinGroups.zip(sqlGroups).forEachIndexed { i, (kotlinGroup, sqlGroup) ->
            val (kotlinScore, kotlinIds) = kotlinGroup
            val (sqlScore, sqlIds) = sqlGroup
            assertEquals(kotlinScore, sqlScore, 1e-9, "score level $i should match exactly")
            if (kotlinIds.size == 1) {
                assertEquals(kotlinIds, sqlIds, "non-tied position $i (score $kotlinScore) should match exactly")
            } else {
                assertEquals(kotlinIds.toSet(), sqlIds.toSet(), "tied group at score $kotlinScore should contain the same chunks, order aside")
            }
        }
    }

    @Test
    fun `SQL fusion searchType classification matches leg membership`() {
        val query = "laboratory"
        val poolSize = searchProperties.candidatePoolSize

        val lexicalIds = lexicalSearchService.search(query, domainId, size = poolSize, page = 0).results.map { it.chunkId }.toSet()
        val vectorIds = vectorSearchService.search(query, domainId, size = poolSize).map { it.chunkId }.toSet()
        assertTrue(lexicalIds.isNotEmpty() && vectorIds.isNotEmpty(), "test needs both legs to return candidates for a meaningful check")

        val sqlPage = hybridSearchService.search(query, domainId, size = 50, page = 0)
        for (result in sqlPage.results) {
            val expected = when {
                result.chunkId in lexicalIds && result.chunkId in vectorIds -> SearchType.BOTH
                result.chunkId in vectorIds -> SearchType.EMBEDDING
                else -> SearchType.LEXICAL
            }
            assertEquals(expected, result.searchType, "searchType for chunk ${result.chunkId} should reflect leg membership")
        }
    }

    private fun awaitTerminalStatus(documentId: UUID, timeout: Duration = Duration.ofSeconds(120)): Document {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val current = documentRepository.findById(documentId) ?: fail("document $documentId disappeared")
            if (current.ingestionStatus != IngestionStatus.PENDING && current.ingestionStatus != IngestionStatus.IN_PROGRESS) {
                return current
            }
            Thread.sleep(500)
        }
        fail("ingestion of $documentId did not finish within $timeout")
    }
}
