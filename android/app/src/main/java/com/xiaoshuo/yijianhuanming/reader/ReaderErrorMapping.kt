package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.content.epub.EpubFailureReason
import com.xiaoshuo.yijianhuanming.content.epub.EpubValidationException
import java.io.FileNotFoundException

enum class ReadingDocumentKind {
    TXT,
    EPUB,
}

class TxtEncodingRequiredException(
    val candidates: List<String>,
) : IllegalArgumentException("无法确定 TXT 编码")

fun recoveryActionFor(code: ReaderErrorCode): RecoveryAction = when (code) {
    ReaderErrorCode.INVALID_URL,
    ReaderErrorCode.UNSAFE_URL,
    -> RecoveryAction.EditUrl
    ReaderErrorCode.TXT_ENCODING_REQUIRED -> RecoveryAction.SelectEncoding
    ReaderErrorCode.TXT_READ_FAILED,
    ReaderErrorCode.EPUB_INVALID,
    ReaderErrorCode.EPUB_DRM_UNSUPPORTED,
    ReaderErrorCode.EPUB_FIXED_LAYOUT_UNSUPPORTED,
    ReaderErrorCode.FILE_PERMISSION_LOST,
    -> RecoveryAction.SelectAnotherFile
    ReaderErrorCode.RUNTIME_LOAD_FAILED -> RecoveryAction.RetryRuntime
    ReaderErrorCode.RULE_APPLY_FAILED -> RecoveryAction.RetryApply
    ReaderErrorCode.RULE_SAVE_FAILED -> RecoveryAction.ReopenDatabaseAndRetryApply
    ReaderErrorCode.RUNTIME_OUT_OF_SYNC -> RecoveryAction.ReloadDocument
}

fun readerError(code: ReaderErrorCode, message: String): ReaderError =
    ReaderError(code, message, recoveryActionFor(code))

fun mapDocumentOpenError(kind: ReadingDocumentKind, failure: Throwable): ReaderError {
    val code = when {
        failure is TxtEncodingRequiredException -> ReaderErrorCode.TXT_ENCODING_REQUIRED
        failure is SecurityException || failure is FileNotFoundException ->
            ReaderErrorCode.FILE_PERMISSION_LOST
        failure is EpubValidationException && failure.reason == EpubFailureReason.DRM ->
            ReaderErrorCode.EPUB_DRM_UNSUPPORTED
        failure is EpubValidationException && failure.reason == EpubFailureReason.FIXED_LAYOUT ->
            ReaderErrorCode.EPUB_FIXED_LAYOUT_UNSUPPORTED
        kind == ReadingDocumentKind.EPUB -> ReaderErrorCode.EPUB_INVALID
        else -> ReaderErrorCode.TXT_READ_FAILED
    }
    val fallback = when (code) {
        ReaderErrorCode.TXT_ENCODING_REQUIRED -> "无法确定 TXT 编码，请选择编码"
        ReaderErrorCode.FILE_PERMISSION_LOST -> "文件读取权限已失效，请重新选择文件"
        ReaderErrorCode.EPUB_DRM_UNSUPPORTED -> "暂不支持受 DRM 保护的 EPUB"
        ReaderErrorCode.EPUB_FIXED_LAYOUT_UNSUPPORTED -> "暂不支持固定版式 EPUB"
        ReaderErrorCode.EPUB_INVALID -> "EPUB 文件损坏或结构无效"
        else -> "无法读取 TXT 文件"
    }
    return readerError(code, failure.message?.takeIf(String::isNotBlank) ?: fallback)
}
