package com.walterdeane.lore.document

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.zip.ZipFile

@Component
class EpubMarkdownParser {

    private val log = LoggerFactory.getLogger(EpubMarkdownParser::class.java)

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
                    val md = htmlToMarkdown(html)
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

    private fun htmlToMarkdown(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("head, script, style, nav, aside, footer, figure, figcaption").remove()
        val sb = StringBuilder()
        appendNode(doc.body() ?: return "", sb)
        return collapse(sb.toString())
    }

    private fun appendNode(node: Node, sb: StringBuilder) {
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
                    if (t.isNotBlank()) sb.append("\n\n$t\n\n")
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
                else -> node.childNodes().forEach { appendNode(it, sb) }
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
