package com.xiaoshuo.yijianhuanming.reader

import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.os.SystemClock
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xiaoshuo.yijianhuanming.content.web.NavigationDecision
import com.xiaoshuo.yijianhuanming.content.web.WebSecurityCallbacks
import com.xiaoshuo.yijianhuanming.content.web.WebViewProfile
import com.xiaoshuo.yijianhuanming.content.txt.TxtAssetPathHandler
import com.xiaoshuo.yijianhuanming.content.txt.TxtReaderDocument
import com.xiaoshuo.yijianhuanming.content.epub.EpubChapter
import com.xiaoshuo.yijianhuanming.content.epub.EpubLocation
import com.xiaoshuo.yijianhuanming.content.epub.EpubReaderDocument
import com.xiaoshuo.yijianhuanming.navigation.ReaderBackTarget
import com.xiaoshuo.yijianhuanming.navigation.readerBackTarget
import com.xiaoshuo.yijianhuanming.library.ReadingProgress
import com.xiaoshuo.yijianhuanming.library.ReadingProgressCoordinator
import com.xiaoshuo.yijianhuanming.library.epubProgress
import com.xiaoshuo.yijianhuanming.library.saveEpubBeforeChapterChange
import com.xiaoshuo.yijianhuanming.library.txtRatio
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal const val EPUB_CHAPTER_RATIO_SCRIPT =
    "(function(){const d=document.documentElement;const max=Math.max(1,d.scrollHeight-d.clientHeight);const value=d.scrollHeight<=d.clientHeight?0:window.scrollY/max;return value})()"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    url: String,
    readerSessionId: String = url,
    onClose: () -> Unit,
    profile: WebViewProfile = WebViewProfile.REMOTE_PUBLIC_WEB,
    txtPathHandler: TxtAssetPathHandler? = null,
    txtDocument: TxtReaderDocument? = null,
    txtSourceId: String? = null,
    txtProgressCoordinator: ReadingProgressCoordinator? = null,
    onTxtProgressChanged: suspend (ReadingProgress) -> Unit = {},
    epubDocument: EpubReaderDocument? = null,
    epubProgressCoordinator: ReadingProgressCoordinator? = null,
    onEpubLocationChanged: suspend (EpubLocation) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onClearEpubCache: () -> Unit = {},
    confirmedCleartextUrl: String? = null,
    onOpenOtherUrl: () -> Unit = onClose,
    viewModel: ReaderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var announcedApplyGeneration by rememberSaveable(readerSessionId) { mutableStateOf(-1L) }
    var showRules by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var runtime by remember { mutableStateOf<RuleRuntime?>(null) }
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var loginBlocked by remember(url) { mutableStateOf(false) }
    var pendingCleartextUrl by remember(url) { mutableStateOf<String?>(null) }
    var currentChapterId by remember(epubDocument) {
        mutableStateOf(epubDocument?.initialLocation?.chapterId)
    }
    var currentUrl by remember(epubDocument, url) {
        mutableStateOf(epubDocument?.initialUrl ?: url)
    }
    var needsInitialRestore by remember(epubDocument) { mutableStateOf(epubDocument != null) }
    var needsInitialTxtRestore by remember(txtDocument) { mutableStateOf(txtDocument != null) }
    var fontScale by remember { mutableStateOf(1f) }
    var lastTxtSampleAt by remember(txtDocument) { mutableStateOf(0L) }
    var epubScrollSaveJob by remember(epubDocument) { mutableStateOf<Job?>(null) }
    val fallbackTxtProgressCoordinator = remember(txtDocument?.sessionId, txtSourceId) {
        ReadingProgressCoordinator(writer = onTxtProgressChanged).also { coordinator ->
            txtDocument?.let { document ->
                coordinator.rememberConfirmed(
                    ReadingProgress(
                        textOffset = document.initialOffset,
                        textTotalAtSave = document.totalUtf16Units,
                        scrollRatio = txtRatio(document.initialOffset, document.totalUtf16Units),
                        lastOpenedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
    val activeTxtProgressCoordinator = txtProgressCoordinator ?: fallbackTxtProgressCoordinator
    val fallbackEpubProgressCoordinator = remember(epubDocument?.sessionId) {
        ReadingProgressCoordinator(writer = { progress ->
            progress.chapterId?.let { chapterId ->
                onEpubLocationChanged(EpubLocation(chapterId, progress.scrollRatio))
            }
        }).also { coordinator ->
            epubDocument?.initialLocation?.let { location ->
                coordinator.rememberConfirmed(epubProgress(location, System.currentTimeMillis()))
            }
        }
    }
    val activeEpubProgressCoordinator =
        epubProgressCoordinator ?: fallbackEpubProgressCoordinator
    val currentChapter = epubDocument?.chapter(currentChapterId)
    val readerTitle = txtDocument?.title
        ?: epubDocument?.title
        ?: android.net.Uri.parse(url).host
        ?: "阅读"
    val persistentError = when (val runtimeState = state.runtimeState) {
        is RuntimeState.Failed -> runtimeState.error
        is RuntimeState.OutOfSync -> runtimeState.error
        else -> null
    }
    DisposableEffect(readerSessionId) {
        viewModel.startSession(readerSessionId)
        onDispose { viewModel.endSession(readerSessionId) }
    }
    androidx.compose.runtime.LaunchedEffect(state.applyGeneration, state.applyState) {
        val success = state.applyState as? ApplyState.Success ?: return@LaunchedEffect
        if (announcedApplyGeneration == state.applyGeneration) return@LaunchedEffect
        announcedApplyGeneration = state.applyGeneration
        showRules = false
        snackbarHostState.showSnackbar(applyResultMessage(success.summary))
    }
    suspend fun captureTxtProgress(): ReadingProgress? {
        val document = txtDocument ?: return null
        val offset = webView?.evaluateLong(
            "window.__TXT_READER__ ? window.__TXT_READER__.characterOffset() : null",
        ) ?: return null
        val clamped = offset.coerceIn(0, document.totalUtf16Units)
        return ReadingProgress(
            textOffset = clamped,
            textTotalAtSave = document.totalUtf16Units,
            scrollRatio = txtRatio(clamped, document.totalUtf16Units),
            lastOpenedAt = System.currentTimeMillis(),
        )
    }
    fun saveTxtProgress() {
        if (txtDocument == null) return
        scope.launch {
            activeTxtProgressCoordinator.captureAndSave(::captureTxtProgress)
        }
    }
    suspend fun captureEpubProgress(): ReadingProgress? {
        val chapterId = currentChapterId
        val ratio = webView?.evaluateDouble(EPUB_CHAPTER_RATIO_SCRIPT) ?: return null
        return epubProgress(EpubLocation(chapterId ?: return null, ratio), System.currentTimeMillis())
    }
    fun saveEpubLocation() {
        if (epubDocument == null) return
        scope.launch {
            activeEpubProgressCoordinator.captureAndSave(::captureEpubProgress)
        }
    }
    fun closeReader() {
        if (epubDocument != null) {
            scope.launch {
                activeEpubProgressCoordinator.saveBeforeClose(
                    timeoutMillis = 300,
                    capture = ::captureEpubProgress,
                )
                onClose()
            }
            return
        }
        val activeTxtDocument = txtDocument
        if (activeTxtDocument == null) {
            onClose()
            return
        }
        scope.launch {
            activeTxtProgressCoordinator.saveBeforeClose(
                totalUtf16Units = activeTxtDocument.totalUtf16Units,
                timeoutMillis = 300,
            ) {
                webView?.evaluateLong(
                    "window.__TXT_READER__ ? window.__TXT_READER__.characterOffset() : null",
                )
            }
            onClose()
        }
    }
    fun openChapter(chapterId: String) {
        val chapter = epubDocument?.chapter(chapterId) ?: return
        val oldChapterId = currentChapterId ?: return
        epubScrollSaveJob?.cancel()
        scope.launch {
            saveEpubBeforeChapterChange(
                oldChapterId = oldChapterId,
                newChapterId = chapter.id,
                coordinator = activeEpubProgressCoordinator,
                captureRatio = {
                    webView?.evaluateDouble(EPUB_CHAPTER_RATIO_SCRIPT)
                },
                loadChapter = {
                    currentChapterId = chapter.id
                    currentUrl = chapter.url
                    needsInitialRestore = false
                    showContents = false
                },
            )
        }
    }
    val callbacks = remember {
        object : WebSecurityCallbacks {
            override fun onNavigationBlocked(decision: NavigationDecision, url: String) {
                when (decision) {
                    NavigationDecision.BlockLogin -> loginBlocked = true
                    NavigationDecision.ConfirmCleartext -> pendingCleartextUrl = url
                    else -> Toast.makeText(context, decision.message(), Toast.LENGTH_LONG).show()
                }
            }

            override fun onLoginRiskDetected() {
                loginBlocked = true
            }
        }
    }
    DisposableEffect(lifecycleOwner, epubDocument, currentChapterId, webView) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                epubScrollSaveJob?.cancel()
                saveEpubLocation()
                saveTxtProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PredictiveBackHandler {
        it.collect {}
        when (
            readerBackTarget(
                panelOpen = showRules || showContents,
                canNavigateWebHistory = webView?.canGoBack() == true,
            )
        ) {
            ReaderBackTarget.DismissPanel -> {
                showRules = false
                showContents = false
            }
            ReaderBackTarget.WebHistory -> webView?.goBack()
            ReaderBackTarget.CloseReader -> closeReader()
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(readerTitle, maxLines = 2) },
                navigationIcon = {
                    TextButton(
                        onClick = ::closeReader,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("返回") }
                },
                actions = {
                    TextButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("更多") }
                },
            )
        },
        bottomBar = {
            if (!loginBlocked && persistentError == null) {
                ReaderToolbar(
                    ruleCount = state.persisted.size,
                    onRules = { showRules = true },
                    showContents = !epubDocument?.tableOfContents.isNullOrEmpty(),
                    onContents = { showContents = true },
                    hasPrevious = currentChapter?.previousChapterId != null,
                    hasNext = currentChapter?.nextChapterId != null,
                    onPrevious = { currentChapter?.previousChapterId?.let(::openChapter) },
                    onNext = { currentChapter?.nextChapterId?.let(::openChapter) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (loginBlocked) {
                LoginBlockedContent(
                    onReturnHome = onClose,
                    onOpenOtherUrl = onOpenOtherUrl,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (persistentError != null) {
                ReaderErrorScreen(
                    error = persistentError,
                    onRecovery = {
                        when (persistentError.recoveryAction) {
                            RecoveryAction.RetryRuntime,
                            RecoveryAction.ReloadDocument,
                            -> viewModel.beginRuntime()
                            RecoveryAction.RetryApply,
                            RecoveryAction.ReopenDatabaseAndRetryApply,
                            -> Unit
                            else -> closeReader()
                        }
                    },
                    onReturnHome = ::closeReader,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AndroidView(
                    factory = { androidContext ->
                        ReaderWebView(
                            context = androidContext,
                            profile = profile,
                            securityCallbacks = callbacks,
                            txtPathHandler = txtPathHandler,
                            epubPathHandler = epubDocument?.pathHandler,
                            confirmedCleartextUrl = confirmedCleartextUrl,
                            onRuntimeReady = { installedRuntime, complete ->
                                runtime = installedRuntime
                                if (needsInitialRestore) {
                                    val ratio = epubDocument?.initialLocation?.scrollRatio ?: 0.0
                                    webView?.post {
                                        webView?.evaluateJavascript(
                                            "scrollTo(0,Math.max(1,document.documentElement.scrollHeight-document.documentElement.clientHeight)*$ratio)",
                                            null,
                                        )
                                    }
                                    needsInitialRestore = false
                                }
                                scope.launch {
                                    val result = viewModel.reapplyPersisted(installedRuntime)
                                    complete(result.isSuccess)
                                    if (result.isSuccess && needsInitialTxtRestore) {
                                        val offset = txtDocument?.initialOffset ?: 0
                                        webView?.evaluateJavascript(
                                            "window.__TXT_READER__ && window.__TXT_READER__.restoreCharacterOffset($offset)",
                                            null,
                                        )
                                        needsInitialTxtRestore = false
                                    }
                                }
                            },
                        ).also {
                            webView = it
                            if (txtDocument != null || epubDocument != null) {
                                it.setOnScrollChangeListener { _, _, _, _, _ ->
                                    if (txtDocument != null) {
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastTxtSampleAt >= 2_000) {
                                            lastTxtSampleAt = now
                                            saveTxtProgress()
                                        }
                                    } else {
                                        epubScrollSaveJob?.cancel()
                                        epubScrollSaveJob = scope.launch {
                                            delay(2_000)
                                            activeEpubProgressCoordinator.captureAndSave(::captureEpubProgress)
                                        }
                                    }
                                }
                            }
                            it.loadUrl(currentUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url != currentUrl) webView.loadUrl(currentUrl)
                    },
                    onRelease = {
                        epubScrollSaveJob?.cancel()
                        webView = null
                        it.destroy()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    MaterialTheme {
        if (showRules) {
            AdaptiveRuleEditorPanel(
                state = state,
                onEdit = viewModel::editRule,
                onChange = viewModel::changeRule,
                onAdd = viewModel::addRule,
                onDelete = viewModel::deleteRule,
                onApply = {
                    runtime?.let { current ->
                        scope.launch { viewModel.applyAll(current) }
                    }
                },
                onDismiss = { showRules = false },
            )
        }
        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }) {
                ReaderSettingsSheet(
                    fontScale = fontScale,
                    onFontScaleChange = { scale ->
                        fontScale = scale
                        webView?.evaluateJavascript(
                            "document.documentElement.style.fontSize='${(scale * 100).toInt()}%'",
                            null,
                        )
                    },
                    onClearWebData = {
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        webView?.clearFormData()
                        webView?.clearCache(true)
                        webView?.clearHistory()
                    },
                    onClearRules = {
                        runtime?.let { current ->
                            scope.launch { viewModel.clearRules(current) }
                        }
                    },
                    onClearHistory = onClearHistory,
                    onClearEpubCache = onClearEpubCache,
                )
            }
        }
        if (showContents && epubDocument != null) {
            ModalBottomSheet(onDismissRequest = { showContents = false }) {
                ContentsSheet(
                    chapters = epubDocument.chapters.map {
                        EpubChapter(
                            id = it.id,
                            title = it.title,
                            href = it.url,
                            previousChapterId = it.previousChapterId,
                            nextChapterId = it.nextChapterId,
                        )
                    },
                    tableOfContents = epubDocument.tableOfContents,
                    currentChapterId = currentChapterId.orEmpty(),
                    onChapterSelected = ::openChapter,
                    onPrevious = { currentChapter?.previousChapterId?.let(::openChapter) },
                    onNext = { currentChapter?.nextChapterId?.let(::openChapter) },
                )
            }
        }
        pendingCleartextUrl?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingCleartextUrl = null },
                title = { Text("此链接未加密") },
                text = { Text("继续打开可能暴露或篡改阅读内容。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingCleartextUrl = null
                            webView?.confirmCleartextAndLoad(target)
                        },
                    ) { Text("仍要打开") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCleartextUrl = null }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun LoginBlockedContent(
    onReturnHome: () -> Unit,
    onOpenOtherUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("不支持在本应用内登录", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onReturnHome, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("返回首页")
        }
        TextButton(onClick = onOpenOtherUrl, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("打开其他公开链接")
        }
    }
}

@Composable
internal fun ReaderToolbar(
    ruleCount: Int,
    onRules: () -> Unit,
    showContents: Boolean,
    onContents: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val actions = readerToolbarRows(
        widthDp = 0,
        fontScale = 1f,
        hasPrevious = hasPrevious,
        showContents = showContents,
        hasNext = hasNext,
    ).single()
    BottomAppBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                Button(
                    onClick = when (action) {
                        ReaderToolbarAction.Previous -> onPrevious
                        ReaderToolbarAction.Contents -> onContents
                        ReaderToolbarAction.Next -> onNext
                        ReaderToolbarAction.Rules -> onRules
                    },
                    modifier = Modifier
                        .widthIn(min = 96.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = when (action) {
                            ReaderToolbarAction.Previous -> "上一章"
                            ReaderToolbarAction.Contents -> "目录"
                            ReaderToolbarAction.Next -> "下一章"
                            ReaderToolbarAction.Rules -> "规则 $ruleCount"
                        },
                    )
                }
            }
        }
    }
}

internal enum class ReaderToolbarAction {
    Previous,
    Contents,
    Next,
    Rules,
}

internal fun readerToolbarRows(
    widthDp: Int,
    fontScale: Float,
    hasPrevious: Boolean,
    showContents: Boolean,
    hasNext: Boolean,
): List<List<ReaderToolbarAction>> {
    val actions = buildList {
        add(ReaderToolbarAction.Rules)
        if (hasPrevious) add(ReaderToolbarAction.Previous)
        if (showContents) add(ReaderToolbarAction.Contents)
        if (hasNext) add(ReaderToolbarAction.Next)
    }
    return listOf(actions)
}

private fun NavigationDecision.message(): String = when (this) {
    NavigationDecision.Allow -> ""
    NavigationDecision.ConfirmCleartext -> "HTTP 页面需要明确确认"
    NavigationDecision.BlockLogin -> "登录页面已被拦截"
    is NavigationDecision.Block -> reason
}

private suspend fun ReaderWebView.evaluateLong(script: String): Long? =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { raw ->
            if (continuation.isActive) {
                continuation.resume(
                    raw
                        ?.trim('"')
                        ?.takeUnless { it == "null" || it == "undefined" }
                        ?.toDoubleOrNull()
                        ?.takeIf(Double::isFinite)
                        ?.toLong(),
                )
            }
        }
    }

private suspend fun ReaderWebView.evaluateDouble(script: String): Double? =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { raw ->
            if (continuation.isActive) {
                continuation.resume(
                    raw
                        ?.trim('"')
                        ?.takeUnless { it == "null" || it == "undefined" }
                        ?.toDoubleOrNull(),
                )
            }
        }
    }
