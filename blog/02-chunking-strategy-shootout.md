# The Chunking Strategy Shootout: TOKEN vs. STRUCTURAL vs. SEMANTIC

## Angle
Chunking strategy is usually treated as a one-line config choice in RAG writeups ("we used 512-token
chunks with 50-token overlap"). This post argues it deserves more scrutiny than that, by actually
comparing three different strategies on the same real documents and showing what each gets right and
wrong.

## Hook
Take one paragraph-spanning fact or one chapter transition from a real ingested book, and show the
three different chunk boundaries TOKEN, STRUCTURAL, and SEMANTIC each draw around it. Let the reader
see the difference before you explain the mechanism.

## Sections

1. **Why chunking strategy matters more than it looks like it should**
   - A chunk boundary in the wrong place either splits a fact across two chunks (hurting recall) or
     merges unrelated content into one chunk (hurting precision/embedding quality).
   - Frame the three strategies as three different bets on where good boundaries come from: fixed
     size, document structure, or semantic drift.

2. **TOKEN — the baseline**
   - Fixed-size chunks via `TokenTextSplitter`, with configurable overlap (`tokenOverlapChars`) to
     avoid hard-cutting a fact in half.
   - Cheapest, simplest, no parsing dependency — but blind to structure and meaning. Good default,
     not a good ceiling.

3. **STRUCTURAL — heading-aware**
   - Parses source to markdown, splits on heading markers, with GENERIC/COOKBOOK/ACADEMIC variants
     for different document shapes.
   - Falls back to a heuristic text splitter when markdown parsing fails or yields too few chunks
     (<3) — worth explaining why a fallback is necessary at all (not every source parses cleanly).
   - The PDF outline story: prefers the document's real embedded outline/TOC over guessing headings
     from font size, because font-based heuristics are fooled by production artifacts (stamps, stray
     glyphs) — link back to post 01 ("What Breaks...") for the full story rather than repeating it.

4. **SEMANTIC — embedding-similarity breakpoints**
   - Mechanism: paragraph sliding windows → cosine distance between neighboring windows → per-
     document percentile threshold → cut at the biggest semantic jumps.
   - Oversized chunks capped via `TokenTextSplitter` as a fallback within the fallback.
   - Defaults (`windowSize=2`, `breakpointPercentile=0.85`, `maxChunkChars=4000`) were tuned against a
     real cookbook — don't just assert this was tuned, show it: include an actual bad chunk boundary
     produced at a wrong percentile (e.g. `0.95`, badly under-chunking) side by side with the good
     boundary at `0.85` on the same passage.
   - This is the most conceptually interesting strategy and probably deserves the most space: explain
     *why* a percentile-based per-document threshold beats a fixed distance cutoff (documents vary
     wildly in how "choppy" their semantic content is).

5. **Head-to-head on the same document**
   - Pick one real book already used in testing (the cookbook, or the Bad Bug Book / Jekyll and Hyde
     fixtures) and show actual chunk boundaries from all three strategies side by side.
   - Be honest about where each one wins/loses — this is more credible than declaring one strategy
     universally best.
   - The title promises a "shootout," so this section needs to actually score something, not just
     describe three strategies again: a small comparison table (chunk count, average/median chunk
     size per strategy) plus an informal retrieval spot-check — run the same 3 real queries against
     all three strategies' chunk sets and note which strategy's chunks actually come back. This is a
     data-gathering task before drafting, not something to write from memory.

6. **When to reach for which**
   - Practical guidance: TOKEN as a safe universal default, STRUCTURAL for well-organized documents
     (textbooks, cookbooks, academic papers) where headings are meaningful, SEMANTIC where structure
     is thin or inconsistent but content still has topical shifts (narrative, transcripts).

## Possible closing note
Acknowledge this is per-document-type tuning, not a solved problem — close on `chunkingStrategyResolver`
picking variants per document, and the open question of whether that resolution should get smarter
over time (more variants, learned thresholds) rather than staying a fixed set of hand-tuned defaults.
(Retrieval-time fixes like HyDE/agentic follow-up retrieval belong to post 03, not here — this post
stays scoped to ingest-time chunking.)
