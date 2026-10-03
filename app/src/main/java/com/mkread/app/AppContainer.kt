package com.mkread.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.DelegatingWorkerFactory
import com.mkread.app.cloud.CloudLibrary
import com.mkread.app.cloud.CloudSyncWorkerFactory
import com.mkread.app.cloud.CloudVoicePackSource
import com.mkread.app.update.AppUpdater
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.files.FileBookStorage
import com.mkread.app.feature.library.AndroidDocumentAccess
import com.mkread.app.feature.library.AssembleBookFragmentsUseCase
import com.mkread.app.feature.library.BookImportScheduler
import com.mkread.app.feature.library.ImportBookUseCase
import com.mkread.app.feature.library.ImportBookWorkerFactory
import com.mkread.app.feature.library.RoomBookRepository
import com.mkread.app.feature.reader.AndroidPaginationEngine
import com.mkread.app.feature.reader.ChapterMetadataSource
import com.mkread.app.feature.reader.FileChapterContentRepository
import com.mkread.app.feature.reader.FileChapterEditor
import com.mkread.app.feature.reader.FilePaginationCache
import com.mkread.app.feature.reader.OfflineReaderNarrationController
import com.mkread.app.feature.reader.PaginationDerivedDataInvalidator
import com.mkread.app.feature.reader.PaginationSpec
import com.mkread.app.feature.reader.ReaderBookSource
import com.mkread.app.feature.reader.RoomChapterEditMetadata
import com.mkread.app.feature.reader.RoomReadingPositionRepository
import com.mkread.app.playback.DataStorePlaybackCheckpointStore
import com.mkread.app.playback.MKREAD_PREFERENCES_FILE_NAME
import com.mkread.app.speech.NarrationVoiceSettings
import com.mkread.app.speech.RoomAudioCacheRepository
import com.mkread.app.ui.theme.ThemeModeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(
    application: Application,
    databaseName: String = DATABASE_NAME,
) {
    private val context = application.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferencesDataStore = context.mkreadPreferencesDataStore
    val playbackCheckpointStore = DataStorePlaybackCheckpointStore(preferencesDataStore)
    val themeModeController = ThemeModeController(preferencesDataStore, applicationScope)

    val database: MkreadDatabase = Room.databaseBuilder(
        context,
        MkreadDatabase::class.java,
        databaseName,
    ).addMigrations(
        MkreadDatabase.MIGRATION_1_2,
        MkreadDatabase.MIGRATION_2_3,
        MkreadDatabase.MIGRATION_3_4,
        MkreadDatabase.MIGRATION_4_5,
    ).build()
    val storage = FileBookStorage(context.filesDir, context.cacheDir)
    val audioCacheRepository = RoomAudioCacheRepository(
        dao = database.audioCacheDao(),
        cacheDir = context.cacheDir,
    )
    val repository = RoomBookRepository(database, storage)
    val documentAccess = AndroidDocumentAccess(context)
    val importer = ImportBookUseCase(storage, repository)
    val cloudLibrary: CloudLibrary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CloudLibrary(
            context = context,
            dataStore = preferencesDataStore,
            importer = importer,
            repository = repository,
            folderForCloudBooks = {
                runCatching { repository.getOrCreateFolder(CloudLibrary.CLOUD_FOLDER_NAME).id }.getOrNull()
            },
        )
    }
    val appUpdater = AppUpdater(
        context = context,
        dataStore = preferencesDataStore,
        serverUrl = { cloudLibrary.refreshSettings().serverUrl },
        scope = applicationScope,
    )
    val workerFactory = DelegatingWorkerFactory().apply {
        addFactory(ImportBookWorkerFactory(importer, documentAccess))
        addFactory(CloudSyncWorkerFactory { cloudLibrary })
    }
    val importScheduler: BookImportScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BookImportScheduler(WorkManager.getInstance(context), documentAccess)
    }
    private val chapterMetadataSource = object : ChapterMetadataSource {
        override suspend fun getChapter(chapterId: String) = database.chapterDao().getById(chapterId)

        override suspend fun getChapters(bookId: String) = database.chapterDao().getByBookId(bookId)
    }
    val readerBookSource = object : ReaderBookSource {
        override suspend fun getBook(bookId: String) = database.bookDao().getById(bookId)

        override suspend fun markOpened(bookId: String) = repository.markOpened(bookId)
    }
    val chapterContentRepository = FileChapterContentRepository(context.filesDir, chapterMetadataSource)
    val fragmentAssembler = AssembleBookFragmentsUseCase(
        storage = storage,
        repository = repository,
        contentRepository = chapterContentRepository,
    )
    val narrationController = OfflineReaderNarrationController(
        context = context,
        cache = audioCacheRepository,
        chapterContentRepository = chapterContentRepository,
        voiceSettings = NarrationVoiceSettings(preferencesDataStore),
        voicePacks = CloudVoicePackSource(cloudLibrary),
        scope = applicationScope,
    ).also { it.prepareSelectedVoice() }
    val readingPositionRepository = RoomReadingPositionRepository(database)
    val paginationCache = FilePaginationCache(context.cacheDir)
    val paginationEngine = AndroidPaginationEngine(paginationCache)
    val chapterEditor = FileChapterEditor(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        metadata = RoomChapterEditMetadata(database),
        invalidator = PaginationDerivedDataInvalidator(paginationCache),
    )
    val initialReaderSpec: PaginationSpec = context.resources.displayMetrics.let { metrics ->
        PaginationSpec(
            widthPx = metrics.widthPixels.coerceAtLeast(1),
            heightPx = (metrics.heightPixels * 3 / 4).coerceAtLeast(1),
            densityDpi = metrics.densityDpi.coerceAtLeast(1),
            fontFamilyId = "sans-serif",
            fontSizeSp = 18f,
            lineSpacingMultiplier = 1.4f,
            horizontalMarginPx = (16f * metrics.density).toInt().coerceAtLeast(0),
            fontScale = context.resources.configuration.fontScale,
        )
    }
    fun reconcileLibraryOnStartup() {
        applicationScope.launch {
            try {
                repository.reconcile()
            } catch (failure: Exception) {
                Log.e(LOG_TAG, "Library reconciliation failed", failure)
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "mkread.db"
        const val LOG_TAG = "MKread.Library"
    }
}

private val Context.mkreadPreferencesDataStore by preferencesDataStore(
    name = MKREAD_PREFERENCES_FILE_NAME.removeSuffix(".preferences_pb"),
)
