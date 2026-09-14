package com.xiaoshuo.yijianhuanming.reader

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xiaoshuo.yijianhuanming.data.ReaderSessionEntity
import com.xiaoshuo.yijianhuanming.intake.ReaderInput
import com.xiaoshuo.yijianhuanming.intake.UrlEntryDialog
import com.xiaoshuo.yijianhuanming.intake.UrlEntryResult
import com.xiaoshuo.yijianhuanming.library.RecentReadingList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenUrl: (ReaderInput.WebUrl) -> Unit,
    onOpenDocument: () -> Unit,
    onOpenSettings: () -> Unit = {},
    recent: List<ReaderSessionEntity> = emptyList(),
    onOpenRecent: (ReaderSessionEntity) -> Unit = {},
    readClipboard: (() -> String?)? = null,
    resolveUrl: suspend (String) -> UrlEntryResult = {
        UrlEntryResult.Invalid(
            ReaderError(
                ReaderErrorCode.INVALID_URL,
                "链接入口尚未配置",
                RecoveryAction.EditUrl,
            ),
        )
    },
    openUrlDialogInitially: Boolean = false,
    onUrlDialogShown: () -> Unit = {},
) {
    var showUrlDialog by remember { mutableStateOf(openUrlDialogInitially) }
    val context = LocalContext.current
    LaunchedEffect(openUrlDialogInitially) {
        if (openUrlDialogInitially) {
            showUrlDialog = true
            onUrlDialogShown()
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("小说一键换名") },
                actions = {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "打开设置" },
                    ) {
                        Text("设置")
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            RecentReadingList(
                items = recent,
                onOpen = onOpenRecent,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("开始阅读", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "导入一本小说，或打开网页，自由设置角色名字。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onOpenDocument,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("打开 TXT / EPUB")
                        }
                        OutlinedButton(
                            onClick = { showUrlDialog = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("打开网页链接")
                        }
                    }
                },
                footer = {
                    Text(
                        text = "不登录网站，不上传阅读内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                },
            )
        }
    }
    if (showUrlDialog) {
        UrlEntryDialog(
            onDismiss = { showUrlDialog = false },
            onOpen = {
                showUrlDialog = false
                onOpenUrl(it)
            },
            readClipboard = readClipboard ?: {
                context.getSystemService(ClipboardManager::class.java)
                    ?.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
            },
            resolveUrl = resolveUrl,
        )
    }
}
