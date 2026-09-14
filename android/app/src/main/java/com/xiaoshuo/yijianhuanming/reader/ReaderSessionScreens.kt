package com.xiaoshuo.yijianhuanming.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ReaderLoadingScreen(
    displayName: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "正在打开《$displayName》",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("取消")
        }
    }
}

@Composable
fun ReaderErrorScreen(
    error: ReaderError,
    onRecovery: () -> Unit,
    onReturnHome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("无法继续阅读", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(error.userMessage, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRecovery,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(recoveryActionLabel(error.recoveryAction))
        }
        if (error.recoveryAction != RecoveryAction.ReturnHome) {
            TextButton(
                onClick = onReturnHome,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("返回首页")
            }
        }
    }
}

fun recoveryActionLabel(action: RecoveryAction): String = when (action) {
    RecoveryAction.RetryRuntime -> "重试加载"
    RecoveryAction.RetryApply -> "重试应用"
    RecoveryAction.ReopenDatabaseAndRetryApply -> "重新连接并重试"
    RecoveryAction.ReloadDocument -> "重新载入内容"
    RecoveryAction.EditUrl -> "修改链接"
    RecoveryAction.SelectEncoding -> "选择编码"
    RecoveryAction.SelectAnotherFile -> "重新选择文件"
    RecoveryAction.ReturnHome -> "返回首页"
}
