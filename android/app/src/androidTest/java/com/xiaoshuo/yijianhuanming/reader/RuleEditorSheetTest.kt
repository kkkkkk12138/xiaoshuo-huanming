package com.xiaoshuo.yijianhuanming.reader

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RuleEditorSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun summary_edit_add_and_delete_last_rule_keep_apply_available() {
        var draft by mutableStateOf(listOf(ReplaceRule("1", "沈清辞", "林惊鹤", 0)))
        var editing by mutableStateOf<String?>(null)
        compose.setContent {
            RuleEditorSheet(
                state = RuleEditorState(draft = draft, editingRuleId = editing),
                onEdit = { editing = it },
                onChange = { id, source, target ->
                    draft = draft.map { if (it.id == id) it.copy(source = source, target = target) else it }
                },
                onAdd = { draft = draft + ReplaceRule("2", "", "", draft.size) },
                onDelete = { id -> draft = draft.filterNot { it.id == id } },
                onApply = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText("沈清辞 → 林惊鹤").assertIsDisplayed().performClick()
        compose.onNodeWithTag("rule-source-1").performTextReplacement("顾怀安")
        compose.runOnIdle { assertEquals("顾怀安", draft.single().source) }
        compose.onNodeWithText("新增规则").performClick()
        compose.runOnIdle { assertEquals(2, draft.size) }
        compose.onNodeWithTag("delete-rule-1").performClick()
        compose.onNodeWithTag("delete-rule-2").performClick()
        compose.onNodeWithText("应用并恢复原文").assertExists()
        compose.onAllNodesWithText("应用并恢复原文")[0]
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
    }

    @Test
    fun failed_apply_keeps_draft_and_shows_error() {
        compose.setContent {
            RuleEditorSheet(
                state = RuleEditorState(
                    draft = listOf(ReplaceRule("draft", "旧名", "新名", 0)),
                    editingRuleId = "draft",
                    applyState = ApplyState.Failed(
                        ReaderError(
                            ReaderErrorCode.RULE_SAVE_FAILED,
                            "保存失败，请重试",
                            RecoveryAction.ReopenDatabaseAndRetryApply,
                        ),
                    ),
                ),
                onEdit = {},
                onChange = { _, _, _ -> },
                onAdd = {},
                onDelete = {},
                onApply = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithTag("rule-source-draft").assertExists()
        compose.onNodeWithText("保存失败，请重试").assertIsDisplayed()
    }

    @Test
    fun new_rule_focuses_source_then_ime_next_focuses_target() {
        compose.setContent {
            RuleEditorSheet(
                state = RuleEditorState(
                    draft = listOf(ReplaceRule("new", "", "", 0)),
                    editingRuleId = "new",
                    runtimeState = RuntimeState.Ready,
                ),
                onEdit = {},
                onChange = { _, _, _ -> },
                onAdd = {},
                onDelete = {},
                onApply = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithTag("rule-source-new").assertIsFocused().performImeAction()
        compose.onNodeWithTag("rule-target-new").assertIsFocused().performImeAction()
        compose.onNodeWithTag("rule-target-new").assertIsNotFocused()
    }

    @Test
    fun inline_validation_disables_primary_action() {
        compose.setContent {
            RuleEditorSheet(
                state = RuleEditorState(
                    draft = listOf(ReplaceRule("invalid", "", "", 0)),
                    editingRuleId = "invalid",
                    runtimeState = RuntimeState.Ready,
                    validationErrors = mapOf(
                        "invalid" to RuleValidationError.SOURCE_REQUIRED,
                    ),
                    hasUnsavedChanges = true,
                ),
                onEdit = {},
                onChange = { _, _, _ -> },
                onAdd = {},
                onDelete = {},
                onApply = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText("请输入原名").assertIsDisplayed()
        compose.onNodeWithText("保存并生效").assertIsNotEnabled()
    }

    @Test
    fun applying_disables_editing_and_closing() {
        compose.setContent {
            RuleEditorSheet(
                state = RuleEditorState(
                    draft = listOf(ReplaceRule("applying", "旧名", "新名", 0)),
                    editingRuleId = "applying",
                    runtimeState = RuntimeState.Ready,
                    applyState = ApplyState.Applying,
                    hasUnsavedChanges = true,
                ),
                onEdit = {},
                onChange = { _, _, _ -> },
                onAdd = {},
                onDelete = {},
                onApply = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText("关闭").assertIsNotEnabled()
        compose.onNodeWithText("新增规则").assertIsNotEnabled()
        compose.onNodeWithText("正在应用").assertIsNotEnabled()
        compose.onNodeWithTag("rule-source-applying").assertIsNotEnabled()
    }

    @Test
    fun unsaved_close_requires_explicit_discard() {
        var dismissed = false
        compose.setContent {
            RuleEditorSheet(
                state = RuleEditorState(
                    draft = listOf(ReplaceRule("draft", "旧名", "新名", 0)),
                    runtimeState = RuntimeState.Ready,
                    hasUnsavedChanges = true,
                ),
                onEdit = {},
                onChange = { _, _, _ -> },
                onAdd = {},
                onDelete = {},
                onApply = {},
                onDismiss = { dismissed = true },
            )
        }

        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("放弃本次修改？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(false, dismissed) }
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("放弃修改").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test
    fun persistent_success_and_zero_match_messages_are_visible() {
        compose.setContent {
            RuleEditorSheet(
                state = RuleEditorState(
                    runtimeState = RuntimeState.Ready,
                    lastApplyResult = RuleApplyResult(
                        activeRuleCount = 1,
                        changedTextNodeCount = 0,
                        replacementCount = 0,
                        perRule = emptyList(),
                    ),
                ),
                onEdit = {},
                onChange = { _, _, _ -> },
                onAdd = {},
                onDelete = {},
                onApply = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText(
            "规则已保存，当前已加载内容未找到匹配；继续阅读时仍会自动匹配",
        ).assertIsDisplayed()
    }

    @Test
    fun width_320_low_height_and_font_scale_2_keep_errors_close_and_primary_action_accessible() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        RuleEditorSheet(
                            state = RuleEditorState(
                                draft = listOf(ReplaceRule("compact", "", "", 0)),
                                editingRuleId = "compact",
                                runtimeState = RuntimeState.Ready,
                                validationErrors = mapOf(
                                    "compact" to RuleValidationError.SOURCE_REQUIRED,
                                ),
                                hasUnsavedChanges = true,
                            ),
                            onEdit = {},
                            onChange = { _, _, _ -> },
                            onAdd = {},
                            onDelete = {},
                            onApply = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("请输入原名").assertExists()
        compose.onNodeWithText("关闭")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("保存并生效")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun width_320_height_320_and_font_scale_2_can_scroll_to_primary_action() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(320.dp)) {
                        RuleEditorSheet(
                            state = RuleEditorState(
                                draft = listOf(
                                    ReplaceRule("first", "旧名", "新名", 0),
                                    ReplaceRule("last", "", "", 1),
                                ),
                                editingRuleId = "last",
                                runtimeState = RuntimeState.Ready,
                                validationErrors = mapOf(
                                    "last" to RuleValidationError.SOURCE_REQUIRED,
                                ),
                                hasUnsavedChanges = true,
                            ),
                            onEdit = {},
                            onChange = { _, _, _ -> },
                            onAdd = {},
                            onDelete = {},
                            onApply = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("保存并生效")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("关闭")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun adaptive_panel_uses_bottom_sheet_below_600_width() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(599.dp).height(800.dp)) {
                    AdaptiveRuleEditorPanel(
                        state = RuleEditorState(runtimeState = RuntimeState.Ready),
                        onEdit = {},
                        onChange = { _, _, _ -> },
                        onAdd = {},
                        onDelete = {},
                        onApply = {},
                        onDismiss = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("rule-editor-bottom-sheet").assertIsDisplayed()
    }

    @Test
    fun adaptive_panel_uses_bottom_sheet_below_480_height_even_when_wide() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(800.dp).height(479.dp)) {
                    AdaptiveRuleEditorPanel(
                        state = RuleEditorState(runtimeState = RuntimeState.Ready),
                        onEdit = {},
                        onChange = { _, _, _ -> },
                        onAdd = {},
                        onDelete = {},
                        onApply = {},
                        onDismiss = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("rule-editor-bottom-sheet").assertIsDisplayed()
    }

    @Test
    fun adaptive_panel_uses_side_panel_at_840_by_480_boundary() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 0.25f, fontScale = 1f)) {
                MaterialTheme {
                    Box(Modifier.width(840.dp).height(480.dp)) {
                        AdaptiveRuleEditorPanel(
                            state = RuleEditorState(runtimeState = RuntimeState.Ready),
                            onEdit = {},
                            onChange = { _, _, _ -> },
                            onAdd = {},
                            onDelete = {},
                            onApply = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("rule-editor-side-panel").assertIsDisplayed()
    }

    @Test
    fun rule_actions_expose_stable_talkback_labels() {
        compose.setContent {
            RuleEditorSheet(
                state = RuleEditorState(
                    draft = listOf(ReplaceRule("semantic", "旧名", "新名", 0)),
                    runtimeState = RuntimeState.Ready,
                ),
                onEdit = {},
                onChange = { _, _, _ -> },
                onAdd = {},
                onDelete = {},
                onApply = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithTag("delete-rule-semantic")
            .assertContentDescriptionEquals("删除规则：旧名替换为新名")
        compose.onNodeWithText("旧名 → 新名")
            .assertContentDescriptionEquals("编辑规则：旧名替换为新名")
        compose.onNodeWithText("保存并生效")
            .assertContentDescriptionEquals("保存规则并应用到当前内容")
    }
}
