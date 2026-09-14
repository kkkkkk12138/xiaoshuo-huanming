package com.xiaoshuo.yijianhuanming.intake

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaoshuo.yijianhuanming.reader.ReaderError
import com.xiaoshuo.yijianhuanming.reader.ReaderErrorCode
import com.xiaoshuo.yijianhuanming.reader.RecoveryAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UrlEntryDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun home_button_opens_dialog_and_paste_opens_https() {
        var opened: ReaderInput.WebUrl? = null
        val input = ReaderInput.WebUrl(Uri.parse("https://example.com/book"))
        compose.setContent {
            MaterialTheme {
                com.xiaoshuo.yijianhuanming.reader.HomeScreen(
                    onOpenUrl = { opened = it },
                    onOpenDocument = {},
                    readClipboard = { "https://example.com/book" },
                    resolveUrl = { UrlEntryResult.Open(input) },
                )
            }
        }

        compose.onNodeWithText("打开网页链接").performClick()
        compose.onNodeWithText("网页地址").assertIsDisplayed()
        compose.onNodeWithText("打开").assertIsNotEnabled()
        compose.onNodeWithText("粘贴").performClick()
        compose.onNodeWithText("打开").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(input, opened)
    }

    @Test
    fun dangerous_scheme_keeps_inline_error_visible() {
        compose.setContent {
            MaterialTheme {
                UrlEntryDialog(
                    onDismiss = {},
                    onOpen = {},
                    readClipboard = { null },
                    resolveUrl = {
                        UrlEntryResult.Invalid(
                            ReaderError(
                                ReaderErrorCode.UNSAFE_URL,
                                "仅支持 HTTP/HTTPS 链接",
                                RecoveryAction.EditUrl,
                            ),
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag("url-input").performTextInput("javascript:alert(1)")
        compose.onNodeWithText("打开").performClick()
        compose.onNodeWithText("仅支持 HTTP/HTTPS 链接").assertIsDisplayed()
    }

    @Test
    fun http_requires_confirmation_before_opening() {
        var opened = false
        val input = ReaderInput.WebUrl(Uri.parse("http://example.com/book"))
        compose.setContent {
            MaterialTheme {
                UrlEntryDialog(
                    onDismiss = {},
                    onOpen = { opened = true },
                    readClipboard = { null },
                    resolveUrl = { UrlEntryResult.ConfirmHttp(input) },
                )
            }
        }

        compose.onNodeWithTag("url-input").performTextInput(input.uri.toString())
        compose.onNodeWithText("打开").performClick()
        compose.onNodeWithText("此链接未加密").assertIsDisplayed()
        assertEquals(false, opened)

        compose.onNodeWithText("仍要打开").performClick()
        assertEquals(true, opened)
    }
}
