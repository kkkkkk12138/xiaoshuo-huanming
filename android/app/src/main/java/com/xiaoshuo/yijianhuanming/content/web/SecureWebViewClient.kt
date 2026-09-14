package com.xiaoshuo.yijianhuanming.content.web

import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.xiaoshuo.yijianhuanming.reader.WebRuntimeController
import com.xiaoshuo.yijianhuanming.reader.RuleRuntime

interface WebSecurityCallbacks {
    fun onNavigationBlocked(decision: NavigationDecision, url: String)
    fun onLoginRiskDetected()
}

object NoOpWebSecurityCallbacks : WebSecurityCallbacks {
    override fun onNavigationBlocked(decision: NavigationDecision, url: String) = Unit
    override fun onLoginRiskDetected() = Unit
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class SecureWebViewClient(
    private val navigationPolicy: NavigationPolicy,
    private val runtimeController: WebRuntimeController,
    private val callbacks: WebSecurityCallbacks = NoOpWebSecurityCallbacks,
    confirmedCleartextUrl: String? = null,
    private val remoteProfile: Boolean = true,
    private val onRuntimeReady: (RuleRuntime, (Boolean) -> Unit) -> Unit = { _, complete ->
        complete(true)
    },
    private val assetLoader: WebViewAssetLoader? = null,
) : WebViewClient() {
    private var pageGeneration: Long = 0
    private var activeUrl: String? = null
    private var confirmedCleartextUrl: String? = confirmedCleartextUrl
    private var blockingLogin = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return blockUnlessAllowed(view, request.url.toString())
    }

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        blockUnlessAllowed(view, url)

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = assetLoader?.shouldInterceptRequest(request.url)
        ?: super.shouldInterceptRequest(view, request)

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        if (blockingLogin && url == CONTROLLED_BLANK_URL) return
        hideUntilChecked(view)
        val target = url.orEmpty()
        when (val decision = navigationPolicy.evaluate(target, confirmedCleartextUrl)) {
            NavigationDecision.Allow -> Unit
            NavigationDecision.BlockLogin -> {
                blockLogin(view)
                return
            }
            else -> {
                view.stopLoading()
                callbacks.onNavigationBlocked(decision, target)
                return
            }
        }
        pageGeneration = runtimeController.beginNavigation()
        activeUrl = url
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (blockingLogin || url != activeUrl) return
        val finishedGeneration = pageGeneration
        runtimeController.installRuntime(finishedGeneration) { installed ->
            if (installed) {
                runtimeController.inspectLoginRisk(finishedGeneration) { hasLoginRisk ->
                    if (hasLoginRisk) {
                        blockLogin(view)
                    } else {
                        onRuntimeReady(runtimeController) { ready ->
                            if (ready && url == activeUrl && !blockingLogin) {
                                showAfterChecks(view)
                            }
                        }
                    }
                }
            }
        }
        super.onPageFinished(view, url)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        callbacks.onNavigationBlocked(
            NavigationDecision.Block("TLS 证书验证失败"),
            error.url,
        )
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String,
    ) {
        handler.cancel()
        callbacks.onLoginRiskDetected()
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        request.cancel()
        callbacks.onLoginRiskDetected()
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        dontResend.sendToTarget()
    }

    private fun blockUnlessAllowed(view: WebView, url: String): Boolean {
        hideUntilChecked(view)
        val decision = navigationPolicy.evaluate(
            rawUrl = url,
            confirmedCleartextUrl = confirmedCleartextUrl,
        )
        if (decision == NavigationDecision.Allow) return false

        view.stopLoading()
        if (decision == NavigationDecision.BlockLogin) {
            blockLogin(view)
        } else {
            callbacks.onNavigationBlocked(decision, url)
        }
        return true
    }

    fun confirmCleartextAndLoad(view: WebView, url: String) {
        confirmedCleartextUrl = url
        blockingLogin = false
        view.loadUrl(url)
    }

    fun markSecurityChecksPassed(view: WebView) {
        blockingLogin = false
        showAfterChecks(view)
    }

    private fun hideUntilChecked(view: WebView) {
        if (!remoteProfile) return
        view.visibility = View.INVISIBLE
        view.isEnabled = false
    }

    private fun showAfterChecks(view: WebView) {
        if (!remoteProfile) return
        view.visibility = View.VISIBLE
        view.isEnabled = true
    }

    private fun blockLogin(view: WebView) {
        blockingLogin = true
        view.stopLoading()
        view.clearFocus()
        hideUntilChecked(view)
        view.loadUrl(CONTROLLED_BLANK_URL)
        callbacks.onLoginRiskDetected()
    }

    private companion object {
        const val CONTROLLED_BLANK_URL = "about:blank"
    }
}
