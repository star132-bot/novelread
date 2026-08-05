package com.mkread.app.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

class DataStorePlaybackCheckpointStore(
    private val dataStore: DataStore<Preferences>,
) : PlaybackCheckpointStore {
    override suspend fun read(): PlaybackCheckpoint? {
        val preferences = dataStore.data.first()
        return runCatching {
            PlaybackCheckpoint(
                sentenceId = SentenceId(
                    bookId = requireNotNull(preferences[BOOK_ID]),
                    chapterId = requireNotNull(preferences[CHAPTER_ID]),
                    index = requireNotNull(preferences[SENTENCE_INDEX]),
                    start = requireNotNull(preferences[SENTENCE_START]),
                    end = requireNotNull(preferences[SENTENCE_END]),
                ),
                queueGenerationId = requireNotNull(preferences[QUEUE_GENERATION_ID]),
                speed = requireNotNull(preferences[SPEED]),
                wasPlaying = requireNotNull(preferences[WAS_PLAYING]),
                updatedAtEpochMillis = requireNotNull(preferences[UPDATED_AT]),
            )
        }.getOrNull()
    }

    override suspend fun save(checkpoint: PlaybackCheckpoint) {
        dataStore.edit { preferences ->
            preferences[BOOK_ID] = checkpoint.sentenceId.bookId
            preferences[CHAPTER_ID] = checkpoint.sentenceId.chapterId
            preferences[SENTENCE_INDEX] = checkpoint.sentenceId.index
            preferences[SENTENCE_START] = checkpoint.sentenceId.start
            preferences[SENTENCE_END] = checkpoint.sentenceId.end
            preferences[QUEUE_GENERATION_ID] = checkpoint.queueGenerationId
            preferences[SPEED] = checkpoint.speed
            preferences[WAS_PLAYING] = checkpoint.wasPlaying
            preferences[UPDATED_AT] = checkpoint.updatedAtEpochMillis
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(BOOK_ID)
            preferences.remove(CHAPTER_ID)
            preferences.remove(SENTENCE_INDEX)
            preferences.remove(SENTENCE_START)
            preferences.remove(SENTENCE_END)
            preferences.remove(QUEUE_GENERATION_ID)
            preferences.remove(SPEED)
            preferences.remove(WAS_PLAYING)
            preferences.remove(UPDATED_AT)
        }
    }

    private companion object {
        val BOOK_ID = stringPreferencesKey("playback_book_id")
        val CHAPTER_ID = stringPreferencesKey("playback_chapter_id")
        val SENTENCE_INDEX = intPreferencesKey("playback_sentence_index")
        val SENTENCE_START = intPreferencesKey("playback_sentence_start")
        val SENTENCE_END = intPreferencesKey("playback_sentence_end")
        val QUEUE_GENERATION_ID = longPreferencesKey("playback_queue_generation_id")
        val SPEED = floatPreferencesKey("playback_speed")
        val WAS_PLAYING = booleanPreferencesKey("playback_was_playing")
        val UPDATED_AT = longPreferencesKey("playback_updated_at")
    }
}
