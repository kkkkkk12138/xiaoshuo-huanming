package com.xiaoshuo.yijianhuanming.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import com.xiaoshuo.yijianhuanming.data.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ReaderViewModel internal constructor(
    private val repository: RuleRepository,
    collectionScope: CoroutineScope?,
) : ViewModel() {
    @Inject constructor(repository: RuleRepository) : this(repository, null)

    private val coordinator = RuleApplicationCoordinator(repository)
    private val mutableState = MutableStateFlow(
        RuleEditorState().startSession(UUID.randomUUID().toString()),
    )
    val state: StateFlow<RuleEditorState> = mutableState.asStateFlow()

    init {
        (collectionScope ?: viewModelScope).launch {
            repository.rules.collect { persisted ->
                mutableState.update { current ->
                    current.copy(
                        persisted = persisted,
                        draft = if (current.hasUnsavedChanges) current.draft else persisted,
                    )
                }
            }
        }
    }

    fun startSession(sessionId: String) {
        mutableState.update { it.startSession(sessionId) }
    }

    fun endSession(sessionId: String) {
        mutableState.update { current ->
            if (current.readerSessionId == sessionId) {
                current.startSession(UUID.randomUUID().toString())
            } else {
                current
            }
        }
    }

    fun beginRuntime(): RuntimeToken {
        mutableState.update { it.beginRuntime() }
        return mutableState.value.currentRuntimeToken()
    }

    suspend fun completeRuntimeSetup(
        token: RuntimeToken,
        runtime: RuleRuntime,
    ): Result<Unit> {
        if (!mutableState.value.isCurrent(token)) {
            return Result.failure(IllegalStateException("Runtime operation is stale"))
        }
        val result = runtime.applyRules(mutableState.value.persisted)
        mutableState.update { current ->
            if (!current.isCurrent(token)) {
                current
            } else if (result.isSuccess) {
                current.markRuntimeReady()
            } else {
                current.copy(
                    runtimeState = RuntimeState.Failed(
                        ReaderError(
                            code = ReaderErrorCode.RUNTIME_LOAD_FAILED,
                            userMessage = result.exceptionOrNull()?.message ?: "阅读运行时加载失败",
                            recoveryAction = RecoveryAction.RetryRuntime,
                        ),
                    ),
                    applyState = ApplyState.Idle,
                )
            }
        }
        return result.map {}
    }

    fun editRule(id: String) {
        mutableState.update {
            if (it.runtimeState is RuntimeState.OutOfSync) it else it.copy(editingRuleId = id)
        }
    }

    fun changeRule(id: String, source: String, target: String) {
        mutableState.update { current ->
            if (current.runtimeState is RuntimeState.OutOfSync) {
                current
            } else {
                val draft = current.draft.map { rule ->
                    if (rule.id == id) rule.copy(source = source, target = target) else rule
                }
                val validated = validateRules(draft)
                current.copy(
                    draft = draft,
                    validationErrors = validated.errors,
                    applyState = ApplyState.Idle,
                    hasUnsavedChanges = true,
                    lastApplyResult = null,
                )
            }
        }
    }

    fun addRule() {
        if (mutableState.value.runtimeState is RuntimeState.OutOfSync) return
        val rule = ReplaceRule(
            id = UUID.randomUUID().toString(),
            source = "",
            target = "",
            order = mutableState.value.draft.size,
        )
        mutableState.update {
            val draft = it.draft + rule
            it.copy(
                draft = draft,
                editingRuleId = rule.id,
                validationErrors = validateRules(draft).errors,
                applyState = ApplyState.Idle,
                hasUnsavedChanges = true,
                lastApplyResult = null,
            )
        }
    }

    fun deleteRule(id: String) {
        mutableState.update { current ->
            if (current.runtimeState is RuntimeState.OutOfSync) {
                current
            } else {
                val draft = current.draft
                    .filterNot { it.id == id }
                    .mapIndexed { index, rule -> rule.copy(order = index) }
                current.copy(
                    draft = draft,
                    editingRuleId = current.editingRuleId.takeUnless { it == id },
                    validationErrors = validateRules(draft).errors,
                    applyState = ApplyState.Idle,
                    hasUnsavedChanges = true,
                    lastApplyResult = null,
                )
            }
        }
    }

    suspend fun applyAll(runtime: RuleRuntime) {
        val before = mutableState.value
        val validated = validateRules(before.draft)
        if (validated.errors.isNotEmpty()) {
            mutableState.update { current ->
                current.copy(
                    draft = validated.normalized,
                    validationErrors = validated.errors,
                    applyState = ApplyState.Idle,
                    hasUnsavedChanges = validated.normalized != current.persisted,
                )
            }
            return
        }
        if (before.runtimeState != RuntimeState.Ready) {
            val error = ReaderError(
                code = ReaderErrorCode.RULE_APPLY_FAILED,
                userMessage = "阅读运行时尚未就绪",
                recoveryAction = RecoveryAction.RetryApply,
            )
            mutableState.update {
                it.copy(
                    draft = validated.normalized,
                    validationErrors = emptyMap(),
                    applyState = ApplyState.Failed(error),
                )
            }
            return
        }

        mutableState.update { it.beginApply().copy(draft = validated.normalized) }
        val token = mutableState.value.currentApplyToken()
        val transaction = coordinator.apply(
            runtime = runtime,
            draft = validated.normalized,
            previous = before.persisted,
            isCurrent = { mutableState.value.isCurrent(token) },
        )
        mutableState.update { current ->
            if (!current.isCurrent(token)) return@update current
            when (transaction) {
                is RuleTransactionResult.Success -> current.copy(
                    persisted = validated.normalized,
                    draft = validated.normalized,
                    editingRuleId = null,
                    validationErrors = emptyMap(),
                    applyState = ApplyState.Success(transaction.summary),
                    hasUnsavedChanges = false,
                    lastApplyResult = transaction.summary,
                )
                is RuleTransactionResult.RuntimeFailed -> current.copy(
                    draft = validated.normalized,
                    applyState = ApplyState.Failed(transaction.error),
                    hasUnsavedChanges = validated.normalized != current.persisted,
                )
                is RuleTransactionResult.SaveFailedRolledBack -> current.copy(
                    draft = validated.normalized,
                    applyState = ApplyState.Failed(transaction.error),
                    hasUnsavedChanges = validated.normalized != current.persisted,
                )
                is RuleTransactionResult.OutOfSync -> current.copy(
                    draft = validated.normalized,
                    runtimeState = RuntimeState.OutOfSync(transaction.error),
                    applyState = ApplyState.Failed(transaction.error),
                    hasUnsavedChanges = validated.normalized != current.persisted,
                )
            }
        }
    }

    suspend fun reapplyPersisted(runtime: RuleRuntime): Result<Unit> {
        val token = beginRuntime()
        return completeRuntimeSetup(token, runtime)
    }

    suspend fun clearRules(runtime: RuleRuntime): Result<Unit> {
        mutableState.update {
            it.copy(
                draft = emptyList(),
                editingRuleId = null,
                validationErrors = emptyMap(),
                hasUnsavedChanges = it.persisted.isNotEmpty(),
            )
        }
        applyAll(runtime)
        val current = mutableState.value
        return when (val apply = current.applyState) {
            is ApplyState.Success -> Result.success(Unit)
            is ApplyState.Failed -> Result.failure(IllegalStateException(apply.error.userMessage))
            else -> Result.failure(IllegalStateException("规则清除未完成"))
        }
    }
}
