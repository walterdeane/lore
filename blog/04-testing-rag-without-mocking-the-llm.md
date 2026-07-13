# Testing a RAG Pipeline Without Mocking the LLM

## Angle
It's tempting to mock everything AI-adjacent in tests — the embedding model, the LLM, the vector
store — because real infra is slow and annoying to set up. This post argues that for a RAG pipeline
specifically, that temptation is a trap: mocks can't catch the failure modes that actually matter
(parsing quirks, real embedding behavior, real infra flakiness), and walks through building a test
suite that hits real Postgres/pgvector and real local Ollama instead.

## Hook
Open with the Testcontainers bug: a shared Postgres `@Container` field, put in an abstract base
class's companion object on the assumption that JUnit5's `@Testcontainers` field-discovery would
treat it as one JVM-wide singleton across subclasses. It didn't — each subclass silently got its own
container, and once two competed for Docker resources, connections intermittently failed. This looked
first like environmental flakiness (a loaded Docker Desktop VM, then a "genuinely unhealthy" Docker
Desktop install that got reinstalled) before the real, deterministic cause was found.

## Sections

1. **The instinct to mock, and why it's wrong here**
   - Mocking the LLM/embedder in a RAG pipeline test tells you your code calls the right functions —
     it tells you nothing about whether extraction, chunking, or retrieval actually work on real data.
   - The bugs that actually hit this project (malformed EPUB XML, PDF outline vs. font heuristics,
     running-header leakage) are all things a mocked pipeline would have sailed through.

2. **Splitting the test suite: `test` vs `integrationTest`**
   - Added the `jvm-test-suite` Gradle plugin with a dedicated `integrationTest` source set/task — the
     modern Gradle equivalent of Maven's Surefire/Failsafe split.
   - Why the split matters: tests needing real local infra (Testcontainers Postgres, local Ollama)
     need to stay out of the default `./gradlew build`/`check` path so CI/quick iteration isn't gated
     on Docker + a running Ollama instance.
   - Close the obvious follow-up question right here, don't let it wait: **does this run in CI?** No —
     there's no CI workflow in this repo today, and the suite needs local Ollama, so it's a pre-release
     local gate, not a CI gate. State the actual runtime cost too (time `./gradlew integrationTest` on
     your machine — Testcontainers Postgres startup + real Ollama embedding calls — and give the real
     number). Two sentences, stated plainly, closes the hole before a reader has to ask.
   - `SemanticTextSplitterSmokeTest` moved here, replacing an ad-hoc `SMOKE=true` env-var gate with the
     task split itself — a good example of formalizing an informal convention once it proves useful.

3. **The Testcontainers singleton bug, in full**
   - The assumption: a companion-object `@Container` field in an abstract base class would be shared
     JVM-wide across subclasses.
   - The reality: each subclass got its own container silently (confirmed via containerId logging).
   - The red herring: looked like flaky infra first (loaded Docker Desktop VM), escalated to "maybe
     Docker Desktop itself is unhealthy" (reinstalled it) before the actual deterministic cause was
     isolated.
   - The fix: a plain top-level Kotlin `object` (an unambiguous singleton) wired via
     `@DynamicPropertySource` instead of `@Testcontainers`/`@Container`/`@ServiceConnection`.
   - The lesson: when a bug is intermittent, resist jumping straight to "environment is flaky" —
     confirm with direct evidence (containerId logging, in this case) before treating it as
     unreproducible noise.

4. **What real end-to-end coverage actually caught**
   - `DocumentIngestionServiceIntegrationTest`: real Tika extraction → real STRUCTURAL chunking → real
     `nomic-embed-text` embedding via local Ollama → real Postgres/pgvector storage, against two
     checked-in public-domain fixtures (FDA "Bad Bug Book", "Jekyll and Hyde").
   - Deliberately used STRUCTURAL explicitly for both fixtures rather than the app's TOKEN default —
     TOKEN never touches the markdown parsers at all, so it wouldn't exercise the outline-parsing/
     running-header logic the test exists to catch regressions in. Worth a beat on this: picking test
     inputs that actually exercise the code path you care about, not just "a" input.
   - Assertions: chunk count, embedding dimensionality (768), full-text-search `search_vector`
     population, domain/tag denormalization — checking real properties of real output, not mock call
     counts.

5. **The fixture that got rejected**
   - An archive.org-scanned EPUB was tried first but rejected as a fixture — Tika's own `EpubParser`
     threw `EpubZipException` on it (not a strictly valid EPUB container per spec). Same incident
     covered in post 01 as an ingestion war story; here, reference it briefly rather than retelling —
     the angle here is fixture selection ("a file happens to be broken" isn't the same bug as "my code
     doesn't handle valid input," and how to tell the difference when picking test fixtures).

6. **Takeaways**
   - For an AI pipeline specifically, real infra in an isolated, non-default test suite gives you
     confidence unit-mocked tests structurally cannot.
   - Intermittent test failures deserve a deterministic root cause before being written off as flaky —
     the Testcontainers bug is the case study.

## Possible closing note
Could pair well as a follow-up/companion piece to the "what breaks with real books" post — that post
is the bugs found in production data, this one is the infrastructure built to catch regressions once
those bugs were fixed.
