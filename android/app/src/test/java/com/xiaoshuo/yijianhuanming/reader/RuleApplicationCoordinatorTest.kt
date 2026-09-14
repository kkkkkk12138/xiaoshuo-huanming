package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import com.xiaoshuo.yijianhuanming.data.RuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleApplicationCoordinatorTest {
    private val previous = listOf(ReplaceRule("old", "旧名", "原名", 0))
    private val draft = listOf(ReplaceRule("new", " 宝宝 ", " 林晚 ", 0))

    @Test
    fun success_applies_runtime_before_saving_room() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = RecordingRepository(previous, calls)
        val runtime = RecordingRuntime(calls)

        val result = RuleApplicationCoordinator(repository).apply(
            runtime = runtime,
            draft = draft,
            previous = previous,
            isCurrent = { true },
        )

        assertTrue(result is RuleTransactionResult.Success)
        assertEquals(listOf("runtime:宝宝", "room:宝宝"), calls)
    }

    @Test
    fun runtime_failure_never_writes_room() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = RecordingRepository(previous, calls)
        val runtime = RecordingRuntime(calls, failures = mutableListOf(true))

        val result = RuleApplicationCoordinator(repository).apply(runtime, draft, previous) { true }

        assertTrue(result is RuleTransactionResult.RuntimeFailed)
        assertEquals(listOf("runtime:宝宝"), calls)
        assertEquals(previous, repository.stored.value)
    }

    @Test
    fun save_failure_rolls_runtime_back_to_previous_rules() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = RecordingRepository(previous, calls, failSave = true)
        val runtime = RecordingRuntime(calls)

        val result = RuleApplicationCoordinator(repository).apply(runtime, draft, previous) { true }

        assertTrue(result is RuleTransactionResult.SaveFailedRolledBack)
        assertEquals(listOf("runtime:宝宝", "room:宝宝", "runtime:旧名"), calls)
        assertEquals(previous, repository.stored.value)
    }

    @Test
    fun failed_save_and_failed_rollback_report_out_of_sync() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = RecordingRepository(previous, calls, failSave = true)
        val runtime = RecordingRuntime(calls, failures = mutableListOf(false, true))

        val result = RuleApplicationCoordinator(repository).apply(runtime, draft, previous) { true }

        val outOfSync = result as RuleTransactionResult.OutOfSync
        assertEquals(ReaderErrorCode.RUNTIME_OUT_OF_SYNC, outOfSync.error.code)
        assertEquals(RecoveryAction.ReloadDocument, outOfSync.error.recoveryAction)
    }
}

private class RecordingRepository(
    initial: List<ReplaceRule>,
    private val calls: MutableList<String>,
    private val failSave: Boolean = false,
) : RuleRepository {
    val stored = MutableStateFlow(initial)
    override val rules: Flow<List<ReplaceRule>> = stored

    override suspend fun replaceAll(rules: List<ReplaceRule>) {
        calls += "room:${rules.firstOrNull()?.source.orEmpty()}"
        if (failSave) error("room failed")
        stored.value = rules
    }
}

private class RecordingRuntime(
    private val calls: MutableList<String>,
    private val failures: MutableList<Boolean> = mutableListOf(),
) : RuleRuntime {
    override suspend fun applyRules(rules: List<ReplaceRule>): Result<RuleApplyResult> {
        calls += "runtime:${rules.firstOrNull()?.source.orEmpty()}"
        if (failures.removeFirstOrNull() == true) {
            return Result.failure(IllegalStateException("runtime failed"))
        }
        return Result.success(summary(rules))
    }
}

private fun summary(rules: List<ReplaceRule>) = RuleApplyResult(
    activeRuleCount = rules.size,
    changedTextNodeCount = rules.size,
    replacementCount = rules.size,
    perRule = rules.map { RuleMatchCount(it.id, 1) },
)
