package com.xiaoshuo.yijianhuanming.content.web

import java.net.URI

class LoginRiskPolicy {
    fun isLoginUrl(rawUrl: String): Boolean {
        val path = runCatching { URI(rawUrl).path }.getOrNull() ?: return false
        return path
            .split('/')
            .filter { it.isNotBlank() }
            .any { segment -> segment.lowercase() in LOGIN_PATH_SEGMENTS }
    }

    companion object {
        private val LOGIN_PATH_SEGMENTS = setOf(
            "login",
            "signin",
            "passport",
            "auth",
        )

        const val PASSWORD_FORM_GUARD_SCRIPT = """
            (() => {
              const passwords = Array.from(document.querySelectorAll('input[type="password"]'));
              const visible = passwords.some(password => {
                const style = getComputedStyle(password);
                const bounds = password.getBoundingClientRect();
                return style.display !== 'none' &&
                  style.visibility !== 'hidden' &&
                  style.opacity !== '0' &&
                  bounds.width > 0 &&
                  bounds.height > 0;
              });
              return JSON.stringify({ loginRisk: visible });
            })()
        """

        fun resultHasLoginRisk(rawResult: String?): Boolean =
            rawResult
                ?.replace("\\\"", "\"")
                ?.contains("\"loginRisk\":true") == true
    }
}
