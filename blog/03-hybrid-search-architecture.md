# Hybrid Search: Why Embeddings Alone Aren't Enough

## Angle
Most RAG tutorials treat vector search as the whole retrieval story. This post makes the case for
hybrid retrieval — BM25 + vector search fused with Reciprocal Rank Fusion, plus optional LLM
reranking — by walking through what each layer catches that the others miss.

## Hook
A query with a specific proper noun, model number, or exact phrase — the kind of query embeddings
often blur past because they optimize for semantic similarity, not literal token overlap. Show a
concrete case where BM25 finds the right chunk and pure vector search doesn't (or ranks it low).

## Sections

1. **The case against embeddings-only retrieval**
   - Embeddings are great at "similar meaning, different words" but can underperform on exact terms,
     rare vocabulary, codes/numbers, and short keyword-y queries — the classic vocabulary-mismatch-in-
     reverse problem.
   - BM25's complementary strength: literal term matching, unaffected by how a query is phrased,
     doesn't require semantic closeness at all.

2. **Reciprocal Rank Fusion — combining two rankings without needing calibrated scores**
   - The core problem RRF solves: BM25 scores and cosine similarities aren't on comparable scales, so
     you can't just average them. RRF sidesteps that by only using rank position, not raw score.
   - Explain the formula briefly and why rank-based fusion is robust to the two legs having wildly
     different score distributions.
   - `SearchType` (BM25/EMBEDDING/BOTH) surfaced per result in the UI — mention this as a debugging/
     evaluation tool, not just an internal implementation detail: being able to see *why* a result
     showed up is valuable for trusting or debugging retrieval.

3. **LLM reranking — a second pass, not a replacement**
   - After RRF fusion, an optional LLM-based listwise rerank reorders the fused list.
   - Why this is a different tool than fusion: RRF combines two retrieval signals cheaply at scale;
     reranking applies a slower, more expensive, more contextually aware judgment to a short list —
     good division of labor (cheap broad recall, expensive precise reordering on a small candidate
     set).
   - Where it helps vs. where it's overkill — be honest about the cost/latency tradeoff of adding an
     LLM call into the retrieval path.

4. **Design decisions worth defending**
   - Why single-domain-per-query (not multi-domain) was the right scope call, if there's a story there.
   - Why cross-encoder reranking was considered and deferred (no local Ollama serving mode for it —
     would mean standing up new infra rather than a config swap) — good example of "the theoretically
     better approach isn't always the pragmatically right one right now."

5. **What this buys you end-to-end**
   - Walk through one query from raw input to final answer: BM25 leg + vector leg → RRF fusion →
     (optional) LLM rerank → top-K passed to chat LLM with citations.

## Possible closing note
Natural bridge to the deferred/considered features: mention HyDE and agentic follow-up retrieval as
the two things being weighed for next investment, and the reasoning for deferring both (unproven
recall gain given existing BM25 + reranking; real value but needs latency bounding) — ties this post
to the roadmap without turning it into a TODO list.
