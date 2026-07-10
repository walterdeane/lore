# Lore — TODO / plan to completion

Status snapshot of what's built vs what's still open. See PRD.md for the domain model and design
rationale — this file just tracks what's left and what's been decided.

## Open — undecided

These need a decision before they can be scoped as real work:

- **Multi-domain search.** The original PRD's query shape took `domainIds` (plural); the actual
  implementation (`HybridSearchService`, `ChatViewController`) only ever scopes to one domain per
  query. Worth building, or is single-domain the real design going forward?
- **Document `createdAt`/`updatedAt`.** In the PRD's original domain model, not in the `Document`
  model or schema. Add them, or confirm they're not needed?
- **Tag "children" endpoint.** `GET /domains/{id}/tags/{tagId}/children` was in the original API
  surface but never built — `TagsService.getTagTree` supersedes it for the UI. Worth adding as a
  real endpoint for something else, or is the tree-building approach sufficient?
- **Documents list layout at scale.** `domain/documents.html` renders documents as a card grid
  (`@PageableDefault(size = 25)`); fine at current volumes (~10 documents), but flagged for revisit
  once there are a lot more. Card blocks don't scale well past a few dozen — a denser table (title,
  author, tags, status, actions as columns) would pack far more rows on screen. Leaning toward
  table + pagination + the existing title search (not search-only, since browsing a domain's full
  catalog is still useful) — not decided.

## Open — scoped, not yet built

- **SEMANTIC chunking strategy** — declared in `ChunkingStrategy` but unimplemented; silently falls
  back to TOKEN (with overlap). Real semantic chunking (e.g. embedding-similarity-based splitting)
  still needs designing.
- **`DocumentIngestionService` integration tests.** Still no test for the actual end-to-end
  ingestion pipeline (Tika extraction → chunk → embed → store) — that needs a real Postgres
  (Testcontainers) and a real or stubbed embedding model, a bigger undertaking than the unit tests
  just added for the pure logic pieces below. Not covered by this pass.

## Explicitly deferred (not active work)

- **JSON `/query` RAG API** — dropped in favor of possibly exposing retrieval/chat as an MCP server
  later, rather than a REST API.
- **Parent-child ("small-to-big") retrieval** — downgraded from planned to "interesting technique to
  demo someday." STRUCTURAL chunking already produces coherent, appropriately-sized chunks for most
  of what parent-child would buy; token overlap covers the "fact split at a chunk boundary" case for
  TOKEN chunking instead. `parent_chunk_id`/`chunk_level` were removed from the schema entirely
  rather than left as unused columns.
- **Markdown / web-clip source types** — PRD said "design for later"; only PDF and EPUB exist today.

## Recently completed

- **Test coverage for chunking and upload-detection logic** — 47 new tests across
  `MarkdownChunkerTest`, `TokenOverlapChunkerTest`, `StructuralTextSplitterTest`,
  `EpubZipResolverTest`, and `RerankerServiceTest`. Two small behavior-preserving refactors made
  this possible without mocks, matching the existing `fuse()`-in-`HybridSearchService` pattern:
  extracted the EPUB/zip upload-detection logic out of `DocumentsService` into its own
  dependency-free `EpubZipResolver`, and pulled `RerankerService`'s response-parsing regex out into
  a standalone `parseRerankOrder` function. `DocumentIngestionService` integration tests are a
  separate, bigger undertaking — see above.
- **Fixed PDF ingestion.** `NoSuchMethodError: PDF2XHTML.setIgnoreContentStreamSpaceGlyphs` — our
  explicit `pdfbox:3.0.3` conflicted with the `pdfbox:3.0.7` that `tika-parser-pdf-module:3.3.1`
  actually requests (confirmed via `./gradlew dependencyInsight --dependency org.apache.pdfbox:pdfbox`).
  Bumped to `3.0.7` to match. Also: `DocumentIngestionService.ingest`'s catch block only caught
  `Exception`, not `Error`, so this failure mode left the document stuck at `PENDING` forever with no
  error recorded instead of marking it `FAILED` — changed to catch `Throwable`.
- **Replaced the `getDocumentChunks` JSON stub** with a "View chunks" link on the document detail
  page that jumps to chunk index 0, using "nexting" to walk the rest — a better fit now that the
  chunk detail page shows far more than a flat JSON array would. `DocumentsController` deleted
  entirely (it only ever held that one stub).
- **"Nexting" — prev/next chunk navigation** on the chunk detail page (`/search/chunks/{id}`), via
  `ChunkRepository.findIdByDocumentIdAndChunkIndex` (no schema changes needed).

- Removed the unused JPA dependency: `spring-boot-starter-data-jpa`, `kotlin("plugin.jpa")`, and the
  `allOpen` block for `@Entity`/`@MappedSuperclass`/`@Embeddable`. The app genuinely uses Spring
  Data's `Pageable`/`Page`/`PageableDefault` for list pagination though (not JPA-specific) — kept
  those by depending directly on `spring-data-commons` (the model classes) and
  `spring-boot-data-commons` (Boot 4.x's per-feature autoconfigure module that registers the MVC
  argument resolver for `Pageable` — Boot 4.x split what used to be one `SpringDataWebAutoConfiguration`
  in the monolithic `spring-boot-autoconfigure` jar into separate per-store modules).
- Fixed the document title/author/source-path edit form (was posting `_method=update`, not a real
  HTTP verb `HiddenHttpMethodFilter` recognizes, so it silently 405'd — added the missing
  `@PutMapping` handler and fixed the form to send `_method=put`).
- Hybrid BM25 + vector search fused with Reciprocal Rank Fusion, plus optional LLM-based listwise
  reranking.
- Three chunking strategies: TOKEN (with configurable overlap), STRUCTURAL (heading-aware, with
  GENERIC/COOKBOOK/ACADEMIC variants), SEMANTIC (stub, falls back to TOKEN).
- RAG chat with an Ollama/Anthropic provider switch, citations shown alongside answers.
- Robust EPUB upload handling: trailing whitespace in filenames, macOS "Compress"-wrapped single
  files, and browser-zipped folder-style EPUB bundles (e.g. Apple Books exports).
- Friendly error page and catch-all exception handling (no more raw Whitelabel pages); configurable
  upload size limit.
- `SearchType` (BM25/EMBEDDING/BOTH) surfaced per result, wired into the search UI, for
  retrieval-accuracy evaluation.
- Full server-rendered UI (domains, tags, documents, search, chat) — see PRD.md's API Surface
  section for what replaced the original JSON-API plan.
- README + `docs/SETUP.md` + `docs/CONFIGURATION.md`.
- Removed dead code: `QueryController`, `DocumentsController`, the unused chunk parent/child
  hierarchy columns.
