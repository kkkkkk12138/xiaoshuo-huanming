package com.xiaoshuo.yijianhuanming.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TxtReaderProgressTest {
    @Test
    fun local_reader_uses_dom_caret_and_runtime_mapping_without_height_estimation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = context.assets.open("reader/local-reader.js")
            .bufferedReader()
            .use { it.readText() }

        val caretPosition = script.indexOf("caretPositionFromPoint")
        val caretRange = script.indexOf("caretRangeFromPoint")
        assertTrue(caretPosition >= 0)
        assertTrue(caretRange > caretPosition)
        assertTrue(script.contains("renderedOffsetToSource"))
        assertTrue(script.contains("sourceOffsetToRendered"))
        assertTrue(script.contains("dataset.sourceStart"))
        assertTrue(script.contains("totalUtf16Units"))
        assertFalse(script.contains("rect.height"))
        assertFalse(script.contains("scrollHeight * ratio"))
    }
}
