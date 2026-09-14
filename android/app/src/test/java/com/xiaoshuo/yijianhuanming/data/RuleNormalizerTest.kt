package com.xiaoshuo.yijianhuanming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleNormalizerTest {
    @Test
    fun trims_filters_and_keeps_ui_order() {
        val result = normalizeRules(
            listOf(
                ReplaceRule("1", " 沈清辞 ", " 林惊鹤 ", 4),
                ReplaceRule("2", "", "无效", 1),
                ReplaceRule("3", "顾怀安", "江望舒", 2),
            ),
        )

        assertEquals(listOf("3", "1"), result.map { it.id })
        assertEquals("沈清辞", result[1].source)
        assertEquals("林惊鹤", result[1].target)
        assertEquals(listOf(2, 4), result.map { it.order })
    }

    @Test
    fun empty_or_incomplete_rules_normalize_to_empty() {
        val result = normalizeRules(
            listOf(
                ReplaceRule("1", "   ", "新名", 0),
                ReplaceRule("2", "原名", "   ", 1),
            ),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun normalization_does_not_change_runtime_longest_first_semantics() {
        val normalized = normalizeRules(
            listOf(
                ReplaceRule("short", "沈清", "A", 0),
                ReplaceRule("long", "沈清辞", "B", 1),
            ),
        )

        assertEquals(listOf("short", "long"), normalized.map { it.id })
        assertEquals(listOf("long", "short"), normalized.forRuntime().map { it.id })
    }

    @Test
    fun normalization_uses_shared_trim_for_nbsp_and_bom() {
        val normalized = normalizeRules(
            listOf(
                ReplaceRule("1", "\uFEFF\u00A0宝宝 ", " 新名\uFEFF", 0),
            ),
        )

        assertEquals("宝宝", normalized.single().source)
        assertEquals("新名", normalized.single().target)
    }
}
