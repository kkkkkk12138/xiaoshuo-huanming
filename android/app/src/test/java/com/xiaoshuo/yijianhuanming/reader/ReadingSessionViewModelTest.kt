package com.xiaoshuo.yijianhuanming.reader

import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadingSessionViewModelTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun loading_uses_real_filename_and_same_request_is_not_opened_twice() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val handle = FakeHandle("真实文件名.txt") {
            gate.await()
            ReadingSessionPayload.Web("test://txt-document")
        }
        val opener = FakeOpener(handle)
        val viewModel = ReadingSessionViewModel(opener, scope)
        val request = ReadingSessionRequest.Txt("content://book", persistedReadPermission = true)

        viewModel.open(request)
        awaitState(viewModel) { it is ReadingSessionUiState.Loading }
        viewModel.open(request)

        val loading = viewModel.state.value as ReadingSessionUiState.Loading
        assertEquals("真实文件名.txt", loading.displayName)
        assertEquals(1, opener.prepareCount.get())

        gate.complete(Unit)
        awaitState(viewModel) { it is ReadingSessionUiState.Ready }
    }

    @Test
    fun cancel_stops_open_job_releases_incomplete_resources_and_returns_home() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val handle = FakeHandle("大文件.epub") {
            started.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
                error("unreachable")
            } finally {
                cancelled.complete(Unit)
            }
        }
        val viewModel = ReadingSessionViewModel(FakeOpener(handle), scope)

        viewModel.open(ReadingSessionRequest.Epub("content://large", true))
        started.await()
        viewModel.cancelOpening()

        withTimeout(1_000) { cancelled.await() }
        awaitState(viewModel) { it == ReadingSessionUiState.Home }
        assertEquals(1, handle.releaseCount.get())
    }

    @Test
    fun permission_loss_is_exposed_as_structured_recoverable_error() = runBlocking {
        val handle = FakeHandle("已失效.txt") {
            throw FileNotFoundException("permission revoked")
        }
        val viewModel = ReadingSessionViewModel(FakeOpener(handle), scope)

        viewModel.open(ReadingSessionRequest.Txt("content://revoked", true))

        awaitState(viewModel) { it is ReadingSessionUiState.Failed }
        val failed = viewModel.state.value as ReadingSessionUiState.Failed
        assertEquals(ReaderErrorCode.FILE_PERMISSION_LOST, failed.error.code)
        assertEquals(RecoveryAction.SelectAnotherFile, failed.error.recoveryAction)
        assertEquals(1, handle.releaseCount.get())
    }

    @Test
    fun close_flushes_progress_before_releasing_resources() = runBlocking {
        val events = mutableListOf<String>()
        val handle = FakeHandle("小说.txt", events = events) {
            ReadingSessionPayload.Web("test://txt-document")
        }
        val viewModel = ReadingSessionViewModel(FakeOpener(handle), scope)
        viewModel.open(ReadingSessionRequest.Txt("content://book", true))
        awaitState(viewModel) { it is ReadingSessionUiState.Ready }

        viewModel.closeReader { events += "flush" }

        awaitState(viewModel) { it == ReadingSessionUiState.Home }
        assertEquals(listOf("flush", "release"), events)
    }

    @Test
    fun late_open_callback_from_previous_session_cannot_replace_current_content() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val first = FakeHandle("旧文件.txt") {
            withContext(NonCancellable) { firstGate.await() }
            ReadingSessionPayload.Web("test://old")
        }
        val second = FakeHandle("新文件.epub") {
            ReadingSessionPayload.Web("test://new")
        }
        val opener = QueueOpener(mutableListOf(first, second))
        val viewModel = ReadingSessionViewModel(opener, scope)

        viewModel.open(ReadingSessionRequest.Txt("content://old", true))
        awaitState(viewModel) {
            it is ReadingSessionUiState.Loading && it.displayName == "旧文件.txt"
        }
        viewModel.open(ReadingSessionRequest.Epub("content://new", true))
        awaitState(viewModel) { it is ReadingSessionUiState.Ready }
        firstGate.complete(Unit)
        yield()

        val ready = viewModel.state.value as ReadingSessionUiState.Ready
        assertEquals(ReadingSessionPayload.Web("test://new"), ready.payload)
        assertEquals(1, first.releaseCount.get())
    }

    @Test
    fun retry_reopens_failed_request_with_a_new_session_token() = runBlocking {
        val failed = FakeHandle("损坏.epub") {
            throw IllegalStateException("invalid")
        }
        val recovered = FakeHandle("损坏.epub") {
            ReadingSessionPayload.Web("test://recovered")
        }
        val opener = QueueOpener(mutableListOf(failed, recovered))
        val viewModel = ReadingSessionViewModel(opener, scope)

        viewModel.open(ReadingSessionRequest.Epub("content://book", true))
        awaitState(viewModel) { it is ReadingSessionUiState.Failed }
        val failedToken = (viewModel.state.value as ReadingSessionUiState.Failed).sessionToken

        viewModel.retry()
        awaitState(viewModel) { it is ReadingSessionUiState.Ready }

        val ready = viewModel.state.value as ReadingSessionUiState.Ready
        assertTrue(ready.sessionToken != failedToken)
        assertEquals(ReadingSessionPayload.Web("test://recovered"), ready.payload)
    }

    private suspend fun awaitState(
        viewModel: ReadingSessionViewModel,
        predicate: (ReadingSessionUiState) -> Boolean,
    ) {
        withTimeout(1_000) {
            while (!predicate(viewModel.state.value)) yield()
        }
    }

    private class FakeOpener(
        private val handle: ReadingSessionHandle,
    ) : ReadingSessionOpener {
        val prepareCount = AtomicInteger()

        override fun prepare(request: ReadingSessionRequest): ReadingSessionHandle {
            prepareCount.incrementAndGet()
            return handle
        }
    }

    private class QueueOpener(
        private val handles: MutableList<ReadingSessionHandle>,
    ) : ReadingSessionOpener {
        override fun prepare(request: ReadingSessionRequest): ReadingSessionHandle =
            handles.removeAt(0)
    }

    private class FakeHandle(
        override val displayName: String,
        private val events: MutableList<String> = mutableListOf(),
        private val openBlock: suspend () -> ReadingSessionPayload,
    ) : ReadingSessionHandle {
        val releaseCount = AtomicInteger()

        override suspend fun open(): ReadingSessionPayload = openBlock()

        override suspend fun release() {
            releaseCount.incrementAndGet()
            events += "release"
        }
    }
}
