package com.xiaoshuo.yijianhuanming.content.epub

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

class EpubHtmlSanitizer {
    fun sanitize(
        html: String,
        chapterPath: String,
        sessionId: String,
        allowedImages: Set<String>,
    ): String {
        if (!SAFE_SESSION.matches(sessionId)) {
            throw EpubValidationException("EPUB 会话标识无效")
        }
        val normalizedImages = allowedImages.mapTo(hashSetOf()) { normalizeArchivePath(it) }
        val document = Jsoup.parse(html)
        document.select(ACTIVE_CONTENT).remove()
        // Preserve only a known paragraph semantic, never arbitrary author CSS.
        document.select("p").forEach { paragraph ->
            val noIndent = paragraph.hasClass("no-indent")
            paragraph.removeAttr("class")
            if (noIndent) paragraph.addClass("reader-no-indent")
        }
        document.select("img").forEach { image ->
            val resolved = resolveLocalResource(chapterPath, image.attr("src"))
            if (resolved == null || resolved !in normalizedImages) {
                image.remove()
            } else {
                image.attr("src", appAssetsUrl(sessionId, resolved))
            }
        }
        document.select("*").forEach { element ->
            element.attributes().asList()
                .filter { it.key.startsWith("on", ignoreCase = true) || it.key == "style" }
                .forEach { element.removeAttr(it.key) }
        }
        val clean = Cleaner(READING_SAFELIST).clean(document)
        clean.outputSettings().syntax(Document.OutputSettings.Syntax.html)
        return clean.outerHtml()
    }

    private fun resolveLocalResource(chapterPath: String, source: String): String? {
        if (
            source.isBlank() ||
            '\u0000' in source ||
            source.startsWith("/") ||
            source.startsWith("\\") ||
            source.startsWith("//") ||
            SCHEME.matches(source)
        ) {
            return null
        }
        val pathWithoutSuffix = source.substringBefore('#').substringBefore('?')
        return runCatching {
            val parent = Paths.get(chapterPath.replace('\\', '/')).parent ?: Paths.get("")
            normalizeArchivePath(parent.resolve(pathWithoutSuffix).normalize().toString())
        }.getOrNull()?.takeUnless { it == ".." || it.startsWith("../") }
    }

    private fun normalizeArchivePath(path: String): String =
        Paths.get(path.replace('\\', '/')).normalize().toString().replace('\\', '/')

    private fun appAssetsUrl(sessionId: String, path: String): String {
        val encodedPath = path.split('/').joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        return "https://appassets.androidplatform.net/epub/$sessionId/$encodedPath"
    }

    private companion object {
        const val ACTIVE_CONTENT =
            "script,style,link,iframe,object,embed,form,input,button,textarea,select,option,svg,math"
        val SAFE_SESSION = Regex("[A-Za-z0-9._-]+")
        val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")
        val READING_SAFELIST = Safelist.none()
            .addTags(
                "html", "head", "body", "title",
                "article", "section", "nav", "main", "header", "footer",
                "h1", "h2", "h3", "h4", "h5", "h6",
                "p", "div", "span", "br", "hr", "pre", "blockquote",
                "ul", "ol", "li", "dl", "dt", "dd",
                "table", "thead", "tbody", "tfoot", "tr", "th", "td",
                "strong", "b", "em", "i", "u", "s", "sub", "sup", "code", "a", "img",
            )
            .addAttributes("img", "src", "alt", "title", "width", "height")
            .addAttributes("a", "title")
            .addAttributes("p", "class")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan")
            .addProtocols("img", "src", "https")
    }
}
