package com.walterdeane.lore.tags

import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller()
class TagsViewController(val tagsService: TagsService) {

    @GetMapping("/domains/{id}/tags")
    fun showPage(
        @PathVariable id: UUID,
        @RequestParam(required = false) q: String?,
        @PageableDefault(size = 25, sort = ["name"]) pageable: Pageable,
        model: Model
    ): String {
        model.addAttribute("domainId", id)
        model.addAttribute("tagsPage", tagsService.getTagsForDomain(id, q, pageable))
        model.addAttribute("query", q)
        return "domain/tags"
    }

    @PostMapping("/domains/{id}/tags")
    fun createTag(@PathVariable id: UUID, @ModelAttribute tag: TagForm, redirectAttributes: RedirectAttributes): String {
        tagsService.createTag(id, tag)
        redirectAttributes.addFlashAttribute("message", "Tag created successfully")
        return "redirect:/domains/$id/tags"
    }

    @PutMapping("/domains/{id}/tags/{tagId}")
    fun updateTag(
            @PathVariable id: UUID,
            @PathVariable tagId: UUID,
            @ModelAttribute tag: TagForm,
            redirectAttributes: RedirectAttributes
    ): String {
        tagsService.updateTag(id, tagId, tag)
        redirectAttributes.addFlashAttribute("message", "Tag updated successfully")
        return "redirect:/domains/$id/tags" // Redirect back to tags page after update
    }

    @DeleteMapping("/domains/{id}/tags/{tagId}")
    fun deleteTag(
            @PathVariable id: UUID,
            @PathVariable tagId: UUID,
            redirectAttributes: RedirectAttributes
        ): String {
        tagsService.deleteTag(id, tagId)
        redirectAttributes.addFlashAttribute("message", "Tag deleted successfully")
        return "redirect:/domains/$id/tags" // Redirect back to tags page after deletion
    }

    @GetMapping("/domains/{id}/tags/{tagId}/children")
    fun getChildTags(
        @PathVariable id: UUID, 
        @PathVariable tagId: UUID,
        model: Model
        ): String {
        model.addAttribute("domainId", id)
        model.addAttribute("tagId", tagId)
        model.addAttribute("childTags", tagsService.getChildTags(id, tagId))
        return "domain/child-tags" // Return view for displaying child tags
    }

}

data class TagForm(val name: String, val description: String, val path: String)
