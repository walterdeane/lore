package com.walterdeane.lore.document

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.zip.ZipFile

/**
 * Converts an EPUB to markdown by reading its spine (reading order) from the OPF manifest and
 * translating each chapter's XHTML to markdown, preserving heading levels so
 * [StructuralTextSplitter]/[MarkdownChunker] can later split on them. Feeds the STRUCTURAL
 * chunking strategy; TOKEN chunking uses Tika's plain-text extraction instead and never calls this.
 */
@Component
class EpubMarkdownParser {

    private val log = LoggerFactory.getLogger(EpubMarkdownParser::class.java)

    /** Reads the EPUB's container/OPF metadata to find spine order, then concatenates each chapter's markdown. */
    fun parse(epubPath: String): String {
        return try {
            ZipFile(epubPath).use { zip ->
                val opfPath = findOpfPath(zip) ?: return ""
                val opfDir = opfPath.substringBeforeLast("/", "")
                val (manifest, spine) = parseOpf(zip, opfPath)

                val sb = StringBuilder()
                for (itemId in spine) {
                    val href = manifest[itemId] ?: continue
                    val fullHref = if (opfDir.isNotEmpty()) normalize("$opfDir/$href") else href
                    val entry = zip.getEntry(fullHref) ?: zip.getEntry(href) ?: continue
                    val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                    val md = htmlToMarkdown(html, zip, fullHref)
                    if (md.isNotBlank()) sb.append(md).append("\n\n")
                }
                sb.toString()
            }
        } catch (e: Exception) {
            log.warn("EPUB markdown parsing failed for {}: {}", epubPath, e.message)
            ""
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val entry = zip.getEntry("META-INF/container.xml") ?: return null
        val xml = zip.getInputStream(entry).bufferedReader().readText()
        return Jsoup.parse(xml, "", Parser.xmlParser()).selectFirst("rootfile")?.attr("full-path")
    }

    private fun parseOpf(zip: ZipFile, opfPath: String): Pair<Map<String, String>, List<String>> {
        val entry = zip.getEntry(opfPath) ?: return emptyMap<String, String>() to emptyList()
        val xml = zip.getInputStream(entry).bufferedReader().readText()
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val manifest = doc.select("item").associate { it.attr("id") to it.attr("href") }
        val spine = doc.select("itemref").map { it.attr("idref") }
        return manifest to spine
    }

    /** Strips non-content elements (nav, scripts, figures) then walks the body converting tags to markdown syntax. */
    private fun htmlToMarkdown(html: String, zip: ZipFile, entryPath: String): String {
        val doc = Jsoup.parse(html)
        val boldClasses = resolveBoldClasses(doc, zip, entryPath)
        doc.select("head, script, style, nav, aside, footer, figure, figcaption").remove()
        val sb = StringBuilder()
        appendNode(doc.body() ?: return "", sb, boldClasses)
        return collapse(sb.toString())
    }

    /**
     * Class names (from this chapter's linked stylesheets) whose declared `font-weight` is bold —
     * needed because many EPUB conversions (calibre-derived ones especially) encode visual bolding
     * as `<span class="bold1">` rather than a real `<b>`/`<strong>` tag. Read from `<head>` before
     * [htmlToMarkdown] strips it.
     */
    private fun resolveBoldClasses(doc: Document, zip: ZipFile, entryPath: String): Set<String> {
        val dir = entryPath.substringBeforeLast("/", "")
        val boldClasses = mutableSetOf<String>()
        for (link in doc.select("link[rel=stylesheet]")) {
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: continue
            val cssPath = normalize(if (dir.isNotEmpty()) "$dir/$href" else href)
            val entry = zip.getEntry(cssPath) ?: continue
            val css = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
            boldClasses += extractBoldClassNames(css)
        }
        return boldClasses
    }

    /**
     * Deliberately simple regex-based CSS scan — flat `.class { font-weight: bold }` rules only, no
     * `@media`/nested selectors/combinators. That covers the flat per-run classes calibre and similar
     * converters emit; anything fancier just isn't recognized as bold, which only costs a missed
     * pseudo-heading, not a wrong split.
     */
    internal fun extractBoldClassNames(css: String): Set<String> {
        val classNames = mutableSetOf<String>()
        for (m in Regex("""([^{}]+)\{([^{}]*)\}""").findAll(css)) {
            val body = m.groupValues[2]
            val weight = Regex("""font-weight\s*:\s*(\w+)""", RegexOption.IGNORE_CASE).find(body) ?: continue
            val value = weight.groupValues[1].lowercase()
            val isBold = value == "bold" || (value.toIntOrNull()?.let { it >= 600 } ?: false)
            if (!isBold) continue
            for (selector in m.groupValues[1].split(",")) {
                val trimmed = selector.trim()
                if (trimmed.startsWith(".") && trimmed.drop(1).matches(Regex("""[\w-]+"""))) {
                    classNames.add(trimmed.drop(1))
                }
            }
        }
        return classNames
    }

    /**
     * A `<p>` whose *entire* visible text is wrapped by a single bold element — a real `<b>`/
     * `<strong>` tag, or a span carrying one of [boldClasses] resolved from the stylesheet — reads
     * as a subhead in print even though it's not a semantic heading tag. Kahneman's "Speaking of
     * System 1 and System 2" recap boxes in *Thinking, Fast and Slow* are exactly this: a bold-only
     * paragraph the ebook conversion never promoted to `<h#>`. The length/punctuation check is the
     * same shape heuristic [StructuralTextSplitter.couldBeHeading] uses for PDFs with no font-size
     * signal: short, title-like, no sentence-ending punctuation.
     */
    internal fun looksLikeBoldHeading(p: Element, text: String, boldClasses: Set<String>): Boolean {
        if (text.length < 3 || text.length > 80) return false
        if (text.last() in ".,;:") return false
        val boldSelector = (listOf("b", "strong") + boldClasses.map { ".$it" }).joinToString(", ")
        return p.select(boldSelector).any { it.text().trim() == text }
    }

    private fun appendNode(node: Node, sb: StringBuilder, boldClasses: Set<String>) {
        when {
            node is TextNode -> {
                val t = node.text()
                if (t.isNotBlank()) sb.append(t)
            }
            node is Element -> when (node.tagName().lowercase()) {
                "h1" -> sb.append("\n\n# ${node.text().trim()}\n\n")
                "h2" -> sb.append("\n\n## ${node.text().trim()}\n\n")
                "h3" -> sb.append("\n\n### ${node.text().trim()}\n\n")
                "h4" -> sb.append("\n\n#### ${node.text().trim()}\n\n")
                "h5", "h6" -> sb.append("\n\n##### ${node.text().trim()}\n\n")
                "p" -> {
                    val t = node.text().trim()
                    when {
                        t.isBlank() -> {}
                        // Emitted at the same level STRUCTURAL's default config splits real chapter
                        // headings on (see BoundaryConfig.markdownHeadingLevel = 2), so a detected
                        // subhead actually becomes a chunk boundary instead of just looking like one.
                        looksLikeBoldHeading(node, t, boldClasses) -> sb.append("\n\n## $t\n\n")
                        else -> sb.append("\n\n$t\n\n")
                    }
                }
                "br" -> sb.append("\n")
                "ul" -> {
                    sb.append("\n")
                    node.select("> li").forEach { sb.append("- ${it.text().trim()}\n") }
                    sb.append("\n")
                }
                "ol" -> {
                    sb.append("\n")
                    node.select("> li").forEachIndexed { i, li ->
                        sb.append("${i + 1}. ${li.text().trim()}\n")
                    }
                    sb.append("\n")
                }
                "strong", "b" -> sb.append("**${node.text()}**")
                "em", "i" -> sb.append("*${node.text()}*")
                "blockquote" -> sb.append("\n> ${node.text().trim()}\n\n")
                "hr" -> sb.append("\n\n---\n\n")
                "table" -> appendTable(node, sb)
                "img" -> {}
                else -> node.childNodes().forEach { appendNode(it, sb, boldClasses) }
            }
        }
    }

    private fun appendTable(table: Element, sb: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return
        sb.append("\n")
        rows.forEachIndexed { i, row ->
            val cells = row.select("th, td").map { it.text().trim() }
            sb.append("| ${cells.joinToString(" | ")} |\n")
            if (i == 0) sb.append("| ${cells.map { "---" }.joinToString(" | ")} |\n")
        }
        sb.append("\n")
    }

    // Collapse runs of 3+ blank lines down to 2.
    private fun collapse(text: String) = text.trim().replace(Regex("\n{3,}"), "\n\n")

    private fun normalize(path: String): String {
        val result = mutableListOf<String>()
        for (part in path.split("/")) {
            when (part) {
                ".." -> if (result.isNotEmpty()) result.removeLast()
                ".", "" -> {}
                else -> result.add(part)
            }
        }
        return result.joinToString("/")
    }
}
