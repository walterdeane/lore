# Configuration

All configuration lives in `src/main/resources/application.yaml`. Most values that are meant to be
overridden per-environment are wired through an environment variable with a default, in the form
`${ENV_VAR:default}` — you can override those without editing the file or rebuilding, e.g.:

```bash
LORE_CHAT_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-ant-... ./start.sh
```

A few settings (the Ollama/Anthropic model names) are plain literals with no environment variable
indirection — change those by editing `application.yaml` directly.

## LLM provider: Ollama vs Anthropic

Lore's chat/generation step can run against a local Ollama model or Anthropic's API. **Embeddings
always run locally via Ollama** — there's no cloud embedding option wired in, and reranking (see
below) uses whichever chat provider is configured, so switching to Anthropic also sends rerank
requests there.

```yaml
lore:
  chat:
    provider: ${LORE_CHAT_PROVIDER:ollama}   # "ollama" or "anthropic"
```

Set via environment variable:

```bash
export LORE_CHAT_PROVIDER=anthropic
export ANTHROPIC_API_KEY=sk-ant-...
```

Both the `OllamaChatModel` and `AnthropicChatModel` Spring beans are created regardless of which
provider is selected (see `ChatModelConfig`) — `ANTHROPIC_API_KEY` only needs to be a real key if
you actually select `anthropic`; it's fine to leave unset while running on Ollama.

**Model names** are configured separately from the provider switch, under `spring.ai.*`:

```yaml
spring:
  ai:
    ollama:
      embedding:
        model: nomic-embed-text     # must match a model you've `ollama pull`ed
      chat:
        model: llama3.1:8b          # must match a model you've `ollama pull`ed
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
      chat:
        options:
          model: claude-haiku-4-5-20251001
```

If you change the Ollama model names here, remember to `ollama pull` the new model first — see
[docs/SETUP.md](SETUP.md#1-install-ollama-and-pull-the-models).

### Reranking

```yaml
lore:
  chat:
    rerank-enabled: ${LORE_CHAT_RERANK_ENABLED:false}
```

When enabled, the chat flow does a second pass over the top hybrid-search candidates: the configured
chat model is asked to rank them by relevance to the query (listwise reranking — there's no local
cross-encoder rerank endpoint to use instead), and only the top few make it into the context sent to
the model for the actual answer. This costs an extra LLM call per question. Disable it if your chat
model is too weak or slow to follow the ranking prompt reliably — a bad ranking is worse than no
reranking at all, since it can bump a relevant chunk out of the context window entirely.

## Chunking

Every document is split into chunks before embedding. The strategy can be set per-document (at
upload time) or per-domain (as a default for documents that don't specify one); if neither specifies
a strategy, this app-wide default is used:

```yaml
lore:
  chunking:
    default-strategy: TOKEN   # TOKEN, STRUCTURAL, or SEMANTIC
    token-overlap-chars: 200
```

- **`TOKEN`** — fixed-size chunking via Spring AI's `TokenTextSplitter`. `token-overlap-chars`
  controls how much trailing context (the last sentence(s), up to this many characters) from each
  chunk is prepended to the next one, so a fact split across a chunk boundary still appears whole in
  at least one chunk. Set to `0` to disable overlap.
- **`STRUCTURAL`** — heading-aware chunking: source files are converted to markdown and split at
  heading boundaries, so a chunk corresponds to a whole semantic section rather than an arbitrary
  token window. Needs a **structural variant** (see below) to know how to interpret headings for the
  content type.
- **`SEMANTIC`** — embedding-similarity chunking: source text is split into paragraphs, each
  embedded together with a few neighboring paragraphs for a stable signal, and a cut is made
  wherever consecutive-paragraph similarity drops below a threshold computed per-document (not a
  fixed constant, since a terse cookbook and a dense academic PDF don't share one similarity scale).
  Any resulting chunk over a size cap is split further with `TokenTextSplitter` as a backstop, since
  a long span of internally-similar content (e.g. a conversion table) can otherwise never trigger a
  cut. Tuning (window size, threshold percentile, size cap) isn't yet exposed as configuration —
  see `SemanticTextSplitter.SemanticConfig` in the source.

### Structural variants

When using `STRUCTURAL` chunking, a variant tunes what counts as a chunk boundary for that kind of
content (set per-document or per-domain, same override rules as the strategy itself):

- **`GENERIC`** — default heading-based splitting, no special-casing.
- **`COOKBOOK`** — treats sub-headers like "Ingredients"/"Method"/"Notes" as part of a recipe's body
  rather than as their own chunk boundary, and auto-detects which heading level a recipe title sits
  at.
- **`ACADEMIC`** — larger minimum chunk size, tuned for prose-heavy section structure rather than
  short recipe-style entries.

## Search / retrieval

```yaml
lore:
  search:
    candidate-pool-size: 50
    rrf-k: 60
```

- **`candidate-pool-size`** — how many results each of the lexical (full-text) and vector search legs retrieve independently
  before being fused. The fused result set (and therefore pagination) is bounded by this pool — it's
  RAG-style top-K retrieval, not exhaustive search-engine pagination, so results won't grow past the
  pool no matter how deep you page.
- **`rrf-k`** — the `k` constant in the Reciprocal Rank Fusion formula (`score = sum of 1/(k + rank)`
  across whichever ranked list(s) a result appears in). Higher `k` flattens the influence of rank
  position; lower `k` weights top ranks more heavily.

## Storage

```yaml
lore:
  storage:
    documents-dir: ${LORE_DOCS_DIR:${LORE_DATA_DIR:${user.home}/lore-data}/documents}
```

Where uploaded source files are kept after ingestion (for re-ingestion with a different chunking
config, source verification, or re-serving the original file — not required for search/chat to
function). Override with `LORE_DOCS_DIR` directly, or `LORE_DATA_DIR` to move the whole data
directory (documents *and* the Postgres volume defined in `compose.yaml`) somewhere else at once.

## Upload size limit

```yaml
spring:
  servlet:
    multipart:
      max-file-size: ${LORE_MAX_UPLOAD_SIZE:1GB}
      max-request-size: ${LORE_MAX_UPLOAD_SIZE:1GB}
```

Both are driven by the same `LORE_MAX_UPLOAD_SIZE` variable, since a request only ever contains one
file. A file over this limit is rejected with a `413` before it reaches any application code — see
`templates/error.html` and `GlobalExceptionHandler` for how that (and other unhandled errors) is
rendered instead of a raw framework error page.

## Error page verbosity

```yaml
server:
  error:
    include-message: always
```

Lore is a personal/local tool, not a public multi-tenant service, so the real error message is always
surfaced in the rendered error page rather than hidden — this is not a sensible default to carry into
a publicly-exposed deployment without reconsidering.

## Full property reference

| Property | Default | Purpose |
|---|---|---|
| `lore.chat.provider` | `ollama` | Chat/generation backend: `ollama` or `anthropic`. |
| `lore.chat.rerank-enabled` | `false` | Enable LLM-based listwise reranking before generation. |
| `lore.chunking.default-strategy` | `TOKEN` | App-wide fallback chunking strategy. |
| `lore.chunking.token-overlap-chars` | `200` | Trailing-context overlap for TOKEN chunking, in characters; `0` disables it. |
| `lore.search.candidate-pool-size` | `50` | Candidates retrieved per leg (lexical, vector) before fusion. |
| `lore.search.rrf-k` | `60` | The `k` constant in Reciprocal Rank Fusion. |
| `lore.storage.documents-dir` | `~/lore-data/documents` | Where original uploaded files are kept. |
| `spring.ai.ollama.embedding.model` | `nomic-embed-text` | Local embedding model name. |
| `spring.ai.ollama.chat.model` | `llama3.1:8b` | Local chat model name. |
| `spring.ai.anthropic.api-key` | *(empty)* | Anthropic API key; only required if `lore.chat.provider=anthropic`. |
| `spring.ai.anthropic.chat.options.model` | `claude-haiku-4-5-20251001` | Anthropic chat model name. |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `1GB` | Max upload size. |
| `server.error.include-message` | `always` | Whether the real error message is shown on the error page. |
