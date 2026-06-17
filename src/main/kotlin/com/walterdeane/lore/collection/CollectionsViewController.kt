package com.walterdeane.lore.collection

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import com.walterdeane.lore.model.LoreCollection
import org.springframework.web.bind.annotation.ModelAttribute
import java.util.UUID

@Controller
class CollectionsViewController(private val collectionsService: CollectionsService) {

    @GetMapping("/collections")
    fun showPage(model:Model): String {
        model.addAttribute("collections", collectionsService.getCollections())
        return "collections/index"
    }

    @PostMapping("/collections")
    fun createCollection(@ModelAttribute collection: CollectionForm): String {
        collectionsService.createCollection(LoreCollection(
            id = UUID.randomUUID(),
            name = collection.name,
            description = collection.description
        ))
        return "redirect:/collections"
    }

    @PutMapping("/collections/{id}")
    fun updateCollectionById(@PathVariable id: UUID, @ModelAttribute collectionForm: CollectionForm, redirectAttributes: RedirectAttributes): String {
        // Placeholder for update collection logic
        collectionsService.updateCollectionById(id, LoreCollection(
            id = id,
            name = collectionForm.name,
            description = collectionForm.description
        ))
        redirectAttributes.addFlashAttribute("message", "Collection updated successfully")
        return "redirect:/collections"
    }

    @DeleteMapping("/collections/{id}")
    fun deleteCollectionById(@PathVariable id: UUID, redirectAttributes: RedirectAttributes): String {
        collectionsService.deleteCollectionById(id)
        redirectAttributes.addFlashAttribute("message", "Collection deleted successfully")
        return "redirect:/collections"
    }
}

data class CollectionForm(
    val name: String,
    val description: String
)