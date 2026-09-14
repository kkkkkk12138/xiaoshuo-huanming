package com.xiaoshuo.yijianhuanming

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaoshuo.yijianhuanming.reader.HomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsPrimaryInputsAndPrivacyBoundary() {
        var settingsOpened = false
        compose.setContent {
            HomeScreen(
                onOpenUrl = {},
                onOpenDocument = {},
                onOpenSettings = { settingsOpened = true },
            )
        }
        compose.onNodeWithText("小说一键换名").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开设置")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(settingsOpened) }
        compose.onNodeWithText("打开网页链接").assertIsDisplayed()
        compose.onNodeWithText("打开 TXT / EPUB").assertIsDisplayed()
        compose.onNodeWithText("不登录网站，不上传阅读内容").assertIsDisplayed()
        compose.onAllNodesWithText("设置").assertCountEquals(1)
    }
}
