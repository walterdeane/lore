package com.walterdeane.lore.document

import com.walterdeane.lore.model.Chunk
import com.walterdeane.lore.model.ChunkingStrategy
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class ChunkRepository(private val jdbcTemplate: JdbcTemplate) {

    fun findById(id: UUID): Chunk? =
        jdbcTemplate.query(
            """SELECT id, document_id, domain_id, tag_paths, content, chunk_index,
                      chunk_strategy, parent_chunk_id, chunk_level, page_number, token_count, created_at
               FROM chunk WHERE id = ?""",
            { rs, _ -> mapRow(rs) },
            id
        ).firstOrNull()

    private fun mapRow(rs: ResultSet) = Chunk(
        id = rs.getObject("id", UUID::class.java),
        documentId = rs.getObject("document_id", UUID::class.java),
        domainId = rs.getObject("domain_id", UUID::class.java),
        tagPaths = (rs.getArray("tag_paths").array as Array<*>).map { it.toString() },
        content = rs.getString("content"),
        embedding = emptyList(),
        chunkIndex = rs.getInt("chunk_index"),
        chunkStrategy = ChunkingStrategy.valueOf(rs.getString("chunk_strategy")),
        parentChunkId = rs.getObject("parent_chunk_id", UUID::class.java),
        chunkLevel = rs.getString("chunk_level"),
        pageNumber = rs.getInt("page_number").takeIf { !rs.wasNull() },
        tokenCount = rs.getInt("token_count").takeIf { !rs.wasNull() },
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    fun save(chunk: Chunk): Chunk {
        val sql =
                "INSERT INTO chunk (id, document_id, domain_id, tag_paths, content, embedding, " +
                " chunk_index, chunk_strategy, parent_chunk_id, chunk_level, page_number, token_count, created_at) " +
                " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        jdbcTemplate.update(sql) { ps ->
            ps.setObject(1, chunk.id)
            ps.setObject(2, chunk.documentId)
            ps.setObject(3, chunk.domainId)
            ps.setArray(4, ps.connection.createArrayOf("ltree", chunk.tagPaths.toTypedArray()))
            ps.setString(5, chunk.content)
            ps.setObject(6, PGobject().apply {
                type = "vector"
                value = chunk.embedding.joinToString(",", "[", "]")
            })
            ps.setInt(7, chunk.chunkIndex)
            ps.setString(8, chunk.chunkStrategy.name)
            ps.setObject(9, chunk.parentChunkId)
            ps.setString(10, chunk.chunkLevel)
            ps.setObject(11, chunk.pageNumber)
            ps.setObject(12, chunk.tokenCount)
            ps.setTimestamp(13, Timestamp.from(chunk.createdAt))
        }
        return chunk
    }

}
