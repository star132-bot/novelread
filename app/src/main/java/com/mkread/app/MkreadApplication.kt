package com.mkread.app

import android.app.Application

class MkreadApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.NONE) {
        AppContainer()
    }
}
