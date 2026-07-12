# Hybrid Search: Why Embeddings Alone Aren't Enough

## Angle
Most RAG tutorials treat vector search as the whole retrieval story. This post makes the case for
hybrid retrieval — a lexical/keyword leg plus a vector leg, fused with Reciprocal Rank Fusion, plus
optional LLM reranking — by walking through what each layer catches that the others miss.

## Terminology note
The class was originally named `BM25SearchService`, but the implementation is Postgres full-text
search (`tsvector`/`ts_rank_cd` against a `plainto_tsquery`) — real lexical/keyword matching, but not
the Okapi BM25 algorithm specifically (no term-frequency saturation, no document-length
normalization). Renamed to `LexicalSearchService` (and `SearchType.BM25` → `LEXICAL`,
`/api/search/bm25` → `/api/search/lexical`) once this was caught, so the post can just say "lexical/
keyword leg" and match the actual code — no caveat needed in the post body. Worth a short aside in the
post itself, though: this naming slip, caught while drafting this very post, is a good real example of
"writing the explanation for an outside audience surfaces sloppy internal naming" — a small meta-beat
about the value of writing publicly, not just a footnote to skip.

## Hook
A query with a specific proper noun, model number, or exact phrase — the kind of query embeddings
often blur past because they optimize for semantic similarity, not literal token overlap. Show a
concrete case where the lexical leg finds the right chunk and pure vector search doesn't (or ranks it
low). This needs a real logged query, not a hypothetical — run some real queries against
`/api/search/lexical` vs. `/search` (vector-only) first and find an actual case before drafting.

## Sections

1. **The case against embeddings-only retrieval**
   - Embeddings are great at "similar meaning, different words" but can underperform on exact terms,
     rare vocabulary, codes/numbers, and short keyword-y queries — the classic vocabulary-mismatch-in-
     reverse problem.
   - The lexical leg's complementary strength: literal term matching, unaffected by how a query is
     phrased, doesn't require semantic closeness at all.

2. **The lexical leg — one Postgres instance, no Elasticsearch**
   - Most readers assume hybrid search means standing up Elasticsearch/OpenSearch alongside a vector
     store. Worth a beat on how Lore does both legs in the same Postgres database: a `tsvector` column
     (`search_vector`) with a GIN index, ranked via `ts_rank_cd` against a `plainto_tsquery` built from
     the raw query.
   - This is part of Lore's actual appeal as a reference implementation — one database, two retrieval
     signals, no second piece of search infrastructure to run/operate.

3. **Reciprocal Rank Fusion — combining two rankings without needing calibrated scores**
   - The core problem RRF solves: lexical rank scores and cosine similarities aren't on comparable
     scales, so you can't just average them. RRF sidesteps that by only using rank position, not raw
     score.
   - Explain the formula briefly and why rank-based fusion is robust to the two legs having wildly
     different score distributions.
   - `SearchType` (LEXICAL/EMBEDDING/BOTH) surfaced per result in the UI — mention this as a
     debugging/evaluation tool, not just an internal implementation detail: being able to see *why* a
     result showed up is valuable for trusting or debugging retrieval.

4. **LLM reranking — a second pass, not a replacement**
   - After RRF fusion, an optional LLM-based listwise rerank reorders the fused list.
   - Why this is a different tool than fusion: RRF combines two retrieval signals cheaply at scale;
     reranking applies a slower, more expensive, more contextually aware judgment to a short list —
     good division of labor (cheap broad recall, expensive precise reordering on a small candidate
     set).
   - Where it helps vs. where it's overkill — be honest about the cost/latency tradeoff of adding an
     LLM call into the retrieval path.

5. **Design decisions worth defending**
   - Why cross-encoder reranking was considered and deferred (no local Ollama serving mode for it —
     would mean standing up new infra rather than a config swap) — good example of "the theoretically
     better approach isn't always the pragmatically right one right now."

6. **What this buys you end-to-end**
   - Walk through one query from raw input to final answer: lexical leg + vector leg → RRF fusion →
     (optional) LLM rerank → top-K passed to chat LLM with citations.

## Possible closing note
This post owns the retrieval roadmap discussion: bridge to HyDE and agentic follow-up retrieval as the
two things being weighed for next investment, and the reasoning for deferring both (unproven recall
gain given the lexical leg + reranking already cover a lot of that ground; real value but needs latency
bounding) — ties this post to the roadmap without turning it into a TODO list. (Other posts in this
series should not also close on this — it belongs here.)
