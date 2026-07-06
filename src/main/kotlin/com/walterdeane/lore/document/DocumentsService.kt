package com.walterdeane.lore.document

import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.IngestionStatus
import com.walterdeane.lore.model.SourceType
import com.walterdeane.lore.exception.UnsupportedDocumentTypeException
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.stereotype.Service
import java.util.UUID
import java.io.File


@Service
class DocumentsService(
    private val documentStorageProperties: DocumentStorageProperties,
    private val documentRepository: DocumentRepository,
    private val documentIngestionService: DocumentIngestionService,
    ) {

    fun getDocuments(query: String?, pageable: Pageable): Page<Document> {
        var documents = listOf(
            Document(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                domainId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                title = "Sourdough Starter Guide",
                author = "Jane Doe",
                sourceFilename = "sourdough_starter_guide.pdf",
                sourcePath = "/path/to/sourdough_starter_guide.pdf",
                sourceType = com.walterdeane.lore.model.SourceType.PDF,
                tags = listOf("sourdough", "starter"),
                ingestionStatus = IngestionStatus.COMPLETED,
            ),
            Document(
                id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                domainId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                title = "Fermentation Timing Notes",
                author = "John Smith",
                sourceFilename = "fermentation_timing_notes.pdf",
                sourcePath = "/path/to/fermentation_timing_notes.pdf",
                sourceType = com.walterdeane.lore.model.SourceType.PDF,
                tags = listOf("fermentation", "timing"),
                ingestionStatus = IngestionStatus.IN_PROGRESS,
            ),
        )
        val filtered = if (query.isNullOrBlank()) {
            documents
        } else {
            documents.filter { it.title.contains(query, ignoreCase = true) }
        }

        val start = pageable.offset.toInt().coerceIn(0, filtered.size)
        val end = (start + pageable.pageSize).coerceIn(start, filtered.size)
        return PageImpl(filtered.subList(start, end), pageable, filtered.size.toLong())
    }

    fun getDocumentById(id: UUID): Document? {
        // Placeholder for actual document retrieval logic
        return Document(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                domainId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                title = "Sourdough Starter Guide",
                author = "Jane Doe",
                sourceFilename = "sourdough_starter_guide.pdf",
                sourcePath = "/path/to/sourdough_starter_guide.pdf",
                sourceType = com.walterdeane.lore.model.SourceType.PDF,
                tags = listOf("sourdough", "starter"),
                ingestionStatus = IngestionStatus.COMPLETED,
            )
    }

    fun updateDocumentTags(id: UUID, tags: List<String>) {
        // Placeholder for actual document tag update logic
    }

    fun getAvailableTags(domainId: UUID): List<String> {
        // Placeholder for actual tag retrieval logic; will return all tags for the domain
        return emptyList()
    }

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
