package com.xiaoshuo.yijianhuanming.content.epub

import java.nio.file.Files
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubCompatibilityTest {
    @Test
    fun imports_233_chapters_with_nested_volume_navigation_and_safe_paragraph_semantics() {
        // Synthetic fixture: no copyrighted book content is committed to the repo.
        val root = Files.createTempDirectory("epub-compatibility").toFile()
        try {
            root.resolve("mimetype").writeText("application/epub+zip")
            root.resolve("META-INF").mkdirs()
            root.resolve("META-INF/container.xml").writeText("""
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            root.resolve("OEBPS").mkdirs()
            val manifest = (1..233).joinToString("") {
                "<item id=\"chapter$it\" href=\"chapter$it.xhtml\" media-type=\"application/xhtml+xml\"/>"
            }
            val spine = (1..233).joinToString("") { "<itemref idref=\"chapter$it\"/>" }
            root.resolve("OEBPS/content.opf").writeText("""
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>兼容性样书</dc:title></metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="info" href="info.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="stylesheet.css" media-type="text/css"/>
                    $manifest
                  </manifest>
                  <spine><itemref idref="info"/>$spine</spine>
                </package>
            """.trimIndent())
            val links = (1..233).joinToString("") {
                "<li><a href=\"chapter$it.xhtml\">第${it}章</a></li>"
            }
            root.resolve("OEBPS/nav.xhtml").writeText("""
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>目录</title></head><body><nav epub:type="toc"><ol>
                    <li><a href="info.xhtml">书籍信息</a></li>
                    <li><a href="chapter1.xhtml">第一卷</a><ol>$links</ol></li>
                  </ol></nav></body>
                </html>
            """.trimIndent())
            root.resolve("OEBPS/stylesheet.css").writeText("p{text-indent:2em}")
            root.resolve("OEBPS/info.xhtml").writeText("<html><body><p class=\"no-indent\">书籍信息</p></body></html>")
            (1..233).forEach {
                root.resolve("OEBPS/chapter$it.xhtml").writeText("""
                    <html xmlns="http://www.w3.org/1999/xhtml"><head>
                      <title>第${it}章</title><link rel="stylesheet" href="stylesheet.css"/>
                    </head><body><h1>第${it}章</h1><p class="body-text">段落一██</p><p>段落二</p></body></html>
                """.trimIndent())
            }
            val metadata = EpubPackageParser().parse(root)
            val book = EpubNavigationParser().parse(root, metadata.packagePath)
            assertEquals("兼容性样书", book.title)
            assertEquals(234, book.chapters.size) // 233 chapters plus the info page.
            assertEquals(234, book.chapters.map { it.href }.toSet().size)
            assertEquals("第1章", book.chapters[1].title)
            assertEquals("chapter233", book.chapters.last().id)
            assertEquals(233, book.tableOfContents[1].children.size)
            val sanitizer = EpubHtmlSanitizer()
            book.chapters.forEach { chapter ->
                val clean = sanitizer.sanitize(root.resolve(chapter.href).readText(), chapter.href, "regression", book.allowedResources)
                val document = Jsoup.parse(clean)
                assertTrue(document.select("p").isNotEmpty())
                assertFalse(clean.contains("stylesheet.css"))
                if (chapter.id == "info") {
                    assertEquals(1, document.select("p.reader-no-indent").size)
                } else {
                    assertEquals(2, document.select("p").size)
                    assertEquals(chapter.title, document.select("h1").text())
                    assertTrue(document.text().contains("██"))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
