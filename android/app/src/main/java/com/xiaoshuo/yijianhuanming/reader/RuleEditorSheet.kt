package com.xiaoshuo.yijianhuanming.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xiaoshuo.yijianhuanming.data.ReplaceRule

internal enum class RuleEditorPresentation {
    ModalBottomSheet,
    SidePanel,
}

internal fun ruleEditorPresentation(widthDp: Int, heightDp: Int): RuleEditorPresentation =
    if (widthDp >= 840 && heightDp >= 480) {
        RuleEditorPresentation.SidePanel
    } else {
        RuleEditorPresentation.ModalBottomSheet
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveRuleEditorPanel(
    state: RuleEditorState,
    onEdit: (String) -> Unit,
    onChange: (id: String, source: String, target: String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val requestDismiss = {
        when {
            state.isApplying -> Unit
            state.hasUnsavedChanges -> showDiscardConfirmation = true
            else -> onDismiss()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        when (ruleEditorPresentation(maxWidth.value.toInt(), maxHeight.value.toInt())) {
            RuleEditorPresentation.ModalBottomSheet -> {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = requestDismiss,
                    sheetState = sheetState,
                    modifier = Modifier.testTag("rule-editor-bottom-sheet"),
                ) {
                    RuleEditorSheet(
                        state = state,
                        onEdit = onEdit,
                        onChange = onChange,
                        onAdd = onAdd,
                        onDelete = onDelete,
                        onApply = onApply,
                        onDismiss = requestDismiss,
                        confirmUnsavedDismiss = false,
                    )
                }
            }
            RuleEditorPresentation.SidePanel -> {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 360.dp, max = 480.dp)
                        .align(Alignment.CenterEnd)
                        .testTag("rule-editor-side-panel"),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                ) {
                    RuleEditorSheet(
                        state = state,
                        onEdit = onEdit,
                        onChange = onChange,
                        onAdd = onAdd,
                        onDelete = onDelete,
                        onApply = onApply,
                        onDismiss = requestDismiss,
                        confirmUnsavedDismiss = false,
                    )
                }
            }
        }
    }
    if (showDiscardConfirmation) {
        DiscardChangesDialog(
            onKeepEditing = { showDiscardConfirmation = false },
            onDiscard = {
                showDiscardConfirmation = false
                onDismiss()
            },
        )
    }
}

@Composable
fun RuleEditorSheet(
    state: RuleEditorState,
    onEdit: (String) -> Unit,
    onChange: (id: String, source: String, target: String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmUnsavedDismiss: Boolean = true,
) {
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val interactionEnabled =
        !state.isApplying && state.runtimeState !is RuntimeState.OutOfSync
    val applyEnabled =
        interactionEnabled &&
            state.runtimeState == RuntimeState.Ready &&
            state.validationErrors.isEmpty()
    val requestDismiss = {
        when {
            state.isApplying -> Unit
            confirmUnsavedDismiss && state.hasUnsavedChanges -> showDiscardConfirmation = true
            else -> onDismiss()
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactHeight = maxHeight < 480.dp * LocalDensity.current.fontScale
        val scrollState = rememberScrollState()
        val surfaceModifier = if (compactHeight) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().imePadding()
        }
        Surface(modifier = surfaceModifier) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (compactHeight) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("换名规则", style = MaterialTheme.typography.titleLarge)
                    Text("${state.draft.size} 条规则", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(
                    onClick = requestDismiss,
                    enabled = !state.isApplying,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "关闭规则面板" },
                ) {
                    Text("关闭")
                }
            }
                Column(
                    modifier = if (compactHeight) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.weight(1f, fill = false).verticalScroll(scrollState)
                    },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                state.draft.forEach { rule ->
                    if (state.editingRuleId == rule.id) {
                        RuleEditRow(
                            rule = rule,
                            error = state.validationErrors[rule.id],
                            enabled = interactionEnabled,
                            onChange = onChange,
                            onDelete = onDelete,
                        )
                    } else {
                        RuleSummaryRow(
                            rule = rule,
                            enabled = interactionEnabled,
                            onEdit = onEdit,
                            onDelete = onDelete,
                        )
                    }
                    HorizontalDivider()
                }
                OutlinedButton(
                    onClick = onAdd,
                    enabled = interactionEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("新增规则")
                }
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                state.lastApplyResult?.let {
                    Text(
                        applyResultMessage(it),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
                Button(
                    onClick = onApply,
                    enabled = applyEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "保存规则并应用到当前内容" },
                ) {
                    if (state.isApplying) {
                        CircularProgressIndicator()
                        Text("正在应用", modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Text(
                            when {
                                state.draft.isEmpty() -> "应用并恢复原文"
                                state.runtimeState == RuntimeState.Loading -> "正在准备阅读内容"
                                else -> "保存并生效"
                            },
                        )
                    }
                }
                if (compactHeight) {
                    Spacer(Modifier.height(WindowInsets.ime.asPaddingValues().calculateBottomPadding()))
                }
            }
        }
    }
    if (showDiscardConfirmation) {
        DiscardChangesDialog(
            onKeepEditing = { showDiscardConfirmation = false },
            onDiscard = {
                showDiscardConfirmation = false
                onDismiss()
            },
        )
    }
}

@Composable
private fun DiscardChangesDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("放弃本次修改？") },
        text = { Text("未保存的规则修改将丢失。") },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("放弃修改") }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepEditing,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("继续编辑") }
        },
    )
}

@Composable
private fun RuleSummaryRow(
    rule: ReplaceRule,
    enabled: Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "编辑规则：${rule.source}替换为${rule.target}"
            }
            .clickable(enabled = enabled) { onEdit(rule.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${rule.source} → ${rule.target}",
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onDelete(rule.id) },
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("delete-rule-${rule.id}")
                .semantics {
                    contentDescription = "删除规则：${rule.source}替换为${rule.target}"
                },
        ) {
            Text("删除")
        }
    }
}

@Composable
private fun RuleEditRow(
    rule: ReplaceRule,
    error: RuleValidationError?,
    enabled: Boolean,
    onChange: (id: String, source: String, target: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val sourceFocus = remember(rule.id) { FocusRequester() }
    val targetFocus = remember(rule.id) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val sourceError = error?.takeIf {
        it == RuleValidationError.SOURCE_REQUIRED ||
            it == RuleValidationError.SAME_VALUE ||
            it == RuleValidationError.DUPLICATE_SOURCE
    }
    val targetError = error?.takeIf { it == RuleValidationError.TARGET_REQUIRED }
    LaunchedEffect(rule.id) {
        sourceFocus.requestFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = rule.source,
            onValueChange = { onChange(rule.id, it, rule.target) },
            label = { Text("原名") },
            enabled = enabled,
            isError = sourceError != null,
            supportingText = sourceError?.let { validationMessage ->
                { Text(validationMessage.message()) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { targetFocus.requestFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(sourceFocus)
                .testTag("rule-source-${rule.id}"),
        )
        OutlinedTextField(
            value = rule.target,
            onValueChange = { onChange(rule.id, rule.source, it) },
            label = { Text("新名") },
            enabled = enabled,
            isError = targetError != null,
            supportingText = targetError?.let { validationMessage ->
                { Text(validationMessage.message()) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(targetFocus)
                .testTag("rule-target-${rule.id}"),
        )
        TextButton(
            onClick = { onDelete(rule.id) },
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("delete-rule-${rule.id}")
                .semantics {
                    contentDescription = "删除规则：${rule.source}替换为${rule.target}"
                },
        ) {
            Text("删除")
        }
    }
}

internal fun applyResultMessage(summary: RuleApplyResult): String = when {
    summary.activeRuleCount == 0 -> "已清除规则并恢复当前内容"
    summary.replacementCount == 0 ->
        "规则已保存，当前已加载内容未找到匹配；继续阅读时仍会自动匹配"
    else ->
        "已保存 ${summary.activeRuleCount} 条规则，当前已加载内容替换 ${summary.replacementCount} 处"
}

private fun RuleValidationError.message(): String = when (this) {
    RuleValidationError.SOURCE_REQUIRED -> "请输入原名"
    RuleValidationError.TARGET_REQUIRED -> "请输入新名"
    RuleValidationError.SAME_VALUE -> "原名和新名不能相同"
    RuleValidationError.DUPLICATE_SOURCE -> "原名不能重复"
}
