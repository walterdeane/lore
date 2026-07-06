package com.walterdeane.lore.document

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "lore.storage")
data class DocumentStorageProperties(
    val documentsDir: String,
)
