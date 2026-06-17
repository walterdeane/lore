package com.walterdeane.lore.document

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import org.springframework.web.multipart.MultipartFile
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE
import org.springframework.stereotype.Controller
import com.walterdeane.lore.model.Document
import java.util.UUID

@Controller
class DocumentsController {

@PostMapping("/documents", consumes = [MULTIPART_FORM_DATA_VALUE])
fun uploadDocument(
    @RequestParam("file") file: MultipartFile,
    @RequestParam("loreCollectionId") loreCollectionId: UUID,
    @RequestParam("title", required = false) title: String?,
    @RequestParam("author", required = false) author: String?,
): ResponseEntity<Void> {
    // Placeholder: persist file to storage, create Document row (PENDING),
    // trigger async ingestion job
    val createdId = UUID.randomUUID() // replace with actual saved Document id

    val location = ServletUriComponentsBuilder.fromCurrentRequest()
        .path("/{id}")
        .buildAndExpand(createdId)
        .toUri()
    return ResponseEntity.created(location).build()
}

@GetMapping("/documents/{id}")
fun getDocumentById(@PathVariable id: UUID): ResponseEntity<Document> {
    // Placeholder: retrieve Document by id, including ingestionStatus
    val document = Document(
        id = id,
        title = "Example Document",
        author = "John Doe",
        sourceFilename = "example.pdf",
        sourcePath = "/path/to/example.pdf",
        sourceType = com.walterdeane.lore.model.SourceType.PDF,
        tags = listOf("example", "test"),
        loreCollectionId = UUID.randomUUID(),
        ingestionStatus = com.walterdeane.lore.model.IngestionStatus.COMPLETED,
        ingestionError = null,
        ingestedAt = java.time.Instant.now()
    )
    return ResponseEntity.ok(document)
}  

@GetMapping("/documents/{id}/chunks")
fun getDocumentChunks(@PathVariable id: UUID): ResponseEntity<List<String>> {
    // Placeholder: retrieve chunks for document id, return chunk content or metadata for debugging
    val chunks = listOf(
        "Chunk 1 content...",
        "Chunk 2 content...",
        "Chunk 3 content..."
    )
    return ResponseEntity.ok(chunks)
}

@DeleteMapping("/documents/{id}")
fun deleteDocumentById(@PathVariable id: UUID): ResponseEntity<Void> {
    // Placeholder: delete document by id, including all associated chunks
    return ResponseEntity.noContent().build()
}

@PostMapping("/documents/{id}/reingest")    
fun reingestDocument(@PathVariable id: UUID): ResponseEntity<Void> {
    // Placeholder: trigger async reingestion job for document id with current config

    return ResponseEntity.accepted().build()
}

@GetMapping("/collections/{collectionId}/documents")
fun getDocumentsByCollectionId(@PathVariable collectionId: UUID): ResponseEntity<List<Document>> {
    // Placeholder: retrieve documents by collection id
    val documents = listOf(
        Document(
            id = UUID.randomUUID(),
            title = "Example Document 1",
            author = "John Doe",
            sourceFilename = "example1.pdf",
            sourcePath = "/path/to/example1.pdf",
            sourceType = com.walterdeane.lore.model.SourceType.PDF,
            tags = listOf("example", "test"),
            loreCollectionId = collectionId,
            ingestionStatus = com.walterdeane.lore.model.IngestionStatus.COMPLETED,
            ingestionError = null,
            ingestedAt = java.time.Instant.now()
        ),
        Document(
            id = UUID.randomUUID(),
            title = "Example Document 2",
            author = "Jane Smith",
            sourceFilename = "example2.epub",
            sourcePath = "/path/to/example2.epub",
            sourceType = com.walterdeane.lore.model.SourceType.EPUB,
            tags = listOf("example", "test"),
            loreCollectionId = collectionId,
            ingestionStatus = com.walterdeane.lore.model.IngestionStatus.COMPLETED,
            ingestionError = null,
            ingestedAt = java.time.Instant.now()
        )
    )
    return ResponseEntity.ok(documents)
}

@PutMapping("/documents/{id}/tags")
fun updateDocumentTags(@PathVariable id: UUID, @RequestBody tags: List<String>): ResponseEntity<Void> {
    // Placeholder: replace/set tag list for document id
    return ResponseEntity.ok().build()
}
}