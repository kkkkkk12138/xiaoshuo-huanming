package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.data.ReplaceRule

enum class ReaderErrorCode {
    INVALID_URL,
    UNSAFE_URL,
    TXT_ENCODING_REQUIRED,
    TXT_READ_FAILED,
    EPUB_INVALID,
    EPUB_DRM_UNSUPPORTED,
    EPUB_FIXED_LAYOUT_UNSUPPORTED,
    RUNTIME_LOAD_FAILED,
    RULE_APPLY_FAILED,
    RULE_SAVE_FAILED,
    RUNTIME_OUT_OF_SYNC,
    FILE_PERMISSION_LOST,
}

enum class RecoveryAction {
    RetryRuntime,
    RetryApply,
    ReopenDatabaseAndRetryApply,
    ReloadDocument,
    EditUrl,
    SelectEncoding,
    SelectAnotherFile,
    ReturnHome,
}

data class ReaderError(
    val code: ReaderErrorCode,
    val userMessage: String,
    val recoveryAction: RecoveryAction,
)

sealed interface RuntimeState {
    data object Loading : RuntimeState
    data object Ready : RuntimeState
    data class Failed(val error: ReaderError) : RuntimeState
    data class OutOfSync(val error: ReaderError) : RuntimeState
}

sealed interface ApplyState {
    data object Idle : ApplyState
    data object Applying : ApplyState
    data class Success(val summary: RuleApplyResult) : ApplyState
    data class Failed(val error: ReaderError) : ApplyState
}

data class RuntimeToken(
    val readerSessionId: String,
    val runtimeGeneration: Long,
)

data class ApplyToken(
    val readerSessionId: String,
    val runtimeGeneration: Long,
    val applyGeneration: Long,
)

data class RuleEditorState(
    val persisted: List<ReplaceRule> = emptyList(),
    val draft: List<ReplaceRule> = emptyList(),
    val editingRuleId: String? = null,
    val runtimeState: RuntimeState = RuntimeState.Loading,
    val validationErrors: Map<String, RuleValidationError> = emptyMap(),
    val applyState: ApplyState = ApplyState.Idle,
    val hasUnsavedChanges: Boolean = false,
    val readerSessionId: String = "",
    val runtimeGeneration: Long = 0,
    val applyGeneration: Long = 0,
    val lastApplyResult: RuleApplyResult? = null,
) {
    val isApplying: Boolean
        get() = applyState is ApplyState.Applying

    val error: String?
        get() = when (val apply = applyState) {
            is ApplyState.Failed -> apply.error.userMessage
            else -> when (val runtime = runtimeState) {
                is RuntimeState.Failed -> runtime.error.userMessage
                is RuntimeState.OutOfSync -> runtime.error.userMessage
                else -> null
            }
        }

    fun startSession(sessionId: String): RuleEditorState {
        require(sessionId.isNotBlank()) { "Reader session id must not be blank" }
        return copy(
            draft = persisted,
            editingRuleId = null,
            runtimeState = RuntimeState.Loading,
            validationErrors = emptyMap(),
            applyState = ApplyState.Idle,
            hasUnsavedChanges = false,
            readerSessionId = sessionId,
            runtimeGeneration = 0,
            applyGeneration = 0,
            lastApplyResult = null,
        )
    }

    fun beginRuntime(): RuleEditorState {
        check(readerSessionId.isNotBlank()) { "Reader session must start before runtime" }
        return copy(
            runtimeState = RuntimeState.Loading,
            applyState = ApplyState.Idle,
            runtimeGeneration = runtimeGeneration + 1,
            applyGeneration = applyGeneration + 1,
        )
    }

    fun markRuntimeReady(): RuleEditorState = copy(
        runtimeState = RuntimeState.Ready,
        applyState = ApplyState.Idle,
    )

    fun beginApply(): RuleEditorState {
        check(runtimeState == RuntimeState.Ready) { "Runtime must be ready before applying rules" }
        return copy(
            applyState = ApplyState.Applying,
            validationErrors = emptyMap(),
            applyGeneration = applyGeneration + 1,
        )
    }

    fun currentRuntimeToken(): RuntimeToken = RuntimeToken(readerSessionId, runtimeGeneration)

    fun currentApplyToken(): ApplyToken =
        ApplyToken(readerSessionId, runtimeGeneration, applyGeneration)

    fun isCurrent(token: RuntimeToken): Boolean =
        readerSessionId == token.readerSessionId &&
            runtimeGeneration == token.runtimeGeneration

    fun isCurrent(token: ApplyToken): Boolean =
        readerSessionId == token.readerSessionId &&
            runtimeGeneration == token.runtimeGeneration &&
            applyGeneration == token.applyGeneration
}
