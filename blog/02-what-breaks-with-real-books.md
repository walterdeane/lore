# What Breaks When You Ingest Real Books Instead of Test Fixtures

## Angle
Every RAG tutorial ingests a clean PDF or two and calls it done. This post is the opposite: a log of
what actually happened when I pointed the ingestion pipeline at real, messy, real-world files —
libgen EPUBs, archive.org scans, professionally typeset cookbooks — and what each failure taught me
about the gap between "works in my test suite" and "works on the internet's actual files."
Recommended lead post: most concrete, most shareable, lowest context needed from readers.

## Hook
Open cold with the "Thinking, Fast and Slow" failure: a single malformed `<divlity>` tag, a scraping/
OCR artifact, buried in 1 of 70 content files, silently killing the whole import. Nothing else about
the book was wrong.

## Sections

1. **The setup** — brief: Lore's ingestion pipeline (Tika extraction → chunking strategy → embed →
   store), just enough context for the failures to land.

2. **Failure 1: the strict parser vs. the lenient one**
   - `TIKA-237: Illegal SAXException` on the "Thinking, Fast and Slow" EPUB.
   - Root cause: a malformed tag tripping Tika's strict SAX parser.
   - The twist: `EpubMarkdownParser` (Jsoup, lenient) extracted the entire 1.16M-character book fine,
     malformed tag and all. The real bug wasn't the file — it was `DocumentIngestionService` calling
     Tika unconditionally *before* branching on chunking strategy, even for strategies that only use
     Tika as a fallback.
   - Fix: made the Tika read lazy (`() -> List<Document>` instead of an eager value), so a strategy
     that never needs Tika never pays for its strictness.
   - Lesson: "unconditional eager work before a branch" is a classic footgun — profile/trace where
     your fallback path actually forks.

3. **Failure 2: a "valid enough" file that isn't valid**
   - An archive.org-scanned EPUB threw `EpubZipException` from Tika's own `EpubParser` — the
     `mimetype` entry inside the zip wasn't first/uncompressed, which the EPUB spec requires but
     apparently plenty of real files don't honor.
   - Decision: rejected this fixture rather than working around it — not a Lore bug, a genuinely
     malformed container. Good moment to talk about where to draw the line between "fix it" and
     "that file is actually broken."

4. **Failure 3: headings that aren't headings**
   - PDF font-size heuristic misread a print-shop production stamp and stray larger-font glyphs as
     markdown headings, fragmenting body text into tiny false-boundary chunks.
   - Fix: prefer the PDF's real embedded outline/TOC (`ParagraphPdfDocumentReader`, keyed off actual
     bookmarks) over guessing from font size, falling back to the heuristic only when no outline
     exists.

5. **Failure 4: running headers, twice**
   - First pass: book-wide running headers (title reprinted on every page) caught via a 30%-of-
     sections frequency threshold; chapter-scoped headers caught via a `^chapter\s+\d+` pattern.
   - Second pass, different book: page numbers baked directly into the header line
     (`THE MEAT HOOK MEAT BOOK 14`) meant every occurrence was a distinct string, none individually
     clearing the frequency bar — 71 of 436 chunks leaked the header mid-sentence.
   - Third pass: even after stripping trailing page numbers, `PDFLayoutTextStripperByArea`'s column
     extraction left irregular internal spacing, splitting one logical header into multiple count
     keys that individually stayed under threshold.
   - The best anecdote here: a "verified clean" diagnostic test gave a false all-clear because it
     compared raw, unnormalized lines — the fix was collapsing whitespace runs before comparing, and
     the real lesson is that your verification tooling can have the same blind spot as the bug it's
     checking for.

6. **What this adds up to** — none of these were exotic edge cases; they were the *first few* books
   thrown at the pipeline. Takeaway: budget real-world ingestion time proportional to how varied your
   input corpus actually is, not how clean your fixtures are. Testing against fixtures tells you the
   code runs; testing against the wild tells you where your assumptions live.

## Possible closing note
Tee up the testing post (topic 4) — mention these bugs are also why the integration-test suite exists
against real fixture files rather than mocks.
