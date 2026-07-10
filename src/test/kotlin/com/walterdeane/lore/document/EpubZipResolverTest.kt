package com.walterdeane.lore.document

import com.walterdeane.lore.exception.UnsupportedDocumentTypeException
import com.walterdeane.lore.model.SourceType
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubZipResolverTest {

    private val resolver = EpubZipResolver()

    /** Builds an in-memory zip from name -> content pairs, in order (mimicking real zip tools). */
    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Reads back a zip's entry names, for asserting on repackStrippingPrefix's output. */
    private fun entryNames(zipBytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zip.nextEntry
            }
        }
        return names
    }

    // --- resolveUpload: the public entry point --------------------------------------------

    @Test
    fun `resolveUpload passes through a declared epub or pdf unchanged`() {
        val bytes = "not actually parsed here".toByteArray()
        val epub = resolver.resolveUpload("epub", bytes, "book.epub")
        assertEquals(SourceType.EPUB, epub.sourceType)
        assertEquals("epub", epub.suffix)
        assertEquals(bytes, epub.content)

        val pdf = resolver.resolveUpload("pdf", bytes, "book.pdf")
        assertEquals(SourceType.PDF, pdf.sourceType)
        assertEquals("pdf", pdf.suffix)
    }

    @Test
    fun `resolveUpload rejects an unrecognized extension`() {
        val ex = assertFailsWith<UnsupportedDocumentTypeException> {
            resolver.resolveUpload("txt", "hello".toByteArray(), "notes.txt")
        }
        assertTrue(ex.message!!.contains("txt"))
    }

    @Test
    fun `resolveUpload rejects a zip that is neither an EPUB container nor a wrapped document`() {
        val zip = zipOf("readme.txt" to "just some random archive")
        assertFailsWith<UnsupportedDocumentTypeException> {
            resolver.resolveUpload("zip", zip, "random.zip")
        }
    }

    @Test
    fun `resolveUpload accepts a zip that is a real EPUB container`() {
        val zip = zipOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<container/>",
        )
        val resolved = resolver.resolveUpload("zip", zip, "book.epub.zip")
        assertEquals(SourceType.EPUB, resolved.sourceType)
        assertEquals("epub", resolved.suffix)
        assertEquals(zip, resolved.content) // no re-rooting needed, so bytes pass through as-is
    }

    // --- findEpubMimetypePrefix: EPUB signature detection, at root or one directory deep ------

    @Test
    fun `findEpubMimetypePrefix finds a top-level mimetype entry with the right content`() {
        val zip = zipOf("mimetype" to "application/epub+zip")
        assertEquals("", resolver.findEpubMimetypePrefix(zip))
    }

    @Test
    fun `findEpubMimetypePrefix finds a folder-bundle mimetype one directory deep`() {
        val zip = zipOf("My Book.epub/mimetype" to "application/epub+zip")
        assertEquals("My Book.epub/", resolver.findEpubMimetypePrefix(zip))
    }

    @Test
    fun `findEpubMimetypePrefix rejects a mimetype entry with the wrong content`() {
        val zip = zipOf("mimetype" to "application/zip")
        assertNull(resolver.findEpubMimetypePrefix(zip))
    }

    @Test
    fun `findEpubMimetypePrefix returns null for a zip with no mimetype entry at all`() {
        val zip = zipOf("readme.txt" to "hello")
        assertNull(resolver.findEpubMimetypePrefix(zip))
    }

    @Test
    fun `findEpubMimetypePrefix returns null for bytes that aren't a zip at all`() {
        assertNull(resolver.findEpubMimetypePrefix("not a zip file".toByteArray()))
    }

    // --- extractSingleWrappedDocument: macOS "Compress"-style single-file wrapper -------------

    @Test
    fun `extractSingleWrappedDocument finds the one real file, ignoring macOS junk`() {
        val zip = zipOf(
            "Recipes.epub" to "epub bytes here",
            "__MACOSX/._Recipes.epub" to "resource fork junk",
        )
        val result = resolver.extractSingleWrappedDocument(zip)
        assertEquals("epub", result?.first)
        assertEquals("epub bytes here", result?.second?.toString(Charsets.UTF_8))
    }

    @Test
    fun `extractSingleWrappedDocument returns null when there's more than one real file`() {
        val zip = zipOf(
            "one.epub" to "a",
            "two.epub" to "b",
        )
        assertNull(resolver.extractSingleWrappedDocument(zip))
    }

    @Test
    fun `extractSingleWrappedDocument returns null for an unsupported inner extension`() {
        val zip = zipOf("notes.txt" to "hello")
        assertNull(resolver.extractSingleWrappedDocument(zip))
    }

    // --- repackStrippingPrefix: re-rooting a folder-style EPUB bundle -------------------------

    @Test
    fun `repackStrippingPrefix strips the prefix and drops macOS junk`() {
        val zip = zipOf(
            "My Book.epub/mimetype" to "application/epub+zip",
            "My Book.epub/META-INF/container.xml" to "<container/>",
            "__MACOSX/My Book.epub/._mimetype" to "junk",
        )

        val repacked = resolver.repackStrippingPrefix(zip, "My Book.epub/")
        val names = entryNames(repacked)

        assertEquals(listOf("mimetype", "META-INF/container.xml"), names)
    }
}