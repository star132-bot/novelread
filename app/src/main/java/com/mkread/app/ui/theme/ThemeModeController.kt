package com.mkread.app.ui.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

class ThemeModeController(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    val mode: StateFlow<ThemeMode> = dataStore.data
        .map { preferences -> preferences[MODE_KEY].toThemeMode() }
        .stateIn(scope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun set(mode: ThemeMode) {
        scope.launch {
            dataStore.edit { preferences -> preferences[MODE_KEY] = mode.name }
        }
    }

    fun toggleNightMode() {
        set(if (mode.value == ThemeMode.DARK) ThemeMode.SYSTEM else ThemeMode.DARK)
    }

    private companion object {
        val MODE_KEY = stringPreferencesKey("theme_mode")
    }
}

private fun String?.toThemeMode(): ThemeMode = runCatching {
    ThemeMode.valueOf(this ?: ThemeMode.SYSTEM.name)
}.getOrDefault(ThemeMode.SYSTEM)
