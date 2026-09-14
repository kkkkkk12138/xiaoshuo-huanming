package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import com.xiaoshuo.yijianhuanming.data.sharedTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidationTest {
    @Test
    fun shared_trim_removes_fixed_boundary_whitespace_but_keeps_internal_text() {
        val value = "\u0009\u00A0\uFEFF 宝\u3000宝 \u2007"

        assertEquals("宝\u3000宝", sharedTrim(value))
    }

    @Test
    fun validation_preserves_rule_ids_and_display_order_after_normalization() {
        val result = validateRules(
            listOf(
                ReplaceRule("later", "\uFEFF 沈清辞 ", " 林惊鹤\u00A0", 4),
                ReplaceRule("earlier", " 顾怀安 ", " 江望舒 ", 2),
            ),
        )

        assertEquals(listOf("earlier", "later"), result.normalized.map { it.id })
        assertEquals(listOf(2, 4), result.normalized.map { it.order })
        assertEquals("沈清辞", result.normalized[1].source)
        assertEquals("林惊鹤", result.normalized[1].target)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun validation_reports_required_and_same_value_errors_without_dropping_rows() {
        val result = validateRules(
            listOf(
                ReplaceRule("source", "\uFEFF", "新名", 0),
                ReplaceRule("target", "原名", "\u00A0", 1),
                ReplaceRule("same", " 宝宝 ", "\uFEFF宝宝\u00A0", 2),
            ),
        )

        assertEquals(3, result.normalized.size)
        assertEquals(RuleValidationError.SOURCE_REQUIRED, result.errors["source"])
        assertEquals(RuleValidationError.TARGET_REQUIRED, result.errors["target"])
        assertEquals(RuleValidationError.SAME_VALUE, result.errors["same"])
    }

    @Test
    fun duplicate_sources_are_reported_after_shared_trim() {
        val result = validateRules(
            listOf(
                ReplaceRule("a", "\u00A0宝宝 ", "甲", 0),
                ReplaceRule("b", "宝宝\uFEFF", "乙", 1),
            ),
        )

        assertEquals(RuleValidationError.DUPLICATE_SOURCE, result.errors["a"])
        assertEquals(RuleValidationError.DUPLICATE_SOURCE, result.errors["b"])
        assertEquals(listOf("a", "b"), result.normalized.map { it.id })
    }

    @Test
    fun duplicate_comparison_is_case_sensitive() {
        val result = validateRules(
            listOf(
                ReplaceRule("upper", "Baby", "甲", 0),
                ReplaceRule("lower", "baby", "乙", 1),
            ),
        )

        assertFalse(result.errors.containsKey("upper"))
        assertFalse(result.errors.containsKey("lower"))
    }
}
