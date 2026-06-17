package com.walterdeane.lore.tags

import com.walterdeane.lore.model.Tag
import org.springframework.stereotype.Service
import java.util.UUID


@Service
class TagsService {
    // Placeholder for actual tag management logic
    fun getTagsForCollection(collectionId: UUID): List<Tag> {
        return emptyList() // Replace with actual tag retrieval logic
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
        return emptyList() // Replace with actual child tag retrieval logic
    }
}
