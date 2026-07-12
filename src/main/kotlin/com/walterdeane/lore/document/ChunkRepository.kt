package com.walterdeane.lore.document

import com.walterdeane.lore.model.Chunk
import com.walterdeane.lore.model.ChunkingStrategy
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * Raw JDBC access to the `chunk` table — the unit of retrieval in this RAG system. Each row holds
 * both the chunk's text (for lexical search/display) and its embedding vector (for pgvector similarity
 * search), so a single table serves both halves of [com.walterdeane.lore.search.HybridSearchService].
 */
@Repository
class ChunkRepository(private val jdbcTemplate: JdbcTemplate) {

    fun deleteByDocumentId(documentId: UUID) {
        jdbcTemplate.update("DELETE FROM chunk WHERE document_id = ?", documentId)
    }

    fun updateTagPathsByDocumentId(documentId: UUID, tagPaths: List<String>) {
        jdbcTemplate.update("UPDATE chunk SET tag_paths = ? WHERE document_id = ?") { ps ->
            ps.setArray(1, ps.connection.createArrayOf("ltree", tagPaths.toTypedArray()))
            ps.setObject(2, documentId)
        }
    }

    fun findById(id: UUID): Chunk? =
        jdbcTemplate.query(
            """SELECT id, document_id, domain_id, tag_paths, content, chunk_index,
                      chunk_strategy, page_number, token_count, created_at
               FROM chunk WHERE id = ?""",
            { rs, _ -> mapRow(rs) },
            id
        ).firstOrNull()

    /** Looks up the chunk immediately before/after a given one in the same document, by [chunkIndex] — for prev/next navigation. */
    fun findIdByDocumentIdAndChunkIndex(documentId: UUID, chunkIndex: Int): UUID? =
        jdbcTemplate.query(
            "SELECT id FROM chunk WHERE document_id = ? AND chunk_index = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            documentId, chunkIndex
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
        pageNumber = rs.getInt("page_number").takeIf { !rs.wasNull() },
        tokenCount = rs.getInt("token_count").takeIf { !rs.wasNull() },
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    /** Persists a chunk's text and embedding; the `embedding` column is pgvector, written via [PGobject]. */
    fun save(chunk: Chunk): Chunk {
        val sql =
                "INSERT INTO chunk (id, document_id, domain_id, tag_paths, content, embedding, " +
                " chunk_index, chunk_strategy, page_number, token_count, created_at) " +
                " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
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
            ps.setObject(9, chunk.pageNumber)
            ps.setObject(10, chunk.tokenCount)
            ps.setTimestamp(11, Timestamp.from(chunk.createdAt))
        }
        return chunk
    }

}
