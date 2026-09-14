package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import com.xiaoshuo.yijianhuanming.data.RuleRepository

sealed interface RuleTransactionResult {
    data class Success(val summary: RuleApplyResult) : RuleTransactionResult
    data class RuntimeFailed(val error: ReaderError) : RuleTransactionResult
    data class SaveFailedRolledBack(val error: ReaderError) : RuleTransactionResult
    data class OutOfSync(val error: ReaderError) : RuleTransactionResult
}

class RuleApplicationCoordinator(
    private val repository: RuleRepository,
) {
    suspend fun apply(
        runtime: RuleRuntime,
        draft: List<ReplaceRule>,
        previous: List<ReplaceRule>,
        isCurrent: () -> Boolean,
    ): RuleTransactionResult {
        val validated = validateRules(draft)
        if (validated.errors.isNotEmpty()) {
            return RuleTransactionResult.RuntimeFailed(
                ReaderError(
                    code = ReaderErrorCode.RULE_APPLY_FAILED,
                    userMessage = "规则校验失败",
                    recoveryAction = RecoveryAction.RetryApply,
                ),
            )
        }
        val normalized = validated.normalized
        if (!isCurrent()) return staleOperation()

        val applied = runtime.applyRules(normalized)
        if (applied.isFailure) {
            return RuleTransactionResult.RuntimeFailed(
                ReaderError(
                    code = ReaderErrorCode.RULE_APPLY_FAILED,
                    userMessage = applied.exceptionOrNull()?.message ?: "网页换名失败",
                    recoveryAction = RecoveryAction.RetryApply,
                ),
            )
        }
        if (!isCurrent()) return staleOperation()

        val saved = runCatching { repository.replaceAll(normalized) }
        if (saved.isSuccess) {
            return RuleTransactionResult.Success(applied.getOrThrow())
        }

        val rolledBack = runtime.applyRules(previous)
        if (rolledBack.isSuccess) {
            return RuleTransactionResult.SaveFailedRolledBack(
                ReaderError(
                    code = ReaderErrorCode.RULE_SAVE_FAILED,
                    userMessage = saved.exceptionOrNull()?.message ?: "保存规则失败",
                    recoveryAction = RecoveryAction.ReopenDatabaseAndRetryApply,
                ),
            )
        }
        return RuleTransactionResult.OutOfSync(
            ReaderError(
                code = ReaderErrorCode.RUNTIME_OUT_OF_SYNC,
                userMessage = rolledBack.exceptionOrNull()?.message ?: "正文与规则状态不同步",
                recoveryAction = RecoveryAction.ReloadDocument,
            ),
        )
    }

    private fun staleOperation() = RuleTransactionResult.RuntimeFailed(
        ReaderError(
            code = ReaderErrorCode.RULE_APPLY_FAILED,
            userMessage = "规则操作已过期",
            recoveryAction = RecoveryAction.RetryApply,
        ),
    )
}
