package com.walterdeane.lore.tags

import com.walterdeane.lore.model.Tag
import org.springframework.stereotype.Service
import java.util.UUID


@Service
class TagsService {
    // Stub data for UI development. Replace with real persistence later.
    fun getTagsForCollection(collectionId: UUID): List<Tag> {
        return listOf(
            Tag(
                id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                loreCollectionId = collectionId,
                name = "Fermentation",
                description = "Notes on fermentation technique and timing",
                path = "technique.fermentation",
            ),
            Tag(
                id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                loreCollectionId = collectionId,
                name = "Italian",
                description = "Italian regional cuisine",
                path = "cuisine.italian",
            ),
            Tag(
                id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                loreCollectionId = collectionId,
                name = "Reference",
                description = "Quick-reference material, not narrative",
                path = "format.reference",
            ),
        )
    }

    fun createTag(collectionId: UUID, tag: TagForm) {
        // Replace with actual tag creation logic
    }

    fun updateTag(collectionId: UUID, tagId: UUID, tag: TagForm) {
        // Replace with actual tag update logic
    }

    fun deleteTag(collectionId: UUID, tagId: UUID) {
        // Replace with actual tag deletion logic
    }

    fun getChildTags(collectionId: UUID, tagId: UUID): List<Tag> {
        return listOf(
            Tag(
                id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                loreCollectionId = collectionId,
                name = "Northern Italian",
                description = "Northern Italian regional variants",
                path = "cuisine.italian.northern",
            ),
            Tag(
                id = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                loreCollectionId = collectionId,
                name = "Southern Italian",
                description = "Southern Italian regional variants",
                path = "cuisine.italian.southern",
            ),
        )
    }
}
