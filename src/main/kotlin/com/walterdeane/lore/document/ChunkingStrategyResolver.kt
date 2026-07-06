package com.walterdeane.lore.document

import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.Domain
import org.springframework.stereotype.Service

@Service
class ChunkingStrategyResolver(private val props: ChunkingProperties) {

    fun resolve(document: Document, domain: Domain): ChunkingStrategy =
        document.chunkStrategy ?: domain.chunkStrategy ?: props.defaultStrategy
}
