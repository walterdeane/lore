package com.walterdeane.lore.domain

import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Domain
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class DomainRepository(private val jdbcTemplate: JdbcTemplate) {

    fun findAll(query: String? = null): List<Domain> {
        if (query.isNullOrBlank()) {
            return jdbcTemplate.query("SELECT id, name, description, chunk_strategy FROM domain") { rs, _ -> mapRow(rs) }
        }
        return jdbcTemplate.query(
            "SELECT id, name, description, chunk_strategy FROM domain WHERE name ILIKE ?",
            { rs, _ -> mapRow(rs) },
            "%$query%"
        )
    }

    fun findById(id: UUID): Domain? {
        return jdbcTemplate.query(
            "SELECT id, name, description, chunk_strategy FROM domain WHERE id = ?",
            { rs, _ -> mapRow(rs) },
            id
        ).firstOrNull()
    }

    fun save(domain: Domain): Domain {
        jdbcTemplate.update(
            "INSERT INTO domain (id, name, description, chunk_strategy) VALUES (?, ?, ?, ?)",
            domain.id, domain.name, domain.description, domain.chunkStrategy?.name
        )
        return domain
    }

    fun update(domain: Domain): Domain {
        jdbcTemplate.update(
            "UPDATE domain SET name = ?, description = ?, chunk_strategy = ? WHERE id = ?",
            domain.name, domain.description, domain.chunkStrategy?.name, domain.id
        )
        return domain
    }

    fun deleteById(id: UUID) {
        jdbcTemplate.update("DELETE FROM domain WHERE id = ?", id)
    }

    private fun mapRow(rs: java.sql.ResultSet) = Domain(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        description = rs.getString("description"),
        chunkStrategy = rs.getString("chunk_strategy")?.let { ChunkingStrategy.valueOf(it) },
    )
}