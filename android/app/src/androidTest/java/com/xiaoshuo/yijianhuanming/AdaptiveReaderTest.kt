package com.xiaoshuo.yijianhuanming

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.xiaoshuo.yijianhuanming.library.RecentReadingList
import com.xiaoshuo.yijianhuanming.navigation.AdaptiveReaderChrome
import com.xiaoshuo.yijianhuanming.navigation.ReaderPaneMode
import com.xiaoshuo.yijianhuanming.reader.ReaderToolbar
import com.xiaoshuo.yijianhuanming.reader.ReaderSettingsSheet
import com.xiaoshuo.yijianhuanming.reader.ReaderLoadingScreen
import com.xiaoshuo.yijianhuanming.reader.ReaderErrorScreen
import com.xiaoshuo.yijianhuanming.reader.ReaderError
import com.xiaoshuo.yijianhuanming.reader.ReaderErrorCode
import com.xiaoshuo.yijianhuanming.reader.RecoveryAction
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class AdaptiveReaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun document_loading_replaces_empty_home_and_can_be_cancelled() {
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                ReaderLoadingScreen(
                    displayName = "真实文件名.epub",
                    onCancel = { cancelled = true },
                )
            }
        }

        compose.onNodeWithText("正在打开《真实文件名.epub》").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        assertTrue(cancelled)
    }

    @Test
    fun permission_error_only_offers_the_structured_recovery_action() {
        var selectedAnotherFile = false
        compose.setContent {
            MaterialTheme {
                ReaderErrorScreen(
                    error = ReaderError(
                        ReaderErrorCode.FILE_PERMISSION_LOST,
                        "文件读取权限已失效",
                        RecoveryAction.SelectAnotherFile,
                    ),
                    onRecovery = { selectedAnotherFile = true },
                )
            }
        }

        compose.onNodeWithText("文件读取权限已失效").assertIsDisplayed()
        compose.onNodeWithText("重新选择文件").performClick()
        assertTrue(selectedAnotherFile)
    }

    @Test
    fun compact_large_font_error_keeps_recovery_and_home_actions_reachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(320.dp)) {
                        ReaderErrorScreen(
                            error = ReaderError(
                                ReaderErrorCode.RULE_SAVE_FAILED,
                                "保存失败，请检查数据库后重试。这里使用较长说明验证多行错误不会挤掉操作。",
                                RecoveryAction.ReopenDatabaseAndRetryApply,
                            ),
                            onRecovery = {},
                            onReturnHome = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("重新连接并重试")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("返回首页")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun phone_opens_tools_in_bottom_sheet_with_talkback_labels() {
        compose.setContent {
            MaterialTheme {
                AdaptiveReaderChrome(
                    paneMode = ReaderPaneMode.BottomSheet,
                    supportingContent = { ReaderSettingsSheet() },
                ) {
                    RecentReadingList(items = emptyList(), onOpen = {})
                }
            }
        }

        compose.onNodeWithContentDescription("打开阅读设置")
            .assertContentDescriptionEquals("打开阅读设置")
            .performClick()
        compose.onNodeWithTag("reader-bottom-sheet").assertIsDisplayed()
        compose.onNodeWithText("阅读设置").assertIsDisplayed()
    }

    @Test
    fun phone_settings_trigger_reserves_space_above_content() {
        compose.setContent {
            MaterialTheme {
                AdaptiveReaderChrome(
                    paneMode = ReaderPaneMode.BottomSheet,
                    supportingContent = { ReaderSettingsSheet() },
                ) {
                    Text("阅读正文")
                }
            }
        }

        val settingsBounds = compose.onNodeWithContentDescription("打开阅读设置")
            .fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithText("阅读正文")
            .fetchSemanticsNode().boundsInRoot

        assert(settingsBounds.bottom <= contentBounds.top)
    }

    @Test
    fun wide_screen_keeps_supporting_pane_visible() {
        compose.setContent {
            MaterialTheme {
                AdaptiveReaderChrome(
                    paneMode = ReaderPaneMode.SupportingPane,
                    supportingContent = { ReaderSettingsSheet() },
                ) {
                    RecentReadingList(items = emptyList(), onOpen = {})
                }
            }
        }

        compose.onNodeWithTag("reader-supporting-pane").assertIsDisplayed()
        compose.onNodeWithText("阅读设置").assertIsDisplayed()
    }

    @Test
    fun destructive_settings_require_confirmation() {
        compose.setContent {
            MaterialTheme {
                ReaderSettingsSheet(onClearHistory = {})
            }
        }

        compose.onNodeWithText("清除最近阅读").performClick()
        compose.onNodeWithText("确认清除最近阅读？").assertIsDisplayed()
        compose.onNodeWithText("确认").assertIsDisplayed()
    }

    @Test
    fun compact_large_font_toolbar_wraps_actions_without_hiding_them() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        ReaderToolbar(
                            ruleCount = 2,
                            onRules = {},
                            showContents = true,
                            onContents = {},
                            hasPrevious = true,
                            hasNext = true,
                            onPrevious = {},
                            onNext = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("规则 2").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("上一章").assertExists()
        compose.onNodeWithText("下一章").assertExists()
        compose.onNodeWithText("目录").assertExists()
    }
}
