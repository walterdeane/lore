# Lore

A personal knowledge RAG (Retrieval-Augmented Generation) system for ingesting and querying your
own documents — cookbooks, reference manuals, guides, anything — using a local LLM. It's built as
both a real tool and a reference implementation: a clean, idiomatic **Spring Boot + Kotlin +
Spring AI** local-RAG example, since most RAG examples out there are Python (LangChain, LlamaIndex).

Answers come back with cited source material, Perplexity-style — the generated answer plus the
exact chunks that informed it, so you can verify the model isn't making things up.

## Documentation

- **[docs/SETUP.md](docs/SETUP.md)** — installing prerequisites (Ollama, Docker, Java), running the
  app for the first time, and a walkthrough of the basic workflow.
- **[docs/CONFIGURATION.md](docs/CONFIGURATION.md)** — every configurable property, how to switch
  between the local Ollama model and Anthropic's Claude, and what each retrieval/chunking knob does.
- **[PRD.md](PRD.md)** — the product/design brief, kept up to date with the actual implementation.
- **[TODO.md](TODO.md)** — what's still open, undecided, or explicitly deferred.

## What it does

Lore ingests EPUB and PDF documents, splits them into chunks, embeds them, and lets you search and
chat over them, scoped by **domain** (a top-level theme, e.g. "Cookbooks" or "Brewing") and
hierarchical **tags** (e.g. `cuisine.italian.northern`) within a domain.

- **Hybrid search** — Postgres full-text search (keyword) and pgvector (semantic) search run
  independently and are fused with Reciprocal Rank Fusion, so results benefit from both exact-term
  and paraphrase matches.
- **Optional LLM reranking** — an extra listwise reranking pass over the fused candidates before
  they're used, since there's no local cross-encoder rerank endpoint to lean on.
- **RAG chat** — retrieves relevant chunks for a question, stuffs them into the model's context, and
  answers with the sources shown alongside the answer.
- **Multiple chunking strategies** — fixed-size token chunking (with configurable overlap),
  heading-aware structural chunking with per-content-type tuning (a cookbook's recipe boundaries
  look nothing like an academic paper's section boundaries), or embedding-similarity semantic
  chunking that cuts where consecutive passages stop being topically similar.
- **Outline-aware PDF parsing** — PDFs with an embedded table of contents are split using that real
  structure rather than guessed from font size, with print-production noise (page numbers, running
  headers) filtered out; PDFs without one fall back to a font-size heuristic.
- **Local-first, cloud-optional** — chat/generation can run entirely on your machine via Ollama, or
  be switched to Anthropic's API when you want a stronger model. Embeddings always run locally.
- **Robust document ingestion** — handles the messy real-world cases: EPUBs that arrive as `.zip`
  (browsers can't upload folders, so a folder-style EPUB bundle gets zipped on the fly), trailing
  whitespace in filenames, etc.

## Tech stack

- Kotlin / Spring Boot, Spring AI (Ollama + Anthropic + pgvector integrations)
- PostgreSQL with the `pgvector` (vector similarity) and `ltree` (hierarchical tags) extensions
- Flyway for schema migrations, plain JDBC (`JdbcTemplate`) for data access — no ORM
- Thymeleaf for a server-rendered UI (no separate frontend build)
- Docker Compose for local Postgres

See [docs/SETUP.md](docs/SETUP.md) to get running, or [docs/CONFIGURATION.md](docs/CONFIGURATION.md)
to see what's tunable.
