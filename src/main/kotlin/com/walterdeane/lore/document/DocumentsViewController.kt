package com.walterdeane.lore.document

import com.walterdeane.lore.domain.DomainsService
import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.tags.TagsService
import org.springframework.core.io.FileSystemResource
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.io.File
import java.util.UUID

@Controller
class DocumentsViewController(
    private val domainsService: DomainsService,
    private val documentsService: DocumentsService,
    private val tagsService: TagsService,
) {

    @GetMapping("/domains/{domainId}/documents")
    fun showPage(
        @PathVariable domainId: UUID,
        @RequestParam(required = false) q: String?,
        @PageableDefault(size = 25, sort = ["title"]) pageable: Pageable,
        model: Model,
    ): String {
        model.addAttribute("domain", domainsService.getDomainById(domainId))
        model.addAttribute("query", q)
        model.addAttribute("documentsPage", documentsService.getDocuments(domainId, q, pageable))
        model.addAttribute("availableTags", tagsService.getDomainTags(domainId))
        model.addAttribute("strategies", ChunkingStrategy.values())
        return "domain/documents"
    }

    @GetMapping("/domains/{domainId}/documents/{documentId}")
    fun showDetailPage(
        @PathVariable domainId: UUID,
        @PathVariable documentId: UUID,
        model: Model,
    ): String {
        val availableTags = tagsService.getDomainTags(domainId)
        model.addAttribute("domain", domainsService.getDomainById(domainId))
        model.addAttribute("document", documentsService.getDocumentById(documentId))
        model.addAttribute("availableTags", availableTags)
        model.addAttribute("tagsByPath", availableTags.associateBy { it.path })
        return "domain/document"
    }

    @PostMapping("/domains/{domainId}/documents", consumes = [MULTIPART_FORM_DATA_VALUE])
    fun uploadDocument(
        @PathVariable domainId: UUID,
        @RequestParam("title", required = false) title: String?,
        @RequestParam("author", required = false) author: String?,
        @RequestParam("tags", required = false) tags: List<String>?,
        @RequestParam("chunkStrategy", required = false) chunkStrategy: String?,
        @RequestParam("file") file: MultipartFile,
    ): String {
        val strategy = chunkStrategy?.takeIf { it.isNotBlank() }?.let { ChunkingStrategy.valueOf(it) }
        val document = documentsService.importDocument(
            domainId, file.originalFilename ?: "untitled", file.bytes, title, author, tags ?: emptyList(), strategy
        )
        return "redirect:/domains/$domainId/documents/${document.id}"
    }

    @GetMapping("/domains/{domainId}/documents/{documentId}/file")
    fun serveFile(
        @PathVariable domainId: UUID,
        @PathVariable documentId: UUID,
    ): ResponseEntity<FileSystemResource> {
        val document = documentsService.getDocumentById(documentId)
            ?: return ResponseEntity.notFound().build()
        val file = File(document.sourcePath)
        if (!file.exists()) return ResponseEntity.notFound().build()

        val mediaType = when (document.sourceType) {
            com.walterdeane.lore.model.SourceType.PDF -> MediaType.APPLICATION_PDF
            com.walterdeane.lore.model.SourceType.EPUB -> MediaType.parseMediaType("application/epub+zip")
        }
        val headers = HttpHeaders().apply {
            contentType = mediaType
            contentDisposition = ContentDisposition.inline()
                .filename(document.sourceFilename)
                .build()
        }
        return ResponseEntity.ok().headers(headers).body(FileSystemResource(file))
    }

    @PostMapping("/domains/{domainId}/documents/{documentId}/reingest")
    fun reingestDocument(
        @PathVariable domainId: UUID,
        @PathVariable documentId: UUID,
        redirectAttributes: RedirectAttributes,
    ): String {
        documentsService.reingestDocument(documentId)
        redirectAttributes.addFlashAttribute("message", "Reingestion started")
        return "redirect:/domains/$domainId/documents/$documentId"
    }

    @DeleteMapping("/domains/{domainId}/documents/{documentId}")
    fun deleteDocument(
        @PathVariable domainId: UUID,
        @PathVariable documentId: UUID,
        redirectAttributes: RedirectAttributes,
    ): String {
        documentsService.deleteDocument(documentId)
        redirectAttributes.addFlashAttribute("message", "Document deleted")
        return "redirect:/domains/$domainId/documents"
    }

    @PutMapping("/domains/{domainId}/documents/{documentId}/tags")
    fun updateDocumentTags(
        @PathVariable domainId: UUID,
        @PathVariable documentId: UUID,
        @ModelAttribute form: DocumentTagsForm,
    ): String {
        documentsService.updateDocumentTags(documentId, form.tags)
        return "redirect:/domains/$domainId/documents/$documentId"
    }
}

data class DocumentTagsForm(
    val tags: List<String> = emptyList(),
)
