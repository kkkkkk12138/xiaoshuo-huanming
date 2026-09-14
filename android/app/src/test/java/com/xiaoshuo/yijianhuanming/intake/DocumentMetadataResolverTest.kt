package com.xiaoshuo.yijianhuanming.intake

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentMetadataResolverTest {
    private val resolver = DocumentMetadataResolver()

    @Test
    fun display_name_takes_priority_over_uri_segment() {
        assertEquals(
            "我的小说.txt",
            resolver.resolve(DocumentKind.TXT, "我的小说.txt", "content://provider/ignored.txt"),
        )
    }

    @Test
    fun percent_decoded_uri_segment_is_used_when_display_name_is_missing() {
        assertEquals(
            "长篇 小说.txt",
            resolver.resolve(DocumentKind.TXT, null, "content://provider/%E9%95%BF%E7%AF%87%20%E5%B0%8F%E8%AF%B4.txt"),
        )
    }

    @Test
    fun opaque_document_ids_numeric_names_and_blank_names_fall_back_by_kind() {
        assertEquals(
            "TXT 文档",
            resolver.resolve(DocumentKind.TXT, "document:1000037924", "content://provider/document%3A1000037924"),
        )
        assertEquals(
            "EPUB 文档",
            resolver.resolve(DocumentKind.EPUB, "document:abc_123", "content://provider/document%3Aabc_123"),
        )
        assertEquals(
            "EPUB 文档",
            resolver.resolve(DocumentKind.EPUB, "1000037924", "content://provider/1000037924"),
        )
        assertEquals(
            "TXT 文档",
            resolver.resolve(DocumentKind.TXT, " \n\t ", "content://provider/"),
        )
    }

    @Test
    fun control_and_bidirectional_characters_are_removed_and_lines_are_folded() {
        assertEquals(
            "危险 标题.txt",
            resolver.resolve(
                DocumentKind.TXT,
                "\u202E危\u0000险\r\n标\u0085题\u2067.txt",
                "content://provider/fallback.txt",
            ),
        )
    }

    @Test
    fun title_is_limited_to_one_hundred_grapheme_clusters() {
        val family = "👨‍👩‍👧‍👦"
        val title = resolver.resolve(
            DocumentKind.EPUB,
            family.repeat(101),
            "content://provider/fallback.epub",
        )

        assertEquals(family.repeat(100), title)
    }
}
