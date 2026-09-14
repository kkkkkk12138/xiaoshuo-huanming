package com.xiaoshuo.yijianhuanming

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoshuo.yijianhuanming.intake.AndroidInputResolver
import com.xiaoshuo.yijianhuanming.intake.DocumentMetadataResolver
import com.xiaoshuo.yijianhuanming.intake.ReaderInput
import com.xiaoshuo.yijianhuanming.intake.UrlEntryResolver
import com.xiaoshuo.yijianhuanming.content.web.WebViewProfile
import com.xiaoshuo.yijianhuanming.data.ReaderSessionDao
import com.xiaoshuo.yijianhuanming.data.ReaderSessionEntity
import com.xiaoshuo.yijianhuanming.reader.AndroidReadingSessionOpener
import com.xiaoshuo.yijianhuanming.reader.HomeScreen
import com.xiaoshuo.yijianhuanming.reader.ReaderErrorScreen
import com.xiaoshuo.yijianhuanming.reader.ReaderLoadingScreen
import com.xiaoshuo.yijianhuanming.reader.ReaderScreen
import com.xiaoshuo.yijianhuanming.reader.ReaderSettingsSheet
import com.xiaoshuo.yijianhuanming.reader.ReadingSessionPayload
import com.xiaoshuo.yijianhuanming.reader.ReadingSessionRequest
import com.xiaoshuo.yijianhuanming.reader.ReadingSessionUiState
import com.xiaoshuo.yijianhuanming.reader.ReadingSessionViewModel
import com.xiaoshuo.yijianhuanming.reader.RecoveryAction
import com.xiaoshuo.yijianhuanming.library.LibraryViewModel
import com.xiaoshuo.yijianhuanming.navigation.NameReplacerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var readerSessionDao: ReaderSessionDao

    private val inputResolver by lazy { AndroidInputResolver(this) }
    private val documentMetadataResolver by lazy { DocumentMetadataResolver(contentResolver) }
    private val urlEntryResolver by lazy { UrlEntryResolver(inputResolver) }
    private var requestUrlDialog by mutableStateOf(false)
    private lateinit var readingSession: ReadingSessionViewModel
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            resolveInput(
                Intent(Intent.ACTION_OPEN_DOCUMENT, it).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                ),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readingSession = ReadingSessionViewModel(
            opener = AndroidReadingSessionOpener(
                context = this,
                sessionDao = readerSessionDao,
                metadataResolver = documentMetadataResolver,
            ),
            scope = lifecycleScope,
        )
        savedInstanceState?.let(::restoreReaderInput)
        setContent {
            val libraryViewModel: LibraryViewModel = viewModel()
            val libraryState by libraryViewModel.state.collectAsState()
            val readingState by readingSession.state.collectAsState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            NameReplacerTheme(darkTheme = isSystemInDarkTheme()) {
                when (val current = readingState) {
                    ReadingSessionUiState.Home -> LibraryContent(
                        recent = libraryState.recent,
                        onClearHistory = { scope.launch { libraryViewModel.clearHistory() } },
                        onClearRules = { scope.launch { libraryViewModel.clearRules(null) } },
                        onClearEpubCache = { scope.launch { libraryViewModel.clearEpubCache() } },
                    )
                    is ReadingSessionUiState.Loading -> ReaderLoadingScreen(
                        displayName = current.displayName,
                        onCancel = readingSession::cancelOpening,
                    )
                    is ReadingSessionUiState.Failed -> ReaderErrorScreen(
                        error = current.error,
                        onRecovery = { recoverSessionError(current) },
                        onReturnHome = readingSession::returnHome,
                    )
                    is ReadingSessionUiState.Ready -> ReaderContent(
                        payload = current.payload,
                        sessionToken = current.sessionToken,
                        onClearHistory = { scope.launch { libraryViewModel.clearHistory() } },
                        onClearEpubCache = { scope.launch { libraryViewModel.clearEpubCache() } },
                    )
                }
            }
        }
        if (savedInstanceState == null && intent.action != Intent.ACTION_MAIN) {
            resolveInput(intent)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun LibraryContent(
        recent: List<ReaderSessionEntity>,
        onClearHistory: () -> Unit,
        onClearRules: () -> Unit,
        onClearEpubCache: () -> Unit,
    ) {
        var showSettings by remember { mutableStateOf(false) }
        HomeScreen(
            onOpenUrl = ::openUrlFromDialog,
            onOpenDocument = ::selectDocument,
            onOpenSettings = { showSettings = true },
            recent = recent,
            onOpenRecent = ::openRecent,
            resolveUrl = urlEntryResolver::resolve,
            openUrlDialogInitially = requestUrlDialog,
            onUrlDialogShown = { requestUrlDialog = false },
        )
        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }) {
                ReaderSettingsSheet(
                    onClearHistory = onClearHistory,
                    onClearRules = onClearRules,
                    onClearEpubCache = onClearEpubCache,
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ReaderContent(
        payload: ReadingSessionPayload,
        sessionToken: String,
        onClearHistory: () -> Unit,
        onClearEpubCache: () -> Unit,
    ) {
        when (payload) {
                is ReadingSessionPayload.Web -> ReaderScreen(
                    url = payload.url,
                    readerSessionId = sessionToken,
                    onClose = { readingSession.closeReader() },
                    confirmedCleartextUrl = payload.url
                        .takeIf { android.net.Uri.parse(it).scheme.equals("http", true) },
                    onOpenOtherUrl = {
                        readingSession.returnHome()
                        requestUrlDialog = true
                    },
                    onClearHistory = onClearHistory,
                    onClearEpubCache = onClearEpubCache,
                )
                is ReadingSessionPayload.Txt -> ReaderScreen(
                        url = payload.document.readerUrl,
                        readerSessionId = sessionToken,
                        profile = WebViewProfile.LOCAL_READER,
                        txtPathHandler = payload.document.pathHandler,
                        txtDocument = payload.document,
                        txtSourceId = payload.sourceId,
                        txtProgressCoordinator = payload.progressCoordinator,
                        onClose = { readingSession.closeReader() },
                        onClearHistory = onClearHistory,
                        onClearEpubCache = onClearEpubCache,
                    )
                is ReadingSessionPayload.Epub -> ReaderScreen(
                        url = payload.document.initialUrl,
                        readerSessionId = sessionToken,
                        profile = WebViewProfile.LOCAL_READER,
                        epubDocument = payload.document,
                        epubProgressCoordinator = payload.progressCoordinator,
                        onClose = { readingSession.closeReader() },
                        onClearHistory = onClearHistory,
                        onClearEpubCache = onClearEpubCache,
                    )
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveInput(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val request = when (val current = readingSession.state.value) {
            is ReadingSessionUiState.Loading -> current.request
            is ReadingSessionUiState.Ready -> current.request
            is ReadingSessionUiState.Failed -> current.request
            ReadingSessionUiState.Home -> null
        }
        request?.let {
            outState.putString(STATE_INPUT_TYPE, it.inputType())
            outState.putString(STATE_INPUT_URI, it.uri)
            outState.putBoolean(STATE_PERSISTED_PERMISSION, it.persistedReadPermission)
        }
    }

    private fun resolveInput(intent: Intent) {
        lifecycleScope.launch {
            inputResolver.resolve(intent)
                .onSuccess(::onReaderInput)
                .onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        it.message ?: "无法识别输入",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun onReaderInput(input: ReaderInput) {
        when (input) {
            is ReaderInput.WebUrl -> {
                if (input.uri.scheme.equals("https", ignoreCase = true)) {
                    readingSession.open(ReadingSessionRequest.Web(input.uri.toString()))
                } else {
                    Toast.makeText(this, "HTTP 页面需要明确确认", Toast.LENGTH_LONG).show()
                }
            }
            is ReaderInput.TxtDocument -> readingSession.open(
                ReadingSessionRequest.Txt(input.uri.toString(), input.persistedReadPermission),
            )
            is ReaderInput.EpubDocument -> readingSession.open(
                ReadingSessionRequest.Epub(input.uri.toString(), input.persistedReadPermission),
            )
        }
    }

    private fun openUrlFromDialog(input: ReaderInput.WebUrl) {
        readingSession.open(ReadingSessionRequest.Web(input.uri.toString()))
    }

    private fun restoreReaderInput(state: Bundle) {
        val type = state.getString(STATE_INPUT_TYPE) ?: return
        val uri = state.getString(STATE_INPUT_URI)?.let(android.net.Uri::parse) ?: return
        val persisted = state.getBoolean(STATE_PERSISTED_PERMISSION)
        onReaderInput(
            when (type) {
                "WEB" -> ReaderInput.WebUrl(uri)
                "TXT" -> ReaderInput.TxtDocument(uri, persisted)
                "EPUB" -> ReaderInput.EpubDocument(uri, persisted)
                else -> return
            },
        )
    }

    private fun openRecent(session: ReaderSessionEntity) {
        readingSession.open(
            when (session.type) {
                "EPUB" -> ReadingSessionRequest.Epub(session.uri, true)
                "TXT" -> ReadingSessionRequest.Txt(session.uri, true)
                else -> ReadingSessionRequest.Web(session.uri)
            },
        )
    }

    private fun selectDocument() {
        openDocument.launch(arrayOf("text/plain", "application/epub+zip"))
    }

    private fun recoverSessionError(failed: ReadingSessionUiState.Failed) {
        when (failed.error.recoveryAction) {
            RecoveryAction.SelectAnotherFile,
            RecoveryAction.SelectEncoding,
            -> {
                readingSession.returnHome()
                selectDocument()
            }
            RecoveryAction.EditUrl -> {
                readingSession.returnHome()
                requestUrlDialog = true
            }
            RecoveryAction.ReturnHome -> readingSession.returnHome()
            else -> readingSession.retry()
        }
    }

    private fun ReadingSessionRequest.inputType(): String = when (this) {
        is ReadingSessionRequest.Web -> "WEB"
        is ReadingSessionRequest.Txt -> "TXT"
        is ReadingSessionRequest.Epub -> "EPUB"
    }

    companion object {
        private const val STATE_INPUT_TYPE = "reader.input.type"
        private const val STATE_INPUT_URI = "reader.input.uri"
        private const val STATE_PERSISTED_PERMISSION = "reader.input.persisted"
    }
}
