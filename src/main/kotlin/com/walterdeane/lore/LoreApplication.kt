package com.walterdeane.lore

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LoreApplication

fun main(args: Array<String>) {
	runApplication<LoreApplication>(*args)
}
