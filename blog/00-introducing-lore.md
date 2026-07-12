# Introducing Lore: A Local-First RAG System for Personal Knowledge

## Angle
This is the scene-setter — the post that has to run first, since the other four all assume the
reader knows what Lore is, why it exists, and roughly how it's put together. Purpose: explain the
problem being solved, the "build a search engine first, then add generation" philosophy, and the
local-first-with-a-Claude-escape-hatch design, so later posts can dive straight into chunking/search/
testing without re-explaining the basics.

## Hook
Not a bug story this time (that's post 2) — open with the actual motivating problem: a personal
library of hobby references (cookbooks, brewing texts, woodworking manuals, lutherie guides,
permaculture texts) that's easy to *own* and hard to *query* — you know the answer is in one of these
books somewhere, but not which one, or which page. That's the gap Lore closes.

## Sections

1. **What Lore is, in one paragraph**
   - A personal knowledge RAG system: ingest documents across hobbies/interests, organize them, query
     them in natural language, get back an answer plus the exact cited source chunks (Perplexity-
     style — answer *and* receipts, not just an answer).
   - Note the secondary motivation briefly: most RAG reference implementations are Python
     (LangChain/LlamaIndex); a clean Spring Boot + Kotlin + Spring AI local-RAG example is
     underrepresented. Personal tool first, reference implementation second — don't lead with this,
     but it's worth a mention for the audience most likely to read a Spring/Kotlin blog.

2. **The domain model, briefly, with a diagram**
   - Domain (a theme: Brewing, Cookbooks, Woodworking, Lutherie, Permaculture) → Documents → Chunks,
     with hierarchical Tags (Postgres `ltree` materialized paths, e.g. `cuisine.italian.northern`)
     scoped per domain for organizing/filtering.
   - *Diagram 1:* simple entity-relationship box diagram — Domain / Tag / Document / Chunk and their
     relationships. Keep it simple; this is context for later posts, not the main content.
   - One sentence on the denormalization choice (chunk carries domainId + tagPaths directly) and why
     — filtered vector search performance over normalization purity — without going deep (that's not
     this post's job).

3. **The build philosophy: search engine first, RAG second**
   - The key sequencing decision: get plain retrieval working and validated *before* ever adding an
     LLM generation step on top of it. Concretely — a retrieval-only path (search without an LLM call)
     came before the full ask-a-question-get-an-answer path.
   - Why this order matters: if retrieval is bad, no amount of prompt engineering on the generation
     step fixes it — you're just asking an LLM to write a fluent answer from the wrong source
     material. Validating "does the right chunk come back for this query" as its own testable,
     inspectable thing, independent of generation, catches retrieval problems where they're cheap to
     see and fix.
   - This is also where BM25 entered before vector/hybrid search — start with a well-understood,
     inspectable, classic retrieval method, confirm the plumbing (ingestion → storage → query →
     ranked results) end-to-end, *then* layer in embeddings and fusion once the foundation is proven.
   - *Diagram 2:* a simple pipeline/timeline diagram showing the build order: ingest → BM25 search →
     add vector search → hybrid fusion (RRF) → add LLM reranking → add chat/generation on top. Framing
     it as layers added over time, not a single architecture that appeared all at once.

4. **Local-first, with an escape hatch**
   - The core design commitment: this runs entirely locally via Ollama — both chat and embeddings —
     for privacy, cost, and offline use on a personal knowledge base you don't want to ship to a third
     party by default.
   - The pragmatic caveat: embeddings always stay local (cheap, fast, no real quality gap for this use
     case), but chat/generation is swappable to Anthropic's Claude API when local model performance
     (on a consumer machine, running an 8B–14B model) isn't good enough for the day-to-day quality bar
     — a config swap, not a rearchitecture.
   - Frame this as a deliberate two-tier decision rather than "local, except when I gave up": the
     boundary is drawn specifically at chat generation because that's where model capability gaps show
     up most, while embedding quality differences matter much less for retrieval at this scale.
   - *Diagram 3 (optional):* a small diagram showing the provider boundary — embeddings always → local
     Ollama; chat → local Ollama *or* Anthropic, behind the same interface.

5. **What's ahead in this series**
   - Short signpost paragraph previewing the other four posts (chunking strategies, real-world
     ingestion failures, hybrid search architecture, testing without mocking the LLM) so this post
     functions as a table of contents as well as an introduction.

## Possible closing note
End on the "personal tool that might become a reference implementation" framing from section 1 — invite
readers who want the Spring Boot + Kotlin + Spring AI angle specifically to follow the series, since
that's the most distinctive thing about this project relative to the (mostly Python) RAG-blog
landscape.

## Diagram notes
Three diagrams suggested above (domain model ERD, build-order/pipeline timeline, provider boundary).
All three are simple enough for a hand-drawn-style or basic box-and-arrow treatment — this post is an
overview, not a deep architecture doc, so don't over-invest in diagram polish here relative to the
technical posts.
