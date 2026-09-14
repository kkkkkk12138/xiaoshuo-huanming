package com.xiaoshuo.yijianhuanming.reader

import android.content.Context
import android.net.Uri
import com.xiaoshuo.yijianhuanming.content.epub.EpubContentSource
import com.xiaoshuo.yijianhuanming.content.epub.EpubLocation
import com.xiaoshuo.yijianhuanming.content.txt.TxtContentSource
import com.xiaoshuo.yijianhuanming.content.txt.TxtOpenResult
import com.xiaoshuo.yijianhuanming.content.txt.TxtResumePosition
import com.xiaoshuo.yijianhuanming.data.ReaderSessionDao
import com.xiaoshuo.yijianhuanming.intake.DocumentKind
import com.xiaoshuo.yijianhuanming.intake.DocumentMetadataResolver
import com.xiaoshuo.yijianhuanming.library.ReadingProgress
import com.xiaoshuo.yijianhuanming.library.ReadingProgressCoordinator
import com.xiaoshuo.yijianhuanming.library.epubProgress
import com.xiaoshuo.yijianhuanming.library.initializeEpubProgress
import com.xiaoshuo.yijianhuanming.library.txtRatio

class AndroidReadingSessionOpener(
    private val context: Context,
    private val sessionDao: ReaderSessionDao,
    private val metadataResolver: DocumentMetadataResolver,
) : ReadingSessionOpener {
    override fun prepare(request: ReadingSessionRequest): ReadingSessionHandle = when (request) {
        is ReadingSessionRequest.Web -> webHandle(request)
        is ReadingSessionRequest.Txt -> txtHandle(request)
        is ReadingSessionRequest.Epub -> epubHandle(request)
    }

    private fun webHandle(request: ReadingSessionRequest.Web) = object : ReadingSessionHandle {
        override val displayName: String =
            Uri.parse(request.uri).host?.takeIf(String::isNotBlank) ?: request.uri

        override suspend fun open(): ReadingSessionPayload {
            sessionDao.upsertMetadataPreservingProgress(
                sourceId = request.uri,
                type = "WEB",
                title = displayName,
                uri = request.uri,
                lastOpenedAt = System.currentTimeMillis(),
            )
            return ReadingSessionPayload.Web(request.uri)
        }

        override suspend fun release() = Unit
    }

    private fun txtHandle(request: ReadingSessionRequest.Txt) = object : ReadingSessionHandle {
        override val displayName: String = metadataResolver.resolve(DocumentKind.TXT, request.uri)
        private var source: TxtContentSource? = null

        override suspend fun open(): ReadingSessionPayload {
            val saved = sessionDao.findBySourceId(request.uri)
            val coordinator = ReadingProgressCoordinator { progress ->
                sessionDao.saveProgress(
                    sourceId = request.uri,
                    chapterId = null,
                    scrollRatio = progress.scrollRatio,
                    textOffset = progress.textOffset,
                    textTotalAtSave = progress.textTotalAtSave,
                    lastOpenedAt = progress.lastOpenedAt,
                )
            }
            val contentSource = TxtContentSource(
                context = context,
                uri = Uri.parse(request.uri),
                selectedCharsetName = request.charsetName,
                resumePosition = saved?.let {
                    TxtResumePosition(it.textOffset, it.textTotalAtSave, it.scrollRatio)
                },
            )
            source = contentSource
            return when (val result = contentSource.open().getOrThrow()) {
                is TxtOpenResult.NeedsEncodingSelection ->
                    throw TxtEncodingRequiredException(result.candidates.map { it.charsetName })
                is TxtOpenResult.Ready -> {
                    val document = result.document
                    coordinator.rememberConfirmed(
                        ReadingProgress(
                            textOffset = document.initialOffset,
                            textTotalAtSave = document.totalUtf16Units,
                            scrollRatio = txtRatio(document.initialOffset, document.totalUtf16Units),
                            lastOpenedAt = System.currentTimeMillis(),
                        ),
                    )
                    sessionDao.upsertMetadataPreservingProgress(
                        sourceId = request.uri,
                        type = "TXT",
                        title = displayName,
                        uri = request.uri,
                        lastOpenedAt = System.currentTimeMillis(),
                    )
                    ReadingSessionPayload.Txt(document, request.uri, coordinator)
                }
            }
        }

        override suspend fun release() {
            source?.close()
            source = null
        }
    }

    private fun epubHandle(request: ReadingSessionRequest.Epub) = object : ReadingSessionHandle {
        override val displayName: String = metadataResolver.resolve(DocumentKind.EPUB, request.uri)
        private var source: EpubContentSource? = null

        override suspend fun open(): ReadingSessionPayload {
            val saved = sessionDao.findBySourceId(request.uri)
            val savedLocation = saved?.chapterId?.let { EpubLocation(it, saved.scrollRatio) }
            val coordinator = ReadingProgressCoordinator { progress ->
                sessionDao.saveProgress(
                    sourceId = request.uri,
                    chapterId = progress.chapterId,
                    scrollRatio = progress.scrollRatio,
                    textOffset = null,
                    textTotalAtSave = null,
                    lastOpenedAt = progress.lastOpenedAt,
                )
            }
            val contentSource = EpubContentSource(context, Uri.parse(request.uri), savedLocation)
            source = contentSource
            val document = contentSource.open().getOrThrow()
            sessionDao.upsertMetadataPreservingProgress(
                sourceId = request.uri,
                type = "EPUB",
                title = displayName,
                uri = request.uri,
                lastOpenedAt = System.currentTimeMillis(),
            )
            initializeEpubProgress(
                location = document.initialLocation,
                repaired = document.initialLocationRepaired,
                coordinator = coordinator,
            )
            if (!document.initialLocationRepaired) {
                coordinator.rememberConfirmed(
                    epubProgress(document.initialLocation, System.currentTimeMillis()),
                )
            }
            return ReadingSessionPayload.Epub(document, coordinator)
        }

        override suspend fun release() {
            source?.close()
            source = null
        }
    }
}
