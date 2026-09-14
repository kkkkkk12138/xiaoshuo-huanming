package com.xiaoshuo.yijianhuanming.content.web

import com.xiaoshuo.yijianhuanming.intake.UrlDecision
import com.xiaoshuo.yijianhuanming.intake.UrlPolicy
import java.net.URI

sealed interface NavigationDecision {
    data object Allow : NavigationDecision
    data object ConfirmCleartext : NavigationDecision
    data object BlockLogin : NavigationDecision
    data class Block(val reason: String) : NavigationDecision
}

enum class NavigationRequestKind {
    NAVIGATION,
    DOWNLOAD,
    NEW_WINDOW,
}

class NavigationPolicy(
    private val profile: WebViewProfile,
    private val urlPolicy: UrlPolicy = UrlPolicy(),
    private val loginRiskPolicy: LoginRiskPolicy = LoginRiskPolicy(),
) {
    fun evaluate(
        rawUrl: String,
        confirmedCleartextUrl: String? = null,
        requestKind: NavigationRequestKind = NavigationRequestKind.NAVIGATION,
    ): NavigationDecision {
        if (requestKind == NavigationRequestKind.DOWNLOAD) {
            return NavigationDecision.Block("不支持网页下载")
        }
        if (requestKind == NavigationRequestKind.NEW_WINDOW) {
            return NavigationDecision.Block("不支持网页新窗口")
        }
        if (profile == WebViewProfile.LOCAL_READER) {
            return evaluateLocal(rawUrl)
        }

        val urlDecision = urlPolicy.evaluate(rawUrl)
        if (urlDecision is UrlDecision.Reject) {
            return NavigationDecision.Block(urlDecision.reason)
        }
        if (loginRiskPolicy.isLoginUrl(rawUrl)) {
            return NavigationDecision.BlockLogin
        }

        return when (urlDecision) {
            UrlDecision.Allow -> NavigationDecision.Allow
            UrlDecision.ConfirmCleartext -> {
                if (urlPolicy.normalize(rawUrl) == confirmedCleartextUrl?.let(urlPolicy::normalize)) {
                    NavigationDecision.Allow
                } else {
                    NavigationDecision.ConfirmCleartext
                }
            }
            is UrlDecision.Reject -> error("Rejected URLs return before login evaluation")
        }
    }

    private fun evaluateLocal(rawUrl: String): NavigationDecision {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
            ?: return NavigationDecision.Block("本地阅读地址无效")
        val allowed = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(APP_ASSETS_HOST, ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.port == -1
        return if (allowed) {
            NavigationDecision.Allow
        } else {
            NavigationDecision.Block("本地阅读器禁止外部资源")
        }
    }

    companion object {
        const val APP_ASSETS_HOST = "appassets.androidplatform.net"
    }
}
