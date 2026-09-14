package com.xiaoshuo.yijianhuanming.content.epub

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

/**
 * Android's DOM factory supports neither Xerces security features nor JAXP
 * accessExternal* attributes. Validate the SAME bytes with a SAX lexical guard
 * before DOM construction instead. startDTD aborts before declarations/entities
 * are processed; a rejecting resolver provides a second line of defence.
 * Do not replace this guard with best-effort/ignored setFeature calls.
 */
internal object EpubXmlParser {
    fun parse(
        file: File,
        documentFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance(),
    ): Document {
        if (!file.isFile) throw EpubValidationException("EPUB XML 文件不存在")
        try {
            val bytes = stripEmptyHtmlDoctype(file.readBytes())
            val guard = object : DefaultHandler2() {
                override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                    throw SAXException("EPUB 不允许 DTD")
                }

                override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                    throw SAXException("EPUB 不允许外部实体")
                }

                override fun resolveEntity(
                    name: String?, publicId: String?, baseURI: String?, systemId: String?,
                ): InputSource {
                    throw SAXException("EPUB 不允许外部实体")
                }

                override fun error(error: SAXParseException) { throw error }
                override fun fatalError(error: SAXParseException) { throw error }
            }
            val reader = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
            }.newSAXParser().xmlReader
            // Android Expat and the JDK both support this standard SAX property.
            // If a provider does not, fail closed rather than parsing unguarded.
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", guard)
            reader.contentHandler = guard
            reader.entityResolver = guard
            reader.errorHandler = guard
            reader.parse(InputSource(ByteArrayInputStream(bytes)))

            documentFactory.isNamespaceAware = true
            documentFactory.isValidating = false
            val builder = documentFactory.newDocumentBuilder()
            builder.setEntityResolver(guard)
            builder.setErrorHandler(guard)
            return builder.parse(ByteArrayInputStream(bytes))
        } catch (error: Exception) {
            throw EpubValidationException("EPUB XML 无效或包含不安全声明", error)
        }
    }

    private fun stripEmptyHtmlDoctype(bytes: ByteArray): ByteArray {
        // ISO-8859-1 is a lossless byte mapping, NOT the document's character
        // encoding. Remove only an exact prolog declaration and preserve every
        // other byte, including the XML encoding declaration and non-ASCII text.
        // Other encodings/declarations remain subject to the SAX DTD guard.
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val match = EMPTY_HTML5_PROLOG.find(raw) ?: return bytes
        val declaration = checkNotNull(match.groups[1]).range
        return raw.removeRange(declaration).toByteArray(Charsets.ISO_8859_1)
    }

    private val EMPTY_HTML5_PROLOG = Regex(
        "\\A(?:\\xEF\\xBB\\xBF)?(?:<\\?xml[\\x20\\t\\r\\n]+[^?]*\\?>)?" +
            "[\\x20\\t\\r\\n]*(<!DOCTYPE[\\x20\\t\\r\\n]+html[\\x20\\t\\r\\n]*>)",
    )
}
