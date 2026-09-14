package com.xiaoshuo.yijianhuanming.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    private val policy = UrlPolicy()

    @Test
    fun allows_public_https_and_requires_confirmation_for_http() {
        assertEquals(UrlDecision.Allow, policy.evaluate("https://example.com/book"))
        assertEquals(UrlDecision.ConfirmCleartext, policy.evaluate("http://example.com/book"))
        assertEquals(
            UrlDecision.Allow,
            policy.evaluate("\u00A0\uFEFFhttps://example.com/book\uFEFF"),
        )
    }

    @Test
    fun rejects_private_and_dangerous_targets() {
        listOf(
            "http://127.0.0.1",
            "http://10.0.0.1",
            "http://172.16.0.1",
            "http://192.168.1.2",
            "http://169.254.1.2",
            "http://[::1]",
            "http://[fc00::1]",
            "http://[fe80::1]",
            "http://localhost",
            "http://reader.local",
            "file:///sdcard/a.txt",
            "content://reader/a.txt",
            "javascript:alert(1)",
            "intent://x",
            "",
            "/chapter/1",
        ).forEach {
            assertTrue("$it should be rejected", policy.evaluate(it) is UrlDecision.Reject)
        }
    }

    @Test
    fun rejects_credentials_malformed_urls_and_non_literal_localhost_aliases() {
        listOf(
            "https://user:password@example.com/book",
            "https://example.com:bad/book",
            "https://localhost./book",
            "https://127.1/book",
            "https://2130706433/book",
            "https://0x7f000001/book",
        ).forEach {
            assertTrue("$it should be rejected", policy.evaluate(it) is UrlDecision.Reject)
        }
    }
}
