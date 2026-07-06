package com.walterdeane.lore.tags

import java.util.UUID
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
import com.walterdeane.lore.domain.DomainsService
import com.walterdeane.lore.model.Tag

@Controller()
class TagsViewController(val tagsService: TagsService, val domainsService: DomainsService) {

    @GetMapping("/domains/{id}/tags")
    fun showPage(
        @PathVariable id: UUID,
        @RequestParam(required = false) q: String?,
        model: Model
    ): String {
        model.addAttribute("domain", domainsService.getDomainById(id))
        model.addAttribute("tagTree", tagsService.getTagTree(id, q))
        model.addAttribute("query", q)
        return "domain/tags"
    }

    @PostMapping("/domains/{id}/tags")
    fun createTag(@PathVariable id: UUID, @ModelAttribute tag: TagForm, redirectAttributes: RedirectAttributes): String {
        tagsService.createTag(Tag(
            id = UUID.randomUUID(),
            domainId = id,
            name = tag.name,
            description = tag.description,
            path = tag.path
        ))
        redirectAttributes.addFlashAttribute("message", "Tag created successfully")
        return "redirect:/domains/$id/tags"
    }

    @PutMapping("/domains/{id}/tags/{tagId}")
    fun updateTag(
            @PathVariable id: UUID,
            @PathVariable tagId: UUID,
            @ModelAttribute tagForm: TagForm,
            redirectAttributes: RedirectAttributes
    ): String {
        val tag = Tag(
            id = tagId,
            domainId = id,
            name = tagForm.name,
            description = tagForm.description,
            path = tagForm.path
        )
        tagsService.updateTag(tag)
        redirectAttributes.addFlashAttribute("message", "Tag updated successfully")
        return "redirect:/domains/$id/tags" // Redirect back to tags page after update
    }

    @DeleteMapping("/domains/{id}/tags/{tagId}")
    fun deleteTag(
            @PathVariable id: UUID,
            @PathVariable tagId: UUID,
            redirectAttributes: RedirectAttributes
        ): String {
        tagsService.deleteTag(tagId)
        redirectAttributes.addFlashAttribute("message", "Tag deleted successfully")
        return "redirect:/domains/$id/tags" // Redirect back to tags page after deletion
    }

}

data class TagForm(val name: String, val description: String, val path: String)
