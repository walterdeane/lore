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
        model.addAttribute("availableTags", tagsService.getDomainTags(id).sortedBy { it.path })
        model.addAttribute("query", q)
        return "domain/tags"
    }

    @PostMapping("/domains/{id}/tags")
    fun createTag(@PathVariable id: UUID, @ModelAttribute tag: TagForm, redirectAttributes: RedirectAttributes): String {
        val slug = tag.name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val path = if (tag.parentPath.isBlank()) slug else "${tag.parentPath}.$slug"
        tagsService.createTag(Tag(
            id = UUID.randomUUID(),
            domainId = id,
            name = tag.name.trim(),
            description = tag.description,
            path = path,
        ))
        redirectAttributes.addFlashAttribute("message", "Tag '${tag.name.trim()}' created at path '$path'")
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

data class TagForm(
    val name: String = "",
    val description: String = "",
    val parentPath: String = "",
    val path: String = "",   // still submitted by the edit row (readonly), ignored by update SQL
)
