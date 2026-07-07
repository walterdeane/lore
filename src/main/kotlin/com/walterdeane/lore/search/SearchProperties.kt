package com.walterdeane.lore.search

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "lore.search")
data class SearchProperties(
    val candidatePoolSize: Int = 50,
    val rrfK: Int = 60,
)
