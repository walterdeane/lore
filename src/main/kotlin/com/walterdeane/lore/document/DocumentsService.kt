package com.walterdeane.lore.document

import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.StructuralVariant
import com.walterdeane.lore.model.IngestionStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.io.File
import java.util.UUID

/**
 * Document lifecycle management (upload, tag, reingest, delete) sitting above [DocumentRepository]/
 * [ChunkRepository]; kicks off [DocumentIngestionService] whenever a document's content or chunking
 * config needs (re)processing into embeddings.
 */
@Service
class DocumentsService(
    private val documentStorageProperties: DocumentStorageProperties,
    private val documentRepository: DocumentRepository,
    private val chunkRepository: ChunkRepository,
    private val documentIngestionService: DocumentIngestionService,
    private val epubZipResolver: EpubZipResolver,
) {

    fun getDocuments(domainId: UUID, query: String?, pageable: Pageable): Page<Document> {
        val total = documentRepository.countByDomainId(domainId, query)
        val documents = documentRepository.findByDomainId(domainId, query, pageable.pageSize, pageable.offset)
        return PageImpl(documents, pageable, total)
    }

    fun getDocumentById(id: UUID): Document? = documentRepository.findById(id)

    /** Entry point into the chunk detail page's prev/next ("nexting") navigation for this document. */
    fun getFirstChunkId(documentId: UUID): UUID? = chunkRepository.findIdByDocumentIdAndChunkIndex(documentId, 0)

    fun updateDocument(id: UUID, title: String, author: String?, sourcePath: String) {
        documentRepository.update(id, title, author, sourcePath)
    }

    fun updateDocumentTags(id: UUID, tags: List<String>) {
        documentRepository.updateTags(id, tags)
        chunkRepository.updateTagPathsByDocumentId(id, tags)
    }

    fun deleteDocument(id: UUID) {
        val document = documentRepository.findById(id)
        documentRepository.deleteById(id) // cascades chunks via FK
        document?.let { File(it.sourcePath).takeIf { f -> f.exists() }?.delete() }
    }

    /** Wipes existing chunks/embeddings and re-runs ingestion — useful after changing chunk strategy or tags. */
    fun reingestDocument(id: UUID) {
        val document = documentRepository.findById(id) ?: return
        chunkRepository.deleteByDocumentId(id)
        documentRepository.updateStatus(id, IngestionStatus.PENDING)
        documentIngestionService.ingest(document)
    }

    fun getAvailableTags(domainId: UUID): List<String> = emptyList()

    /** Writes the uploaded file to disk, records a PENDING [Document] row, then hands off to async ingestion. */
    fun importDocument(domainId: UUID, filename: String, content: ByteArray, title: String?, author: String?, tags: List<String>, chunkStrategy: ChunkingStrategy? = null, structuralVariant: StructuralVariant? = null): Document {
        val trimmedFilename = filename.trim()
        val declaredSuffix = trimmedFilename.substringAfterLast('.', "").lowercase()
        val resolved = epubZipResolver.resolveUpload(declaredSuffix, content, trimmedFilename)

        val id = UUID.randomUUID()
        val filePath = "${documentStorageProperties.documentsDir}/$id.${resolved.suffix}"

        val document = Document(
            id = id,
            domainId = domainId,
            title = title?.takeIf { it.isNotBlank() } ?: trimmedFilename,
            author = author,
            sourceFilename = trimmedFilename,
            sourcePath = filePath,
            sourceType = resolved.sourceType,
            tags = tags,
            ingestionStatus = IngestionStatus.PENDING,
            chunkStrategy = chunkStrategy,
            structuralVariant = structuralVariant,
        )
        documentRepository.save(document)

        val file = File(filePath)
        file.parentFile.mkdirs()
        file.writeBytes(resolved.content)

        documentIngestionService.ingest(document)
        return document
    }
}
