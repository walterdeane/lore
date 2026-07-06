package com.walterdeane.lore.tags

import com.walterdeane.lore.model.Tag
import org.springframework.stereotype.Service
import java.util.UUID

data class TagNode(val tag: Tag, val children: List<TagNode> = emptyList())

@Service
class TagsService(private val tagsRepository: TagsRepository) {

    // Builds parent/child relationships from each tag's materialized path (e.g.
    // "cuisine.italian.northern" is a child of "cuisine.italian"), so the whole
    // tree can be rendered from one collection without per-node lookups.
    private fun buildTagTree(tags: List<Tag>): List<TagNode> {
        val byPath = tags.associateBy { it.path }
        val childrenByParentPath = mutableMapOf<String, MutableList<Tag>>()
        val roots = mutableListOf<Tag>()

        for (tag in tags) {
            val parentPath = tag.path.substringBeforeLast(".", "")
            if (parentPath.isNotEmpty() && byPath.containsKey(parentPath)) {
                childrenByParentPath.getOrPut(parentPath) { mutableListOf() }.add(tag)
            } else {
                roots.add(tag)
            }
        }

        fun toNode(tag: Tag): TagNode =
            TagNode(tag, childrenByParentPath[tag.path]?.sortedBy { it.name }?.map(::toNode) ?: emptyList())

        return roots.sortedBy { it.name }.map(::toNode)
    }

    fun getTagTree(domainId: UUID, query: String?): List<TagNode> {
        val tags = getDomainTags(domainId)
        val filtered = if (query.isNullOrBlank()) {
            tags
        } else {
            tags.filter { it.name.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true) }
        }
        return buildTagTree(filtered)
    }

    fun createTag(tag: Tag) {
        println("Creating tag: $tag")
        tagsRepository.save(tag)
    }

    fun updateTag(tag: Tag) {
        tagsRepository.update(tag)
    }

    fun deleteTag(tagId: UUID) {
        tagsRepository.deleteById(tagId)
    }

    fun getDomainTags(domainId: UUID): List<Tag> {
        return tagsRepository.findAllByDomainId(domainId)
    }
}
