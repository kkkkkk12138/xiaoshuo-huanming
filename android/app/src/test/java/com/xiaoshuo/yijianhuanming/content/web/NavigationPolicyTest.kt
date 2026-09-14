package com.xiaoshuo.yijianhuanming.content.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    private val policy = NavigationPolicy(WebViewProfile.REMOTE_PUBLIC_WEB)

    @Test
    fun blocks_login_paths_and_allows_public_reading_paths() {
        assertEquals(
            NavigationDecision.BlockLogin,
            policy.evaluate("https://site.test/login"),
        )
        assertEquals(
            NavigationDecision.BlockLogin,
            policy.evaluate("https://site.test/account/SIGNIN?next=/read/1"),
        )
        assertEquals(
            NavigationDecision.BlockLogin,
            policy.evaluate("http://site.test/passport"),
        )
        assertEquals(
            NavigationDecision.Allow,
            policy.evaluate("https://site.test/read/1"),
        )
    }

    @Test
    fun revalidates_every_redirect_target() {
        assertEquals(
            NavigationDecision.Allow,
            policy.evaluate("https://public.example/chapter/1"),
        )

        listOf(
            "https://public.example/passport",
            "http://127.0.0.1/private",
            "file:///sdcard/secret.txt",
            "content://documents/secret",
            "javascript:alert(1)",
            "intent://pay",
        ).forEach { target ->
            assertTrue(
                "$target should be blocked after a safe page redirects",
                policy.evaluate(target) !is NavigationDecision.Allow,
            )
        }
    }

    @Test
    fun blocks_downloads_and_new_windows_even_for_public_https() {
        assertTrue(
            policy.evaluate(
                "https://public.example/book.epub",
                requestKind = NavigationRequestKind.DOWNLOAD,
            ) is NavigationDecision.Block,
        )
        assertTrue(
            policy.evaluate(
                "https://public.example/popup",
                requestKind = NavigationRequestKind.NEW_WINDOW,
            ) is NavigationDecision.Block,
        )
    }

    @Test
    fun cleartext_requires_confirmation_for_each_exact_top_level_url() {
        val url = "http://public.example/chapter/1"

        assertEquals(NavigationDecision.ConfirmCleartext, policy.evaluate(url))
        assertEquals(NavigationDecision.Allow, policy.evaluate(url, confirmedCleartextUrl = url))
        assertEquals(
            NavigationDecision.ConfirmCleartext,
            policy.evaluate("http://public.example/chapter/2", confirmedCleartextUrl = url),
        )
        assertEquals(
            NavigationDecision.ConfirmCleartext,
            policy.evaluate(
                "http://public.example/chapter/1",
                confirmedCleartextUrl = "https://public.example/chapter/1",
            ),
        )
    }

    @Test
    fun local_reader_only_allows_the_appassets_origin() {
        val localPolicy = NavigationPolicy(WebViewProfile.LOCAL_READER)

        assertEquals(
            NavigationDecision.Allow,
            localPolicy.evaluate("https://appassets.androidplatform.net/reader/index.html"),
        )
        assertTrue(
            localPolicy.evaluate("https://example.com/tracker.js") is NavigationDecision.Block,
        )
    }
}
