package com.walterdeane.lore.document

import com.walterdeane.lore.AbstractIntegrationTest
import com.walterdeane.lore.domain.DomainRepository
import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.Domain
import com.walterdeane.lore.model.IngestionStatus
import com.walterdeane.lore.model.SourceType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end ingestion: real Tika extraction, real chunking, real `nomic-embed-text` embedding
 * calls against local Ollama, real Postgres/pgvector storage — the pipeline unit tests elsewhere
 * (chunkers, EpubZipResolver, RerankerService) don't touch. Needs Ollama running locally with
 * `nomic-embed-text` pulled; not part of `./gradlew test` — run via `./gradlew integrationTest`.
 *
 * Both fixtures use STRUCTURAL, not the app's TOKEN default: TOKEN goes straight from Tika to
 * TokenTextSplitter and never touches PdfMarkdownParser/EpubMarkdownParser, so it wouldn't exercise
 * the outline-parsing and running-header-stripping logic this test exists to catch regressions in.
 */
class DocumentIngestionServiceIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var documentIngestionService: DocumentIngestionService

    @Autowired
    private lateinit var documentRepository: DocumentRepository

    @Autowired
    private lateinit var domainRepository: DomainRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `ingests a real PDF with an embedded outline end-to-end`() {
        ingestFixtureAndAssert(fixtureFile = "fda-bad-bug-book-2nd-ed.pdf", sourceType = SourceType.PDF)
    }

    @Test
    fun `ingests a real EPUB end-to-end`() {
        ingestFixtureAndAssert(fixtureFile = "jekyll-and-hyde.epub", sourceType = SourceType.EPUB)
    }

    private fun ingestFixtureAndAssert(fixtureFile: String, sourceType: SourceType) {
        val fixturePath = javaClass.classLoader.getResource("fixtures/$fixtureFile")
            ?.let { java.io.File(it.toURI()).path }
            ?: fail("fixture not found on classpath: fixtures/$fixtureFile")

        val domain = domainRepository.save(
            Domain(
                id = UUID.randomUUID(),
                name = "Integration test domain $fixtureFile",
                description = "created by DocumentIngestionServiceIntegrationTest",
                chunkStrategy = ChunkingStrategy.STRUCTURAL,
            )
        )
        val document = documentRepository.save(
            Document(
                id = UUID.randomUUID(),
                title = fixtureFile,
                sourceFilename = fixtureFile,
                sourcePath = fixturePath,
                sourceType = sourceType,
                tags = listOf("integration-test"),
                domainId = domain.id,
                ingestionStatus = IngestionStatus.PENDING,
            )
        )

        documentIngestionService.ingest(document)
        val completed = awaitTerminalStatus(document.id)

        if (completed.ingestionStatus != IngestionStatus.COMPLETED) {
            fail("ingestion did not complete: status=${completed.ingestionStatus} error=${completed.ingestionError}")
        }
        assertNotNull(completed.ingestedAt)

        val chunkCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chunk WHERE document_id = ?", Long::class.java, document.id
        )
        assertTrue(chunkCount!! > 0, "expected at least one chunk for $fixtureFile")

        // embedding/tag_paths/domain_id aren't on Chunk as returned by ChunkRepository (embedding is
        // deliberately left empty there — see its mapRow), so check the stored rows directly here.
        val rows = jdbcTemplate.queryForList(
            """SELECT vector_dims(embedding) AS dims, length(content) AS content_len,
                      chunk_strategy, domain_id, tag_paths, search_vector IS NOT NULL AS has_tsvector
               FROM chunk WHERE document_id = ? ORDER BY chunk_index""",
            document.id
        )
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            assertEquals(768, row["dims"], "embedding should be nomic-embed-text's 768 dims")
            assertTrue((row["content_len"] as Number).toInt() > 0, "chunk content should not be blank")
            assertEquals(ChunkingStrategy.STRUCTURAL.name, row["chunk_strategy"])
            assertEquals(domain.id.toString(), row["domain_id"].toString())
            assertEquals(true, row["has_tsvector"], "BM25 search_vector generated column should populate")
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
