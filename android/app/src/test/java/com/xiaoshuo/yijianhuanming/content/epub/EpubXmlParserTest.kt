package com.xiaoshuo.yijianhuanming.content.epub

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubXmlParserTest {
    @Test
    fun parses_namespaced_xml_without_xerces_features() {
        val file = Files.createTempFile("epub", ".xml").toFile().apply {
            writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <body><p class="body-text">克苏鲁</p></body>
                </html>
            """.trimIndent())
        }

        val document = EpubXmlParser.parse(file)

        assertEquals("html", document.documentElement.localName)
        assertTrue(document.documentElement.textContent.contains("克苏鲁"))
    }

    @Test
    fun accepts_empty_html5_doctype_used_by_xhtml_epub_files() {
        val file = Files.createTempFile("epub", ".xhtml").toFile().apply {
            writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"><body><p>第1章</p></body></html>
            """.trimIndent())
        }

        val document = EpubXmlParser.parse(file)

        assertEquals("第1章", document.documentElement.textContent.trim())
    }

    @Test
    fun rejects_dtd_and_external_entities_before_dom_parsing() {
        listOf(
            "<!DOCTYPE html [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>",
            "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"xhtml11.dtd\">",
            "<html><body><!DOCTYPE html></body></html>",
        ).forEach { doctype ->
            val file = Files.createTempFile("epub", ".xml").toFile().apply {
                writeText("""
                    <?xml version="1.0"?>
                    $doctype
                    <html><body></body></html>
                """.trimIndent())
            }

            val failure = runCatching { EpubXmlParser.parse(file) }.exceptionOrNull()

            assertTrue(doctype, failure is EpubValidationException)
        }
    }
}
