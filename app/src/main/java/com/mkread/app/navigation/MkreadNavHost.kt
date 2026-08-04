package com.mkread.app.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mkread.app.AppContainer
import com.mkread.app.BuildConfig
import com.mkread.app.feature.library.LibraryRoute
import com.mkread.app.feature.library.LibraryViewModel
import com.mkread.app.feature.library.LibraryViewModelFactory
import com.mkread.app.feature.reader.ReaderUiState
import com.mkread.app.feature.reader.ReaderViewModel
import com.mkread.app.feature.reader.ReaderViewModelFactory
import com.mkread.app.speech.SpikeScreen

@Composable
fun MkreadNavHost(
    container: AppContainer,
    onOpenBook: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val openBook: (String) -> Unit = onOpenBook ?: { bookId ->
        navController.navigate(readerRoute(bookId))
    }
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
                onOpenBook = openBook,
                onOpenSpeechDebug = if (BuildConfig.DEBUG) {
                    { navController.navigate(SPEECH_DEBUG_ROUTE) }
                } else {
                    null
                },
            )
        }
        composable(
            route = READER_ROUTE,
            arguments = listOf(navArgument(BOOK_ID_ARGUMENT) { type = NavType.StringType }),
        ) { backStackEntry ->
            val bookId = requireNotNull(backStackEntry.arguments?.getString(BOOK_ID_ARGUMENT))
            val factory = remember(container, bookId) {
                ReaderViewModelFactory(
                    bookId = bookId,
                    bookSource = container.readerBookSource,
                    contentRepository = container.chapterContentRepository,
                    positionRepository = container.readingPositionRepository,
                    paginationEngine = container.paginationEngine,
                    chapterEditor = container.chapterEditor,
                    initialSpec = container.initialReaderSpec,
                )
            }
            val readerViewModel: ReaderViewModel = viewModel(factory = factory)
            ReaderStatePlaceholder(readerViewModel)
        }
        if (BuildConfig.DEBUG) {
            composable(SPEECH_DEBUG_ROUTE) {
                SpikeScreen()
            }
        }
    }
}

@Composable
private fun ReaderStatePlaceholder(viewModel: ReaderViewModel) {
    val state by viewModel.uiState.collectAsState()
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            ReaderUiState.Loading -> CircularProgressIndicator()
            is ReaderUiState.Error -> Text(current.message)
            is ReaderUiState.Paginating -> Text(current.chapter.title)
            is ReaderUiState.Ready -> Text(current.chapter.title)
        }
    }
}

private fun readerRoute(bookId: String): String = "reader/${Uri.encode(bookId)}"

private const val LIBRARY_ROUTE = "library"
private const val BOOK_ID_ARGUMENT = "bookId"
private const val READER_ROUTE = "reader/{$BOOK_ID_ARGUMENT}"
private const val SPEECH_DEBUG_ROUTE = "debug/speech"
