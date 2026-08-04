package com.mkread.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mkread.app.AppContainer
import com.mkread.app.BuildConfig
import com.mkread.app.feature.library.LibraryRoute
import com.mkread.app.feature.library.LibraryViewModel
import com.mkread.app.feature.library.LibraryViewModelFactory
import com.mkread.app.speech.SpikeScreen

@Composable
fun MkreadNavHost(
    container: AppContainer,
    onOpenBook: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = LIBRARY_ROUTE,
        modifier = modifier,
    ) {
        composable(LIBRARY_ROUTE) {
            val factory = remember(container) {
                LibraryViewModelFactory(
                    repository = container.repository,
                    importManager = container.importScheduler,
                )
            }
            val libraryViewModel: LibraryViewModel = viewModel(factory = factory)
            LibraryRoute(
                viewModel = libraryViewModel,
                onOpenBook = onOpenBook,
                onOpenSpeechDebug = if (BuildConfig.DEBUG) {
                    { navController.navigate(SPEECH_DEBUG_ROUTE) }
                } else {
                    null
                },
            )
        }
        if (BuildConfig.DEBUG) {
            composable(SPEECH_DEBUG_ROUTE) {
                SpikeScreen()
            }
        }
    }
}

private const val LIBRARY_ROUTE = "library"
private const val SPEECH_DEBUG_ROUTE = "debug/speech"
