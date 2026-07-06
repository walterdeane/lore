package com.walterdeane.lore.document

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.multipart.MultipartFile
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE
import org.springframework.stereotype.Controller
import org.springframework.data.web.PageableDefault
import org.springframework.data.domain.Pageable
import org.springframework.ui.Model
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.tags.TagsService
import com.walterdeane.lore.domain.DomainsService
import java.util.UUID

@Controller
class DocumentsViewController(
    private val domainsService: DomainsService,
    private val documentsService: DocumentsService,
    private val tagsService: TagsService
    )  {

    @GetMapping("/domains/{domainId}/documents")
    fun showPage(
        @PathVariable domainId: UUID,
        @RequestParam(required = false) q: String?,
        @PageableDefault(size = 25, sort = ["name"]) pageable: Pageable,
        model: Model
    ): String {
        model.addAttribute("domain", domainsService.getDomainById(domainId))    
        model.addAttribute("query", q)
        model.addAttribute("documentsPage", documentsService.getDocuments(q, pageable))
        model.addAttribute("availableTags", tagsService.getDomainTags(domainId))
        return "domain/documents"
    }

    @GetMapping("/domains/{domainId}/documents/{documentId}")
    fun showDetailPage(
        @PathVariable domainId: UUID,
        @PathVariable documentId: UUID,
        model: Model
    ): String {
        model.addAttribute("domain", domainsService.getDomainById(domainId))  
        model.addAttribute("document", documentsService.getDocumentById(documentId))  
        model.addAttribute("availableTags", tagsService.getDomainTags(domainId))
        return "domain/document"
    }

@PostMapping("/domains/{domainId}/documents", consumes = [MULTIPART_FORM_DATA_VALUE])
fun uploadDocument(
    @PathVariable("domainId") domainId: UUID,
    @RequestParam("title", required = false) title: String?,
    @RequestParam("author", required = false) author: String?,
    @RequestParam("tags", required = false) tags: List<String>?,
    @RequestParam("file") file: MultipartFile,
): String {
    val document = documentsService.importDocument(domainId, file.originalFilename ?: "untitled", file.bytes, title, author, tags ?: emptyList())
    return "redirect:/domains/$domainId/documents/${document.id}"
}

// @GetMapping("/documents/{id}")
// fun getDocumentById(@PathVariable id: UUID): ResponseEntity<Document> {
//     // Placeholder: retrieve Document by id, including ingestionStatus
//     val document = Document(
//         id = id,
//         title = "Example Document",
//         author = "John Doe",
//         sourceFilename = "example.pdf",
//         sourcePath = "/path/to/example.pdf",
//         sourceType = com.walterdeane.lore.model.SourceType.PDF,
//         tags = listOf("example", "test"),
//         domainId = UUID.randomUUID(),
//         ingestionStatus = com.walterdeane.lore.model.IngestionStatus.COMPLETED,
//         ingestionError = null,
//         ingestedAt = java.time.Instant.now()
//     )
//     return ResponseEntity.ok(document)
// }  

// @GetMapping("/documents/{id}/chunks")
// fun getDocumentChunks(@PathVariable id: UUID): ResponseEntity<List<String>> {
//     // Placeholder: retrieve chunks for document id, return chunk content or metadata for debugging
//     val chunks = listOf(
//         "Chunk 1 content...",
//         "Chunk 2 content...",
//         "Chunk 3 content..."
//     )
//     return ResponseEntity.ok(chunks)
// }

// @DeleteMapping("/documents/{id}")
// fun deleteDocumentById(@PathVariable id: UUID): ResponseEntity<Void> {
//     // Placeholder: delete document by id, including all associated chunks
//     return ResponseEntity.noContent().build()
// }

// @PostMapping("/documents/{id}/reingest")    
// fun reingestDocument(@PathVariable id: UUID): ResponseEntity<Void> {
//     // Placeholder: trigger async reingestion job for document id with current config

//     return ResponseEntity.accepted().build()
// }

// @GetMapping("/domains/{domainId}/documents")
// fun getDocumentsByDomainId(@PathVariable domainId: UUID): ResponseEntity<List<Document>> {
//     // Placeholder: retrieve documents by domain id
//     val documents = listOf(
//         Document(
//             id = UUID.randomUUID(),
//             title = "Example Document 1",
//             author = "John Doe",
//             sourceFilename = "example1.pdf",
//             sourcePath = "/path/to/example1.pdf",
//             sourceType = com.walterdeane.lore.model.SourceType.PDF,
//             tags = listOf("example", "test"),
//             domainId = domainId,
//             ingestionStatus = com.walterdeane.lore.model.IngestionStatus.COMPLETED,
//             ingestionError = null,
//             ingestedAt = java.time.Instant.now()
//         ),
//         Document(
//             id = UUID.randomUUID(),
//             title = "Example Document 2",
//             author = "Jane Smith",
//             sourceFilename = "example2.epub",
//             sourcePath = "/path/to/example2.epub",
//             sourceType = com.walterdeane.lore.model.SourceType.EPUB,
//             tags = listOf("example", "test"),
//             domainId = domainId,
//             ingestionStatus = com.walterdeane.lore.model.IngestionStatus.COMPLETED,
//             ingestionError = null,
//             ingestedAt = java.time.Instant.now()
//         )
//     )
//     return ResponseEntity.ok(documents)
// }

@PutMapping("/domains/{domainId}/documents/{id}/tags")
fun updateDocumentTags(
    @PathVariable domainId: UUID,
    @PathVariable id: UUID,
    @ModelAttribute documentTagsForm: DocumentTagsForm
    ): String {
        documentsService.updateDocumentTags(id, documentTagsForm.tags)

    return "redirect:/domains/$domainId/documents"
}
}

data class DocumentTagsForm(
    val tags: List<String> = emptyList(),
)