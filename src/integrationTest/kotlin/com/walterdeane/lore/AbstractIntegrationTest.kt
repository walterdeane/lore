package com.walterdeane.lore

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One real Postgres container (`pgvector/pgvector:pg16`, same image as compose.yaml) shared by every
 * integration test in the JVM. A genuine Kotlin `object` — not a `@Container` field on
 * [AbstractIntegrationTest]'s companion — because JUnit5's `@Testcontainers` field-discovery turned
 * out not to actually share one container across subclasses of an abstract base: each subclass got
 * its own container (confirmed via containerId logging), silently doubling Docker load and causing
 * intermittent "connection refused" failures once two containers were competing for the same
 * resources. A top-level `object` is an unambiguous JVM-wide singleton, so this sidesteps that
 * class-hierarchy/reflection ambiguity entirely.
 */
object SharedPostgresContainer {
    val instance: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .apply { start() }
}

/**
 * Base for tests that need a real Postgres. `@DynamicPropertySource` wires [SharedPostgresContainer]
 * into the Spring context in place of the app's normal docker-compose-based connection;
 * `spring.docker.compose.enabled=false` stops the app's own `compose.yaml` (a second, unrelated
 * Postgres) from also starting during the test.
 */
@SpringBootTest(properties = ["spring.docker.compose.enabled=false"])
abstract class AbstractIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { SharedPostgresContainer.instance.jdbcUrl }
            registry.add("spring.datasource.username") { SharedPostgresContainer.instance.username }
            registry.add("spring.datasource.password") { SharedPostgresContainer.instance.password }
        }
    }
}
