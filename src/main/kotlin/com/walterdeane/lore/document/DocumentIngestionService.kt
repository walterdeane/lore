package com.walterdeane.lore.document

import com.walterdeane.lore.domain.DomainRepository
import com.walterdeane.lore.model.Chunk
import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.IngestionStatus
import com.walterdeane.lore.model.SourceType
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.reader.tika.TikaDocumentReader
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.core.io.FileSystemResource
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * The ingestion half of the RAG pipeline: turns an uploaded [Document] into searchable [Chunk] rows.
 * Steps are: (1) extract raw text with Tika, (2) split it into chunks using the resolved
 * [ChunkingStrategy], (3) embed each chunk's text with [EmbeddingModel], (4) persist chunk text +
 * embedding to Postgres/pgvector. Everything downstream — [com.walterdeane.lore.search.LexicalSearchService],
 * [com.walterdeane.lore.search.VectorSearchService] — reads what this writes.
 */
@Service
class DocumentIngestionService(
    private val embeddingModel: EmbeddingModel,
    private val chunkRepository: ChunkRepository,
    private val documentRepository: DocumentRepository,
    private val domainRepository: DomainRepository,
    private val chunkingStrategyResolver: ChunkingStrategyResolver,
    private val structuralTextSplitter: StructuralTextSplitter,
    private val semanticTextSplitter: SemanticTextSplitter,
    private val tokenOverlapChunker: TokenOverlapChunker,
    private val chunkingProperties: ChunkingProperties,
    private val epubMarkdownParser: EpubMarkdownParser,
    private val pdfMarkdownParser: PdfMarkdownParser,
) {
    private val log = LoggerFactory.getLogger(DocumentIngestionService::class.java)

    /**
     * Runs off the request thread ([Async]) since parsing, chunking, and embedding a whole document
     * can take a while. Updates [document]'s [IngestionStatus] to COMPLETED or FAILED so the UI can
     * poll for progress; any exception mid-pipeline is caught and recorded rather than left to crash
     * the async executor.
     */
    @Async
    fun ingest(document: Document) {
        try {
            val domain = domainRepository.findById(document.domainId)
            if (domain == null) {
                log.error("[{}] domain {} not found, aborting ingestion", document.id, document.domainId)
                documentRepository.updateStatus(document.id, IngestionStatus.FAILED, error = "Domain not found")
                return
            }
            val strategy = chunkingStrategyResolver.resolve(document, domain)
            log.info("[{}] extracting text from {} using strategy {}", document.id, document.sourceFilename, strategy)

            val reader = TikaDocumentReader(FileSystemResource(document.sourcePath))

            log.info("[{}] chunking document", document.id)
            val tokenSplitter = TokenTextSplitter.builder().withChunkSize(chunkingProperties.tokenChunkSize).build()
            // STRUCTURAL/SEMANTIC extract via their own Jsoup-based markdown parsers and only fall
            // back to Tika's pages if that comes back blank/insufficient — passing reader::get as a
            // supplier rather than calling it upfront means a file Tika's strict parser chokes on
            // (e.g. malformed XHTML) doesn't fail ingestion for a strategy that never needed Tika.
            // TOKEN used to call reader.get() unconditionally with no such fallback, so a real book
            // (a single malformed tag in a scraped EPUB triggering TIKA-237) failed outright even
            // though the same file ingests fine under STRUCTURAL/SEMANTIC — that asymmetry is why
            // TOKEN now falls back to the same lenient markdown parser on a Tika failure.
            val splitDocuments = when (strategy) {
                ChunkingStrategy.TOKEN -> {
                    val tikaDocuments = try {
                        reader.get()
                    } catch (e: Exception) {
                        log.warn("[{}] Tika failed for TOKEN strategy ({}), falling back to markdown parser",
                            document.id, e.message)
                        val markdown = when (document.sourceType) {
                            SourceType.EPUB -> epubMarkdownParser.parse(document.sourcePath)
                            SourceType.PDF -> pdfMarkdownParser.parse(document.sourcePath)
                        }
                        listOf(org.springframework.ai.document.Document(markdown))
                    }
                    tokenOverlapChunker.applyOverlap(tokenSplitter.split(tikaDocuments), chunkingProperties.tokenOverlapChars)
                }

                ChunkingStrategy.STRUCTURAL -> {
                    val variant = chunkingStrategyResolver.resolveVariant(document, domain)
                    log.info("[{}] STRUCTURAL variant: {}", document.id, variant)
                    structuralTextSplitter.split(document.sourcePath, document.sourceType, reader::get, variant)
                }

                ChunkingStrategy.SEMANTIC -> {
                    semanticTextSplitter.split(document.sourcePath, document.sourceType, reader::get)
                }
            }

            log.info("[{}] embedding and storing {} chunks", document.id, splitDocuments.size)
            splitDocuments.forEachIndexed { index, splitDocument ->
                val text = splitDocument.text ?: ""
                val embedding = embeddingModel.embed(text)
                chunkRepository.save(
                    Chunk(
                        id = UUID.randomUUID(),
                        documentId = document.id,
                        domainId = document.domainId,
                        tagPaths = document.tags,
                        content = text,
                        embedding = embedding.toList(),
                        chunkIndex = index,
                        chunkStrategy = strategy,
                        createdAt = Instant.now(),
                    )
                )
            }

            documentRepository.updateStatus(document.id, IngestionStatus.COMPLETED, ingestedAt = Instant.now())
            log.info("[{}] ingestion complete", document.id)
        } catch (e: Throwable) {
            // Catches Error too, not just Exception — a classpath/version mismatch surfaces as
            // something like NoSuchMethodError, which isn't an Exception. Without this, such a
            // failure never gets recorded: the document sits at PENDING forever with no error
            // message, since Spring's async executor logs and swallows it instead of propagating.
            log.error("[{}] ingestion failed", document.id, e)
            documentRepository.updateStatus(document.id, IngestionStatus.FAILED, error = e.message)
        }
    }
}
