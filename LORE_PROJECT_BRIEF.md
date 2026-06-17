# Lore — Project Brief

A personal knowledge RAG system for ingesting and querying documents across my many hobbies and interests using local LLMs.

---

## How I want us to work together

**We are pair programming. I am the driver — you are the navigator.**

- Ask me questions and let me make the decisions. Suggest approaches, don't implement them unless I explicitly ask you to write something.
- When I'm working through a problem, resist the urge to autocomplete the whole solution. A good pair asks "what do you think should happen here?" and lets me work it out.
- I am deliberately rebuilding my raw coding fluency after years of mostly architecture and design work, so I want to feel the friction of writing code myself. Don't take that away from me.
- If I'm stuck, nudge me with a question or a pointer to the relevant docs before handing me an answer.
- When you do write code at my request, keep it small and scoped to exactly what I asked. Don't gold-plate.

If you find yourself about to write a large block of code I didn't ask for, stop and ask first.

---

## What Lore is

A Spring Boot + Kotlin + Spring AI application that lets me ingest personal documents (cookbooks, brewing references, woodworking manuals, lutherie guides, permaculture texts, etc.), organise them by theme, and query them with a local LLM via Ollama. Answers come back with cited source material in the style of Perplexity — the generated answer plus the exact chunks that informed it.

This is for personal use first, with an eye to releasing it as an open-source reference implementation. There is a real gap: most RAG examples are Python (LangChain, LlamaIndex). A clean, idiomatic Spring Boot + Spring AI + Kotlin local-RAG reference is underrepresented.

**Important:** get it working for myself first, then tidy for public consumption. Do not over-design for the open-source case before the core works.

---

## Tech Stack

- Kotlin / Spring Boot 4.1.0
- Spring AI 2.0 (milestone track — not yet GA; expect some rough edges and incomplete docs)
- PostgreSQL with the `pgvector` and `ltree` extensions
- Ollama running locally for both chat and embedding models
- Docker Compose for local infrastructure (Postgres)
- Flyway for schema migrations (not Hibernate auto-ddl)
- Gradle (Kotlin DSL), Java 21+

### Build setup notes

Spring AI isn't reliably on the standard Initializr or Maven Central yet on the milestone track. Add manually:

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0-M4"))
    implementation("org.springframework.ai:spring-ai-ollama-spring-boot-starter")
    implementation("org.springframework.ai:spring-ai-pgvector-store-spring-boot-starter")
}
```

(Verify the exact current milestone version and artifact coordinates — these move.)

`pgvector` and `ltree` are Postgres extensions, enabled via Flyway migrations and the Docker Compose Postgres image — not Spring dependencies.

### Local models (M4 Pro, 24GB RAM)

- Chat: an 8B (Llama 3.1 8B) or 14B (Qwen 2.5 14B) model — comfortable in 24GB
- Embeddings: `nomic-embed-text` — negligible footprint
- 32B models will work but push into swap; avoid for now

---

## Domain Model

```
Collection
  └── has many Documents
  └── has many Tags (hierarchical, scoped to this Collection)

Document
  └── belongs to one Collection
  └── has many Tags (via join table)
  └── has many Chunks
  └── has a local sourcePath (file reference, not operationally critical)
  └── tracks ingestion lifecycle

Tag
  └── belongs to a Collection
  └── hierarchical via ltree materialised path (e.g. cuisine.italian.northern)
  └── optional parent

Chunk
  └── belongs to a Document
  └── denormalised collectionId (for filtered vector search)
  └── denormalised tagPaths array (for filtered vector search)
  └── carries the embedding vector (pgvector)
```

### Collection
Top-level, first-class organiser. A named theme: *Brewing*, *Cookbooks*, *Woodworking*, *Lutherie*, *Permaculture*. Has name, description, slug.

### Tag
Hierarchical within a Collection, using an `ltree` materialised path. This mirrors how I encode hierarchy in DynamoDB sort keys (`CUISINE#ITALIAN#NORTHERN` with `begins_with`) — same mental model. Use Postgres `ltree` with its `@>`, `<@`, `~` operators and a GiST index. Example hierarchy inside a Cookbooks collection: `cuisine.italian`, `technique.fermentation`, `format.reference`.

### Document
- `id` (UUID), `title`, `author` (nullable), `sourceFilename`, `sourcePath`, `sourceType` (PDF first; design for EPUB/Markdown/web-clip later)
- `collectionId`
- Ingestion lifecycle: `ingestionStatus` enum (`PENDING` → `PROCESSING` → `COMPLETE` / `FAILED`), `ingestionError` (nullable), `ingestedAt` (nullable)
- `createdAt`, `updatedAt`
- Original file stored on the local filesystem; the path is a reference. The app functions without the original after ingestion — it's kept for re-ingestion (new chunking/embedding config), source verification, and fidelity. Not operationally critical.

### Chunk
- `id` (UUID), `documentId`
- `collectionId` (denormalised), `tagPaths` (denormalised text array)
- `content` (raw text), `embedding` (pgvector `vector` type)
- `chunkIndex`, `pageNumber` (nullable), `tokenCount` (nullable), `createdAt`
- Denormalisation is deliberate — filtered vector similarity searches run a `WHERE` on the Chunk row itself rather than joining through Document. Retrieval performance/accuracy over normalisation purity is the right tradeoff here.

---

## API Surface

### Collections & Tags
```
GET    /collections
POST   /collections
GET    /collections/{id}
PUT    /collections/{id}
DELETE /collections/{id}

GET    /collections/{id}/tags
POST   /collections/{id}/tags
PUT    /collections/{id}/tags/{tagId}
DELETE /collections/{id}/tags/{tagId}
GET    /collections/{id}/tags/{tagId}/children
```

### Documents (ingestion is async)
```
POST   /documents                 # upload file, create Document, trigger async ingestion
GET    /documents/{id}            # includes ingestionStatus
GET    /documents/{id}/chunks     # inspect produced chunks (debugging)
DELETE /documents/{id}            # removes document and all chunks
POST   /documents/{id}/reingest   # reprocess with current config
GET    /collections/{id}/documents
```

### Query / RAG
```
POST   /query          # RAG: retrieve + generate answer with sources
POST   /query/search   # retrieval only, no LLM call (cheaper, for exploring)
```

**Query request:**
```json
{
  "question": "what temperature should I ferment at?",
  "collectionIds": ["uuid"],
  "tagPaths": ["technique.fermentation"],
  "limit": 5
}
```

**Query response (Perplexity-style — answer plus verifiable sources):**
```json
{
  "answer": "For ale fermentation you generally want...",
  "sources": [
    {
      "chunkId": "uuid",
      "documentId": "uuid",
      "documentTitle": "The Complete Joy of Homebrewing",
      "collectionName": "Brewing",
      "pageNumber": 47,
      "tagPaths": ["technique.fermentation"],
      "content": "...the raw chunk text...",
      "similarityScore": 0.92
    }
  ],
  "query": {
    "question": "what temperature should I ferment at?",
    "collectionIds": ["uuid"],
    "tagPaths": ["technique.fermentation"]
  }
}
```

Expose `similarityScore` — useful for learning what score thresholds mean good vs poor retrieval.

---

## Suggested build order

1. Docker Compose + Postgres with `pgvector` and `ltree` enabled; Flyway baseline migration
2. Domain entities + JPA mappings + repositories (start with Collection, then Document, Tag, Chunk)
3. Collection & Tag CRUD endpoints (get the ltree hierarchy queries working)
4. Document upload + local file storage + Document record creation
5. Ingestion pipeline (Spring AI ETL: read PDF → split/chunk → embed via Ollama → write to pgvector with denormalised metadata) running async, updating ingestionStatus
6. `/query/search` retrieval-only endpoint (validate filtered vector search works before adding the LLM)
7. `/query` full RAG with answer generation and source assembly

Start narrow: one Collection, upload one PDF, get a single end-to-end query working. Then grow it.

---

## Things to read before/while building

- Postgres `ltree` docs — `@>`, `<@`, `~`, GiST indexing
- `pgvector` README — vector type, HNSW vs IVFFlat (HNSW is the likely default)
- Spring AI reference — Ollama integration, then the ETL pipeline, then the vector store abstraction
- Ollama model library — Llama 3.1 8B, Qwen 2.5 14B, nomic-embed-text
