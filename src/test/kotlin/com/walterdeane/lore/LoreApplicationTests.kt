package com.walterdeane.lore

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@Disabled("Requires a running Postgres — wire up Testcontainers before enabling")
class LoreApplicationTests {

	@Test
	fun contextLoads() {
	}

}
