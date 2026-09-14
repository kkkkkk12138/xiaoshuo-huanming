package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import com.xiaoshuo.yijianhuanming.data.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewModelTest {
    @Test
    fun runtime_installation_applies_persisted_rules_before_becoming_ready() = runBlocking {
        val persisted = listOf(ReplaceRule("1", "旧名", "新名", 0))
        val repository = FakeRuleRepository(initial = persisted)
        val runtime = FakeRuntime()
        val viewModel = ReaderViewModel(repository, testScope())
        yield()

        val token = viewModel.beginRuntime()
        val result = viewModel.completeRuntimeSetup(token, runtime)

        assertTrue(result.isSuccess)
        assertEquals(listOf(persisted), runtime.applied)
        assertEquals(RuntimeState.Ready, viewModel.state.value.runtimeState)
    }

    @Test
    fun initial_rule_application_failure_marks_runtime_load_failed() = runBlocking {
        val repository = FakeRuleRepository()
        val runtime = FakeRuntime(failures = mutableListOf(true))
        val viewModel = ReaderViewModel(repository, testScope())
        yield()

        val token = viewModel.beginRuntime()
        viewModel.completeRuntimeSetup(token, runtime)

        val failed = viewModel.state.value.runtimeState as RuntimeState.Failed
        assertEquals(ReaderErrorCode.RUNTIME_LOAD_FAILED, failed.error.code)
        assertEquals(RecoveryAction.RetryRuntime, failed.error.recoveryAction)
    }

    @Test
    fun successful_apply_synchronizes_normalized_draft_and_result() = runBlocking {
        val repository = FakeRuleRepository()
        val runtime = FakeRuntime()
        val viewModel = ReaderViewModel(repository, testScope())
        yield()
        makeReady(viewModel, runtime)
        viewModel.addRule()
        val id = viewModel.state.value.draft.single().id
        viewModel.changeRule(id, " 旧名 ", " 新名 ")

        viewModel.applyAll(runtime)

        assertEquals("旧名", viewModel.state.value.persisted.single().source)
        assertEquals("新名", viewModel.state.value.draft.single().target)
        assertNull(viewModel.state.value.editingRuleId)
        assertEquals(false, viewModel.state.value.hasUnsavedChanges)
        assertNotNull(viewModel.state.value.lastApplyResult)
        assertTrue(viewModel.state.value.applyState is ApplyState.Success)
    }

    @Test
    fun save_failure_rolls_back_and_preserves_draft_and_editing() = runBlocking {
        val previous = listOf(ReplaceRule("old", "旧名", "原名", 0))
        val repository = FakeRuleRepository(initial = previous)
        val runtime = FakeRuntime()
        val viewModel = ReaderViewModel(repository, testScope())
        yield()
        makeReady(viewModel, runtime)
        repository.failSave = true
        viewModel.addRule()
        val editingId = viewModel.state.value.editingRuleId
        viewModel.changeRule(editingId!!, " 宝宝 ", " 林晚 ")

        viewModel.applyAll(runtime)

        assertEquals(
            listOf(listOf("旧名"), listOf("旧名", "宝宝"), listOf("旧名")),
            runtime.applied.map { rules -> rules.map(ReplaceRule::source) },
        )
        assertEquals("宝宝", viewModel.state.value.draft.last().source)
        assertEquals(editingId, viewModel.state.value.editingRuleId)
        assertEquals(previous, viewModel.state.value.persisted)
        val failed = viewModel.state.value.applyState as ApplyState.Failed
        assertEquals(ReaderErrorCode.RULE_SAVE_FAILED, failed.error.code)
    }

    @Test
    fun rollback_failure_marks_runtime_out_of_sync() = runBlocking {
        val previous = listOf(ReplaceRule("old", "旧名", "原名", 0))
        val repository = FakeRuleRepository(initial = previous)
        val runtime = FakeRuntime()
        val viewModel = ReaderViewModel(repository, testScope())
        yield()
        makeReady(viewModel, runtime)
        repository.failSave = true
        runtime.failures += listOf(false, true)
        viewModel.addRule()
        val id = viewModel.state.value.draft.last().id
        viewModel.changeRule(id, "宝宝", "林晚")

        viewModel.applyAll(runtime)

        val outOfSync = viewModel.state.value.runtimeState as RuntimeState.OutOfSync
        assertEquals(ReaderErrorCode.RUNTIME_OUT_OF_SYNC, outOfSync.error.code)
        assertEquals(RecoveryAction.ReloadDocument, outOfSync.error.recoveryAction)
    }

    @Test
    fun late_apply_token_cannot_save_or_change_new_runtime_state() = runBlocking {
        val repository = FakeRuleRepository()
        val runtime = BlockingRuntime()
        val viewModel = ReaderViewModel(repository, testScope())
        yield()
        makeReady(viewModel, FakeRuntime())
        viewModel.addRule()
        val id = viewModel.state.value.draft.single().id
        viewModel.changeRule(id, "宝宝", "林晚")

        val applying = async { viewModel.applyAll(runtime) }
        runtime.entered.await()
        viewModel.beginRuntime()
        runtime.release.complete(Unit)
        applying.await()

        assertEquals(0, repository.saveCalls)
        assertEquals(RuntimeState.Loading, viewModel.state.value.runtimeState)
        assertEquals(ApplyState.Idle, viewModel.state.value.applyState)
    }

    @Test
    fun ending_session_invalidates_late_runtime_setup_callback() = runBlocking {
        val repository = FakeRuleRepository()
        val runtime = BlockingRuntime()
        val viewModel = ReaderViewModel(repository, testScope())
        yield()
        viewModel.startSession("reader-a")
        val token = viewModel.beginRuntime()

        val completing = async { viewModel.completeRuntimeSetup(token, runtime) }
        runtime.entered.await()
        viewModel.endSession("reader-a")
        runtime.release.complete(Unit)
        completing.await()

        assertFalse(viewModel.state.value.isCurrent(token))
        assertEquals(RuntimeState.Loading, viewModel.state.value.runtimeState)
    }

    @Test
    fun validation_errors_do_not_submit_to_runtime_or_room() = runBlocking {
        val repository = FakeRuleRepository()
        val runtime = FakeRuntime()
        val viewModel = ReaderViewModel(repository, testScope())
        yield()
        makeReady(viewModel, runtime)
        runtime.applied.clear()
        viewModel.addRule()

        viewModel.applyAll(runtime)

        val id = viewModel.state.value.draft.single().id
        assertEquals(RuleValidationError.SOURCE_REQUIRED, viewModel.state.value.validationErrors[id])
        assertEquals(0, repository.saveCalls)
        assertEquals(emptyList<List<ReplaceRule>>(), runtime.applied)
    }

    @Test
    fun editing_revalidates_all_draft_rows_immediately() = runBlocking {
        val repository = FakeRuleRepository()
        val runtime = FakeRuntime()
        val viewModel = ReaderViewModel(repository, testScope())
        yield()
        makeReady(viewModel, runtime)
        viewModel.addRule()
        val firstId = viewModel.state.value.draft.single().id

        assertEquals(
            RuleValidationError.SOURCE_REQUIRED,
            viewModel.state.value.validationErrors[firstId],
        )

        viewModel.changeRule(firstId, "宝宝", "宝宝")
        assertEquals(
            RuleValidationError.SAME_VALUE,
            viewModel.state.value.validationErrors[firstId],
        )

        viewModel.changeRule(firstId, "宝宝", "林晚")
        assertTrue(viewModel.state.value.validationErrors.isEmpty())

        viewModel.addRule()
        val secondId = viewModel.state.value.draft.last().id
        viewModel.changeRule(secondId, " 宝宝 ", "顾明")

        assertEquals(
            RuleValidationError.DUPLICATE_SOURCE,
            viewModel.state.value.validationErrors[firstId],
        )
        assertEquals(
            RuleValidationError.DUPLICATE_SOURCE,
            viewModel.state.value.validationErrors[secondId],
        )
    }

    private suspend fun makeReady(viewModel: ReaderViewModel, runtime: RuleRuntime) {
        val token = viewModel.beginRuntime()
        viewModel.completeRuntimeSetup(token, runtime).getOrThrow()
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
}

private class FakeRuleRepository(
    initial: List<ReplaceRule> = emptyList(),
    var failSave: Boolean = false,
) : RuleRepository {
    private val stored = MutableStateFlow(initial)
    override val rules: Flow<List<ReplaceRule>> = stored
    var saveCalls = 0

    override suspend fun replaceAll(rules: List<ReplaceRule>) {
        saveCalls += 1
        if (failSave) error("room failed")
        stored.value = rules
    }
}

private class FakeRuntime(
    val failures: MutableList<Boolean> = mutableListOf(),
) : RuleRuntime {
    val applied = mutableListOf<List<ReplaceRule>>()

    override suspend fun applyRules(rules: List<ReplaceRule>): Result<RuleApplyResult> {
        applied += rules
        if (failures.removeFirstOrNull() == true) {
            return Result.failure(IllegalStateException("js failed"))
        }
        return Result.success(testSummary(rules))
    }
}

private class BlockingRuntime : RuleRuntime {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun applyRules(rules: List<ReplaceRule>): Result<RuleApplyResult> {
        entered.complete(Unit)
        release.await()
        return Result.success(testSummary(rules))
    }
}

private fun testSummary(rules: List<ReplaceRule>) = RuleApplyResult(
    activeRuleCount = rules.size,
    changedTextNodeCount = 0,
    replacementCount = 0,
    perRule = emptyList(),
)
