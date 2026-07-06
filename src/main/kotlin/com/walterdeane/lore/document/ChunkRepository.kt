package com.walterdeane.lore.document

import com.walterdeane.lore.model.Chunk
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
class ChunkRepository(private val jdbcTemplate: JdbcTemplate) {

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
