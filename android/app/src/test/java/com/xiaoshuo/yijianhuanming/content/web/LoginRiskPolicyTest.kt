package com.xiaoshuo.yijianhuanming.content.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginRiskPolicyTest {
    @Test
    fun detects_common_login_paths_without_blocking_normal_reading_paths() {
        val policy = LoginRiskPolicy()

        assertTrue(policy.isLoginUrl("https://site.test/login"))
        assertFalse(policy.isLoginUrl("https://site.test/oauth/sign-in"))
        assertTrue(policy.isLoginUrl("https://site.test/passport/index"))
        assertTrue(policy.isLoginUrl("https://site.test/account/AUTH/callback"))
        assertFalse(policy.isLoginUrl("https://site.test/books/login-history"))
        assertFalse(policy.isLoginUrl("https://site.test/authors"))
        assertFalse(policy.isLoginUrl("https://site.test/read/1"))
    }

    @Test
    fun guard_script_blocks_password_form_submission_without_a_native_bridge() {
        val script = LoginRiskPolicy.PASSWORD_FORM_GUARD_SCRIPT

        assertTrue(script.contains("input[type=\"password\"]"))
        assertTrue(script.contains("getComputedStyle"))
        assertTrue(script.contains("getBoundingClientRect"))
        assertFalse(script.contains(".value"))
        assertFalse(script.contains("addJavascriptInterface"))
    }
}
