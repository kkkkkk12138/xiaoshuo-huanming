package com.xiaoshuo.yijianhuanming.content.web

import android.webkit.WebSettings
import android.webkit.WebView
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiaoshuo.yijianhuanming.reader.ReaderWebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewSecurityTest {
    @Test
    fun remote_profile_disables_dangerous_web_capabilities() {
        onMainThread {
            val view = ReaderWebView(
                context = ApplicationProvider.getApplicationContext(),
                profile = WebViewProfile.REMOTE_PUBLIC_WEB,
            )

            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
            assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
            assertTrue(view.settings.mediaPlaybackRequiresUserGesture)
            assertNotNull(WebView.getCurrentWebViewPackage())
            view.destroy()
        }
    }

    @Test
    fun local_profile_blocks_network_and_keeps_file_schemes_disabled() {
        onMainThread {
            val view = ReaderWebView(
                context = ApplicationProvider.getApplicationContext(),
                profile = WebViewProfile.LOCAL_READER,
            )

            assertTrue(view.settings.blockNetworkLoads)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            view.destroy()
        }
    }

    @Test
    fun remote_main_frame_is_hidden_and_disabled_until_checks_pass() {
        onMainThread {
            val view = ReaderWebView(
                context = ApplicationProvider.getApplicationContext(),
                profile = WebViewProfile.REMOTE_PUBLIC_WEB,
            )

            assertEquals(View.INVISIBLE, view.visibility)
            assertFalse(view.isEnabled)
            view.webViewClient.onPageStarted(view, "https://example.com/read", null)
            assertEquals(View.INVISIBLE, view.visibility)
            assertFalse(view.isEnabled)

            view.markSecurityChecksPassed()
            assertEquals(View.VISIBLE, view.visibility)
            assertTrue(view.isEnabled)
            view.destroy()
        }
    }

    @Test
    fun login_path_stops_before_display_and_reports_blocked_state() {
        onMainThread {
            var blocked = false
            val view = ReaderWebView(
                context = ApplicationProvider.getApplicationContext(),
                profile = WebViewProfile.REMOTE_PUBLIC_WEB,
                securityCallbacks = object : WebSecurityCallbacks {
                    override fun onNavigationBlocked(decision: NavigationDecision, url: String) = Unit
                    override fun onLoginRiskDetected() {
                        blocked = true
                    }
                },
            )

            view.webViewClient.onPageStarted(view, "https://example.com/account/auth", null)

            assertTrue(blocked)
            assertEquals(View.INVISIBLE, view.visibility)
            assertFalse(view.isEnabled)
            view.destroy()
        }
    }

    private fun onMainThread(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
