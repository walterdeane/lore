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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Phase 1 acceptance test (post-03 `code-instructions-post-03.md`): proves the stage-timing/explain-
 * endpoint instrumentation added in this commit is a pure side effect — hybrid search results for a
 * fixed corpus+query are unchanged. Chunk/document/domain ids are randomly generated at ingestion
 * time regardless of this commit (that randomness predates Phase 1, and Testcontainers gives every
 * test run its own throwaway Postgres), so this compares the stable, order-relevant fields
 * (chunkIndex, rank, searchType, headline, documentTitle) captured from an actual run rather than raw
 * ids. If these values ever need updating for a reason *other than* a deliberate Phase 2 behavior
 * change, something regressed.
 */
class HybridSearchInstrumentationAcceptanceTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var documentIngestionService: DocumentIngestionService

    @Autowired
    private lateinit var documentRepository: DocumentRepository

    @Autowired
    private lateinit var domainRepository: DomainRepository

    @Autowired
    private lateinit var hybridSearchService: HybridSearchService

    @Test
    fun `hybrid search results are unaffected by Phase 1 instrumentation`() {
        val domain = domainRepository.save(
            Domain(
                id = UUID.randomUUID(),
                name = "Phase 1 acceptance test domain",
                description = "created by HybridSearchInstrumentationAcceptanceTest",
                chunkStrategy = ChunkingStrategy.STRUCTURAL,
            )
        )
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

        val page = hybridSearchService.search("Mr Hyde", domain.id, size = 5, page = 0)

        // Captured from an actual run against this exact fixture/query — see class KDoc.
        assertEquals(5, page.results.size)
        val actual = page.results.map {
            "${it.chunkIndex}|${"%.6f".format(it.rank)}|${it.searchType}|${it.chunkStrategy}|${it.documentTitle}"
        }
        assertEquals(EXPECTED_TOP_5, actual)
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

    companion object {
        // Format: chunkIndex|rank(6dp)|searchType|chunkStrategy|documentTitle — captured from an
        // actual run of this exact test (fixture + query + STRUCTURAL strategy) with Phase 1's
        // instrumentation already in place. Re-run and update deliberately only for a real Phase 2
        // behavior change; any other change to these values is a regression.
        private val EXPECTED_TOP_5 = listOf(
            "10|0.032787|BOTH|STRUCTURAL|jekyll-and-hyde.epub",
            "11|0.031514|BOTH|STRUCTURAL|jekyll-and-hyde.epub",
            "9|0.031498|BOTH|STRUCTURAL|jekyll-and-hyde.epub",
            "12|0.030798|BOTH|STRUCTURAL|jekyll-and-hyde.epub",
            "7|0.030777|BOTH|STRUCTURAL|jekyll-and-hyde.epub",
        )
    }
}
