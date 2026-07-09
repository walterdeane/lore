package com.walterdeane.lore.domain

import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Domain
import com.walterdeane.lore.model.StructuralVariant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/** Raw JDBC access to the `domain` table — the top-level scope every document, chunk, and tag belongs to. */
@Repository
class DomainRepository(private val jdbcTemplate: JdbcTemplate) {

    fun findAll(query: String? = null): List<Domain> {
        if (query.isNullOrBlank()) {
            return jdbcTemplate.query("SELECT id, name, description, chunk_strategy, structural_variant FROM domain") { rs, _ -> mapRow(rs) }
        }
        return jdbcTemplate.query(
            "SELECT id, name, description, chunk_strategy, structural_variant FROM domain WHERE name ILIKE ?",
            { rs, _ -> mapRow(rs) },
            "%$query%"
        )
    }

    fun findById(id: UUID): Domain? {
        return jdbcTemplate.query(
            "SELECT id, name, description, chunk_strategy, structural_variant FROM domain WHERE id = ?",
            { rs, _ -> mapRow(rs) },
            id
        ).firstOrNull()
    }

    fun save(domain: Domain): Domain {
        jdbcTemplate.update(
            "INSERT INTO domain (id, name, description, chunk_strategy, structural_variant) VALUES (?, ?, ?, ?, ?)",
            domain.id, domain.name, domain.description, domain.chunkStrategy?.name, domain.structuralVariant?.name
        )
        return domain
    }

    fun update(domain: Domain): Domain {
        jdbcTemplate.update(
            "UPDATE domain SET name = ?, description = ?, chunk_strategy = ?, structural_variant = ? WHERE id = ?",
            domain.name, domain.description, domain.chunkStrategy?.name, domain.structuralVariant?.name, domain.id
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
        structuralVariant = rs.getString("structural_variant")?.let { StructuralVariant.valueOf(it) },
    )
}