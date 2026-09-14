package com.xiaoshuo.yijianhuanming.library

import com.xiaoshuo.yijianhuanming.data.ReaderSessionEntity
import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import com.xiaoshuo.yijianhuanming.data.RuleRepository
import com.xiaoshuo.yijianhuanming.reader.RuleApplyResult
import com.xiaoshuo.yijianhuanming.reader.RuleRuntime
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModelTest {
    @Test
    fun clear_history_removes_only_database_rows_not_source_files() = runBlocking {
        val source = File.createTempFile("reader-source", ".epub").apply { writeText("book") }
        val history = FakeHistory(listOf(session(source)))
        val rules = FakeRules(listOf(ReplaceRule("1", "旧名", "新名", 0)))
        val cache = FakeCache()
        val viewModel = LibraryViewModel(history, rules, cache, testScope())
        yield()

        viewModel.clearHistory()

        assertTrue(history.items.value.isEmpty())
        assertTrue(source.exists())
        assertEquals(1, rules.items.value.size)
        assertFalse(cache.cleared)
        source.delete()
        Unit
    }

    @Test
    fun clear_epub_cache_keeps_history_and_rules() = runBlocking {
        val source = File.createTempFile("reader-source", ".epub")
        val history = FakeHistory(listOf(session(source)))
        val rules = FakeRules(listOf(ReplaceRule("1", "旧名", "新名", 0)))
        val cache = FakeCache()
        val viewModel = LibraryViewModel(history, rules, cache, testScope())

        viewModel.clearEpubCache()

        assertTrue(cache.cleared)
        assertEquals(1, history.items.value.size)
        assertEquals(1, rules.items.value.size)
        source.delete()
        Unit
    }

    @Test
    fun clear_rules_restores_original_text_on_current_page() = runBlocking {
        val history = FakeHistory()
        val rules = FakeRules(listOf(ReplaceRule("1", "旧名", "新名", 0)))
        val runtime = FakeRuntime()
        val viewModel = LibraryViewModel(history, rules, FakeCache(), testScope())

        viewModel.clearRules(runtime)

        assertTrue(rules.items.value.isEmpty())
        assertEquals(1, runtime.restoreCalls)
    }

    private fun session(source: File) = ReaderSessionEntity(
        sourceId = source.toURI().toString(),
        type = "EPUB",
        title = "样书",
        uri = source.toURI().toString(),
        chapterId = "chapter-2",
        scrollRatio = 0.35,
        lastOpenedAt = 10,
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
}

private class FakeHistory(
    initial: List<ReaderSessionEntity> = emptyList(),
) : LibraryHistory {
    val items = MutableStateFlow(initial)
    override val recent: Flow<List<ReaderSessionEntity>> = items
    override suspend fun clear() {
        items.value = emptyList()
    }
}

private class FakeRules(
    initial: List<ReplaceRule>,
) : RuleRepository {
    val items = MutableStateFlow(initial)
    override val rules: Flow<List<ReplaceRule>> = items
    override suspend fun replaceAll(rules: List<ReplaceRule>) {
        items.value = rules
    }
}

private class FakeCache : EpubCache {
    var cleared = false
    override fun cleanup() = Unit
    override fun clear() {
        cleared = true
    }
}

private class FakeRuntime : RuleRuntime {
    var restoreCalls = 0
    override suspend fun applyRules(rules: List<ReplaceRule>) = Result.success(
        RuleApplyResult(
            activeRuleCount = rules.size,
            changedTextNodeCount = 0,
            replacementCount = 0,
            perRule = emptyList(),
        ),
    )
    override suspend fun restoreOriginalText(): Result<Unit> {
        restoreCalls += 1
        return Result.success(Unit)
    }
}
