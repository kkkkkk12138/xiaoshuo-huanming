package com.xiaoshuo.yijianhuanming.library

import com.xiaoshuo.yijianhuanming.content.epub.EpubLocation
import com.xiaoshuo.yijianhuanming.content.epub.normalizeChapterRatio
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

data class ReadingProgress(
    val textOffset: Long,
    val textTotalAtSave: Long,
    val scrollRatio: Double,
    val lastOpenedAt: Long,
    val chapterId: String? = null,
)

fun txtRatio(offset: Long, totalUtf16Units: Long): Double {
    if (totalUtf16Units <= 0) return 0.0
    return offset.coerceIn(0, totalUtf16Units).toDouble() / totalUtf16Units.toDouble()
}

fun restoreTxtOffset(
    savedOffset: Long?,
    savedTotal: Long?,
    savedRatio: Double,
    currentTotal: Long,
): Long {
    if (currentTotal <= 0) return 0
    if (savedOffset != null && savedTotal == currentTotal) {
        return savedOffset.coerceIn(0, currentTotal)
    }
    val ratio = savedRatio.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    return (ratio * currentTotal).toLong().coerceIn(0, currentTotal)
}

fun epubProgress(location: EpubLocation, now: Long): ReadingProgress = ReadingProgress(
    textOffset = 0,
    textTotalAtSave = 0,
    scrollRatio = normalizeChapterRatio(location.scrollRatio),
    lastOpenedAt = now,
    chapterId = location.chapterId,
)

suspend fun initializeEpubProgress(
    location: EpubLocation,
    repaired: Boolean,
    coordinator: ReadingProgressCoordinator,
    now: () -> Long = System::currentTimeMillis,
) {
    val progress = epubProgress(location, now())
    if (repaired) {
        coordinator.save(progress)
    } else {
        coordinator.rememberConfirmed(progress)
    }
}

class ReadingProgressCoordinator(
    private val backgroundScope: CoroutineScope? = null,
    private val writer: suspend (ReadingProgress) -> Unit,
) {
    private val nextSequence = AtomicLong(0)
    private val latestRequested = AtomicLong(0)
    private val writeMutex = Mutex()

    private var newestPersisted = 0L

    @Volatile
    private var confirmed: ReadingProgress? = null

    fun beginSequence(): Long = nextSequence.incrementAndGet().also { sequence ->
        latestRequested.accumulateAndGet(sequence, ::maxOf)
    }

    fun latestConfirmed(): ReadingProgress? = confirmed

    fun rememberConfirmed(progress: ReadingProgress) {
        confirmed = progress
    }

    suspend fun save(progress: ReadingProgress): Boolean =
        save(beginSequence(), progress)

    fun enqueue(progress: ReadingProgress) {
        val scope = requireNotNull(backgroundScope) {
            "ReadingProgressCoordinator requires a background scope for enqueue"
        }
        scope.launch { save(progress) }
    }

    suspend fun save(sequence: Long, progress: ReadingProgress): Boolean {
        if (sequence < latestRequested.get()) return false
        writeMutex.lock()
        return try {
            if (sequence < latestRequested.get() || sequence <= newestPersisted) {
                false
            } else {
                writer(progress)
                newestPersisted = sequence
                confirmed = progress
                true
            }
        } finally {
            writeMutex.unlock()
        }
    }

    suspend fun captureAndSave(
        capture: suspend () -> ReadingProgress?,
    ): Boolean {
        val sequence = beginSequence()
        val progress = capture() ?: return false
        return save(sequence, progress)
    }

    suspend fun saveBeforeClose(
        totalUtf16Units: Long,
        timeoutMillis: Long = 300,
        now: () -> Long = System::currentTimeMillis,
        captureOffset: suspend () -> Long?,
    ): ReadingProgress? {
        val sequence = beginSequence()
        val fresh = withTimeoutOrNull(timeoutMillis) {
            val offset = captureOffset() ?: return@withTimeoutOrNull null
            val clamped = offset.coerceIn(0, totalUtf16Units.coerceAtLeast(0))
            val progress = ReadingProgress(
                textOffset = clamped,
                textTotalAtSave = totalUtf16Units.coerceAtLeast(0),
                scrollRatio = txtRatio(clamped, totalUtf16Units),
                lastOpenedAt = now(),
            )
            progress.takeIf { save(sequence, it) }
        }
        if (fresh != null) return fresh

        val fallback = confirmed?.copy(lastOpenedAt = now()) ?: return null
        if (backgroundScope != null) {
            enqueue(fallback)
        } else {
            save(fallback)
        }
        return fallback
    }

    suspend fun saveBeforeClose(
        timeoutMillis: Long = 300,
        now: () -> Long = System::currentTimeMillis,
        capture: suspend () -> ReadingProgress?,
    ): ReadingProgress? {
        val sequence = beginSequence()
        val fresh = withTimeoutOrNull(timeoutMillis) {
            capture()?.copy(lastOpenedAt = now())?.takeIf { save(sequence, it) }
        }
        if (fresh != null) return fresh

        val fallback = confirmed?.copy(lastOpenedAt = now()) ?: return null
        save(fallback)
        return fallback
    }
}

suspend fun saveEpubBeforeChapterChange(
    oldChapterId: String,
    newChapterId: String,
    coordinator: ReadingProgressCoordinator,
    now: () -> Long = System::currentTimeMillis,
    captureRatio: suspend () -> Double?,
    loadChapter: (String) -> Unit,
) {
    val sequence = coordinator.beginSequence()
    val fallbackRatio = coordinator.latestConfirmed()
        ?.takeIf { it.chapterId == oldChapterId }
        ?.scrollRatio
        ?: 0.0
    val location = EpubLocation(oldChapterId, captureRatio() ?: fallbackRatio)
    if (coordinator.save(sequence, epubProgress(location, now()))) {
        loadChapter(newChapterId)
    }
}
