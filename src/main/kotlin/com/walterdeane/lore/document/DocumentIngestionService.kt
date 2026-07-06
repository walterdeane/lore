package com.walterdeane.lore.document

import com.walterdeane.lore.domain.DomainRepository
import com.walterdeane.lore.model.Chunk
import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.IngestionStatus
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.reader.tika.TikaDocumentReader
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.core.io.FileSystemResource
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class DocumentIngestionService(
    private val embeddingModel: EmbeddingModel,
    private val chunkRepository: ChunkRepository,
    private val documentRepository: DocumentRepository,
    private val domainRepository: DomainRepository,
    private val chunkingStrategyResolver: ChunkingStrategyResolver,
    private val structuralTextSplitter: StructuralTextSplitter,
) {
    private val log = LoggerFactory.getLogger(DocumentIngestionService::class.java)

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
            val pages = reader.get()

            log.info("[{}] chunking document", document.id)
            val tokenSplitter = TokenTextSplitter.builder().build()
            val splitDocuments = when (strategy) {
                ChunkingStrategy.TOKEN -> tokenSplitter.split(pages)
                ChunkingStrategy.STRUCTURAL -> {
                    val variant = chunkingStrategyResolver.resolveVariant(document, domain)
                    log.info("[{}] STRUCTURAL variant: {}", document.id, variant)
                    structuralTextSplitter.split(pages, variant)
                }
                ChunkingStrategy.SEMANTIC -> {
                    log.warn("[{}] SEMANTIC strategy not yet implemented, falling back to TOKEN", document.id)
                    tokenSplitter.split(pages)
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
        } catch (e: Exception) {
            log.error("[{}] ingestion failed", document.id, e)
            documentRepository.updateStatus(document.id, IngestionStatus.FAILED, error = e.message)
        }
    }
}
