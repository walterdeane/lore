package com.walterdeane.lore

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan
class LoreApplication

fun main(args: Array<String>) {
	runApplication<LoreApplication>(*args)
}
