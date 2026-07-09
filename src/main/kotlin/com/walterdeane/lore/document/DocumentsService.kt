package com.walterdeane.lore.document

import com.walterdeane.lore.model.ChunkingStrategy
import com.walterdeane.lore.model.Document
import com.walterdeane.lore.model.StructuralVariant
import com.walterdeane.lore.model.SourceType
import com.walterdeane.lore.exception.UnsupportedDocumentTypeException
import com.walterdeane.lore.model.IngestionStatus
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Document lifecycle management (upload, tag, reingest, delete) sitting above [DocumentRepository]/
 * [ChunkRepository]; kicks off [DocumentIngestionService] whenever a document's content or chunking
 * config needs (re)processing into embeddings.
 */
@Service
class DocumentsService(
    private val documentStorageProperties: DocumentStorageProperties,
    private val documentRepository: DocumentRepository,
    private val chunkRepository: ChunkRepository,
    private val documentIngestionService: DocumentIngestionService,
) {
    private val log = LoggerFactory.getLogger(DocumentsService::class.java)

    fun getDocuments(domainId: UUID, query: String?, pageable: Pageable): Page<Document> {
        val total = documentRepository.countByDomainId(domainId, query)
        val documents = documentRepository.findByDomainId(domainId, query, pageable.pageSize, pageable.offset)
        return PageImpl(documents, pageable, total)
    }

    fun getDocumentById(id: UUID): Document? = documentRepository.findById(id)

    fun updateDocument(id: UUID, title: String, author: String?, sourcePath: String) {
        documentRepository.update(id, title, author, sourcePath)
    }

    fun updateDocumentTags(id: UUID, tags: List<String>) {
        documentRepository.updateTags(id, tags)
        chunkRepository.updateTagPathsByDocumentId(id, tags)
    }

    fun deleteDocument(id: UUID) {
        val document = documentRepository.findById(id)
        documentRepository.deleteById(id) // cascades chunks via FK
        document?.let { File(it.sourcePath).takeIf { f -> f.exists() }?.delete() }
    }

    /** Wipes existing chunks/embeddings and re-runs ingestion — useful after changing chunk strategy or tags. */
    fun reingestDocument(id: UUID) {
        val document = documentRepository.findById(id) ?: return
        chunkRepository.deleteByDocumentId(id)
        documentRepository.updateStatus(id, IngestionStatus.PENDING)
        documentIngestionService.ingest(document)
    }

    fun getAvailableTags(domainId: UUID): List<String> = emptyList()

    /** Writes the uploaded file to disk, records a PENDING [Document] row, then hands off to async ingestion. */
    fun importDocument(domainId: UUID, filename: String, content: ByteArray, title: String?, author: String?, tags: List<String>, chunkStrategy: ChunkingStrategy? = null, structuralVariant: StructuralVariant? = null): Document {
        val trimmedFilename = filename.trim()
        val declaredSuffix = trimmedFilename.substringAfterLast('.', "").lowercase()
        val resolved = resolveUpload(declaredSuffix, content, trimmedFilename)

        val id = UUID.randomUUID()
        val filePath = "${documentStorageProperties.documentsDir}/$id.${resolved.suffix}"

        val document = Document(
            id = id,
            domainId = domainId,
            title = title?.takeIf { it.isNotBlank() } ?: trimmedFilename,
            author = author,
            sourceFilename = trimmedFilename,
            sourcePath = filePath,
            sourceType = resolved.sourceType,
            tags = tags,
            ingestionStatus = IngestionStatus.PENDING,
            chunkStrategy = chunkStrategy,
            structuralVariant = structuralVariant,
        )
        documentRepository.save(document)

        val file = File(filePath)
        file.parentFile.mkdirs()
        file.writeBytes(resolved.content)

        documentIngestionService.ingest(document)
        return document
    }

    private data class ResolvedUpload(val sourceType: SourceType, val suffix: String, val content: ByteArray)

    /**
     * Resolves the declared extension to a [SourceType], with two zip-specific fallbacks — browsers/
     * OSes sometimes relabel an upload with a `.zip` extension:
     * 1. An EPUB *is* a zip container, so a `.zip` upload gets checked via [findEpubMimetypePrefix]
     *    — this also catches a folder-style EPUB (an unpacked directory bundle, e.g. as exported by
     *    Apple Books) that a browser zipped as a whole to work around HTML file inputs not
     *    supporting directory uploads, where every entry sits one directory level deeper than a
     *    normal EPUB. [repackStrippingPrefix] re-roots the zip so it's a standard EPUB before storing.
     * 2. Failing that, [extractSingleWrappedDocument] handles the simpler case of a zip that merely
     *    *wraps* one already-zipped epub/pdf file — e.g. macOS's Finder "Compress" on `Recipes.epub`
     *    produces `Recipes.epub.zip` containing the original file (plus `__MACOSX/` junk).
     * Returns the resolved content bytes too, since an unwrapped/repacked document's real bytes
     * aren't the outer zip's — and a confirmed EPUB/PDF is always stored under its real extension.
     */
    private fun resolveUpload(declaredSuffix: String, content: ByteArray, filename: String): ResolvedUpload =
        when (declaredSuffix) {
            "epub" -> ResolvedUpload(SourceType.EPUB, "epub", content)
            "pdf" -> ResolvedUpload(SourceType.PDF, "pdf", content)
            "zip" -> resolveZipUpload(content, filename) ?: rejectUnsupported(filename, declaredSuffix)
            else -> rejectUnsupported(filename, declaredSuffix)
        }

    private fun resolveZipUpload(content: ByteArray, filename: String): ResolvedUpload? {
        findEpubMimetypePrefix(content)?.let { prefix ->
            log.info(
                "'{}' uploaded with .zip extension, detected as EPUB content{}", filename,
                if (prefix.isEmpty()) "" else " (folder-style bundle, prefix '$prefix')",
            )
            val epubContent = if (prefix.isEmpty()) content else repackStrippingPrefix(content, prefix)
            return ResolvedUpload(SourceType.EPUB, "epub", epubContent)
        }
        val (innerSuffix, innerContent) = extractSingleWrappedDocument(content) ?: return null
        log.info("'{}' is a zip wrapping a single .{} file, unwrapping", filename, innerSuffix)
        return when (innerSuffix) {
            "epub" -> ResolvedUpload(SourceType.EPUB, "epub", innerContent)
            "pdf" -> ResolvedUpload(SourceType.PDF, "pdf", innerContent)
            else -> null
        }
    }

    private fun rejectUnsupported(filename: String, declaredSuffix: String): Nothing {
        val detected = declaredSuffix.ifBlank { "none" }
        log.warn("rejected upload '{}': unsupported extension (detected: {})", filename, detected)
        throw UnsupportedDocumentTypeException(
            "Unsupported file type for '$filename' (detected extension: $detected) — expected .epub or .pdf"
        )
    }

    /**
     * EPUB's OCF container spec requires a `mimetype` entry containing exactly `application/epub+zip`;
     * a plain zip won't have one. Also matches that same entry one directory level deep (a path
     * ending in "/mimetype") to catch a folder-style EPUB zipped as a whole, returning the path prefix in front of it —
     * empty string for an already-standard EPUB, or e.g. `"My Book.epub/"` for a folder bundle.
     * Returns null if no EPUB signature is found at either level.
     */
    private fun findEpubMimetypePrefix(content: ByteArray): String? = try {
        ZipInputStream(content.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "mimetype" || name.endsWith("/mimetype")) {
                    val text = zip.readBytes().toString(Charsets.US_ASCII).trim()
                    if (text == "application/epub+zip") {
                        return if (name == "mimetype") "" else name.removeSuffix("mimetype")
                    }
                }
                entry = zip.nextEntry
            }
            null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Rewrites [content] as a new zip with [prefix] stripped from every entry name (also dropping
     * directory entries and macOS `__MACOSX/`/`._*` junk), turning a folder-style EPUB bundle into a
     * standard top-level EPUB container that [EpubMarkdownParser]'s exact-path lookups can read.
     */
    private fun repackStrippingPrefix(content: ByteArray, prefix: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zipOut ->
            ZipInputStream(content.inputStream()).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val relativeName = entry.name.removePrefix(prefix)
                    val isJunk = entry.isDirectory || !entry.name.startsWith(prefix) || relativeName.isBlank() ||
                        relativeName.startsWith("__MACOSX/") || relativeName.substringAfterLast('/').startsWith("._")
                    if (!isJunk) {
                        zipOut.putNextEntry(ZipEntry(relativeName))
                        zipIn.copyTo(zipOut)
                        zipOut.closeEntry()
                    }
                    entry = zipIn.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * If [content] is a zip containing exactly one real file (ignoring directories and macOS
     * `__MACOSX/`/`._*` AppleDouble metadata that Finder's "Compress" adds) with a `.epub`/`.pdf`
     * name, returns its extension and bytes. Returns null for anything else — multiple real files,
     * an unsupported inner extension, or a malformed zip — so callers fall back to rejecting.
     */
    private fun extractSingleWrappedDocument(content: ByteArray): Pair<String, ByteArray>? = try {
        ZipInputStream(content.inputStream()).use { zip ->
            var entry = zip.nextEntry
            var found: Pair<String, ByteArray>? = null
            while (entry != null) {
                val name = entry.name
                val isJunk = entry.isDirectory || name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith("._")
                if (!isJunk) {
                    if (found != null) return null
                    val suffix = name.substringAfterLast('.', "").lowercase()
                    if (suffix != "epub" && suffix != "pdf") return null
                    found = suffix to zip.readBytes()
                }
                entry = zip.nextEntry
            }
            found
        }
    } catch (e: Exception) {
        null
    }
}
