package com.xiaoshuo.yijianhuanming.content.epub

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

data class EpubPackageMetadata(
    val packagePath: String,
    val title: String,
)

class EpubPackageParser {
    fun parse(sessionDir: File): EpubPackageMetadata {
        var succeeded = false
        try {
            val root = sessionDir.canonicalFile
            val mediaType = root.resolve("mimetype")
                .takeIf(File::isFile)
                ?.readText(Charsets.US_ASCII)
                ?.trim()
            if (mediaType != "application/epub+zip") {
                throw EpubValidationException("文件不是有效 EPUB")
            }
            if (root.resolve("META-INF/encryption.xml").exists()) {
                throw EpubValidationException(
                    "不支持加密或 DRM EPUB",
                    reason = EpubFailureReason.DRM,
                )
            }
            val container = parseXml(root.resolve("META-INF/container.xml"))
            val rootfile = container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element
                ?: throw EpubValidationException("EPUB 缺少 rootfile")
            val packagePath = rootfile.getAttribute("full-path")
            val packageFile = resolveInside(root, packagePath)
            val opf = parseXml(packageFile)
            rejectFixedLayout(opf)
            val title = opf.getElementsByTagNameNS("*", "title").item(0)
                ?.textContent
                ?.trim()
                .orEmpty()
            succeeded = true
            return EpubPackageMetadata(packagePath.replace('\\', '/'), title)
        } catch (error: EpubValidationException) {
            throw error
        } catch (error: Exception) {
            throw EpubValidationException("EPUB 包文档无效", error)
        } finally {
            if (!succeeded) {
                sessionDir.deleteRecursively()
            }
        }
    }

    private fun parseXml(file: File): Document {
        if (!file.isFile) throw EpubValidationException("EPUB 包文件不存在")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun resolveInside(root: File, path: String): File {
        if (path.isBlank() || '\u0000' in path || path.startsWith("/") || path.startsWith("\\")) {
            throw EpubValidationException("EPUB 包路径无效")
        }
        val target = File(root, path.replace('\\', '/')).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            throw EpubValidationException("EPUB 包路径越界")
        }
        return target
    }

    private fun rejectFixedLayout(document: Document) {
        val metadata = document.getElementsByTagNameNS("*", "meta")
        for (index in 0 until metadata.length) {
            val element = metadata.item(index) as? Element ?: continue
            val key = element.getAttribute("property").ifBlank { element.getAttribute("name") }
            val value = element.getAttribute("content").ifBlank { element.textContent }.trim()
            val isFixed = (key == "rendition:layout" && value == "pre-paginated") ||
                (key == "fixed-layout" && value.equals("true", ignoreCase = true))
            if (isFixed) {
                throw EpubValidationException(
                    "首版不支持 fixed-layout EPUB",
                    reason = EpubFailureReason.FIXED_LAYOUT,
                )
            }
        }
    }
}
