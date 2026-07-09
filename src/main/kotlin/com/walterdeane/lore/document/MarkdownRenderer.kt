package com.walterdeane.lore.document

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.stereotype.Component

/** Renders stored chunk/document/chat-answer markdown to sanitized HTML for display templates. */
@Component
class MarkdownRenderer {

    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().sanitizeUrls(true).build()

    fun toHtml(markdown: String): String = renderer.render(parser.parse(markdown))
}
