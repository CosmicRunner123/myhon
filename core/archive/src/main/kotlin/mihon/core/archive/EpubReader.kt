package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * Wrapper over ArchiveReader to load files in epub format.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    /**
     * Returns the path of all the images found in the epub file.
     */
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref).toList()
    }

    /**
     * Returns the path of the first image found in the epub file, reading only the pages up to it.
     */
    fun getFirstImage(): String? {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref).firstOrNull()
    }

    /**
     * Returns the path to the package document.
     */
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    fun getPackageDocument(ref: String): Document {
        return getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    /**
     * Returns all the pages from the epub.
     */
    private fun getPagesFromDocument(document: Document): List<String> {
        val pages = document.select("manifest > item")
            .filter { node -> "application/xhtml+xml" == node.attr("media-type") }
            .associateBy { it.attr("id") }

        val spine = document.select("spine > itemref").map { it.attr("idref") }
        return spine.mapNotNull { pages[it] }.map { it.attr("href") }
    }

    /**
     * Returns all the images contained in every page from the epub, reading each page when the sequence reaches it.
     */
    private fun getImagesFromPages(pages: List<String>, packageHref: String): Sequence<String> = sequence {
        val basePath = getParentDirectory(packageHref)
        for (page in pages) {
            val entryPath = resolveZipPath(basePath, page)
            val document = getInputStream(entryPath)!!.use { Jsoup.parse(it, null, "") }
            val imageBasePath = getParentDirectory(entryPath)

            for (element in document.allElements) {
                when (element.tagName()) {
                    "img" -> yield(resolveZipPath(imageBasePath, element.attr("src")))
                    "image" -> yield(resolveZipPath(imageBasePath, element.attr("xlink:href")))
                }
            }
        }
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            // Path is absolute, so return as-is.
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, File.separator)
        if (!fixedBasePath.startsWith(File.separator)) {
            fixedBasePath = "${File.separator}$fixedBasePath"
        }

        val fixedRelativePath = relativePath.replace(pathSeparator, File.separator)
        val resolvedPath = File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(File.separator, pathSeparator).substring(1)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }
}
