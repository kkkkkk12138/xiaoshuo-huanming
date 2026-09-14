package com.xiaoshuo.yijianhuanming.intake

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

class AndroidInputResolver(context: Context) : InputResolver {
    private val delegate = DefaultInputResolver(
        urlPolicy = UrlPolicy(),
        documents = ContentResolverDocumentAccess(context.contentResolver),
    )

    override suspend fun resolve(intent: Intent): Result<ReaderInput> {
        val hasPersistableReadGrant =
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        val request = InputRequest(
            action = intent.action,
            mimeType = intent.type,
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT),
            data = intent.dataString,
            persistableReadGrant = hasPersistableReadGrant,
        )
        return delegate.resolve(request).map { input ->
            when (input) {
                is ResolvedInput.WebUrl -> ReaderInput.WebUrl(Uri.parse(input.uri))
                is ResolvedInput.TxtDocument -> ReaderInput.TxtDocument(
                    Uri.parse(input.uri),
                    input.persistedReadPermission,
                )
                is ResolvedInput.EpubDocument -> ReaderInput.EpubDocument(
                    Uri.parse(input.uri),
                    input.persistedReadPermission,
                )
            }
        }
    }

    override suspend fun resolveUrl(rawUrl: String): Result<ReaderInput.WebUrl> =
        delegate.resolve(
            InputRequest(
                action = InputAction.Send,
                mimeType = "text/plain",
                sharedText = rawUrl,
            ),
        ).mapCatching { input ->
            val web = input as? ResolvedInput.WebUrl ?: error("输入不是网页链接")
            ReaderInput.WebUrl(Uri.parse(web.uri))
        }
}

private class ContentResolverDocumentAccess(
    private val resolver: ContentResolver,
) : DocumentAccess {
    override fun inspect(uri: String): DocumentMetadata {
        val parsedUri = Uri.parse(uri)
        val displayName = resolver.query(
            parsedUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val header = resolver.openInputStream(parsedUri)?.use { input ->
            val buffer = ByteArray(HEADER_LIMIT)
            val count = input.read(buffer)
            if (count <= 0) byteArrayOf() else buffer.copyOf(count)
        } ?: error("无法读取文件")
        return DocumentMetadata(
            mimeType = resolver.getType(parsedUri),
            displayName = displayName,
            header = header,
        )
    }

    override fun persistReadPermission(uri: String): Boolean =
        try {
            resolver.takePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }

    private companion object {
        const val HEADER_LIMIT = 512
    }
}
