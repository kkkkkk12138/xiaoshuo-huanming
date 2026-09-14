package com.xiaoshuo.yijianhuanming.content.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubHtmlSanitizerTest {
    private val sanitizer = EpubHtmlSanitizer()

    @Test
    fun keeps_reading_markup_and_rewrites_only_allowlisted_local_images() {
        val html = resource("malicious-content.xhtml")

        val clean = sanitizer.sanitize(
            html = html,
            chapterPath = "OEBPS/text/chapter.xhtml",
            sessionId = "book-1",
            allowedImages = setOf("OEBPS/images/cover.jpg"),
        )

        assertTrue(clean.contains("<h1>标题</h1>"))
        assertTrue(clean.contains("<p><a>正文</a></p>"))
        assertTrue(
            clean.contains(
                "src=\"https://appassets.androidplatform.net/epub/book-1/OEBPS/images/cover.jpg\"",
            ),
        )
        assertFalse(clean.contains("id=\"remote\""))
    }

    @Test
    fun preserves_only_known_no_indent_paragraph_semantic() {
        val html = """
            <html><body>
              <p class="body-text">正文段落</p>
              <p class="no-indent custom">书籍信息</p>
            </body></html>
        """.trimIndent()

        val clean = sanitizer.sanitize(
            html = html,
            chapterPath = "OEBPS/info.xhtml",
            sessionId = "book",
            allowedImages = emptySet(),
        )

        assertTrue(clean.contains("<p class=\"reader-no-indent\">书籍信息</p>"))
        assertFalse(clean.contains("body-text"))
        assertFalse(clean.contains("custom"))
    }

    @Test
    fun removes_scripts_active_content_author_css_events_and_remote_urls() {
        val clean = sanitizer.sanitize(
            html = resource("malicious-content.xhtml"),
            chapterPath = "OEBPS/text/chapter.xhtml",
            sessionId = "book-1",
            allowedImages = setOf("OEBPS/images/cover.jpg"),
        ).lowercase()

        listOf(
            "<script", "<iframe", "<object", "<embed", "<form", "<input",
            "<style", "stylesheet", "onclick", "onload", "onerror",
            "javascript:", "https://evil.test", "@import", "style=",
        ).forEach { forbidden ->
            assertFalse("$forbidden must be removed", clean.contains(forbidden))
        }
    }

    @Test
    fun rejects_image_paths_that_escape_or_are_not_in_the_manifest_allowlist() {
        val html = """
            <html><body>
              <img id="escape" src="../../../outside.png">
              <img id="unknown" src="../images/unknown.png">
              <img id="data" src="data:image/png;base64,AAAA">
            </body></html>
        """.trimIndent()

        val clean = sanitizer.sanitize(
            html = html,
            chapterPath = "OEBPS/text/chapter.xhtml",
            sessionId = "book",
            allowedImages = setOf("OEBPS/images/cover.jpg"),
        )

        assertFalse(clean.contains("<img"))
        assertFalse(clean.contains("outside.png"))
        assertFalse(clean.contains("data:image"))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/epub/$name")).readText()
}
