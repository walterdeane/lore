package com.walterdeane.lore.document

import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.IngestionStatus
import com.walterdeane.lore.model.SourceType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class DocumentRepository(private val jdbcTemplate: JdbcTemplate) {

    fun save(document: Document): Document {
        val sql =
            "INSERT INTO document (id, domain_id, title, author, source_filename, source_path, " +
            " source_type, tags, ingestion_status, ingestion_error, ingested_at, chunk_strategy) " +
            " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        jdbcTemplate.update(sql) { ps ->
            ps.setObject(1, document.id)
            ps.setObject(2, document.domainId)
            ps.setString(3, document.title)
            ps.setString(4, document.author)
            ps.setString(5, document.sourceFilename)
            ps.setString(6, document.sourcePath)
            ps.setString(7, document.sourceType.name)
            ps.setArray(8, ps.connection.createArrayOf("ltree", document.tags.toTypedArray()))
            ps.setString(9, document.ingestionStatus.name)
            ps.setString(10, document.ingestionError)
            ps.setTimestamp(11, document.ingestedAt?.let { Timestamp.from(it) })
            ps.setString(12, document.chunkStrategy?.name)
        }
        return document
    }

    fun findById(id: UUID): Document? =
        jdbcTemplate.query(
            "SELECT * FROM document WHERE id = ?",
            { rs, _ -> mapRow(rs) },
            id
        ).firstOrNull()

    fun findByDomainId(domainId: UUID, query: String?, limit: Int, offset: Long): List<Document> {
        return if (query.isNullOrBlank()) {
            jdbcTemplate.query(
                "SELECT * FROM document WHERE domain_id = ? ORDER BY title LIMIT ? OFFSET ?",
                { rs, _ -> mapRow(rs) },
                domainId, limit, offset
            )
        } else {
            jdbcTemplate.query(
                "SELECT * FROM document WHERE domain_id = ? AND title ILIKE ? ORDER BY title LIMIT ? OFFSET ?",
                { rs, _ -> mapRow(rs) },
                domainId, "%$query%", limit, offset
            )
        }
    }

    fun countByDomainId(domainId: UUID, query: String?): Long {
        return if (query.isNullOrBlank()) {
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document WHERE domain_id = ?",
                Long::class.java, domainId
            ) ?: 0L
        } else {
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document WHERE domain_id = ? AND title ILIKE ?",
                Long::class.java, domainId, "%$query%"
            ) ?: 0L
        }
    }

    fun updateStatus(id: UUID, status: IngestionStatus, error: String? = null, ingestedAt: Instant? = null) {
        jdbcTemplate.update(
            "UPDATE document SET ingestion_status = ?, ingestion_error = ?, ingested_at = ? WHERE id = ?",
        ) { ps ->
            ps.setString(1, status.name)
            ps.setString(2, error)
            ps.setTimestamp(3, ingestedAt?.let { Timestamp.from(it) })
            ps.setObject(4, id)
        }
    }

    fun updateTags(id: UUID, tags: List<String>) {
        jdbcTemplate.update(
            "UPDATE document SET tags = ? WHERE id = ?"
        ) { ps ->
            ps.setArray(1, ps.connection.createArrayOf("ltree", tags.toTypedArray()))
            ps.setObject(2, id)
        }
    }

    fun deleteById(id: UUID) {
        jdbcTemplate.update("DELETE FROM document WHERE id = ?", id)
    }

    private fun mapRow(rs: ResultSet) = Document(
        id = rs.getObject("id", UUID::class.java),
        domainId = rs.getObject("domain_id", UUID::class.java),
        title = rs.getString("title"),
        author = rs.getString("author"),
        sourceFilename = rs.getString("source_filename"),
        sourcePath = rs.getString("source_path"),
        sourceType = SourceType.valueOf(rs.getString("source_type")),
        tags = (rs.getArray("tags").array as Array<*>).map { it.toString() },
        ingestionStatus = IngestionStatus.valueOf(rs.getString("ingestion_status")),
        ingestionError = rs.getString("ingestion_error"),
        ingestedAt = rs.getTimestamp("ingested_at")?.toInstant(),
        chunkStrategy = rs.getString("chunk_strategy")?.let { ChunkingStrategy.valueOf(it) },
    )
}
