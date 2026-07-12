plugins {
	kotlin("jvm") version "2.1.0"
	kotlin("plugin.spring") version "2.1.0"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	`jvm-test-suite`
}

group = "com.walterdeane"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	// Pageable/Page/PageableDefault (used for document/domain list pagination) need both the model
	// classes (spring-data-commons) and Boot's autoconfiguration that registers the MVC argument
	// resolver for them (spring-boot-data-commons, in Boot 4.x's per-feature autoconfigure split) —
	// this app has no JPA repositories, so pull these in directly rather than via
	// spring-boot-starter-data-jpa.
	implementation("org.springframework.data:spring-data-commons")
	implementation("org.springframework.boot:spring-boot-data-commons")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("nz.net.ultraq.thymeleaf:thymeleaf-layout-dialect:3.3.0")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springframework.ai:spring-ai-starter-model-ollama")
	implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
	implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
	implementation("org.springframework.ai:spring-ai-vector-store-advisor")
	implementation("org.springframework.ai:spring-ai-tika-document-reader")
	// Used by PdfMarkdownParser to key off a PDF's real embedded outline/TOC when one exists,
	// instead of always guessing headings from font size — see TODO.md's PdfMarkdownParser entry.
	implementation("org.springframework.ai:spring-ai-pdf-document-reader")
	implementation("org.jsoup:jsoup:1.18.3")
	// Must match the pdfbox version tika-parser-pdf-module (pulled in transitively via
	// spring-ai-tika-document-reader) actually requests, or Tika's PDF parser breaks at runtime
	// with NoSuchMethodError — check via `./gradlew dependencyInsight --dependency org.apache.pdfbox:pdfbox`.
	implementation("org.apache.pdfbox:pdfbox:3.0.7")
	implementation("org.commonmark:commonmark:0.22.0")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.springframework.boot:spring-boot-docker-compose")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.springframework.ai:spring-ai-spring-boot-docker-compose")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Real-Postgres/real-Ollama tests: slow, need local infra, not part of `./gradlew build`/`check`.
// Run explicitly with `./gradlew integrationTest`. Equivalent to what Failsafe was for in Maven.
testing {
	suites {
		val integrationTest by registering(JvmTestSuite::class) {
			useJUnitJupiter()
			dependencies {
				implementation(project())
				runtimeOnly("org.junit.platform:junit-platform-launcher")
			}
			targets {
				all {
					testTask.configure {
						shouldRunAfter(tasks.test)
					}
				}
			}
		}
	}
}

// integrationTest (above) is a source set the jvm-test-suite plugin creates, not the built-in
// "test" one, so it doesn't get Kotlin's automatic friend-module access to main's `internal`
// declarations for free — associate it explicitly. Lazy `named(...)` (not `getByName`) since this
// script runs top-to-bottom and the compilation is only created by the `testing` block above.
kotlin {
	target.compilations.named("integrationTest") {
		associateWith(target.compilations.getByName("main"))
	}
}
