package com.mkread.app.playback

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCheckpointStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checkpointRoundTripsEveryRequiredFieldAndCanBeCleared() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(temporaryFolder.root, "mkread.preferences_pb") },
        )
        val store = DataStorePlaybackCheckpointStore(dataStore)
        val checkpoint = PlaybackCheckpoint(
            sentenceId = SentenceId("book", "chapter", index = 4, start = 21, end = 37),
            queueGenerationId = 99L,
            speed = 1.35f,
            wasPlaying = true,
            updatedAtEpochMillis = 1_234_567L,
        )

        store.save(checkpoint)
        assertEquals(checkpoint, store.read())

        store.clear()
        assertNull(store.read())
    }

    @Test
    fun applicationDataStoreContractUsesTheRequiredFileAndNamespacedKeys() = runTest {
        assertEquals("mkread.preferences_pb", MKREAD_PREFERENCES_FILE_NAME)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(temporaryFolder.root, MKREAD_PREFERENCES_FILE_NAME) },
        )
        val store = DataStorePlaybackCheckpointStore(dataStore)
        store.save(
            PlaybackCheckpoint(
                sentenceId = SentenceId("book", "chapter", 0, 3, 9),
                queueGenerationId = 7L,
                speed = 0.5f,
                wasPlaying = false,
                updatedAtEpochMillis = 42L,
            ),
        )

        val keys = dataStore.data.first().asMap().keys
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.all { key -> key.name.startsWith("playback_") })
    }

    @Test
    fun invalidSpeedOrIncompleteCheckpointRestoresAsAbsent() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(temporaryFolder.root, "invalid.preferences_pb") },
        )
        val store = DataStorePlaybackCheckpointStore(dataStore)

        dataStore.edit { preferences ->
            preferences[floatPreferencesKey("playback_speed")] = 2.01f
            preferences[stringPreferencesKey("playback_book_id")] = "book"
        }

        assertNull(store.read())
    }

    @Test
    fun checkpointRejectsSpeedOutsideSupportedRange() {
        listOf(0.49f, 2.01f, Float.NaN, Float.POSITIVE_INFINITY).forEach { speed ->
            val failure = runCatching {
                PlaybackCheckpoint(
                    sentenceId = SentenceId("book", "chapter", 0, 0, 1),
                    queueGenerationId = 1L,
                    speed = speed,
                    wasPlaying = false,
                    updatedAtEpochMillis = 1L,
                )
            }.exceptionOrNull()

            requireNotNull(failure)
        }
    }
}
