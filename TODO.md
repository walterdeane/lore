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

- **SEMANTIC chunking strategy** — `SymanticTextSplitter` now implements embedding-similarity
  breakpoint detection (paragraph sliding windows → cosine distance between neighbors → per-document
  percentile threshold → cut, oversized chunks capped via `TokenTextSplitter` fallback). Defaults
  (`windowSize=2`, `breakpointPercentile=0.85`, `maxChunkChars=4000`) were tuned against a real
  cookbook EPUB via `SymanticTextSplitterSmokeTest` (`SMOKE=true` env var, not part of the normal
  suite — needs local Ollama running). Wired into `DocumentIngestionService`'s `SEMANTIC` branch.
- **`DocumentIngestionService` integration tests.** Still no test for the actual end-to-end
  ingestion pipeline (Tika extraction → chunk → embed → store) — that needs a real Postgres
  (Testcontainers) and a real or stubbed embedding model, a bigger undertaking than the unit tests
  just added for the pure logic pieces below. Not covered by this pass.
- **HyDE (Hypothetical Document Embeddings).** Pre-search query transformation: ask the chat LLM to
  generate a hypothetical answer to the query, then embed and search with that instead of (or
  alongside) the raw query — a hypothetical answer sits closer in embedding space to real answer
  chunks than the bare question does. Complements the existing post-search LLM reranking rather
  than replacing it (see `RerankerService`). Not yet designed — where it'd hook into
  `HybridSearchService`, whether it's on by default or a toggle like `rerank-enabled`, and how it
  interacts with the BM25 leg (which wants the literal query terms, not a paraphrase) all need
  thought.

## Explicitly deferred (not active work)

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
