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
- **Test coverage.** Only `HybridSearchServiceTest` (the pure `fuse()` function) and the boilerplate
  Spring context test exist. Nothing covers chunking (`MarkdownChunker`, `StructuralTextSplitter`,
  `TokenOverlapChunker`), ingestion, the reranker's response parsing, or the EPUB zip/folder-bundle
  detection logic in `DocumentsService`.
- **`DocumentsController.getDocumentChunks`** — intentional stub; no backing query yet
  (`ChunkRepository` has no `findByDocumentId`).

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
- Removed dead code: `QueryController`, most of `DocumentsController`, the unused chunk
  parent/child hierarchy columns.
