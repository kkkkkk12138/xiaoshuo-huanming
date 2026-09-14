package com.xiaoshuo.yijianhuanming.content.epub

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class EpubAssetPathHandler(
    private val sessionId: String,
    private val sessionDir: File,
    private val book: EpubBook,
    private val sanitizer: EpubHtmlSanitizer = EpubHtmlSanitizer(),
) : WebViewAssetLoader.PathHandler {
    private val chapters = book.chapters.associateBy(EpubChapter::href)
    private val allowedPaths = chapters.keys + book.allowedResources

    override fun handle(path: String): WebResourceResponse? {
        val relative = decodeRequestPath(path) ?: return null
        val file = resolveAllowlistedFile(relative) ?: return null
        val chapter = chapters[relative]
        return if (chapter != null) {
            val html = sanitizer.sanitize(
                html = file.readText(),
                chapterPath = relative,
                sessionId = sessionId,
                allowedImages = book.allowedResources,
            )
            response(
                mimeType = "text/html",
                stream = ByteArrayInputStream(injectReaderShell(html).toByteArray()),
                csp = STRICT_CSP,
            )
        } else {
            response(
                mimeType = mimeType(relative),
                stream = FileInputStream(file),
                csp = "default-src 'none'",
            )
        }
    }

    private fun decodeRequestPath(path: String): String? {
        val clean = path.trimStart('/')
        val slash = clean.indexOf('/')
        if (slash <= 0 || clean.substring(0, slash) != sessionId) return null
        val encodedSegments = clean.substring(slash + 1).split('/')
        if (encodedSegments.any { it.isBlank() }) return null
        return runCatching {
            encodedSegments.joinToString("/") {
                URLDecoder.decode(it, StandardCharsets.UTF_8.name())
            }
        }.getOrNull()?.takeIf(allowedPaths::contains)
    }

    private fun resolveAllowlistedFile(relative: String): File? {
        val root = sessionDir.canonicalFile
        val file = File(root, relative).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        return file
    }

    private fun injectReaderShell(sanitized: String): String {
        val head = """
            <meta http-equiv="Content-Security-Policy" content="$STRICT_CSP">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$BUILT_IN_THEME</style>
        """.trimIndent()
        return if (sanitized.contains("<head>")) {
            sanitized.replaceFirst("<head>", "<head>$head")
        } else {
            "<!doctype html><html><head>$head</head><body>$sanitized</body></html>"
        }
    }

    private fun response(
        mimeType: String,
        stream: java.io.InputStream,
        csp: String,
    ) = WebResourceResponse(
        mimeType,
        if (mimeType.startsWith("text/")) "UTF-8" else null,
        200,
        "OK",
        mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to csp,
            "X-Content-Type-Options" to "nosniff",
        ),
        stream,
    )

    private fun mimeType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    companion object {
        const val STRICT_CSP =
            "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'none'; connect-src 'none'; font-src 'none'; frame-src 'none'"
        const val BUILT_IN_THEME = """
            html{color-scheme:light dark;-webkit-text-size-adjust:100%}
            body{box-sizing:border-box;max-width:42rem;margin:0 auto;padding:24px 20px 64px;font-family:serif;font-size:1.08rem;line-height:1.85;overflow-wrap:anywhere}
            h1{font-size:1.35em;line-height:1.55;text-align:center;margin:0 0 1.5em;font-weight:600}
            h2,h3,h4,h5,h6{line-height:1.6;margin:1.5em 0 .8em}
            p{margin:.65em 0;text-align:justify;text-indent:2em}
            p.reader-no-indent,blockquote p,li p,td p,th p{text-indent:0}
            img{display:block;max-width:100%;height:auto;margin:1em auto}
            pre{white-space:pre-wrap}blockquote{margin:1em 0;padding-inline-start:1em;border-inline-start:2px solid currentColor}
            table{display:block;max-width:100%;overflow-x:auto}a{color:inherit}
            @media(max-width:360px){body{padding-inline:16px}}
        """
    }
}
