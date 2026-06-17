package com.walterdeane.lore.collection

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import com.walterdeane.lore.model.LoreCollection
import com.github.victools.jsonschema.generator.InstanceAttributeOverrideV2
import java.util.UUID

@RestController
class CollectionsController(private val collectionsService: CollectionsService) {

    @GetMapping("/collections")
    fun getCollections(): ResponseEntity<List<LoreCollection>> {
        return ResponseEntity.ok(collectionsService.getCollections())
    }

    @PostMapping("/collections")
    fun createCollection(@RequestBody collection: LoreCollection): ResponseEntity<LoreCollection> {
        val created = collectionsService.createCollection(collection)
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.id)
            .toUri()
        return ResponseEntity.created(location).body(created)
    }

    @GetMapping("/collections/{id}")
    fun getCollectionById(@PathVariable id: UUID): ResponseEntity<LoreCollection> {
        return ResponseEntity.ok(collectionsService.getCollectionById(id))
    }

    @PutMapping("/collections/{id}")
    fun updateCollectionById(@PathVariable id: UUID, @RequestBody collection: LoreCollection): ResponseEntity<Void> {
        // Placeholder for update collection logic
        collectionsService.updateCollectionById(id, collection)
        return ResponseEntity.ok().build() 
    }

    @DeleteMapping("/collections/{id}")
    fun deleteCollectionById(@PathVariable id: UUID): ResponseEntity<Void> {
        collectionsService.deleteCollectionById(id)
        return ResponseEntity.noContent().build()
    }









}
