package com.xiaoshuo.yijianhuanming.content.txt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReaderAssetsTest {
    private val assets = File("src/main/assets/reader")

    @Test
    fun html_uses_strict_csp_and_only_local_assets() {
        val html = assets.resolve("local-reader.html").readText()

        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("script-src 'self'"))
        assertTrue(html.contains("style-src 'self'"))
        assertTrue(html.contains("img-src 'self' data:"))
        assertTrue(html.contains("connect-src 'self'"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("http://"))
    }

    @Test
    fun javascript_keeps_a_bounded_dom_and_reports_character_offsets() {
        val script = assets.resolve("local-reader.js").readText()

        assertTrue(script.contains("MAX_DOM_CHUNKS = 5"))
        assertTrue(script.contains("dataset.sourceStart"))
        assertTrue(script.contains("dataset.sourceEnd"))
        assertTrue(script.contains("totalUtf16Units"))
        assertTrue(script.contains("caretPositionFromPoint"))
        assertTrue(script.contains("caretRangeFromPoint"))
        assertTrue(script.contains("sourceOffsetWithinChunk"))
        assertTrue(script.contains("consumed += renderedOffsetToSource(node, renderedLength)"))
        assertTrue(script.contains("viewportHeight * 2"))
        assertTrue(script.contains("replaceChildren"))
        assertFalse(script.contains("scrollHeight * ratio"))
    }
}
