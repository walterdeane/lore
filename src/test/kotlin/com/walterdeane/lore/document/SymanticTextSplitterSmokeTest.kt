package com.walterdeane.lore.document

import com.walterdeane.lore.model.SourceType
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions
import java.io.File
import kotlin.math.roundToInt

/**
 * Not a real assertion-based test — a diagnostic runner for eyeballing whether
 * [SymanticTextSplitter]'s window size / breakpoint percentile produce sane chunk boundaries on a
 * real document, before wiring the class into [DocumentIngestionService]. Requires Ollama running
 * locally (`ollama serve`, with `nomic-embed-text` pulled) and at least one real EPUB/PDF sitting in
 * the configured documents directory, so it's off by default (`SMOKE=true` env var to run it) rather
 * than part of the normal unit-test suite.
 */
class SymanticTextSplitterSmokeTest {

    @EnabledIfEnvironmentVariable(named = "SMOKE", matches = "true")
    @Test
    fun `print paragraph, window, and breakpoint stats for a real document`() {
        val docsDir = resolveDocsDir()
        assumeTrue(docsDir.isDirectory, "no documents dir at $docsDir, skipping")

        val (file, sourceType) = pickSmallestDocument(docsDir)
            ?: run { assumeTrue(false, "no .epub/.pdf found in $docsDir, skipping"); error("unreachable") }

        println("=== using ${file.name} (${sourceType}, ${file.length() / 1024}KB) ===")

        val embeddingModel = OllamaEmbeddingModel.builder()
            .ollamaApi(OllamaApi.builder().build())
            .options(OllamaEmbeddingOptions.builder().model("nomic-embed-text").build())
            .build()

        val splitter = SymanticTextSplitter(EpubMarkdownParser(), PdfMarkdownParser(), embeddingModel)

        val markdown = when (sourceType) {
            SourceType.EPUB -> EpubMarkdownParser().parse(file.path)
            SourceType.PDF -> PdfMarkdownParser().parse(file.path)
        }
        assumeTrue(markdown.isNotBlank(), "markdown parser returned blank for ${file.name}, skipping")

        val allParagraphs = splitter.extractParagraphs(markdown)
        println("paragraphs in document: ${allParagraphs.size}")
        assumeTrue(allParagraphs.size >= 3, "only ${allParagraphs.size} paragraphs, too few for breakpoint stats")

        // The local Ollama runner for nomic-embed-text is a single-slot process (`-np 1`); sending
        // the whole document's windows as one batched embed(List<String>) call made it time out
        // mid-request. Capping to the first maxParagraphs and embedding one at a time keeps this
        // a quick, reliable eyeball check rather than a full-document ingestion run.
        val maxParagraphs = System.getenv("MAX_PARAGRAPHS")?.toIntOrNull() ?: 200
        val paragraphs = allParagraphs.take(maxParagraphs)
        println("using first ${paragraphs.size} paragraphs (set MAX_PARAGRAPHS to change)\n")

        // windowSize changes what gets embedded, so it's re-embedded per value; breakpointPercentile
        // only changes the post-hoc threshold, so it's swept for free against the same distances.
        val windowSizes = (System.getenv("WINDOW_SIZES") ?: "0,1,2").split(",").map { it.trim().toInt() }
        val percentiles = (System.getenv("PERCENTILES") ?: "0.75,0.85,0.90,0.95")
            .split(",").map { it.trim().toDouble() }

        for (windowSize in windowSizes) {
            println("--- windowSize=$windowSize ---")
            val windows = splitter.buildWindows(paragraphs, windowSize)
            val embeddings = windows.mapIndexed { i, text ->
                if (i % 25 == 0) println("  embedding window $i/${windows.size}...")
                embeddingModel.embed(text)
            }
            val distances = (0 until embeddings.size - 1).map { i ->
                1.0 - splitter.cosineSimilarity(embeddings[i], embeddings[i + 1])
            }
            println("  distances: min=${distances.min().fmt()} max=${distances.max().fmt()} mean=${distances.average().fmt()}")

            for (p in percentiles) {
                val threshold = splitter.percentile(distances, p)
                val rawChunks = splitter.groupAtBreakpoints(paragraphs, distances, threshold)
                val merged = splitter.mergeShort(rawChunks, SemanticConfig().minChunkChars)
                val chunks = splitter.capOversizedChunks(merged, SemanticConfig().maxChunkChars)
                val sizes = chunks.map { it.length }
                println("  p=$p threshold=${threshold.fmt()} → ${rawChunks.size} raw → ${merged.size} merged → " +
                    "${chunks.size} chunks (avg ${sizes.average().roundToInt()} chars, min ${sizes.min()}, max ${sizes.max()})")
            }
            println()
        }

        // Full preview at the config currently in SemanticConfig()'s defaults, so it's easy to see
        // actual chunk boundaries rather than just the summary counts above.
        val defaultConfig = SemanticConfig()
        println("=== full chunks at defaults (windowSize=${defaultConfig.windowSize}, " +
            "breakpointPercentile=${defaultConfig.breakpointPercentile}) ===")
        val windows = splitter.buildWindows(paragraphs, defaultConfig.windowSize)
        val embeddings = windows.map { embeddingModel.embed(it) }
        val distances = (0 until embeddings.size - 1).map { i ->
            1.0 - splitter.cosineSimilarity(embeddings[i], embeddings[i + 1])
        }
        val threshold = splitter.percentile(distances, defaultConfig.breakpointPercentile)
        val merged = splitter.mergeShort(splitter.groupAtBreakpoints(paragraphs, distances, threshold), defaultConfig.minChunkChars)
        val chunks = splitter.capOversizedChunks(merged, defaultConfig.maxChunkChars)
        chunks.forEachIndexed { i, text ->
            val preview = text.lines().firstOrNull { it.isNotBlank() }?.take(80) ?: ""
            println("[$i] ${text.length} chars — $preview")
        }
    }

    private fun Double.fmt() = "%.4f".format(this)

    private fun resolveDocsDir(): File {
        val explicit = System.getenv("LORE_DOCS_DIR")
        if (explicit != null) return File(explicit)
        val dataDir = System.getenv("LORE_DATA_DIR") ?: "${System.getProperty("user.home")}/lore-data"
        return File(dataDir, "documents")
    }

    private fun pickSmallestDocument(dir: File): Pair<File, SourceType>? {
        val wanted = System.getenv("SOURCE_TYPE")?.uppercase()?.let { SourceType.valueOf(it) }
        return dir.listFiles()
            ?.mapNotNull { f ->
                when (f.extension.lowercase()) {
                    "epub" -> f to SourceType.EPUB
                    "pdf" -> f to SourceType.PDF
                    else -> null
                }
            }
            ?.filter { wanted == null || it.second == wanted }
            ?.minByOrNull { it.first.length() }
    }
}
