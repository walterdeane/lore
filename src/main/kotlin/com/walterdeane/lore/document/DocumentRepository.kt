package com.walterdeane.lore.document

import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.IngestionStatus

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
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

    fun updateStatus(id: UUID, status: IngestionStatus, error: String? = null, ingestedAt: Instant? = null) {
        val sql = "UPDATE document SET ingestion_status = ?, ingestion_error = ?, ingested_at = ? WHERE id = ?"
        jdbcTemplate.update(sql) { ps ->
            ps.setString(1, status.name)
            ps.setString(2, error)
            ps.setTimestamp(3, ingestedAt?.let { Timestamp.from(it) })
            ps.setObject(4, id)
        }
    }

}
