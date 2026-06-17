package com.walterdeane.lore.tags

import java.util.UUID
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller()
class TagsViewController(val tagsService: TagsService) {

    @GetMapping("/collections/{id}/tags")
    fun showPage(@PathVariable id: UUID, model: Model): String {
        model.addAttribute("collectionId", id)
        model.addAttribute("tags", tagsService.getTagsForCollection(id))
        return "collection/tags"
    }

    @PostMapping("/collections/{id}/tags")
    fun createTag(@PathVariable id: UUID, @ModelAttribute tag: TagForm, redirectAttributes: RedirectAttributes): String {
        tagsService.createTag(id, tag)
        redirectAttributes.addFlashAttribute("message", "Tag created successfully")
        return "redirect:/collections/$id/tags"
    }

    @PutMapping("/collections/{id}/tags/{tagId}")
    fun updateTag(
            @PathVariable id: UUID,
            @PathVariable tagId: UUID,
            @ModelAttribute tag: TagForm,
            redirectAttributes: RedirectAttributes
    ): String {
        tagsService.updateTag(id, tagId, tag)
        redirectAttributes.addFlashAttribute("message", "Tag updated successfully")
        return "redirect:/collections/$id/tags" // Redirect back to tags page after update
    }

    @DeleteMapping("/collections/{id}/tags/{tagId}")
    fun deleteTag(
            @PathVariable id: UUID,
            @PathVariable tagId: UUID,
            redirectAttributes: RedirectAttributes
        ): String {
        tagsService.deleteTag(id, tagId)
        redirectAttributes.addFlashAttribute("message", "Tag deleted successfully")
        return "redirect:/collections/$id/tags" // Redirect back to tags page after deletion
    }

    @GetMapping("/collections/{id}/tags/{tagId}/children")
    fun getChildTags(
        @PathVariable id: UUID, 
        @PathVariable tagId: UUID,
        model: Model
        ): String {
        model.addAttribute("collectionId", id)
        model.addAttribute("tagId", tagId)
        model.addAttribute("childTags", tagsService.getChildTags(id, tagId))
        return "collection/child-tags" // Return view for displaying child tags
    }

}

data class TagForm(val name: String, val description: String, val path: String)
