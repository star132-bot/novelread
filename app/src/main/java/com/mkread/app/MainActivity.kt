package com.mkread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.mkread.app.update.UpdateDialog
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mkread.app.navigation.MkreadNavHost
import com.mkread.app.ui.theme.MkreadTheme
import com.mkread.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Continue an update after the user allowed "install unknown apps".
        (application as MkreadApplication).container.appUpdater.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MkreadApplication).container
        if (savedInstanceState == null) container.appUpdater.checkAutomatically()
        setContent {
            val themeMode by container.themeModeController.mode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MkreadTheme(
                darkTheme = darkTheme,
            ) {
                MkreadNavHost(
                    container = container,
                    nightModeEnabled = themeMode == ThemeMode.DARK,
                    onToggleNightMode = container.themeModeController::toggleNightMode,
                )
                UpdateDialog(container.appUpdater)
            }
        }
    }
}
