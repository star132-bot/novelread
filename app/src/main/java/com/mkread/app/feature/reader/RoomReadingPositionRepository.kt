package com.mkread.app.feature.reader

import androidx.room.withTransaction
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.database.ReadingPositionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomReadingPositionRepository(
    private val database: MkreadDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stationaryWriteIntervalMillis: Long = 1_000L,
) : ReadingPositionRepository {
    private val writeMutex = Mutex()

    override suspend fun get(bookId: String): ReadingPosition? =
        database.readingPositionDao().getByBookId(bookId)?.toModel()

    override suspend fun save(
        position: ReadingPosition,
        chapterLength: Int,
        force: Boolean,
    ): Boolean = writeMutex.withLock {
        val now = clock()
        val normalized = position.normalize(chapterLength)
        val current = database.readingPositionDao().getByBookId(position.bookId)
        val sentenceChanged = current != null &&
            (current.chapterId != normalized.chapterId || current.sentenceIndex != normalized.sentenceIndex)
        val stationary = current?.matches(normalized) == true
        val elapsed = current?.let { now - it.updatedAt } ?: Long.MAX_VALUE

        if (!force && !sentenceChanged && stationary && elapsed < stationaryWriteIntervalMillis) {
            return@withLock false
        }

        database.withTransaction {
            database.readingPositionDao().upsert(normalized.toEntity(now))
        }
        true
    }

    override suspend fun checkpoint(position: ReadingPosition, chapterLength: Int): Boolean =
        save(position, chapterLength, force = true)

    override suspend fun clear(bookId: String) = writeMutex.withLock {
        database.readingPositionDao().deleteByBookId(bookId)
    }

    private fun ReadingPosition.normalize(chapterLength: Int): ReadingPosition = copy(
        characterOffset = characterOffset.coerceIn(0, chapterLength.coerceAtLeast(0)),
        pageIndex = pageIndex.coerceAtLeast(0),
        sentenceIndex = sentenceIndex.coerceAtLeast(0),
    )

    private fun ReadingPositionEntity.matches(position: ReadingPosition): Boolean =
        chapterId == position.chapterId &&
            characterOffset == position.characterOffset &&
            pageIndex == position.pageIndex &&
            sentenceIndex == position.sentenceIndex

    private fun ReadingPositionEntity.toModel() = ReadingPosition(
        bookId = bookId,
        chapterId = chapterId,
        characterOffset = characterOffset,
        pageIndex = pageIndex,
        sentenceIndex = sentenceIndex,
        updatedAt = updatedAt,
    )

    private fun ReadingPosition.toEntity(now: Long) = ReadingPositionEntity(
        bookId = bookId,
        chapterId = chapterId,
        characterOffset = characterOffset,
        pageIndex = pageIndex,
        sentenceIndex = sentenceIndex,
        updatedAt = now,
    )
}
