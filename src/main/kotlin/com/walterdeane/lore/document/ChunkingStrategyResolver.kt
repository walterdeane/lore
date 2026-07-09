package com.walterdeane.lore.document

import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.Domain
import com.walterdeane.lore.model.StructuralVariant
import org.springframework.stereotype.Service

/**
 * Picks which chunking approach [DocumentIngestionService] uses for a given document: a per-document
 * override, else a per-domain default, else the app-wide default from [ChunkingProperties].
 */
@Service
class ChunkingStrategyResolver(private val props: ChunkingProperties) {

    /** TOKEN (fixed-size), STRUCTURAL (heading-aware, see [StructuralTextSplitter]), or SEMANTIC (unimplemented). */
    fun resolve(document: Document, domain: Domain): ChunkingStrategy =
        document.chunkStrategy ?: domain.chunkStrategy ?: props.defaultStrategy

    /** Only meaningful for STRUCTURAL: which [BoundaryConfig] shape (cookbook, academic, generic) to apply. */
    fun resolveVariant(document: Document, domain: Domain): StructuralVariant =
        document.structuralVariant ?: domain.structuralVariant ?: StructuralVariant.GENERIC
}
