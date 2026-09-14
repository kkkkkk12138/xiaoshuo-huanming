package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderUiStateTest {
    @Test
    fun new_session_rejects_tokens_from_the_previous_session() {
        val persisted = listOf(ReplaceRule("saved", "旧名", "新名", 0))
        val first = RuleEditorState(
            persisted = persisted,
            draft = persisted + ReplaceRule("draft", "宝宝", "林晚", 1),
            editingRuleId = "draft",
            hasUnsavedChanges = true,
        ).startSession("session-a").beginRuntime()
        val oldToken = first.currentRuntimeToken()

        val second = first.startSession("session-b")

        assertFalse(second.isCurrent(oldToken))
        assertEquals(persisted, second.draft)
        assertFalse(second.hasUnsavedChanges)
        assertNull(second.editingRuleId)
    }

    @Test
    fun new_runtime_invalidates_apply_and_returns_to_loading() {
        val applying = RuleEditorState()
            .startSession("session")
            .beginRuntime()
            .markRuntimeReady()
            .beginApply()

        val loading = applying.beginRuntime()

        assertEquals(2L, loading.runtimeGeneration)
        assertEquals(3L, loading.applyGeneration)
        assertEquals(RuntimeState.Loading, loading.runtimeState)
        assertEquals(ApplyState.Idle, loading.applyState)
    }

    @Test
    fun new_apply_rejects_the_previous_apply_token() {
        val first = RuleEditorState()
            .startSession("session")
            .beginRuntime()
            .markRuntimeReady()
            .beginApply()
        val oldToken = first.currentApplyToken()

        val second = first.beginApply()

        assertFalse(second.isCurrent(oldToken))
        assertTrue(second.isCurrent(second.currentApplyToken()))
    }
}
