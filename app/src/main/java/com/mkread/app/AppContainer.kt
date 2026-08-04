package com.mkread.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.work.WorkManager
import com.mkread.app.core.database.MkreadDatabase
import com.mkread.app.core.files.FileBookStorage
import com.mkread.app.feature.library.AndroidDocumentAccess
import com.mkread.app.feature.library.BookImportScheduler
import com.mkread.app.feature.library.ImportBookUseCase
import com.mkread.app.feature.library.ImportBookWorkerFactory
import com.mkread.app.feature.library.RoomBookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    private val context = application.applicationContext

    val database: MkreadDatabase = Room.databaseBuilder(
        context,
        MkreadDatabase::class.java,
        DATABASE_NAME,
    ).build()
    val storage = FileBookStorage(context.filesDir, context.cacheDir)
    val repository = RoomBookRepository(database, storage)
    val documentAccess = AndroidDocumentAccess(context)
    val importer = ImportBookUseCase(storage, repository)
    val importWorkerFactory = ImportBookWorkerFactory(importer, documentAccess)
    val importScheduler: BookImportScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BookImportScheduler(WorkManager.getInstance(context), documentAccess)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
