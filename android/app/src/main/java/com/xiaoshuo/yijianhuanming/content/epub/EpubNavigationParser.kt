package com.xiaoshuo.yijianhuanming.content.epub

import java.io.File
import java.nio.file.Paths
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class EpubNavigationParser {
    fun parse(sessionDir: File, packagePath: String): EpubBook {
        val root = sessionDir.canonicalFile
        val normalizedPackagePath = normalize(packagePath)
            ?: throw EpubValidationException("EPUB 包路径无效")
        val packageFile = resolveInside(root, normalizedPackagePath)
        val packageDocument = parseXml(packageFile)
        val packageBase = Paths.get(normalizedPackagePath).parent?.toString().orEmpty()
        val manifest = elements(packageDocument, "item").associate { item ->
            val id = item.getAttribute("id")
            if (id.isBlank()) throw EpubValidationException("EPUB manifest id 无效")
            id to ManifestItem(
                id = id,
                href = resolveRelative(packageBase, item.getAttribute("href"))
                    ?: throw EpubValidationException("EPUB manifest 路径无效"),
                mediaType = item.getAttribute("media-type"),
                properties = item.getAttribute("properties").split(WHITESPACE).filter(String::isNotBlank).toSet(),
            )
        }
        val spine = elements(packageDocument, "itemref").mapNotNull { itemref ->
            manifest[itemref.getAttribute("idref")]
        }.filter { it.mediaType in CHAPTER_MEDIA_TYPES }
        if (spine.isEmpty()) throw EpubValidationException("EPUB 书脊没有可阅读章节")

        val navEntries = parseNavigation(root, packageDocument, manifest)
        val titles = flatten(navEntries).associate { it.chapterId to it.title }
        val chapters = spine.mapIndexed { index, item ->
            EpubChapter(
                id = item.id,
                title = titles[item.id] ?: "第 ${index + 1} 章",
                href = item.href,
                previousChapterId = spine.getOrNull(index - 1)?.id,
                nextChapterId = spine.getOrNull(index + 1)?.id,
            )
        }
        val chapterIds = chapters.mapTo(hashSetOf()) { it.id }
        val filteredNavigation = navEntries.mapNotNull { it.retainChapters(chapterIds) }
        val title = elements(packageDocument, "title").firstOrNull()?.textContent?.trim()
            .orEmpty().ifBlank { "EPUB 阅读" }
        val allowedResources = manifest.values
            .filterNot { it.mediaType in CHAPTER_MEDIA_TYPES || it.mediaType == NCX_MEDIA_TYPE || "nav" in it.properties }
            .mapTo(linkedSetOf()) { it.href }

        return EpubBook(
            title = title,
            packagePath = normalizedPackagePath,
            chapters = chapters,
            tableOfContents = filteredNavigation,
            allowedResources = allowedResources,
        )
    }

    private fun parseNavigation(
        root: File,
        packageDocument: Document,
        manifest: Map<String, ManifestItem>,
    ): List<EpubTocEntry> {
        val nav = manifest.values.firstOrNull { "nav" in it.properties }
        if (nav != null) return parseEpub3Nav(root, nav.href, manifest)
        val spineElement = elements(packageDocument, "spine").firstOrNull()
        val ncxId = spineElement?.getAttribute("toc").orEmpty()
        val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }
        return ncx?.let { parseNcx(root, it.href, manifest) }.orEmpty()
    }

    private fun parseEpub3Nav(
        root: File,
        navPath: String,
        manifest: Map<String, ManifestItem>,
    ): List<EpubTocEntry> {
        val document = parseXml(resolveInside(root, navPath))
        val nav = elements(document, "nav").firstOrNull {
            it.getAttributeNS("http://www.idpf.org/2007/ops", "type") == "toc" ||
                it.getAttribute("epub:type").split(WHITESPACE).contains("toc")
        } ?: return emptyList()
        val list = nav.childElements("ol").firstOrNull() ?: return emptyList()
        return parseNavList(list, navPath, manifest)
    }

    private fun parseNavList(
        list: Element,
        navigationPath: String,
        manifest: Map<String, ManifestItem>,
    ): List<EpubTocEntry> = list.childElements("li").mapNotNull { item ->
        val anchor = item.childElements("a").firstOrNull()
        val nested = item.childElements("ol").firstOrNull()
            ?.let { parseNavList(it, navigationPath, manifest) }
            .orEmpty()
        anchor?.let {
            navigationEntry(
                title = it.textContent.trim(),
                source = it.getAttribute("href"),
                navigationPath = navigationPath,
                manifest = manifest,
                children = nested,
            )
        }
    }

    private fun parseNcx(
        root: File,
        ncxPath: String,
        manifest: Map<String, ManifestItem>,
    ): List<EpubTocEntry> {
        val document = parseXml(resolveInside(root, ncxPath))
        val navMap = elements(document, "navMap").firstOrNull() ?: return emptyList()
        return navMap.childElements("navPoint").mapNotNull { parseNavPoint(it, ncxPath, manifest) }
    }

    private fun parseNavPoint(
        point: Element,
        navigationPath: String,
        manifest: Map<String, ManifestItem>,
    ): EpubTocEntry? {
        val label = point.childElements("navLabel").firstOrNull()
            ?.let { elements(it, "text").firstOrNull()?.textContent?.trim() }
            .orEmpty()
        val source = point.childElements("content").firstOrNull()?.getAttribute("src").orEmpty()
        val children = point.childElements("navPoint").mapNotNull {
            parseNavPoint(it, navigationPath, manifest)
        }
        return navigationEntry(label, source, navigationPath, manifest, children)
    }

    private fun navigationEntry(
        title: String,
        source: String,
        navigationPath: String,
        manifest: Map<String, ManifestItem>,
        children: List<EpubTocEntry>,
    ): EpubTocEntry? {
        val path = resolveRelative(
            Paths.get(navigationPath).parent?.toString().orEmpty(),
            source.substringBefore('#'),
        ) ?: return null
        val chapterId = manifest.values.firstOrNull { it.href == path }?.id ?: return null
        return EpubTocEntry(
            title = title.ifBlank { "未命名章节" },
            chapterId = chapterId,
            fragment = source.substringAfter('#', "").ifBlank { null },
            children = children,
        )
    }

    private fun parseXml(file: File): Document = EpubXmlParser.parse(file)

    private fun resolveInside(root: File, path: String): File {
        val target = File(root, path).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw EpubValidationException("EPUB 导航路径越界")
        }
        return target
    }

    private fun resolveRelative(base: String, reference: String): String? {
        if (
            reference.isBlank() || '\u0000' in reference ||
            reference.startsWith("/") || reference.startsWith("\\") ||
            SCHEME.matches(reference)
        ) return null
        return normalize(Paths.get(base).resolve(reference).normalize().toString())
    }

    private fun normalize(path: String): String? = runCatching {
        Paths.get(path.replace('\\', '/')).normalize().toString().replace('\\', '/')
            .takeUnless { it.isBlank() || it == ".." || it.startsWith("../") || it.startsWith("/") }
    }.getOrNull()

    private fun elements(node: Node, localName: String): List<Element> {
        val matches = mutableListOf<Element>()
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                if (child.localName == localName || child.tagName == localName) matches += child
                matches += elements(child, localName)
            }
        }
        return matches
    }

    private fun Element.childElements(localName: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
            .filter { it.localName == localName || it.tagName == localName }

    private fun flatten(entries: List<EpubTocEntry>): List<EpubTocEntry> =
        entries.flatMap { listOf(it) + flatten(it.children) }

    private fun EpubTocEntry.retainChapters(ids: Set<String>): EpubTocEntry? {
        val retainedChildren = children.mapNotNull { it.retainChapters(ids) }
        return if (chapterId in ids) copy(children = retainedChildren) else retainedChildren.firstOrNull()
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: Set<String>,
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")
        val CHAPTER_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")
        const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
    }
}
