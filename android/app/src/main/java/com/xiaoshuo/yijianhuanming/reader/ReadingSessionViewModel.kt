package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.content.epub.EpubReaderDocument
import com.xiaoshuo.yijianhuanming.content.txt.TxtReaderDocument
import com.xiaoshuo.yijianhuanming.library.ReadingProgressCoordinator
import java.util.UUID
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReadingSessionRequest {
    val uri: String
    val persistedReadPermission: Boolean

    data class Web(
        override val uri: String,
    ) : ReadingSessionRequest {
        override val persistedReadPermission: Boolean = false
    }

    data class Txt(
        override val uri: String,
        override val persistedReadPermission: Boolean,
        val charsetName: String? = null,
    ) : ReadingSessionRequest

    data class Epub(
        override val uri: String,
        override val persistedReadPermission: Boolean,
    ) : ReadingSessionRequest
}

sealed interface ReadingSessionPayload {
    data class Web(val url: String) : ReadingSessionPayload
    data class Txt(
        val document: TxtReaderDocument,
        val sourceId: String,
        val progressCoordinator: ReadingProgressCoordinator,
    ) : ReadingSessionPayload
    data class Epub(
        val document: EpubReaderDocument,
        val progressCoordinator: ReadingProgressCoordinator,
    ) : ReadingSessionPayload
}

interface ReadingSessionHandle {
    val displayName: String
    suspend fun open(): ReadingSessionPayload
    suspend fun release()
}

fun interface ReadingSessionOpener {
    fun prepare(request: ReadingSessionRequest): ReadingSessionHandle
}

sealed interface ReadingSessionUiState {
    data object Home : ReadingSessionUiState

    data class Loading(
        val request: ReadingSessionRequest,
        val displayName: String,
        val sessionToken: String,
    ) : ReadingSessionUiState

    data class Ready(
        val request: ReadingSessionRequest,
        val payload: ReadingSessionPayload,
        val displayName: String,
        val sessionToken: String,
    ) : ReadingSessionUiState

    data class Failed(
        val request: ReadingSessionRequest,
        val error: ReaderError,
        val displayName: String,
        val sessionToken: String,
    ) : ReadingSessionUiState
}

class ReadingSessionViewModel(
    private val opener: ReadingSessionOpener,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ReadingSessionUiState>(ReadingSessionUiState.Home)
    val state: StateFlow<ReadingSessionUiState> = mutableState.asStateFlow()

    private var generation = 0L
    private var openJob: Job? = null
    private var activeHandle: ReadingSessionHandle? = null
    private var lastRequest: ReadingSessionRequest? = null
    private val releasedHandles = Collections.newSetFromMap(
        IdentityHashMap<ReadingSessionHandle, Boolean>(),
    )

    fun open(request: ReadingSessionRequest) {
        val current = mutableState.value
        if (current is ReadingSessionUiState.Loading && current.request == request) return

        lastRequest = request
        val token = UUID.randomUUID().toString()
        val operationGeneration = ++generation
        openJob?.cancel()
        val previousHandle = activeHandle
        val handle = opener.prepare(request)
        activeHandle = handle
        mutableState.value = ReadingSessionUiState.Loading(request, handle.displayName, token)

        openJob = scope.launch {
            previousHandle?.let { releaseOnce(it) }
            try {
                val payload = handle.open()
                if (generation == operationGeneration && activeHandle === handle) {
                    mutableState.value =
                        ReadingSessionUiState.Ready(request, payload, handle.displayName, token)
                } else {
                    releaseOnce(handle)
                }
            } catch (cancelled: CancellationException) {
                releaseOnce(handle)
                throw cancelled
            } catch (failure: Throwable) {
                releaseOnce(handle)
                if (generation == operationGeneration && activeHandle === handle) {
                    activeHandle = null
                    mutableState.value = ReadingSessionUiState.Failed(
                        request = request,
                        error = mapDocumentOpenError(request.documentKind(), failure),
                        displayName = handle.displayName,
                        sessionToken = token,
                    )
                }
            }
        }
    }

    fun retry() {
        lastRequest?.let(::open)
    }

    fun cancelOpening() {
        val handle = activeHandle
        generation += 1
        activeHandle = null
        openJob?.cancel()
        openJob = scope.launch {
            handle?.let { releaseOnce(it) }
            mutableState.value = ReadingSessionUiState.Home
        }
    }

    fun closeReader(flushProgress: suspend () -> Unit = {}) {
        val handle = activeHandle
        val operationGeneration = ++generation
        activeHandle = null
        openJob?.cancel()
        openJob = scope.launch {
            flushProgress()
            handle?.let { releaseOnce(it) }
            if (generation == operationGeneration) {
                mutableState.value = ReadingSessionUiState.Home
            }
        }
    }

    fun returnHome() = closeReader()

    private suspend fun releaseOnce(handle: ReadingSessionHandle) {
        if (releasedHandles.add(handle)) handle.release()
    }

    private fun ReadingSessionRequest.documentKind(): ReadingDocumentKind = when (this) {
        is ReadingSessionRequest.Epub -> ReadingDocumentKind.EPUB
        is ReadingSessionRequest.Txt,
        is ReadingSessionRequest.Web,
        -> ReadingDocumentKind.TXT
    }
}
