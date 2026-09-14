package com.xiaoshuo.yijianhuanming.intake

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.ibm.icu.text.BreakIterator
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class DocumentKind(val fallbackTitle: String) {
    TXT("TXT 文档"),
    EPUB("EPUB 文档"),
}

class DocumentMetadataResolver(
    private val contentResolver: ContentResolver? = null,
) {
    fun resolve(kind: DocumentKind, uri: String): String =
        resolve(kind, queryDisplayName(uri), uri)

    fun resolve(kind: DocumentKind, displayName: String?, uri: String): String {
        val displayTitle = sanitize(displayName.orEmpty())
        if (displayTitle.isUsableDocumentTitle()) return displayTitle

        val uriTitle = sanitize(decodedLastSegment(uri))
        return uriTitle.takeIf { it.isUsableDocumentTitle() } ?: kind.fallbackTitle
    }

    private fun queryDisplayName(uri: String): String? =
        runCatching {
            contentResolver?.query(
                Uri.parse(uri),
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun decodedLastSegment(uri: String): String {
        val rawSegment = uri.substringBefore('#').substringBefore('?').substringAfterLast('/')
        if (rawSegment.isBlank()) return ""
        return runCatching {
            URLDecoder.decode(rawSegment.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(rawSegment)
    }

    private fun sanitize(raw: String): String {
        val cleaned = buildString(raw.length) {
            raw.forEach { character ->
                when {
                    character == '\r' || character == '\n' -> append(' ')
                    character.isDisallowedBidiControl() -> Unit
                    Character.isISOControl(character) -> Unit
                    else -> append(character)
                }
            }
        }.replace(WHITESPACE, " ").trim()
        if (cleaned.isEmpty()) return ""

        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(cleaned)
        var end = iterator.first()
        repeat(MAX_GRAPHEME_CLUSTERS) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return cleaned
            end = next
        }
        return cleaned.substring(0, end)
    }

    private fun String.isUsableDocumentTitle(): Boolean =
        isNotBlank() && !matches(NUMERIC_ID) && !matches(DOCUMENT_ID)

    private fun Char.isDisallowedBidiControl(): Boolean =
        this == '\u061C' ||
            this == '\u200E' ||
            this == '\u200F' ||
            this in '\u202A'..'\u202E' ||
            this in '\u2066'..'\u2069'

    private companion object {
        const val MAX_GRAPHEME_CLUSTERS = 100
        val WHITESPACE = Regex("""[\s\p{Z}]+""")
        val NUMERIC_ID = Regex("""\d+""")
        val DOCUMENT_ID = Regex("""(?i)document:.+""")
    }
}
