package com.xiaoshuo.yijianhuanming.content.epub

import android.content.Context
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EpubReaderChapter(
    val id: String,
    val title: String,
    val url: String,
    val previousChapterId: String?,
    val nextChapterId: String?,
)

data class EpubReaderDocument(
    val title: String,
    val sessionId: String,
    val chapters: List<EpubReaderChapter>,
    val tableOfContents: List<EpubTocEntry>,
    val initialLocation: EpubLocation,
    val pathHandler: EpubAssetPathHandler,
    val initialLocationRepaired: Boolean = false,
) {
    val initialUrl: String
        get() = chapter(initialLocation.chapterId)?.url ?: chapters.first().url

    fun chapter(id: String?): EpubReaderChapter? = chapters.firstOrNull { it.id == id }
}

class EpubContentSource(
    context: Context,
    private val uri: Uri,
    private val savedLocation: EpubLocation? = null,
    private val extractor: SecureZipExtractor = SecureZipExtractor(),
    private val packageParser: EpubPackageParser = EpubPackageParser(),
    private val navigationParser: EpubNavigationParser = EpubNavigationParser(),
) {
    private val resolver = context.contentResolver
    private val cacheRoot = context.cacheDir.resolve("epub")
    private var sessionDir: java.io.File? = null

    suspend fun open(): Result<EpubReaderDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val sessionId = UUID.randomUUID().toString()
            val directory = cacheRoot.resolve(sessionId)
            sessionDir = directory
            val extraction = resolver.openInputStream(uri)?.use { input ->
                extractor.extract(input, directory)
            } ?: throw EpubValidationException("无法读取 EPUB 文件")
            val metadata = packageParser.parse(directory)
            val book = navigationParser.parse(directory, metadata.packagePath)
            val whitelist = book.chapters.mapTo(linkedSetOf(), EpubChapter::href).apply {
                addAll(book.allowedResources)
            }
            if (!extraction.files.containsAll(whitelist)) {
                throw EpubValidationException("EPUB manifest 引用了不存在的资源")
            }
            val resourceResolver = EpubResourceResolver(sessionId, whitelist)
            val locationResolution = book.resolveLocation(savedLocation)
            val chapters = book.chapters.map { chapter ->
                EpubReaderChapter(
                    id = chapter.id,
                    title = chapter.title,
                    url = resourceResolver.appAssetsUrl(chapter.href),
                    previousChapterId = chapter.previousChapterId,
                    nextChapterId = chapter.nextChapterId,
                )
            }
            EpubReaderDocument(
                title = book.title,
                sessionId = sessionId,
                chapters = chapters,
                tableOfContents = book.tableOfContents,
                initialLocation = locationResolution.location,
                initialLocationRepaired = locationResolution.repaired,
                pathHandler = EpubAssetPathHandler(sessionId, directory, book),
            )
        }.onFailure {
            directoryCleanup()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        directoryCleanup()
    }

    private fun directoryCleanup() {
        sessionDir?.deleteRecursively()
        sessionDir = null
    }
}
