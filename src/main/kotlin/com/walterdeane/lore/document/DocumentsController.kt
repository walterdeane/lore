package com.walterdeane.lore.document

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import java.util.UUID

/**
 * JSON API for document management. Upload/detail/delete/reingest/tag-update are all handled by
 * [DocumentsViewController] for the server-rendered UI; this controller only holds endpoints with
 * no equivalent there yet.
 */
@Controller
class DocumentsController(private val documentsService: DocumentsService) {

    // TODO: not backed by a real query yet — ChunkRepository has no findByDocumentId, only findById.
    @GetMapping("/api/documents/{id}/chunks")
    fun getDocumentChunks(@PathVariable id: UUID): ResponseEntity<List<String>> {
        val chunks = listOf(
            "Chunk 1 content...",
            "Chunk 2 content...",
            "Chunk 3 content..."
        )
        return ResponseEntity.ok(chunks)
    }
}
