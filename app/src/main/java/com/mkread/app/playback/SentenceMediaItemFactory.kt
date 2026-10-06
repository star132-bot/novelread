package com.mkread.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

object SentenceMediaItemFactory {
    fun create(
        sentenceId: SentenceId,
        waveFile: File,
        bookTitle: String,
        chapterTitle: String,
    ): MediaItem {
        require(waveFile.isFile) { "Narration WAV does not exist" }
        WaveFileContract.requirePlayable(waveFile)
        val extras = Bundle().apply {
            putString(EXTRA_BOOK_ID, sentenceId.bookId)
            putString(EXTRA_CHAPTER_ID, sentenceId.chapterId)
            putInt(EXTRA_SENTENCE_INDEX, sentenceId.index)
            putInt(EXTRA_SENTENCE_START, sentenceId.start)
            putInt(EXTRA_SENTENCE_END, sentenceId.end)
        }
        return MediaItem.Builder()
            .setMediaId(mediaId(sentenceId))
            .setUri(Uri.fromFile(waveFile))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(bookTitle)
                    .setArtist(chapterTitle)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    fun decode(mediaId: String): SentenceId? = runCatching {
        val values = String(Base64.getUrlDecoder().decode(mediaId), StandardCharsets.UTF_8)
            .split(FIELD_SEPARATOR)
        require(values.size == FIELD_COUNT)
        SentenceId(
            bookId = decodeText(values[0]),
            chapterId = decodeText(values[1]),
            index = values[2].toInt(),
            start = values[3].toInt(),
            end = values[4].toInt(),
        )
    }.getOrNull()

    fun mediaId(id: SentenceId): String {
        val payload = listOf(
            encodeText(id.bookId),
            encodeText(id.chapterId),
            id.index.toString(),
            id.start.toString(),
            id.end.toString(),
        ).joinToString(FIELD_SEPARATOR)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun encodeText(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    const val EXTRA_BOOK_ID = "mkread_book_id"
    const val EXTRA_CHAPTER_ID = "mkread_chapter_id"
    const val EXTRA_SENTENCE_INDEX = "mkread_sentence_index"
    const val EXTRA_SENTENCE_START = "mkread_sentence_start"
    const val EXTRA_SENTENCE_END = "mkread_sentence_end"
    private const val FIELD_SEPARATOR = "."
    private const val FIELD_COUNT = 5
}

private object WaveFileContract {
    fun requirePlayable(file: File) {
        com.mkread.app.speech.WaveValidator.requirePlayable(file)
    }
}
