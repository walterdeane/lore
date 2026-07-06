package com.walterdeane.lore.document

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.stereotype.Component

@Component
class StructuralTextSplitter {

    private val log = LoggerFactory.getLogger(StructuralTextSplitter::class.java)

    companion object {
        private val SECTION_HEADERS = setOf(
            "ingredients", "instructions", "directions", "method", "methods",
            "notes", "note", "preparation", "serves", "makes", "servings",
            "prep time", "cook time", "yield", "equipment", "to serve",
            "to finish", "to make", "for serving", "for the", "tips",
            "variations", "storage", "make ahead", "do ahead",
        )
        private const val MAX_TITLE_LENGTH = 80
        private const val MIN_CHUNK_CHARS = 200
    }

    fun split(pages: List<Document>): List<Document> {
        val fullText = pages.joinToString("\n\n") { it.text ?: "" }
        val lines = fullText.lines()

        val segments = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()

        for (i in lines.indices) {
            if (isBoundary(lines[i], lines, i)) {
                if (current.any { it.isNotBlank() }) segments.add(current)
                current = mutableListOf(lines[i])
            } else {
                current.add(lines[i])
            }
        }
        if (current.any { it.isNotBlank() }) segments.add(current)

        val merged = mergeShortSegments(segments)
        log.info("structural split: {} boundaries detected, {} chunks after merge", segments.size, merged.size)

        return merged
            .map { it.joinToString("\n").trim() }
            .filter { it.isNotBlank() }
            .map { Document(it) }
    }

    private fun isBoundary(line: String, lines: List<String>, index: Int): Boolean {
        val trimmed = line.trim()

        if (trimmed.length < 3 || trimmed.length > MAX_TITLE_LENGTH) return false
        if (!trimmed[0].isUpperCase()) return false
        if (trimmed.endsWith(".") || trimmed.endsWith(",") || trimmed.endsWith(";") || trimmed.endsWith(":")) return false
        if (trimmed[0].isDigit()) return false

        val lower = trimmed.lowercase()
        if (lower in SECTION_HEADERS) return false
        if (SECTION_HEADERS.any { lower.startsWith(it) }) return false

        // Must be preceded by a blank line (recipe titles follow whitespace)
        val prevBlank = index == 0 || lines[index - 1].isBlank()
        if (!prevBlank) return false

        // Must be followed by content (not another short title-like line immediately)
        val nextLine = if (index + 1 < lines.size) lines[index + 1].trim() else ""
        if (nextLine.isBlank()) return false

        return true
    }

    private fun mergeShortSegments(segments: List<MutableList<String>>): List<List<String>> {
        if (segments.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<String>>()
        var acc = segments[0].toMutableList()

        for (i in 1 until segments.size) {
            if (acc.joinToString("").trim().length < MIN_CHUNK_CHARS) {
                acc.addAll(segments[i])
            } else {
                result.add(acc)
                acc = segments[i].toMutableList()
            }
        }
        result.add(acc)
        return result
    }
}
