package com.xiaoshuo.yijianhuanming.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputResolverTest {
    private val documents = FakeDocumentAccess()
    private val resolver = DefaultInputResolver(UrlPolicy(), documents)

    @Test
    fun action_send_accepts_exactly_one_absolute_public_url() {
        val result = resolver.resolve(
            InputRequest(
                action = InputAction.Send,
                mimeType = "text/plain",
                sharedText = "推荐 https://example.com/book?chapter=1",
            ),
        )

        assertEquals(
            "https://example.com/book?chapter=1",
            (result.getOrThrow() as ResolvedInput.WebUrl).uri,
        )
    }

    @Test
    fun action_send_rejects_multiple_urls_plain_text_and_private_urls() {
        listOf(
            "https://one.example/a https://two.example/b",
            "这是一段普通文字",
            "访问 http://192.168.1.2/book",
        ).forEach { sharedText ->
            val result = resolver.resolve(
                InputRequest(InputAction.Send, "text/plain", sharedText = sharedText),
            )
            assertTrue("$sharedText should fail", result.isFailure)
        }
    }

    @Test
    fun action_view_recognizes_txt_and_epub_using_metadata_and_header() {
        val txt = "content://books/story.txt"
        documents.add(
            txt,
            mimeType = "text/plain",
            displayName = "story.txt",
            header = "第一章".toByteArray(),
        )
        val epub = "content://books/story.epub"
        documents.add(
            epub,
            mimeType = "application/epub+zip",
            displayName = "story.epub",
            header = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
        )

        assertTrue(
            resolver.resolve(InputRequest(InputAction.View, data = txt)).getOrThrow()
                is ResolvedInput.TxtDocument,
        )
        assertTrue(
            resolver.resolve(InputRequest(InputAction.View, data = epub)).getOrThrow()
                is ResolvedInput.EpubDocument,
        )
    }

    @Test
    fun accepts_epub_when_provider_reports_generic_zip_mime() {
        val epub = "content://books/provider-mislabeled.epub"
        documents.add(
            epub,
            mimeType = "application/zip",
            displayName = "provider-mislabeled.epub",
            header = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
        )

        assertTrue(
            resolver.resolve(InputRequest(InputAction.OpenDocument, data = epub)).getOrThrow()
                is ResolvedInput.EpubDocument,
        )
    }

    @Test
    fun open_document_persists_read_only_permission_or_marks_session_only() {
        val persisted = "content://books/persisted.txt"
        documents.add(persisted, "text/plain", "persisted.txt", "text".toByteArray())
        val sessionOnly = "content://books/session.txt"
        documents.add(
            sessionOnly,
            "text/plain",
            "session.txt",
            "text".toByteArray(),
            canPersist = false,
        )

        val persistedInput = resolver.resolve(
            InputRequest(InputAction.OpenDocument, data = persisted, persistableReadGrant = true),
        ).getOrThrow() as ResolvedInput.TxtDocument
        val sessionInput = resolver.resolve(
            InputRequest(InputAction.OpenDocument, data = sessionOnly, persistableReadGrant = true),
        ).getOrThrow() as ResolvedInput.TxtDocument

        assertTrue(persistedInput.persistedReadPermission)
        assertFalse(sessionInput.persistedReadPermission)
        assertEquals(listOf(persisted, sessionOnly), documents.persistAttempts)
    }

    @Test
    fun rejects_unsupported_or_spoofed_documents() {
        val html = "content://books/fake.txt"
        documents.add(html, "text/html", "fake.txt", "<html>".toByteArray())
        val fakeEpub = "content://books/fake.epub"
        documents.add(fakeEpub, "application/epub+zip", "fake.epub", "not zip".toByteArray())

        assertTrue(resolver.resolve(InputRequest(InputAction.View, data = html)).isFailure)
        assertTrue(resolver.resolve(InputRequest(InputAction.View, data = fakeEpub)).isFailure)
    }
}

private class FakeDocumentAccess : DocumentAccess {
    private data class Entry(
        val mimeType: String?,
        val displayName: String?,
        val header: ByteArray,
        val canPersist: Boolean,
    )

    private val entries = mutableMapOf<String, Entry>()
    val persistAttempts = mutableListOf<String>()

    fun add(
        uri: String,
        mimeType: String?,
        displayName: String?,
        header: ByteArray,
        canPersist: Boolean = true,
    ) {
        entries[uri] = Entry(mimeType, displayName, header, canPersist)
    }

    override fun inspect(uri: String): DocumentMetadata {
        val entry = checkNotNull(entries[uri])
        return DocumentMetadata(entry.mimeType, entry.displayName, entry.header)
    }

    override fun persistReadPermission(uri: String): Boolean {
        persistAttempts += uri
        return checkNotNull(entries[uri]).canPersist
    }
}
