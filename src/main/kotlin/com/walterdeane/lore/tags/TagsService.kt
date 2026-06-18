package com.walterdeane.lore.tags

import com.walterdeane.lore.model.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.util.UUID


@Service
class TagsService {
    // Stub data for UI development. Replace with real persistence later.
    // A real repository can take Pageable straight into the query; this filters/slices in memory.
    fun getTagsForDomain(domainId: UUID, query: String?, pageable: Pageable): Page<Tag> {
        val allTags = listOf(
            Tag(
                id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                domainId = domainId,
                name = "Fermentation",
                description = "Notes on fermentation technique and timing",
                path = "technique.fermentation",
            ),
            Tag(
                id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                domainId = domainId,
                name = "Italian",
                description = "Italian regional cuisine",
                path = "cuisine.italian",
            ),
            Tag(
                id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                domainId = domainId,
                name = "Reference",
                description = "Quick-reference material, not narrative",
                path = "format.reference",
            ),
        )

        val filtered = if (query.isNullOrBlank()) {
            allTags
        } else {
            allTags.filter { it.name.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true) }
        }

        val start = pageable.offset.toInt().coerceIn(0, filtered.size)
        val end = (start + pageable.pageSize).coerceIn(start, filtered.size)
        return PageImpl(filtered.subList(start, end), pageable, filtered.size.toLong())
    }

    fun createTag(domainId: UUID, tag: TagForm) {
        // Replace with actual tag creation logic
    }

    fun updateTag(domainId: UUID, tagId: UUID, tag: TagForm) {
        // Replace with actual tag update logic
    }

    fun deleteTag(domainId: UUID, tagId: UUID) {
        // Replace with actual tag deletion logic
    }

    fun getChildTags(domainId: UUID, tagId: UUID): List<Tag> {
        return listOf(
            Tag(
                id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                domainId = domainId,
                name = "Northern Italian",
                description = "Northern Italian regional variants",
                path = "cuisine.italian.northern",
            ),
            Tag(
                id = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                domainId = domainId,
                name = "Southern Italian",
                description = "Southern Italian regional variants",
                path = "cuisine.italian.southern",
            ),
        )
    }
}
