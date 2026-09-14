package com.xiaoshuo.yijianhuanming.content.txt

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.UUID
import com.xiaoshuo.yijianhuanming.library.restoreTxtOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TxtResumePosition(
    val textOffset: Long?,
    val textTotalAtSave: Long?,
    val scrollRatio: Double,
)

data class TxtReaderDocument(
    val title: String,
    val sessionId: String,
    val readerUrl: String,
    val totalUtf16Units: Long,
    val initialOffset: Long,
    val manifest: TxtChunkManifest,
    val pathHandler: TxtAssetPathHandler,
)

sealed interface TxtOpenResult {
    data class Ready(val document: TxtReaderDocument) : TxtOpenResult
    data class NeedsEncodingSelection(val candidates: List<EncodingCandidate>) : TxtOpenResult
}

class TxtContentSource(
    context: Context,
    private val uri: Uri,
    private val selectedCharsetName: String? = null,
    private val resumePosition: TxtResumePosition? = null,
    private val detector: TxtEncodingDetector = TxtEncodingDetector(),
) {
    private val resolver = context.contentResolver
    private val chunkStore = TxtChunkStore(context.cacheDir.resolve("txt"))
    private var sessionId: String? = null

    suspend fun open(): Result<TxtOpenResult> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = resolver.metadata(uri)
            metadata.size?.let(TxtLimits::requireSupportedFileSize)
            val sample = resolver.openRequired(uri).use { input ->
                input.readUpTo(TxtLimits.SAMPLE_BYTES)
            }
            val decision = detector.detect(sample)
            val charsetName = selectedCharsetName ?: when (decision) {
                is EncodingDecision.Confirmed -> decision.charsetName
                is EncodingDecision.NeedsSelection ->
                    return@runCatching TxtOpenResult.NeedsEncodingSelection(decision.candidates)
            }
            val bomBytes = if (selectedCharsetName == null && decision is EncodingDecision.Confirmed) {
                decision.bomBytes
            } else {
                bomLength(sample, charsetName)
            }

            val id = UUID.randomUUID().toString()
            sessionId = id
            val manifest = resolver.openRequired(uri).use { raw ->
                val bounded = BoundedInputStream(raw, TxtLimits.MAX_FILE_BYTES)
                repeat(bomBytes) {
                    if (bounded.read() < 0) error("TXT BOM 不完整")
                }
                InputStreamReader(bounded, Charset.forName(charsetName)).use { reader ->
                    chunkStore.writeSession(id, reader)
                }
            }
            val starts = manifest.chunks.joinToString(",") { it.startCharacterOffset.toString() }
            val totalUtf16Units = manifest.totalCharacterOffset
            val initialOffset = resumePosition?.let {
                restoreTxtOffset(
                    savedOffset = it.textOffset,
                    savedTotal = it.textTotalAtSave,
                    savedRatio = it.scrollRatio,
                    currentTotal = totalUtf16Units,
                )
            } ?: 0L
            val url = buildString {
                append("https://appassets.androidplatform.net/assets/reader/local-reader.html")
                append("?session=").append(id)
                append("&chunks=").append(manifest.chunks.size)
                append("&starts=").append(starts)
                append("&totalUtf16Units=").append(totalUtf16Units)
                append("&offset=").append(initialOffset)
            }
            TxtOpenResult.Ready(
                TxtReaderDocument(
                    title = metadata.displayName ?: "TXT 阅读",
                    sessionId = id,
                    readerUrl = url,
                    totalUtf16Units = totalUtf16Units,
                    initialOffset = initialOffset,
                    manifest = manifest,
                    pathHandler = TxtAssetPathHandler(manifest),
                ),
            )
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        sessionId?.let(chunkStore::closeSession)
        sessionId = null
    }

    private fun bomLength(sample: ByteArray, charsetName: String): Int = when {
        charsetName.equals("UTF-8", true) &&
            sample.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) -> 3
        charsetName.equals("UTF-16LE", true) &&
            sample.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> 2
        charsetName.equals("UTF-16BE", true) &&
            sample.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> 2
        else -> 0
    }
}

private data class TxtMetadata(
    val displayName: String?,
    val size: Long?,
)

private fun ContentResolver.metadata(uri: Uri): TxtMetadata {
    var displayName: String? = null
    var size: Long? = null
    query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return TxtMetadata(displayName, size)
}

private fun ContentResolver.openRequired(uri: Uri): InputStream =
    openInputStream(uri) ?: error("无法读取 TXT 文件")

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val output = ByteArray(limit)
    var total = 0
    while (total < limit) {
        val count = read(output, total, limit - total)
        if (count < 0) break
        total += count
    }
    return output.copyOf(total)
}

private class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) count(count)
        return count
    }

    private fun count(bytes: Int) {
        consumed += bytes
        TxtLimits.requireSupportedFileSize(consumed)
    }
}
