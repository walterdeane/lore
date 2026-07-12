# Setup

This gets you from a clean checkout to uploading your first document and asking it a question.

## Prerequisites

- **Java 21+**
- **Docker** (Docker Desktop or equivalent) — used to run Postgres via Docker Compose
- **[Ollama](https://ollama.com)** — runs the local embedding model, and the local chat model
  unless you [switch to Anthropic](CONFIGURATION.md#llm-provider-ollama-vs-anthropic)

Postgres itself doesn't need to be installed separately — Spring Boot's Docker Compose integration
starts it automatically from `compose.yaml` the first time you run the app, using the `pgvector/pgvector`
image (which bundles the `pgvector` extension) with the `ltree` extension enabled via the Flyway
migration in `src/main/resources/db/migration/V1__schema.sql`.

## 1. Install Ollama and pull the models

Install Ollama from [ollama.com](https://ollama.com), or via Homebrew:

```bash
brew install ollama
```

Lore needs two local models: an **embedding model** (always used, regardless of chat provider) and
a **chat model** (used unless you configure Anthropic instead):

```bash
ollama pull nomic-embed-text   # embedding model — matches lore's default config
ollama pull llama3.1:8b        # chat model — matches lore's default config
```

If you plan to change either model, see
[Chat provider: Ollama vs Anthropic](CONFIGURATION.md#llm-provider-ollama-vs-anthropic) — the model
names in `application.yaml` need to match whatever you `ollama pull`.

`./start.sh` (see below) will start Ollama for you and pull the embedding model automatically if it's
missing, but **it does not pull the chat model** — pull that yourself first, or the first chat
request will fail.

## 2. Start the app

```bash
./start.sh
```

This checks Docker is running, makes sure Ollama is up (starting it if needed) and the embedding
model is present, builds the project, then runs it via `./gradlew bootRun`. On first run, Spring
Boot's Docker Compose integration brings up the `pgvector` container defined in `compose.yaml`, and
Flyway applies the schema migration automatically — nothing to run manually.

The app is then available at **http://localhost:8080**.

(`./start.sh build` just compiles without running, if you want to check for compile errors first.)

## 3. First-time walkthrough

1. Go to **[/domains](http://localhost:8080/domains)** and create a domain — a top-level theme for
   a group of documents, e.g. "Cookbooks" or "Brewing".
2. Open the domain and go to its **Documents** tab. Upload an EPUB or PDF. Ingestion (extracting
   text, chunking, embedding) runs asynchronously — the document's status will move from `PENDING`
   to `COMPLETED` (or `FAILED`, with an error message, if something went wrong).
3. Optionally, add some hierarchical tags in the domain's **Tags** tab (e.g. `cuisine.italian`), or
   use the quick-add tag form right on the document upload page. Tags let you scope search/chat to
   a subset of a domain's documents.
4. Try **[/search](http://localhost:8080/search)** — pick the domain, type a query. This is
   retrieval only (hybrid full-text + vector search), no LLM call, useful for seeing what the retrieval
   pipeline actually finds before an LLM ever sees it.
5. Try **[/chat](http://localhost:8080/chat)** — same retrieval, but the results are fed to the chat
   model as context and it answers your question, with the source chunks shown alongside the answer
   so you can verify what it was grounded in.

## Data locations

By default, everything lives under `~/lore-data`:

- `~/lore-data/pgdata` — the Postgres data directory (Docker volume mount)
- `~/lore-data/documents` — original uploaded files (kept for re-ingestion/reference, not required
  for the app to function after ingestion)

Override the base directory with the `LORE_DATA_DIR` environment variable, or override the documents
directory independently with `LORE_DOCS_DIR` — see
[docs/CONFIGURATION.md](CONFIGURATION.md#storage) for details.

## Troubleshooting

- **"Docker is not running"** — start Docker Desktop and re-run `./start.sh`.
- **Chat requests fail / model not found** — you likely haven't pulled the chat model. Run
  `ollama pull llama3.1:8b` (or whatever model you've configured).
- **Upload rejected as an unsupported file type** — Lore tries hard to recover from common EPUB
  packaging quirks (browsers zipping folder-style EPUB bundles, macOS "Compress" wrapping a single
  file, trailing whitespace in filenames). If a file is still rejected, the error message states the
  detected extension; check the server log for a `rejected upload` line with more detail.
- **413 / file too large** — see [Upload size limit](CONFIGURATION.md#upload-size-limit).
- **Need to reset the database** — stop the app, run `docker compose down`, delete
  `~/lore-data/pgdata` (or your configured `LORE_DATA_DIR`), then start again; Flyway will recreate
  the schema from scratch. This discards all ingested data.
