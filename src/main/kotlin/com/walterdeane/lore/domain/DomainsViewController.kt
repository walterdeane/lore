package com.walterdeane.lore.domain

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Domain
import com.walterdeane.lore.model.StructuralVariant
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.RequestParam
import com.walterdeane.lore.tags.TagsService
import java.util.UUID

/** CRUD UI for domains, including their default chunking strategy/structural variant. */
@Controller
class DomainsViewController(
    private val domainsService: DomainsService,
private val tagsService: TagsService
) {

    @GetMapping("/domains")
    fun showPage(
        @RequestParam(required = false) q: String?,
        @PageableDefault(size = 25, sort = ["name"]) pageable: Pageable,
        model: Model
    ): String {
        model.addAttribute("query", q)
        model.addAttribute("domainsPage", domainsService.getDomains(q, pageable))
        model.addAttribute("strategies", ChunkingStrategy.values())
        model.addAttribute("variants", StructuralVariant.values())
        return "domain/index"
    }

    @PostMapping("/domains")
    fun createDomain(@ModelAttribute domainForm: DomainForm): String {
        domainsService.createDomain(Domain(
            id = UUID.randomUUID(),
            name = domainForm.name,
            description = domainForm.description,
            chunkStrategy = domainForm.chunkStrategy?.takeIf { it.isNotBlank() }?.let { ChunkingStrategy.valueOf(it) },
            structuralVariant = domainForm.structuralVariant?.takeIf { it.isNotBlank() }?.let { StructuralVariant.valueOf(it) },
        ))
        return "redirect:/domains"
    }

    @PutMapping("/domains/{id}")
    fun updateDomainById(@PathVariable id: UUID, @ModelAttribute domainForm: DomainForm, redirectAttributes: RedirectAttributes): String {
        domainsService.updateDomainById(Domain(
            id = id,
            name = domainForm.name,
            description = domainForm.description,
            chunkStrategy = domainForm.chunkStrategy?.takeIf { it.isNotBlank() }?.let { ChunkingStrategy.valueOf(it) },
            structuralVariant = domainForm.structuralVariant?.takeIf { it.isNotBlank() }?.let { StructuralVariant.valueOf(it) },
        ))
        redirectAttributes.addFlashAttribute("message", "Domain updated successfully")
        return "redirect:/domains"
    }

    @DeleteMapping("/domains/{id}")
    fun deleteDomainById(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        domainsService.deleteDomainById(id)
        redirectAttributes.addFlashAttribute("message", "Domain deleted successfully")
        return "redirect:/domains"
    }
}

data class DomainForm(
    val name: String,
    val description: String,
    val chunkStrategy: String? = null,
    val structuralVariant: String? = null,
)