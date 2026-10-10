package com.mkread.app.core.files

object ImportLimits {
    /** TXT and EPUB sources. */
    const val SOURCE_BYTES = 20L * 1024L * 1024L
    /** Any imported file; MKBook packages with illustrations may be this large (same as the server). */
    const val PACKAGE_BYTES = 200L * 1024L * 1024L
    const val EXPANDED_MKBOOK_BYTES = 400L * 1024L * 1024L
    const val IMAGE_BYTES = 10L * 1024L * 1024L
    const val IMAGES = 2_000
    const val EXPANDED_EPUB_BYTES = 80L * 1024L * 1024L
    const val ZIP_ENTRIES = 5_000
    const val CHAPTER_CHARACTERS = 5_000_000
    const val COVER_BYTES = 10L * 1024L * 1024L
    const val COPY_BUFFER_BYTES = 64 * 1024
    const val STALE_TRANSACTION_MILLIS = 24L * 60L * 60L * 1_000L
}
