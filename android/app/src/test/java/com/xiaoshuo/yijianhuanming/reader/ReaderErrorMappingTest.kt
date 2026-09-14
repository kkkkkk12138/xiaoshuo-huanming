package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.content.epub.EpubFailureReason
import com.xiaoshuo.yijianhuanming.content.epub.EpubValidationException
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderErrorMappingTest {
    @Test
    fun every_reader_error_code_has_the_designed_recovery_action() {
        val expected = mapOf(
            ReaderErrorCode.INVALID_URL to RecoveryAction.EditUrl,
            ReaderErrorCode.UNSAFE_URL to RecoveryAction.EditUrl,
            ReaderErrorCode.TXT_ENCODING_REQUIRED to RecoveryAction.SelectEncoding,
            ReaderErrorCode.TXT_READ_FAILED to RecoveryAction.SelectAnotherFile,
            ReaderErrorCode.EPUB_INVALID to RecoveryAction.SelectAnotherFile,
            ReaderErrorCode.EPUB_DRM_UNSUPPORTED to RecoveryAction.SelectAnotherFile,
            ReaderErrorCode.EPUB_FIXED_LAYOUT_UNSUPPORTED to RecoveryAction.SelectAnotherFile,
            ReaderErrorCode.RUNTIME_LOAD_FAILED to RecoveryAction.RetryRuntime,
            ReaderErrorCode.RULE_APPLY_FAILED to RecoveryAction.RetryApply,
            ReaderErrorCode.RULE_SAVE_FAILED to RecoveryAction.ReopenDatabaseAndRetryApply,
            ReaderErrorCode.RUNTIME_OUT_OF_SYNC to RecoveryAction.ReloadDocument,
            ReaderErrorCode.FILE_PERMISSION_LOST to RecoveryAction.SelectAnotherFile,
        )

        assertEquals(ReaderErrorCode.entries.toSet(), expected.keys)
        expected.forEach { (code, action) ->
            assertEquals(action, recoveryActionFor(code))
        }
    }

    @Test
    fun file_permission_failures_are_mapped_structurally() {
        listOf(
            SecurityException("permission revoked"),
            FileNotFoundException("content provider denied access"),
        ).forEach { failure ->
            val error = mapDocumentOpenError(ReadingDocumentKind.TXT, failure)
            assertEquals(ReaderErrorCode.FILE_PERMISSION_LOST, error.code)
            assertEquals(RecoveryAction.SelectAnotherFile, error.recoveryAction)
        }
    }

    @Test
    fun epub_validation_reasons_remain_distinguishable_at_the_ui_boundary() {
        val drm = mapDocumentOpenError(
            ReadingDocumentKind.EPUB,
            EpubValidationException("encrypted", reason = EpubFailureReason.DRM),
        )
        val fixed = mapDocumentOpenError(
            ReadingDocumentKind.EPUB,
            EpubValidationException("fixed", reason = EpubFailureReason.FIXED_LAYOUT),
        )
        val invalid = mapDocumentOpenError(
            ReadingDocumentKind.EPUB,
            EpubValidationException("invalid"),
        )

        assertEquals(ReaderErrorCode.EPUB_DRM_UNSUPPORTED, drm.code)
        assertEquals(ReaderErrorCode.EPUB_FIXED_LAYOUT_UNSUPPORTED, fixed.code)
        assertEquals(ReaderErrorCode.EPUB_INVALID, invalid.code)
        assertEquals(
            ReaderErrorCode.TXT_READ_FAILED,
            mapDocumentOpenError(ReadingDocumentKind.TXT, IOException("broken")).code,
        )
    }
}
