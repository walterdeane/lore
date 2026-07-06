package com.walterdeane.lore.tags

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import com.walterdeane.lore.model.Tag 
import java.util.UUID


@Repository
class TagsRepository(private val jdbcTemplate: JdbcTemplate) {
    fun findAllByDomainId(domainId: java.util.UUID, query: String? = null): List<Tag> {
        val sql = if (query.isNullOrBlank()) {
            "SELECT id, domain_id, name, description, path FROM tag WHERE domain_id = ?"
        } else {
            "SELECT id, domain_id, name, description, path FROM tag WHERE domain_id = ? AND name ILIKE ?"
        }
        return jdbcTemplate.query(sql, { rs, _ ->
            Tag(
                id = rs.getObject("id", java.util.UUID::class.java),
                domainId = rs.getObject("domain_id", java.util.UUID::class.java),
                name = rs.getString("name"),
                description = rs.getString("description"),
                path = rs.getString("path")
            )
        }, *(if (query.isNullOrBlank()) arrayOf(domainId) else arrayOf(domainId, "%$query%")))
    }

    fun findAllChildren(id: UUID): List<Tag> {
        val sql = "SELECT id, domain_id, name, description, path FROM tag WHERE path <@ (SELECT path FROM tag WHERE id = ?)::ltree AND id != ?"
        return jdbcTemplate.query(sql, { rs, _ ->
            Tag(
                id = rs.getObject("id", java.util.UUID::class.java),
                domainId = rs.getObject("domain_id", java.util.UUID::class.java),
                name = rs.getString("name"),
                description = rs.getString("description"),
                path = rs.getString("path")
            )
        }, id, id)
    }

    fun isRootTag(id: UUID): Boolean {
        val sql = "SELECT path FROM tag WHERE id = ?"
        val path = jdbcTemplate.queryForObject(sql, String::class.java, id)
        return path != null && !path.contains(".")
    }

    private fun createMissingTagPath(tag: Tag) {
        val parentPath = tag.path.substringBeforeLast(".", "")
        if (parentPath.isNotEmpty()) {
            val sql = "SELECT COUNT(*) FROM tag WHERE domain_id = ? AND path = ?::ltree"
            val count = jdbcTemplate.queryForObject(sql, Int::class.java, tag.domainId, parentPath)
            if (count == 0) {
                val parentTag = Tag(
                    id = UUID.randomUUID(),
                    domainId = tag.domainId,
                    name = parentPath.substringAfterLast(".").replace("_", " ").replaceFirstChar { it.uppercase() },
                    description = "",
                    path = parentPath
                )
                save(parentTag)
            }
        }
    }

    fun save(tag: Tag): Tag {
        val sql = "INSERT INTO tag (id, domain_id, name, description, path) VALUES (?, ?, ?, ?, ?::ltree)"
        createMissingTagPath(tag)
        jdbcTemplate.update(sql, tag.id, tag.domainId, tag.name, tag.description, tag.path)
        return tag
    }

    fun update(tag: Tag): Tag {
        val sql = "UPDATE tag SET name = ?, description = ? WHERE id = ? AND domain_id = ?"
        jdbcTemplate.update(sql, tag.name, tag.description, tag.id, tag.domainId)
        return tag
    }

    fun deleteById(id: UUID) {
        if (findAllChildren(id).isNotEmpty()) {
            throw IllegalStateException("Cannot delete tag with children ${findAllChildren(id)}")
        }
        val sql = "DELETE FROM tag WHERE id = ?"
        jdbcTemplate.update(sql, id)
    }
}