package com.xiaoshuo.yijianhuanming.intake

import android.content.Intent
import com.xiaoshuo.yijianhuanming.reader.ReaderErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlEntryResolverTest {
    private val resolver = UrlEntryResolver(
        inputResolver = object : InputResolver {
            override suspend fun resolve(intent: Intent): Result<ReaderInput> =
                Result.failure(UnsupportedOperationException())

            override suspend fun resolveUrl(rawUrl: String): Result<ReaderInput.WebUrl> =
                Result.failure(IllegalArgumentException("测试不应解析被拒绝的 URL"))
        },
    )

    @Test
    fun malformed_url_uses_invalid_url_error_code() = runBlocking {
        val result = resolver.resolve("not a url") as UrlEntryResult.Invalid

        assertEquals(ReaderErrorCode.INVALID_URL, result.error.code)
    }

    @Test
    fun dangerous_scheme_uses_unsafe_url_error_code() = runBlocking {
        val result = resolver.resolve("javascript:alert(1)") as UrlEntryResult.Invalid

        assertEquals(ReaderErrorCode.UNSAFE_URL, result.error.code)
    }
}
