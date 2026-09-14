package com.xiaoshuo.yijianhuanming.intake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xiaoshuo.yijianhuanming.reader.ReaderError
import com.xiaoshuo.yijianhuanming.reader.ReaderErrorCode
import com.xiaoshuo.yijianhuanming.reader.RecoveryAction
import kotlinx.coroutines.launch

sealed interface UrlEntryResult {
    data class Open(val input: ReaderInput.WebUrl) : UrlEntryResult
    data class ConfirmHttp(val input: ReaderInput.WebUrl) : UrlEntryResult
    data class Invalid(val error: ReaderError) : UrlEntryResult
}

class UrlEntryResolver(
    private val inputResolver: InputResolver,
    private val urlPolicy: UrlPolicy = UrlPolicy(),
) {
    suspend fun resolve(rawUrl: String): UrlEntryResult {
        val normalized = urlPolicy.normalize(rawUrl)
        val decision = urlPolicy.evaluate(normalized)
        if (decision is UrlDecision.Reject) {
            val code = when (decision.kind) {
                UrlRejectKind.INVALID -> ReaderErrorCode.INVALID_URL
                UrlRejectKind.UNSAFE -> ReaderErrorCode.UNSAFE_URL
            }
            return UrlEntryResult.Invalid(
                ReaderError(code, decision.reason, RecoveryAction.EditUrl),
            )
        }
        val input = inputResolver.resolveUrl(normalized).getOrElse {
            return UrlEntryResult.Invalid(
                ReaderError(
                    ReaderErrorCode.INVALID_URL,
                    it.message ?: "链接格式无效",
                    RecoveryAction.EditUrl,
                ),
            )
        }
        return when (decision) {
            UrlDecision.Allow -> UrlEntryResult.Open(input)
            UrlDecision.ConfirmCleartext -> UrlEntryResult.ConfirmHttp(input)
            is UrlDecision.Reject -> error("Rejected URL returned above")
        }
    }
}

@Composable
fun UrlEntryDialog(
    onDismiss: () -> Unit,
    onOpen: (ReaderInput.WebUrl) -> Unit,
    readClipboard: () -> String?,
    resolveUrl: suspend (String) -> UrlEntryResult,
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingHttp by remember { mutableStateOf<ReaderInput.WebUrl?>(null) }
    val scope = rememberCoroutineScope()
    val bringIntoView = remember { BringIntoViewRequester() }

    fun submit() {
        scope.launch {
            when (val result = resolveUrl(value)) {
                is UrlEntryResult.Open -> onOpen(result.input)
                is UrlEntryResult.ConfirmHttp -> pendingHttp = result.input
                is UrlEntryResult.Invalid -> error = result.error.userMessage
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            BoxWithConstraints {
                val useVerticalActions = maxWidth < 360.dp
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("打开网页链接", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            error = null
                            pendingHttp = null
                        },
                        label = { Text("网页地址") },
                        supportingText = error?.let { message -> ({ Text(message) }) },
                        isError = error != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("url-input")
                            .bringIntoViewRequester(bringIntoView)
                            .onFocusEvent {
                                if (it.isFocused) scope.launch { bringIntoView.bringIntoView() }
                            },
                    )
                    TextButton(
                        onClick = {
                            readClipboard()?.let {
                                value = it
                                error = null
                                pendingHttp = null
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("粘贴")
                    }
                    if (pendingHttp != null) {
                        Text("此链接未加密", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "内容和访问地址可能被篡改。",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { pendingHttp?.let(onOpen) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("仍要打开")
                        }
                    }
                    if (useVerticalActions) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DialogActions(
                                canOpen = value.isNotBlank(),
                                onDismiss = onDismiss,
                                onSubmit = ::submit,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            DialogActions(
                                canOpen = value.isNotBlank(),
                                onDismiss = onDismiss,
                                onSubmit = ::submit,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogActions(
    canOpen: Boolean,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("取消")
    }
    Button(
        onClick = onSubmit,
        enabled = canOpen,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text("打开")
    }
}
