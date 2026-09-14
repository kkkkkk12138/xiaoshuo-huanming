package com.xiaoshuo.yijianhuanming.intake

import android.content.Intent

interface InputResolver {
    suspend fun resolve(intent: Intent): Result<ReaderInput>
    suspend fun resolveUrl(rawUrl: String): Result<ReaderInput.WebUrl>
}

object InputAction {
    const val Send = "android.intent.action.SEND"
    const val View = "android.intent.action.VIEW"
    const val OpenDocument = "android.intent.action.OPEN_DOCUMENT"
}

data class InputRequest(
    val action: String?,
    val mimeType: String? = null,
    val sharedText: String? = null,
    val data: String? = null,
    val persistableReadGrant: Boolean = false,
)

data class DocumentMetadata(
    val mimeType: String?,
    val displayName: String?,
    val header: ByteArray,
)

interface DocumentAccess {
    fun inspect(uri: String): DocumentMetadata
    fun persistReadPermission(uri: String): Boolean
}

sealed interface ResolvedInput {
    data class WebUrl(val uri: String) : ResolvedInput
    data class TxtDocument(val uri: String, val persistedReadPermission: Boolean) : ResolvedInput
    data class EpubDocument(val uri: String, val persistedReadPermission: Boolean) : ResolvedInput
}

class DefaultInputResolver(
    private val urlPolicy: UrlPolicy,
    private val documents: DocumentAccess,
) {
    fun resolve(request: InputRequest): Result<ResolvedInput> = runCatching {
        when (request.action) {
            InputAction.Send -> resolveSharedText(request)
            InputAction.View, InputAction.OpenDocument -> resolveDocument(request)
            else -> error("不支持的输入操作")
        }
    }

    private fun resolveSharedText(request: InputRequest): ResolvedInput {
        require(request.mimeType == "text/plain") { "仅支持分享文本链接" }
        val text = request.sharedText.orEmpty()
        val urls = HTTP_URL.findAll(text)
            .map { it.value.trimEnd(*TRAILING_PUNCTUATION) }
            .distinct()
            .toList()
        require(urls.size == 1) { "分享内容必须包含且仅包含一个绝对 URL" }
        val url = urls.single()
        require(urlPolicy.evaluate(url) !is UrlDecision.Reject) { "分享链接不是可访问的公网 URL" }
        return ResolvedInput.WebUrl(url)
    }

    private fun resolveDocument(request: InputRequest): ResolvedInput {
        val uri = requireNotNull(request.data) { "文件 URI 缺失" }
        require(uri.startsWith("content://", ignoreCase = true)) {
            "仅支持系统文件选择器提供的 content URI"
        }
        val metadata = documents.inspect(uri)
        val persisted = if (request.persistableReadGrant) {
            documents.persistReadPermission(uri)
        } else {
            false
        }

        return when {
            isEpub(metadata) -> ResolvedInput.EpubDocument(uri, persisted)
            isTxt(metadata) -> ResolvedInput.TxtDocument(uri, persisted)
            else -> error("仅支持 TXT 或 EPUB 文件")
        }
    }

    private fun isEpub(metadata: DocumentMetadata): Boolean {
        val mime = metadata.mimeType?.lowercase()
        val extensionMatches = metadata.displayName?.lowercase()?.endsWith(".epub") == true
        val mimeMatches = mime == EPUB_MIME || mime == GENERIC_MIME || mime == null
        return extensionMatches && mimeMatches && metadata.header.startsWith(ZIP_HEADER)
    }

    private fun isTxt(metadata: DocumentMetadata): Boolean {
        val mime = metadata.mimeType?.lowercase()
        val extensionMatches = metadata.displayName?.lowercase()?.endsWith(".txt") == true
        val mimeMatches = mime == "text/plain" || mime == GENERIC_MIME || mime == null
        return extensionMatches && mimeMatches && metadata.header.looksLikeText()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.looksLikeText(): Boolean =
        take(512).none { byte -> byte == 0.toByte() }

    private companion object {
        val HTTP_URL = Regex("""(?i)\bhttps?://[^\s<>"']+""")
        val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '。', '，', '；', '！', '？')
        val ZIP_HEADER = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        const val EPUB_MIME = "application/epub+zip"
        const val GENERIC_MIME = "application/octet-stream"
    }
}
