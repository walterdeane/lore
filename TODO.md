# Lore — TODO / plan to completion

Status snapshot of what's built vs what's still open. See PRD.md for the domain model and design
rationale — this file just tracks what's left and what's been decided.

## Open — scoped, not yet built

- **HyDE (Hypothetical Document Embeddings).** Pre-search query transformation: ask the chat LLM to
  generate a hypothetical answer to the query, then embed and search with that instead of (or
  alongside) the raw query — a hypothetical answer sits closer in embedding space to real answer
  chunks than the bare question does. Complements the existing post-search LLM reranking rather
  than replacing it (see `RerankerService`). Not yet designed — where it'd hook into
  `HybridSearchService`, whether it's on by default or a toggle like `rerank-enabled`, and how it
  interacts with the BM25 leg (which wants the literal query terms, not a paraphrase) all need
  thought.

## Explicitly deferred (not active work)

- **Multi-domain search** — confirmed as out of scope. The PRD's original query shape took
  `domainIds` (plural); single-domain-per-query (`HybridSearchService`, `ChatViewController`) is the
  real design going forward, not a gap to fill in.
- **Document `createdAt`/`updatedAt`** — confirmed as not needed. Was in the PRD's original domain
  model but never made it into the `Document` model or schema; not useful metadata for this app.
- **Tag "children" endpoint** — confirmed not worth building. `GET /domains/{id}/tags/{tagId}/children`
  was in the original planned API surface but `TagsService.getTagTree` (builds the whole tree in one
  call) already covers the UI need. The only cases where a scoped children-lookup would matter — a
  future MCP/API consumer, or lazy-loaded tree UI at much larger tag counts — are both speculative
  and cheap to add later on demand via ltree, not worth building ahead of an actual need.
- **JSON `/query` RAG API** — dropped in favor of possibly exposing retrieval/chat as an MCP server
  later, rather than a REST API.
- **Parent-child ("small-to-big") retrieval** — downgraded from planned to "interesting technique to
  demo someday." STRUCTURAL chunking already produces coherent, appropriately-sized chunks for most
  of what parent-child would buy; token overlap covers the "fact split at a chunk boundary" case for
  TOKEN chunking instead. `parent_chunk_id`/`chunk_level` were removed from the schema entirely
  rather than left as unused columns.
- **Markdown / web-clip source types** — PRD said "design for later"; only PDF and EPUB exist today.
- **Cross-encoder reranking** — an alternative to the current LLM-listwise reranker (`RerankerService`),
  scoring `(query, passage)` pairs directly via a dedicated model (e.g. `bge-reranker-base/large`,
  `ms-marco-MiniLM`) instead of asking the chat LLM to order a list. Likely cheaper/faster and more
  reliable than listwise rerank, but Ollama has no cross-encoder serving mode, so it would mean
  standing up a new local serving path (ONNX/sentence-transformers runtime or a dedicated rerank
  server) — new infra surface, not a config swap. Revisit if listwise rerank quality proves
  inadequate in practice; not a near-term priority.

## Recently completed

- **Fixed ingestion failing outright on a Tika-incompatible file even under STRUCTURAL/SEMANTIC,
  which never needed Tika's extraction in the first place.** Found via a real failed import
  ("Thinking, Fast and Slow", a libgen.li-sourced EPUB) — `TIKA-237: Illegal SAXException`, root
  cause a single malformed tag (`<divlity ...></div>`, a scraping/OCR artifact) in 1 of the book's 70
  content files, tripping Tika's strict SAX parser. Confirmed via a scratch probe that
  `EpubMarkdownParser` (Jsoup, lenient) extracts the entire 1.16M-character book fine, malformed tag
  and all — the only reason ingestion failed was `DocumentIngestionService.ingest()` calling Tika's
  `TikaDocumentReader.get()` unconditionally, before even branching on chunking strategy, even though
  STRUCTURAL/SEMANTIC only use Tika's `pages` as a fallback when their own Jsoup-based markdown parse
  comes back blank/insufficient. Fixed by changing `StructuralTextSplitter.split`/
  `SymanticTextSplitter.split`'s `pages` parameter from `List<Document>` to `() -> List<Document>`
  (`reader::get`), so Tika only actually runs for TOKEN (which needs it unconditionally) or for the
  other two strategies' fallback path if it's ever reached. Re-verified against the real failing book
  end-to-end: 496 chunks, ingestion completes.
- **Fixed tag pills showing raw ltree paths instead of names.** Both `domain/documents.html` and
  `domain/document.html` resolved a document's tag paths to display names via
  `${tagsByPath[tagPath] != null ? tagsByPath[tagPath].name : tagPath}` — the bracket `[tagPath]`
  map-index syntax silently returned `null` for every lookup (confirmed by comparing it directly
  against `tagsByPath.get(tagPath)` in the same request, which found the entry correctly) even
  though `tagsByPath` and the document's tag paths were byte-identical, same-domain, correctly
  populated data all the way down through the JDBC layer — a genuine SpringEL/Thymeleaf quirk with
  bracket-indexer syntax on a `Map<String, _>` keyed by a dynamic String containing dots, not a data
  or repository bug. Fixed by switching both templates to explicit `.get(tagPath)` calls.
- **SEMANTIC chunking strategy** — `SymanticTextSplitter` implements embedding-similarity breakpoint
  detection (paragraph sliding windows → cosine distance between neighbors → per-document percentile
  threshold → cut, oversized chunks capped via `TokenTextSplitter` fallback). Defaults
  (`windowSize=2`, `breakpointPercentile=0.85`, `maxChunkChars=4000`) were tuned against a real
  cookbook EPUB via `SymanticTextSplitterSmokeTest`, now in the `integrationTest` source set (run via
  `./gradlew integrationTest`, not part of the normal `test` task — needs local Ollama running).
  Wired into `DocumentIngestionService`'s `SEMANTIC` branch.
- **Documents list is now a dense table, not a card grid.** `domain/documents.html` renders title,
  author, tags, status, and actions as table columns (reusing the `.tags-table` CSS pattern already
  established by `domain/tags.html`, broadened to `.tags-table, .documents-table` rather than
  duplicating rules). Pagination and the existing title search were untouched — same
  `@PageableDefault`/query logic as before, just a different row layout. `DocumentsViewController`
  now also passes `tagsByPath` to the list page (previously only the detail page had it), so tag
  pills can resolve ltree paths to names the same way the detail page already does.
- **`DocumentIngestionService` integration tests.** Real end-to-end coverage of the ingestion
  pipeline (Tika extraction → STRUCTURAL chunking → real `nomic-embed-text` embedding via local
  Ollama → Postgres/pgvector storage), in the new `integrationTest` Gradle source set (see "Gradle
  `integrationTest` suite" below). `DocumentIngestionServiceIntegrationTest` ingests two checked-in
  public-domain fixtures — `fda-bad-bug-book-2nd-ed.pdf` (FDA "Bad Bug Book", 2nd ed.; 292 pages,
  real embedded outline) and `jekyll-and-hyde.epub` (Standard Ebooks) — and asserts on chunk count,
  embedding dimensionality (768), BM25 `search_vector` population, and domain/tag denormalization.
  Both fixtures use STRUCTURAL explicitly rather than the app's TOKEN default, since TOKEN never
  touches `PdfMarkdownParser`/`EpubMarkdownParser` at all (a separate Tika → `TokenTextSplitter`
  path) and so wouldn't exercise the outline-parsing/running-header-stripping logic this test exists
  to catch regressions in. `LoreApplicationTests` (previously `@Disabled` pending Testcontainers) now
  runs for real as part of the same suite.
  One fixture swap along the way: an archive.org-scanned EPUB was tried first but rejected — Tika's
  own `EpubParser` threw `EpubZipException` on it (not a strictly valid EPUB container; the
  `mimetype` entry wasn't first/uncompressed as the EPUB spec requires), unrelated to any Lore code.
  Real bug found and fixed in the process: the container-sharing design in `AbstractIntegrationTest`
  originally put the shared Postgres `@Container` field in an abstract base class's companion
  object, assuming JUnit5's `@Testcontainers` field-discovery would treat it as one JVM-wide
  singleton across subclasses — it didn't. Each subclass silently got its own separate container
  (confirmed via containerId logging), and once two were competing for the same Docker resources,
  connections intermittently failed with `CannotGetJdbcConnectionException`. Looked at first like
  environmental flakiness (a loaded Docker Desktop VM, then a genuinely unhealthy Docker Desktop
  install needing a reinstall) before the real, deterministic cause was found. Fixed by moving the
  container into a plain top-level Kotlin `object` (an unambiguous singleton) and wiring it via
  `@DynamicPropertySource` instead of `@Testcontainers`/`@Container`/`@ServiceConnection` — confirmed
  with several consecutive clean `./gradlew integrationTest` runs.
- **Gradle `integrationTest` suite.** Added the `jvm-test-suite` plugin with a dedicated
  `integrationTest` source set/task — the modern Gradle equivalent of Maven's Surefire/Failsafe
  split, for tests needing real local infra (Postgres via Testcontainers, local Ollama) that should
  stay out of `./gradlew build`/`check`. `SymanticTextSplitterSmokeTest` moved here from `src/test`,
  replacing its ad-hoc `SMOKE=true` env-var gate with the task split itself.
- **`PdfMarkdownParser` now prefers a PDF's real embedded outline/TOC over guessing headings from
  font size.** Found while smoke-testing the semantic splitter against a real PDF ("Great Meat"):
  the old font-ratio heuristic misread stray larger-font glyphs and a print-shop production stamp as
  markdown headings (`##`/`###`), fragmenting body text into tiny false-boundary paragraphs.
  `PdfMarkdownParser.parseFromOutline` now tries Spring AI's `ParagraphPdfDocumentReader` first (keys
  off `PDDocument.getDocumentCatalog().getDocumentOutline()` — real bookmarks, not a guess), falling
  back to the old `FontTrackingStripper` heuristic when a PDF has no embedded outline (the reader
  throws at construction in that case). `ParagraphPdfDocumentReader`'s underlying
  `PDFLayoutTextStripperByArea` extraction turned out to carry its own page-furniture noise — bare
  page numbers, InDesign export filenames (`012-015_30591.indd`), press-run stamps
  (`(Fogra 39)Job:05-30591...`, `Dtp:225 Page:11`) — each becoming its own spurious paragraph once
  split on blank lines; `cleanLayoutExtractedBody` strips those line patterns and collapses the
  layout engine's column-padding whitespace before paragraph-splitting. Also stripped: running
  headers. Book-wide ones (e.g. the book's own title, reprinted on every page) are caught by
  `detectRunningHeaders` — a line repeating verbatim across at least 30% of the PDF's distinct
  outline sections is page furniture, not content (real content, even short repeated phrases like
  "Serves 4", doesn't clear that bar). Chapter-scoped headers (e.g. "chapter 1 Beef", reprinted on
  every page of that chapter only) don't clear a book-wide frequency threshold, so those are instead
  matched by a `^chapter\s+\d+` pattern in `isPageFurniture`. Benefits `StructuralTextSplitter` too,
  since it shares `PdfMarkdownParser`. New dependency: `spring-ai-pdf-document-reader`.
  Two follow-ups found via a real re-ingest ("The Meat Hook Meat Book"), both in the same
  `detectRunningHeaders`/`normalizeForHeaderDetection` machinery:
  1. Some books bake the page number directly into the header line (`THE MEAT HOOK MEAT BOOK 14` on
     page 14, `...15` on page 15) — every occurrence was a distinct string under exact-match
     counting, so none individually cleared the frequency bar and the header leaked into 71 of 436
     chunks (16%), mid-sentence. Fixed by stripping a trailing page number before counting/matching —
     but only when the remaining phrase is 3+ words and 12+ chars, so it can't collapse something
     like "Serves 4" down to "Serves" and mistake it for a title.
  2. That fix alone wasn't enough: `PDFLayoutTextStripperByArea`'s column-based extraction leaves
     irregular internal spacing on the *same* logical header line (`THE MEAT HOOK  MEAT BOOK` vs
     `THE  MEAT HOOK MEAT  BOOK`), fragmenting one header into several distinct count keys — 30
     occurrences of one spacing variant plus 18 of another, neither alone clearing the 27-occurrence
     (30%-of-90-sections) bar, so 32 chunks still leaked the header after fix #1. Caught by making
     `sectionCounts`/`detectRunningHeaders`/`normalizeForHeaderDetection` `internal` and adding a
     diagnostic test that dumps raw per-line counts instead of just the final filtered set — the
     first "verified clean" check had a matching blind spot (compared raw, unnormalized lines) and
     gave a false all-clear. Fixed by collapsing runs of spaces before comparing. Re-verified against
     a live reingest of the real document: 0 polluted chunks (was 71, then 32, now 0 of 434).
  Not yet fixed: the cover/title page's stylized display text extracts with literal letter-spacing
  intact (e.g. "T he\nME A T\nHOOK" instead of "The\nMEAT\nHOOK") — cosmetic, isolated to one chunk
  per book, not chased.
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
  GENERIC/COOKBOOK/ACADEMIC variants), SEMANTIC (embedding-similarity breakpoint detection — see
  above).
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
