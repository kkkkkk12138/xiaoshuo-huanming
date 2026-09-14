package com.xiaoshuo.yijianhuanming.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderToolbarLayoutTest {
    @Test
    fun rule_editor_uses_bottom_sheet_when_width_is_below_600() {
        assertEquals(
            RuleEditorPresentation.ModalBottomSheet,
            ruleEditorPresentation(widthDp = 599, heightDp = 800),
        )
    }

    @Test
    fun rule_editor_uses_bottom_sheet_when_height_is_compact() {
        assertEquals(
            RuleEditorPresentation.ModalBottomSheet,
            ruleEditorPresentation(widthDp = 840, heightDp = 479),
        )
    }

    @Test
    fun rule_editor_uses_side_panel_at_840_by_480_boundary() {
        assertEquals(
            RuleEditorPresentation.SidePanel,
            ruleEditorPresentation(widthDp = 840, heightDp = 480),
        )
    }

    @Test
    fun epub_ratio_script_uses_document_scroll_extent_and_guards_short_chapters() {
        assertEquals(
            """
            (function(){const d=document.documentElement;const max=Math.max(1,d.scrollHeight-d.clientHeight);const value=d.scrollHeight<=d.clientHeight?0:window.scrollY/max;return value})()
            """.trimIndent(),
            EPUB_CHAPTER_RATIO_SCRIPT,
        )
    }

    @Test
    fun compact_width_keeps_bottom_actions_in_one_scrollable_row() {
        val rows = readerToolbarRows(
            widthDp = 360,
            fontScale = 1.3f,
            hasPrevious = true,
            showContents = true,
            hasNext = true,
        )

        assertEquals(
            listOf(
                listOf(
                    ReaderToolbarAction.Rules,
                    ReaderToolbarAction.Previous,
                    ReaderToolbarAction.Contents,
                    ReaderToolbarAction.Next,
                ),
            ),
            rows,
        )
    }

    @Test
    fun regular_width_keeps_short_toolbar_on_one_row() {
        val rows = readerToolbarRows(
            widthDp = 412,
            fontScale = 1f,
            hasPrevious = false,
            showContents = true,
            hasNext = false,
        )

        assertEquals(
            listOf(
                listOf(
                    ReaderToolbarAction.Rules,
                    ReaderToolbarAction.Contents,
                ),
            ),
            rows,
        )
    }

    @Test
    fun width_320_and_font_scale_2_keeps_primary_action_first_without_extra_rows() {
        val rows = readerToolbarRows(
            widthDp = 320,
            fontScale = 2f,
            hasPrevious = true,
            showContents = true,
            hasNext = true,
        )

        assertEquals(
            listOf(
                listOf(
                    ReaderToolbarAction.Rules,
                    ReaderToolbarAction.Previous,
                    ReaderToolbarAction.Contents,
                    ReaderToolbarAction.Next,
                ),
            ),
            rows,
        )
    }

    @Test
    fun landscape_width_keeps_all_actions_in_one_row() {
        val rows = readerToolbarRows(
            widthDp = 640,
            fontScale = 1.3f,
            hasPrevious = true,
            showContents = true,
            hasNext = true,
        )

        assertEquals(1, rows.size)
        assertEquals(4, rows.single().size)
    }
}
