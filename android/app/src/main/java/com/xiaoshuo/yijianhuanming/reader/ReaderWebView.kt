package com.xiaoshuo.yijianhuanming.reader

import android.content.Context
import android.os.Message
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import com.xiaoshuo.yijianhuanming.content.web.NavigationDecision
import com.xiaoshuo.yijianhuanming.content.web.NavigationPolicy
import com.xiaoshuo.yijianhuanming.content.web.NavigationRequestKind
import com.xiaoshuo.yijianhuanming.content.web.NoOpWebSecurityCallbacks
import com.xiaoshuo.yijianhuanming.content.web.SecureWebViewClient
import com.xiaoshuo.yijianhuanming.content.web.WebSecurityCallbacks
import com.xiaoshuo.yijianhuanming.content.web.WebViewProfile
import com.xiaoshuo.yijianhuanming.content.txt.TxtAssetPathHandler
import com.xiaoshuo.yijianhuanming.content.epub.EpubAssetPathHandler

class ReaderWebView(
    context: Context,
    val profile: WebViewProfile,
    private val securityCallbacks: WebSecurityCallbacks = NoOpWebSecurityCallbacks,
    confirmedCleartextUrl: String? = null,
    txtPathHandler: TxtAssetPathHandler? = null,
    epubPathHandler: EpubAssetPathHandler? = null,
    onRuntimeReady: (RuleRuntime, (Boolean) -> Unit) -> Unit = { _, complete ->
        complete(true)
    },
) : WebView(context) {
    private val navigationPolicy = NavigationPolicy(profile)
    private val runtimeController = WebRuntimeController(this)
    private val secureClient: SecureWebViewClient
    private val assetLoader = if (profile == WebViewProfile.LOCAL_READER) {
        WebViewAssetLoader.Builder()
            .apply {
                if (txtPathHandler != null) {
                    addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    addPathHandler("/txt/", txtPathHandler)
                }
                if (epubPathHandler != null) addPathHandler("/epub/", epubPathHandler)
            }
            .build()
    } else {
        null
    }

    init {
        if (profile == WebViewProfile.REMOTE_PUBLIC_WEB) {
            visibility = View.INVISIBLE
            isEnabled = false
        }
        profile.applyTo(this)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        secureClient = SecureWebViewClient(
            navigationPolicy = navigationPolicy,
            runtimeController = runtimeController,
            callbacks = securityCallbacks,
            confirmedCleartextUrl = confirmedCleartextUrl,
            remoteProfile = profile == WebViewProfile.REMOTE_PUBLIC_WEB,
            onRuntimeReady = onRuntimeReady,
            assetLoader = assetLoader,
        )
        webViewClient = secureClient
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                securityCallbacks.onNavigationBlocked(
                    NavigationDecision.Block("不支持网页新窗口"),
                    view.url.orEmpty(),
                )
                return false
            }
        }
        setDownloadListener { url, _, _, _, _ ->
            val decision = navigationPolicy.evaluate(
                rawUrl = url.orEmpty(),
                requestKind = NavigationRequestKind.DOWNLOAD,
            )
            securityCallbacks.onNavigationBlocked(decision, url.orEmpty())
        }
    }

    fun clearSession() {
        runtimeController.clearRemoteSession()
    }

    fun confirmCleartextAndLoad(url: String) {
        secureClient.confirmCleartextAndLoad(this, url)
    }

    fun markSecurityChecksPassed() {
        secureClient.markSecurityChecksPassed(this)
    }

    override fun destroy() {
        if (profile == WebViewProfile.REMOTE_PUBLIC_WEB) {
            clearSession()
        } else {
            runtimeController.invalidate()
        }
        removeAllViews()
        super.destroy()
    }
}
