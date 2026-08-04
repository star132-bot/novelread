package com.mkread.app

import android.app.Application
import androidx.work.Configuration

class MkreadApplication : Application(), Configuration.Provider {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.importWorkerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        container.reconcileLibraryOnStartup()
    }
}
