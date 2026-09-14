package com.xiaoshuo.yijianhuanming.content.epub

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubNavigationParserTest {
    private val parser = EpubNavigationParser()

    @Test
    fun normalizes_non_finite_and_out_of_bounds_chapter_ratios() {
        assertEquals(0.0, normalizeChapterRatio(Double.NaN), 0.0)
        assertEquals(0.0, normalizeChapterRatio(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(0.0, normalizeChapterRatio(-1.0), 0.0)
        assertEquals(0.4, normalizeChapterRatio(0.4), 0.0)
        assertEquals(1.0, normalizeChapterRatio(2.0), 0.0)
    }

    @Test
    fun parses_epub3_nav_in_spine_order_and_resolves_relative_fragment_links() {
        val root = fixture(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>EPUB3 样书</dc:title></metadata>
                  <manifest>
                    <item id="nav" href="nav/toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="two" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="one" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="images/cover.jpg" media-type="image/jpeg"/>
                  </manifest>
                  <spine><itemref idref="one"/><itemref idref="two"/></spine>
                </package>
            """,
            extras = mapOf(
                "OEBPS/nav/toc.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                      <body><nav epub:type="toc"><ol>
                        <li><a href="../text/c1.xhtml#start">第一章</a></li>
                        <li><a href="../text/c2.xhtml">第二章</a></li>
                      </ol></nav></body>
                    </html>
                """,
            ),
        )

        val book = parser.parse(root, "OEBPS/content.opf")

        assertEquals(listOf("one", "two"), book.chapters.map { it.id })
        assertEquals(listOf("OEBPS/text/c1.xhtml", "OEBPS/text/c2.xhtml"), book.chapters.map { it.href })
        assertEquals(listOf("第一章", "第二章"), book.tableOfContents.map { it.title })
        assertEquals("one", book.tableOfContents.first().chapterId)
        assertEquals("start", book.tableOfContents.first().fragment)
        assertEquals("two", book.chapters.first().nextChapterId)
        assertNull(book.chapters.first().previousChapterId)
        assertEquals(setOf("OEBPS/images/cover.jpg"), book.allowedResources)
    }

    @Test
    fun parses_epub2_ncx_and_nested_navigation() {
        val root = fixture(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>EPUB2 样书</dc:title></metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="one" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="one"/><itemref idref="two"/></spine>
                </package>
            """,
            extras = mapOf(
                "OEBPS/toc.ncx" to """
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
                      <navPoint id="p1"><navLabel><text>卷一</text></navLabel><content src="text/c1.xhtml"/>
                        <navPoint id="p2"><navLabel><text>第二章</text></navLabel><content src="text/c2.xhtml#middle"/></navPoint>
                      </navPoint>
                    </navMap></ncx>
                """,
            ),
        )

        val book = parser.parse(root, "OEBPS/content.opf")

        assertEquals("卷一", book.tableOfContents.single().title)
        assertEquals("第二章", book.tableOfContents.single().children.single().title)
        assertEquals("two", book.tableOfContents.single().children.single().chapterId)
        assertEquals("middle", book.tableOfContents.single().children.single().fragment)
    }

    @Test
    fun follows_spine_order_when_navigation_is_missing() {
        val root = fixture(
            opf = """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>无目录</dc:title></metadata>
                  <manifest>
                    <item id="two" href="c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="one" href="c1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="one"/><itemref idref="two"/></spine>
                </package>
            """,
        )

        val book = parser.parse(root, "OEBPS/content.opf")

        assertEquals(listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml"), book.chapters.map { it.href })
        assertTrue(book.tableOfContents.isEmpty())
    }

    @Test
    fun enforces_chapter_boundaries_and_round_trips_valid_location() {
        val book = parser.parse(
            fixture(
                opf = """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>边界</dc:title></metadata>
                      <manifest>
                        <item id="one" href="c1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="two" href="c2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="one"/><itemref idref="two"/></spine>
                    </package>
                """,
            ),
            "OEBPS/content.opf",
        )

        assertNull(book.previousChapter("one"))
        assertEquals("two", book.nextChapter("one")?.id)
        assertEquals("one", book.previousChapter("two")?.id)
        assertNull(book.nextChapter("two"))
        val restored = book.restoreLocation(EpubLocation.decode(EpubLocation("two", 0.625).encode()))
        assertEquals(EpubLocation("two", 0.625), restored)
    }

    @Test
    fun falls_back_safely_when_saved_location_cannot_be_restored() {
        val book = parser.parse(
            fixture(
                opf = """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>恢复</dc:title></metadata>
                      <manifest><item id="one" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="one"/></spine>
                    </package>
                """,
            ),
            "OEBPS/content.opf",
        )

        val missing = book.resolveLocation(EpubLocation("missing", 0.5))
        assertEquals(EpubLocation("one", 0.0), missing.location)
        assertTrue(missing.repaired)
        val normalized = book.resolveLocation(EpubLocation("one", Double.NaN))
        assertEquals(EpubLocation("one", 0.0), normalized.location)
        assertTrue(normalized.repaired)
        assertNull(EpubLocation.decode("not-a-location"))
    }

    @Test
    fun resource_resolver_rejects_escape_remote_and_non_allowlisted_paths() {
        val resolver = EpubResourceResolver(
            sessionId = "book-1",
            allowedPaths = setOf("OEBPS/text/c1.xhtml", "OEBPS/images/cover image.jpg"),
        )

        assertEquals(
            "https://appassets.androidplatform.net/epub/book-1/OEBPS/images/cover%20image.jpg",
            resolver.resolveUrl("OEBPS/text/c1.xhtml", "../images/cover image.jpg"),
        )
        assertNull(resolver.resolveUrl("OEBPS/text/c1.xhtml", "../../../secret"))
        assertNull(resolver.resolveUrl("OEBPS/text/c1.xhtml", "https://evil.test/x"))
        assertNull(resolver.resolveUrl("OEBPS/text/c1.xhtml", "../images/other.jpg"))
    }

    private fun fixture(opf: String, extras: Map<String, String> = emptyMap()) =
        Files.createTempDirectory("epub-navigation").toFile().apply {
            resolve("OEBPS").mkdirs()
            resolve("OEBPS/content.opf").writeText(opf.trimIndent())
            listOf("c1.xhtml", "c2.xhtml", "text/c1.xhtml", "text/c2.xhtml").forEach { path ->
                resolve("OEBPS/$path").also {
                    checkNotNull(it.parentFile).mkdirs()
                }.writeText("<html><body>$path</body></html>")
            }
            extras.forEach { (path, contents) ->
                resolve(path).also {
                    checkNotNull(it.parentFile).mkdirs()
                }.writeText(contents.trimIndent())
            }
            resolve("OEBPS/images/cover.jpg").also {
                checkNotNull(it.parentFile).mkdirs()
            }.writeBytes(byteArrayOf(1))
            resolve("OEBPS/images/cover image.jpg").writeBytes(byteArrayOf(2))
        }
}
