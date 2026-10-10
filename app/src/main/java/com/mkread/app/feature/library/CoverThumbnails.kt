package com.mkread.app.feature.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.mkread.app.core.model.BookSummary
import java.io.File

/** Small decoded covers for the shelf, kept in memory so scrolling does not decode them again. */
object CoverThumbnails {
    private const val TARGET_WIDTH_PX = 192
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(book: BookSummary): Bitmap? = key(book)?.let(cache::get)

    fun load(filesDir: File, book: BookSummary): Bitmap? {
        val key = key(book) ?: return null
        cache.get(key)?.let { return it }
        // coverPath is a file name written by BookStorage.writeCover (cover.jpg/png/webp).
        val name = book.coverPath?.takeIf { COVER_NAME.matches(it) } ?: return null
        val file = File(filesDir, "books/${book.id}/$name")
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun key(book: BookSummary): String? = book.coverPath?.let { "${book.id}/$it" }

    private val COVER_NAME = Regex("^cover\\.(?:jpg|png|webp)$")
}
