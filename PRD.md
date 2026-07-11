Lore — Project Brief

A personal knowledge RAG system for ingesting and querying documents across my many hobbies and interests using local LLMs.

Status: this brief has been updated to reflect the actual implementation, which has diverged from
the original plan in a few places (noted inline below). See TODO.md for what's still open or
undecided.


How I want us to work together


Ask me questions and let me make the decisions. Suggest approaches, don't implement them unless I explicitly ask you to write something.
When I'm working through a problem, resist the urge to autocomplete the whole solution. A good pair asks "what do you think should happen here?" and lets me work it out.
I am deliberately rebuilding my raw coding fluency after years of mostly architecture and design work, so I want to feel the friction of writing code myself. Don't take that away from me.
If I'm stuck, nudge me with a question or a pointer to the relevant docs before handing me an answer.
When you do write code at my request, keep it small and scoped to exactly what I asked. Don't gold-plate.


If you find yourself about to write a large block of code I didn't ask for, stop and ask first.


What Lore is


A Spring Boot + Kotlin + Spring AI application that lets me ingest personal documents (cookbooks, brewing references, woodworking manuals, lutherie guides, permaculture texts, etc.), organise them by theme, and query them with an LLM. Answers come back with cited source material in the style of Perplexity — the generated answer plus the exact chunks that informed it.

This is for personal use first, with an eye to releasing it as an open-source reference implementation. There is a real gap: most RAG examples are Python (LangChain, LlamaIndex). A clean, idiomatic Spring Boot + Spring AI + Kotlin local-RAG reference is underrepresented.

Important: get it working for myself first, then tidy for public consumption. Do not over-design for the open-source case before the core works.

Delivery ended up UI-first rather than API-first (see API Surface below) — a server-rendered
Thymeleaf app, not the JSON REST API originally sketched out. A JSON RAG API may still happen later,
but as an MCP server rather than a REST endpoint — see TODO.md.


Tech Stack


Kotlin / Spring Boot 4.1.0
Spring AI 2.0
PostgreSQL with the pgvector and ltree extensions
Ollama for embeddings always, and for chat by default — chat/generation can be switched to Anthropic's API instead (see docs/CONFIGURATION.md)
Docker Compose for local infrastructure (Postgres)
Flyway for schema migrations
Plain JDBC (JdbcTemplate) for data access, not JPA/Hibernate — every repository is hand-written SQL. `spring-boot-starter-data-jpa` and the JPA Gradle plugin are still on the classpath from the initial scaffold but unused (zero @Entity classes); see TODO.md.
Gradle (Kotlin DSL), Java 21+


Build setup notes

Current working dependency block (see build.gradle.kts for the authoritative version):

kotlinrepositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springframework.ai:spring-ai-vector-store-advisor")
    implementation("org.springframework.ai:spring-ai-tika-document-reader")
}

pgvector and ltree are Postgres extensions, enabled via the Flyway migration and the Docker Compose Postgres image — not Spring dependencies.

Local models

See docs/CONFIGURATION.md for the full provider/model configuration, including how to switch chat
generation to Anthropic. Defaults: nomic-embed-text (embeddings, always local) and llama3.1:8b (chat,
local — swappable for Anthropic's Claude).


Domain Model

Domain
  └── has many Documents
  └── has many Tags (hierarchical, scoped to this Domain)

Document
  └── belongs to one Domain
  └── has tags as a denormalised ltree[] array directly on the row (not a join table — see below)
  └── has many Chunks
  └── has a local sourcePath (file reference, not operationally critical)
  └── tracks ingestion lifecycle
  └── may override the domain's default chunking strategy/structural variant

Tag
  └── belongs to a Domain
  └── hierarchical via ltree materialised path (e.g. cuisine.italian.northern)
  └── optional parent

Chunk
  └── belongs to a Document
  └── denormalised domainId (for filtered vector search)
  └── denormalised tagPaths array (for filtered vector search)
  └── carries the embedding vector (pgvector) and a BM25 tsvector (generated column)
  └── records which chunking strategy produced it

Domain

Top-level, first-class organiser. A named theme: Brewing, Cookbooks, Woodworking, Lutherie, Permaculture. Has name, description, and an optional default chunking strategy/structural variant that documents in it inherit unless they override it. (No slug field currently — domains are looked up by id.)

Tag

Hierarchical within a Domain, using an ltree materialised path. This mirrors how I encode hierarchy in DynamoDB sort keys (CUISINE#ITALIAN#NORTHERN with begins_with) — same mental model. Use Postgres ltree with its @>, <@, ~ operators and a GiST index. Example hierarchy inside a Cookbooks Domain: cuisine.italian, technique.fermentation, format.reference.

Document


id (UUID), title, author (nullable), sourceFilename, sourcePath, sourceType (PDF and EPUB implemented; Markdown/web-clip still not built — see TODO.md)
domainId
tags: List<String> of ltree paths, stored directly on the document row (LTREE[] column) — not a separate join table as originally planned. Denormalising here (rather than a document_tag join table) keeps the one-hop "what tags does this document have" query trivial, at the cost of not being able to attach arbitrary per-tag metadata to the association; that tradeoff hasn't mattered in practice.
Ingestion lifecycle: ingestionStatus enum (PENDING → IN_PROGRESS → COMPLETED / FAILED — renamed from the original PROCESSING/COMPLETE), ingestionError (nullable), ingestedAt (nullable)
chunkStrategy, structuralVariant (nullable) — per-document override of the domain's defaults
No createdAt/updatedAt currently — not implemented; see TODO.md if these turn out to matter
Original file stored on the local filesystem; the path is a reference. The app functions without the original after ingestion — it's kept for re-ingestion (new chunking/embedding config), source verification, and fidelity. Not operationally critical.


Chunk


id (UUID), documentId
domainId (denormalised), tagPaths (denormalised ltree[] array)
content (raw text), embedding (pgvector vector(768) type), a generated tsvector column for BM25
chunkIndex, chunkStrategy
pageNumber (nullable), tokenCount (nullable) — columns exist but nothing in the ingestion pipeline populates them yet; treat as reserved, not reliable
createdAt
An earlier design also carried parentChunkId/chunkLevel for hierarchical "small-to-big" retrieval and chunk-to-chunk navigation. Removed — never populated, and the STRUCTURAL chunking strategy already produces chunks sized to a semantic unit, which was most of what the hierarchy was chasing. See TODO.md for where this landed (token overlap instead, for TOKEN chunking; "nexting" via chunkIndex, not yet built).
Denormalisation is deliberate — filtered vector similarity searches run a WHERE on the Chunk row itself rather than joining through Document. Retrieval performance/accuracy over normalisation purity is the right tradeoff here.

Retrieval

Search is hybrid, not pure vector similarity as originally sketched: BM25 (Postgres full-text
search) and pgvector cosine similarity run as two independent legs, each returning a candidate pool,
fused by Reciprocal Rank Fusion. An optional LLM-based listwise reranking pass can run on top of the
fused candidates before they're used (there's no local cross-encoder rerank endpoint to use instead).
Every search result records which leg(s) surfaced it (BM25 / EMBEDDING / BOTH), useful for evaluating
retrieval quality. See docs/CONFIGURATION.md for the tuning knobs (candidate pool size, RRF's k,
reranking on/off).

Chunking

Three strategies, selectable per-document or per-domain: TOKEN (fixed-size, via Spring AI's
TokenTextSplitter, with configurable trailing-context overlap between chunks), STRUCTURAL
(heading-aware — converts the source to markdown and splits at heading boundaries, with
GENERIC/COOKBOOK/ACADEMIC variants tuning what counts as a boundary for that content type), and
SEMANTIC (embedding-similarity — paragraphs embedded in sliding windows, cut where consecutive
similarity drops below a per-document threshold, oversized chunks capped via TokenTextSplitter).
See docs/CONFIGURATION.md.


API Surface

The original plan below was a JSON REST API. What actually got built is a server-rendered
Thymeleaf UI instead — no separate frontend, no JSON RAG endpoint. The routes that exist:

Domains & Tags (HTML, server-rendered)

GET    /domains
POST   /domains
PUT    /domains/{id}
DELETE /domains/{id}
GET    /domains/{id}/tags
POST   /domains/{id}/tags          # also used as a quick-add form from the document upload page
PUT    /domains/{id}/tags/{tagId}
DELETE /domains/{id}/tags/{tagId}

No GET /domains/{id}/tags/{tagId}/children endpoint — TagsService.getTagTree builds the whole tree
for the UI in one call instead.

Documents (HTML, server-rendered; ingestion is async)

GET    /domains/{id}/documents                              # list + upload form
POST   /domains/{id}/documents                               # upload, trigger async ingestion
GET    /domains/{id}/documents/{documentId}                  # detail + status
POST   /domains/{id}/documents/{documentId}/reingest
DELETE /domains/{id}/documents/{documentId}
PUT    /domains/{id}/documents/{documentId}/tags
GET    /domains/{id}/documents/{documentId}/file              # serve the original file

GET    /api/documents/{id}/chunks    # JSON, intentional stub — no backing query yet

Search & Chat (HTML, server-rendered — this replaces the planned /query and /query/search)

GET    /search    # retrieval only (hybrid BM25 + vector), no LLM call — for inspecting what
                   # retrieval finds before an LLM ever sees it
GET    /search/chunks/{chunkId}
GET    /chat      # retrieval + generation: builds a prompt from the top (optionally reranked)
                   # chunks, asks the configured chat model, shows the answer with its sources

GET    /api/search/bm25     # JSON, retrieval only, one leg
GET    /api/search/hybrid   # JSON, retrieval only, fused

Every route above is scoped to a single domain per request. There's no cross-domain query — see
TODO.md.


Things to read before/while building


Postgres ltree docs — @>, <@, ~, GiST indexing
pgvector README — vector type, HNSW vs IVFFlat
Spring AI reference — Ollama and Anthropic integration, the ETL/document pipeline, the vector store abstraction
Reciprocal Rank Fusion — the fusion formula used to combine BM25 and vector search results
Ollama model library — chat and embedding models
