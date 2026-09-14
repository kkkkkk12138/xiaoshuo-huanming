package com.xiaoshuo.yijianhuanming.content.epub

import java.io.IOException

data class EpubLimits(
    val maxArchiveBytes: Long = 100L * 1024 * 1024,
    val maxTotalBytes: Long = 500L * 1024 * 1024,
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Long = 50L * 1024 * 1024,
    val maxCompressionRatio: Int = 100,
) {
    init {
        require(maxArchiveBytes > 0)
        require(maxTotalBytes > 0)
        require(maxEntries > 0)
        require(maxEntryBytes > 0)
        require(maxCompressionRatio > 0)
    }

    companion object {
        val DEFAULT = EpubLimits()
    }
}

enum class EpubFailureReason {
    INVALID,
    DRM,
    FIXED_LAYOUT,
}

class EpubValidationException(
    message: String,
    cause: Throwable? = null,
    val reason: EpubFailureReason = EpubFailureReason.INVALID,
) : IOException(message, cause)
