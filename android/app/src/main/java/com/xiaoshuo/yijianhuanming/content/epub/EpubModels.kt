package com.xiaoshuo.yijianhuanming.content.epub

import java.nio.charset.StandardCharsets
import java.util.Base64

data class EpubChapter(
    val id: String,
    val title: String,
    val href: String,
    val previousChapterId: String?,
    val nextChapterId: String?,
)

data class EpubTocEntry(
    val title: String,
    val chapterId: String,
    val fragment: String? = null,
    val children: List<EpubTocEntry> = emptyList(),
)

fun normalizeChapterRatio(value: Double): Double =
    value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0

data class EpubLocation(
    val chapterId: String,
    val scrollRatio: Double,
) {
    fun encode(): String {
        val encodedId = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(chapterId.toByteArray(StandardCharsets.UTF_8))
        return "$encodedId:${normalizeChapterRatio(scrollRatio)}"
    }

    companion object {
        fun decode(value: String): EpubLocation? = runCatching {
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val chapterId = String(
                Base64.getUrlDecoder().decode(parts[0]),
                StandardCharsets.UTF_8,
            )
            val ratio = parts[1].toDouble()
            require(chapterId.isNotBlank())
            EpubLocation(chapterId, normalizeChapterRatio(ratio))
        }.getOrNull()
    }
}

data class EpubLocationResolution(
    val location: EpubLocation,
    val repaired: Boolean,
)

data class EpubBook(
    val title: String,
    val packagePath: String,
    val chapters: List<EpubChapter>,
    val tableOfContents: List<EpubTocEntry>,
    val allowedResources: Set<String>,
) {
    fun previousChapter(chapterId: String): EpubChapter? =
        chapter(chapter(chapterId)?.previousChapterId)

    fun nextChapter(chapterId: String): EpubChapter? =
        chapter(chapter(chapterId)?.nextChapterId)

    fun chapter(chapterId: String?): EpubChapter? =
        chapters.firstOrNull { it.id == chapterId }

    fun resolveLocation(location: EpubLocation?): EpubLocationResolution {
        val first = chapters.firstOrNull()
            ?: throw EpubValidationException("EPUB 书脊没有可阅读章节")
        val chapter = location?.let { candidate -> chapters.firstOrNull { it.id == candidate.chapterId } }
        val restored = if (chapter == null) {
            EpubLocation(first.id, 0.0)
        } else {
            EpubLocation(chapter.id, normalizeChapterRatio(location.scrollRatio))
        }
        return EpubLocationResolution(
            location = restored,
            repaired = location != null && (
                location.chapterId != restored.chapterId ||
                    location.scrollRatio != restored.scrollRatio
                ),
        )
    }

    fun restoreLocation(location: EpubLocation?): EpubLocation = resolveLocation(location).location
}
