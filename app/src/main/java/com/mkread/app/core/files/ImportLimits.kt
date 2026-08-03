package com.mkread.app.core.files

object ImportLimits {
    const val SOURCE_BYTES = 20L * 1024L * 1024L
    const val EXPANDED_EPUB_BYTES = 80L * 1024L * 1024L
    const val ZIP_ENTRIES = 5_000
    const val CHAPTER_CHARACTERS = 5_000_000
    const val COPY_BUFFER_BYTES = 64 * 1024
    const val STALE_TRANSACTION_MILLIS = 24L * 60L * 60L * 1_000L
}
