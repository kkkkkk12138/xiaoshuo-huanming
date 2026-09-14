package com.xiaoshuo.yijianhuanming.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRuntimeControllerTest {
    @Test
    fun generation_token_rejects_callbacks_from_an_old_navigation() {
        val generations = NavigationGenerations()
        val first = generations.beginNavigation()
        val second = generations.beginNavigation()

        assertFalse(generations.isCurrent(first))
        assertTrue(generations.isCurrent(second))
    }

    @Test
    fun invalidation_rejects_the_current_pages_pending_callbacks() {
        val generations = NavigationGenerations()
        val current = generations.beginNavigation()

        generations.invalidate()

        assertFalse(generations.isCurrent(current))
    }

    @Test
    fun parser_returns_structured_replacement_counts() {
        val raw = """"{\"ok\":true,\"activeRuleCount\":2,\"changedTextNodeCount\":1,\"replacementCount\":3,\"perRule\":[{\"ruleId\":\"a\",\"replacementCount\":2},{\"ruleId\":\"b\",\"replacementCount\":1}]}""""

        val result = RuntimeResultParser().parse(raw).getOrThrow()

        assertEquals(2, result.activeRuleCount)
        assertEquals(1, result.changedTextNodeCount)
        assertEquals(3, result.replacementCount)
        assertEquals(
            listOf(
                RuleMatchCount("a", 2),
                RuleMatchCount("b", 1),
            ),
            result.perRule,
        )
    }

    @Test
    fun parser_rejects_runtime_errors_and_malformed_results() {
        val parser = RuntimeResultParser()

        assertTrue(
            parser.parse(
                """"{\"ok\":false,\"code\":\"RUNTIME_ERROR\",\"message\":\"boom\"}"""",
            ).isFailure,
        )
        assertTrue(parser.parse("null").isFailure)
        assertTrue(parser.parse("\"not-json\"").isFailure)
    }

}
