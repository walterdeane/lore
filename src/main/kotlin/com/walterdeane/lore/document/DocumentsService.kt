package com.walterdeane.lore.document

import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.SourceType
import com.walterdeane.lore.exception.UnsupportedDocumentTypeException
import com.walterdeane.lore.model.IngestionStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.io.File
import java.util.UUID

@Service
class DocumentsService(
    private val documentStorageProperties: DocumentStorageProperties,
    private val documentRepository: DocumentRepository,
    private val documentIngestionService: DocumentIngestionService,
) {

    fun getDocuments(domainId: UUID, query: String?, pageable: Pageable): Page<Document> {
        val total = documentRepository.countByDomainId(domainId, query)
        val documents = documentRepository.findByDomainId(domainId, query, pageable.pageSize, pageable.offset)
        return PageImpl(documents, pageable, total)
    }

    fun getDocumentById(id: UUID): Document? = documentRepository.findById(id)

    fun updateDocumentTags(id: UUID, tags: List<String>) {
        documentRepository.updateTags(id, tags)
    }

    fun deleteDocument(id: UUID) {
        documentRepository.deleteById(id)
    }

    fun getAvailableTags(domainId: UUID): List<String> = emptyList()

    fun importDocument(domainId: UUID, filename: String, content: ByteArray, title: String?, author: String?, tags: List<String>): Document {
        val fileSuffix = filename.substringAfterLast('.', "").lowercase()
        val sourceType = when (fileSuffix) {
            "epub" -> SourceType.EPUB
            "pdf" -> SourceType.PDF
            else -> throw UnsupportedDocumentTypeException()
        }

        val id = UUID.randomUUID()
        val filePath = "${documentStorageProperties.documentsDir}/$id.$fileSuffix"

        val document = Document(
            id = id,
            domainId = domainId,
            title = title?.takeIf { it.isNotBlank() } ?: filename,
            author = author,
            sourceFilename = filename,
            sourcePath = filePath,
            sourceType = sourceType,
            tags = tags,
            ingestionStatus = IngestionStatus.PENDING,
        )
        documentRepository.save(document)

        val file = File(filePath)
        file.parentFile.mkdirs()
        file.writeBytes(content)

        documentIngestionService.ingest(document)
        return document
    }
}
