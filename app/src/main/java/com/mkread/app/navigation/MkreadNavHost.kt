package com.mkread.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.mkread.app.feature.reader.ChapterEditorRoute
import com.mkread.app.feature.reader.ReaderRoute
import com.mkread.app.feature.reader.ReaderViewModel
import com.mkread.app.feature.reader.ReaderViewModelFactory
import com.mkread.app.speech.SpikeScreen

@Composable
fun MkreadNavHost(
    container: AppContainer,
    onOpenBook: ((String) -> Unit)? = null,
    nightModeEnabled: Boolean = false,
    onToggleNightMode: (() -> Unit)? = null,
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
                readerViewModelFactory(container, bookId)
            }
            val readerViewModel: ReaderViewModel = viewModel(factory = factory)
            ReaderRoute(
                viewModel = readerViewModel,
                narrationController = container.narrationController,
                nightModeEnabled = nightModeEnabled,
                onToggleNightMode = onToggleNightMode,
                onBack = navController::popBackStack,
                onOpenEditor = { chapterId ->
                    navController.navigate(chapterEditorRoute(bookId, chapterId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            route = CHAPTER_EDITOR_ROUTE,
            arguments = listOf(
                navArgument(BOOK_ID_ARGUMENT) { type = NavType.StringType },
                navArgument(CHAPTER_ID_ARGUMENT) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val bookId = requireNotNull(backStackEntry.arguments?.getString(BOOK_ID_ARGUMENT))
            val chapterId = requireNotNull(backStackEntry.arguments?.getString(CHAPTER_ID_ARGUMENT))
            val readerEntry = remember(backStackEntry, navController, bookId) {
                navController.getBackStackEntry(readerRoute(bookId))
            }
            val factory = remember(container, bookId) {
                readerViewModelFactory(container, bookId)
            }
            val readerViewModel: ReaderViewModel = viewModel(
                viewModelStoreOwner = readerEntry,
                factory = factory,
            )
            ChapterEditorRoute(
                viewModel = readerViewModel,
                chapterId = chapterId,
                onClose = navController::popBackStack,
            )
        }
        if (BuildConfig.DEBUG) {
            composable(SPEECH_DEBUG_ROUTE) {
                SpikeScreen()
            }
        }
    }
}

private fun readerRoute(bookId: String): String = "reader/${Uri.encode(bookId)}"

private fun chapterEditorRoute(bookId: String, chapterId: String): String =
    "editor/${Uri.encode(bookId)}/${Uri.encode(chapterId)}"

private fun readerViewModelFactory(container: AppContainer, bookId: String) = ReaderViewModelFactory(
    bookId = bookId,
    bookSource = container.readerBookSource,
    contentRepository = container.chapterContentRepository,
    positionRepository = container.readingPositionRepository,
    paginationEngine = container.paginationEngine,
    chapterEditor = container.chapterEditor,
    initialSpec = container.initialReaderSpec,
)

private const val LIBRARY_ROUTE = "library"
private const val BOOK_ID_ARGUMENT = "bookId"
private const val CHAPTER_ID_ARGUMENT = "chapterId"
private const val READER_ROUTE = "reader/{$BOOK_ID_ARGUMENT}"
private const val CHAPTER_EDITOR_ROUTE = "editor/{$BOOK_ID_ARGUMENT}/{$CHAPTER_ID_ARGUMENT}"
private const val SPEECH_DEBUG_ROUTE = "debug/speech"
