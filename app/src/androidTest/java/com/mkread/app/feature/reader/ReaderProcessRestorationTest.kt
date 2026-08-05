package com.mkread.app.feature.reader

import android.graphics.Rect
import android.os.Process
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.mkread.app.MainActivity
import com.mkread.app.MkreadApplication
import com.mkread.app.feature.library.ImportBookWorker
import com.mkread.app.feature.library.ImportTestContentProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderProcessRestorationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: MkreadApplication
        get() = composeRule.activity.application as MkreadApplication

    @Test
    fun seedSemanticPositionForHostRestart() {
        assumeHostStage(STAGE_SEED)

        val sourceText = processRestoreSource()
        val sourceBytes = sourceText.toByteArray(Charsets.UTF_8)
        val sourceSha = sourceBytes.sha256()
        var published = false
        try {
            ImportTestContentProvider.clear()
            cleanupExisting(sourceSha)
            stateFile().delete()

            val sourceUri = ImportTestContentProvider.register("reader-process-restore", sourceBytes)
            val importResult = runBlocking {
                TestListenableWorkerBuilder.from(application, ImportBookWorker::class.java)
                    .setWorkerFactory(application.container.importWorkerFactory)
                    .setInputData(
                        ImportBookWorker.inputData(
                            uri = sourceUri,
                            displayName = "$BOOK_TITLE.txt",
                            mimeType = "text/plain",
                            permissionPersisted = false,
                        ),
                    )
                    .build()
                    .doWork()
            } as ListenableWorker.Result.Success
            assertEquals(
                ImportBookWorker.STATUS_SUCCESS,
                importResult.outputData.getString(ImportBookWorker.KEY_STATUS),
            )
            val bookId = requireNotNull(
                importResult.outputData.getString(ImportBookWorker.KEY_BOOK_ID),
            )
            val chapter = runBlocking {
                application.container.database.chapterDao().getByBookId(bookId).single()
            }
            val bookRoot = File(application.filesDir, "books/$bookId")
            val chapterText = File(bookRoot, chapter.relativePath).readText(Charsets.UTF_8)
            val sourceFile = File(bookRoot, "source.txt")
            val expectedOffset = chapterText.indexOf(SEMANTIC_MARKER)
            assertTrue(expectedOffset > 0)
            assertEquals(sourceSha, sourceFile.readBytes().sha256())
            val sentence = requireNotNull(
                SentenceSegmenter().segment(chapterText).nearestBoundary(expectedOffset),
            )
            runBlocking {
                application.container.readingPositionRepository.checkpoint(
                    ReadingPosition(
                        bookId = bookId,
                        chapterId = chapter.id,
                        characterOffset = sentence.startInclusive,
                        pageIndex = 999,
                        sentenceIndex = sentence.index,
                        updatedAt = 0L,
                    ),
                    chapterLength = chapterText.length,
                )
            }

            waitForText(BOOK_TITLE)
            composeRule.onNodeWithText(BOOK_TITLE).performClick()
            val visibleRange = waitForReaderPageContainingOffset(chapterText, sentence.startInclusive)
            val properties = Properties().apply {
                setProperty(KEY_PID, Process.myPid().toString())
                setProperty(KEY_BOOK_ID, bookId)
                setProperty(KEY_CHAPTER_ID, chapter.id)
                setProperty(KEY_OFFSET, sentence.startInclusive.toString())
                setProperty(KEY_SOURCE_SHA, sourceSha)
                setProperty(KEY_PAGE_START, visibleRange.first.toString())
                setProperty(KEY_PAGE_END, (visibleRange.last + 1).toString())
                setProperty(KEY_FONT_SCALE, application.resources.configuration.fontScale.toString())
            }
            writeState(properties)
            Log.i(
                LOG_TAG,
                "process-restore-seed pid=${Process.myPid()} offset=${sentence.startInclusive} " +
                    "pageStart=${visibleRange.first} pageEnd=${visibleRange.last + 1} " +
                    "fontScale=${application.resources.configuration.fontScale} sourceSha=$sourceSha",
            )
            published = true
        } finally {
            if (!published) cleanupGateState(sourceSha)
        }
    }

    @Test
    fun verifySemanticPositionAfterHostRestart() {
        assumeHostStage(STAGE_VERIFY)

        val deterministicSourceSha = processRestoreSource().toByteArray(Charsets.UTF_8).sha256()
        try {
            val properties = readState()
            val bookId = requireNotNull(properties.getProperty(KEY_BOOK_ID))
            val seedPid = requireNotNull(properties.getProperty(KEY_PID)).toInt()
            val chapterId = requireNotNull(properties.getProperty(KEY_CHAPTER_ID))
            val expectedOffset = requireNotNull(properties.getProperty(KEY_OFFSET)).toInt()
            val expectedSourceSha = requireNotNull(properties.getProperty(KEY_SOURCE_SHA))
            val baselineRange =
                requireNotNull(properties.getProperty(KEY_PAGE_START)).toInt() until
                    requireNotNull(properties.getProperty(KEY_PAGE_END)).toInt()
            val baselineFontScale = requireNotNull(properties.getProperty(KEY_FONT_SCALE)).toFloat()
            val restoredFontScale = application.resources.configuration.fontScale

            assertNotEquals("Host gate must start a new target process", seedPid, Process.myPid())
            assertTrue(
                "Host gate must change font scale between stages",
                abs(restoredFontScale - baselineFontScale) >= MIN_FONT_SCALE_DELTA,
            )
            assertNotNull(runBlocking { application.container.database.bookDao().getById(bookId) })
            val position = runBlocking {
                application.container.database.readingPositionDao().getByBookId(bookId)
            }
            assertEquals(chapterId, position?.chapterId)
            assertEquals(expectedOffset, position?.characterOffset)

            val chapter = runBlocking {
                application.container.database.chapterDao().getById(chapterId)
            }
            val chapterText = File(
                application.filesDir,
                "books/$bookId/${requireNotNull(chapter).relativePath}",
            ).readText(Charsets.UTF_8)
            assertEquals(
                expectedSourceSha,
                File(application.filesDir, "books/$bookId/source.txt").readBytes().sha256(),
            )
            waitForText(BOOK_TITLE)
            composeRule.onNodeWithText(BOOK_TITLE).performClick()
            val restoredRange = waitForReaderPageContainingOffset(chapterText, expectedOffset)

            assertTrue(expectedOffset in restoredRange)
            assertNotEquals(
                "A changed font scale must produce a different visible page boundary",
                baselineRange,
                restoredRange,
            )
            Log.i(
                LOG_TAG,
                "process-restore-verify seedPid=$seedPid restoredPid=${Process.myPid()} " +
                    "offset=$expectedOffset baselinePage=${baselineRange.first}..${baselineRange.last + 1} " +
                    "restoredPage=${restoredRange.first}..${restoredRange.last + 1} " +
                    "baselineFontScale=$baselineFontScale restoredFontScale=$restoredFontScale " +
                    "sourceSha=$expectedSourceSha",
            )
        } finally {
            cleanupGateState(deterministicSourceSha)
        }
    }

    private fun cleanupExisting(sourceSha: String) {
        runBlocking {
            application.container.database.bookDao()
                .getBySourceSha256(sourceSha)
                ?.let { application.container.repository.removeBook(it.id) }
        }
    }

    private fun cleanupGateState(sourceSha: String) {
        try {
            cleanupExisting(sourceSha)
        } finally {
            stateFile().delete()
            ImportTestContentProvider.clear()
        }
    }

    private fun assumeHostStage(expectedStage: String) {
        val actualStage = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_PROCESS_RESTORATION_STAGE)
        Assume.assumeTrue(
            "Host-only process-restoration stage '$expectedStage' requires " +
                "$ARGUMENT_PROCESS_RESTORATION_STAGE=$expectedStage; " +
                "was ${actualStage ?: "<unset>"}",
            actualStage == expectedStage,
        )
    }

    private fun waitForText(expected: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(expected, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForReaderPageContainingOffset(chapterText: String, expectedOffset: Int): IntRange {
        var visibleStart = -1
        var visibleLength = 0
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            val visibleText = visibleReaderTextViewOrNull()?.text?.toString().orEmpty()
            visibleStart = chapterText.indexOf(visibleText)
            visibleLength = visibleText.length
            visibleText.isNotEmpty() &&
                expectedOffset in visibleStart until (visibleStart + visibleLength)
        }
        return visibleStart until (visibleStart + visibleLength)
    }

    private fun visibleReaderTextViewOrNull(): SelectableReaderTextView? = composeRule.runOnIdle {
        composeRule.activity.window.decorView
            .descendants()
            .filterIsInstance<SelectableReaderTextView>()
            .filter { it.isShown && it.text.isNotEmpty() }
            .maxByOrNull { view ->
                val bounds = Rect()
                if (view.getGlobalVisibleRect(bounds)) bounds.width() * bounds.height() else 0
            }
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            repeat(childCount) { index -> yieldAll(getChildAt(index).descendants()) }
        }
    }

    private fun writeState(properties: Properties) {
        val destination = stateFile()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            properties.store(output, "MKread process restoration gate")
            output.fd.sync()
        }
        check(!destination.exists() || destination.delete()) { "Cannot replace process gate state" }
        check(temporary.renameTo(destination)) { "Cannot publish process gate state" }
    }

    private fun readState(): Properties = Properties().apply {
        FileInputStream(stateFile()).use(::load)
    }

    private fun stateFile() = File(application.filesDir, STATE_FILE_NAME)

    private fun processRestoreSource(): String = List(SOURCE_LINE_COUNT) { index ->
        if (index == MARKER_LINE) {
            "$SEMANTIC_MARKER remains the exact semantic sentence after process recreation."
        } else {
            "%03d Process restoration line keeps every page boundary deterministic and reviewable."
                .format(index)
        }
    }.joinToString("\n")

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BOOK_TITLE = "MKread process restoration gate"
        const val SEMANTIC_MARKER = "SEMANTIC_PROCESS_RESTORE_MARKER"
        const val SOURCE_LINE_COUNT = 60
        const val MARKER_LINE = 36
        const val UI_TIMEOUT_MILLIS = 60_000L
        const val MIN_FONT_SCALE_DELTA = 0.2f
        const val STATE_FILE_NAME = "reader-process-restoration.properties"
        const val LOG_TAG = "MKreadReaderGate"
        const val ARGUMENT_PROCESS_RESTORATION_STAGE = "mkread.processRestorationStage"
        const val STAGE_SEED = "seed"
        const val STAGE_VERIFY = "verify"
        const val KEY_PID = "pid"
        const val KEY_BOOK_ID = "bookId"
        const val KEY_CHAPTER_ID = "chapterId"
        const val KEY_OFFSET = "offset"
        const val KEY_SOURCE_SHA = "sourceSha"
        const val KEY_PAGE_START = "pageStart"
        const val KEY_PAGE_END = "pageEnd"
        const val KEY_FONT_SCALE = "fontScale"
    }
}
