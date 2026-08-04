package com.mkread.app.feature.reader

import android.content.ClipboardManager
import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.ui.theme.MkreadTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectablePageTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun singleWordSelectionReportsChapterOffsets() {
        val harness = launch(text = "zero one two", page = PageRange(0, 5, 12))

        composeRule.runOnIdle {
            harness.view.setReaderSelection(0, 3)
        }

        assertEquals(ReaderTextRange(5, 8), harness.selection.get())
    }

    @Test
    fun crossLineChineseSelectionReportsNormalizedOffsets() {
        val text = "第一行文字\n第二行文字"
        val harness = launch(text = text, page = PageRange(0, 0, text.length))

        composeRule.runOnIdle {
            harness.view.setReaderSelection(2, 8)
        }

        assertEquals(ReaderTextRange(2, 8), harness.selection.get())
    }

    @Test
    fun selectionMayTouchBothPageBoundaries() {
        val harness = launch(text = "0123456789ABCDE", page = PageRange(0, 5, 10))

        composeRule.runOnIdle {
            harness.view.setReaderSelection(0, 5)
        }

        assertEquals(ReaderTextRange(5, 10), harness.selection.get())
    }

    @Test
    fun copyPlacesOnlySelectedPlainTextOnClipboard() {
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        val harness = launch(text = "copy only this", page = PageRange(0, 0, 14))

        composeRule.runOnIdle {
            harness.view.setReaderSelection(5, 9)
            harness.view.performReaderContextAction(android.R.id.copy)
        }

        assertEquals("only", clipboard.primaryClip?.getItemAt(0)?.coerceToText(harness.view.context).toString())
        assertTrue(harness.copied.get())
    }

    @Test
    fun readFromHereReportsChapterSelectionStart() {
        val harness = launch(text = "0123456789ABCDE", page = PageRange(0, 5, 12))

        composeRule.runOnIdle {
            harness.view.setReaderSelection(2, 5)
            harness.view.performReaderContextAction(SelectableReaderTextView.ACTION_READ_FROM_HERE)
        }

        assertEquals(ReaderTextRange(7, 10), harness.readSelection.get())
    }

    @Test
    fun editChapterReportsCurrentChapterSelection() {
        val harness = launch(text = "0123456789ABCDE", page = PageRange(0, 5, 12))

        composeRule.runOnIdle {
            harness.view.setReaderSelection(1, 4)
            harness.view.performReaderContextAction(SelectableReaderTextView.ACTION_EDIT_CHAPTER)
        }

        assertEquals(ReaderTextRange(6, 9), harness.editSelection.get())
    }

    @Test
    fun leftAndRightTapZonesReportPageCommands() {
        val harness = launch(text = "Tap page zones", page = PageRange(0, 0, 14))

        composeRule.runOnIdle {
            dispatchTap(harness.view, x = 1f)
            dispatchTap(harness.view, x = harness.view.width - 1f)
        }

        assertEquals(listOf(ReaderPageTap.Previous, ReaderPageTap.Next), harness.pageTaps)
    }

    @Test
    fun activeHighlightUpdateDoesNotChangeMeasuredDimensions() {
        val active = mutableStateOf<SentenceRange?>(null)
        val text = "First sentence. Second sentence."
        val harness = launch(
            text = text,
            page = PageRange(0, 0, text.length),
            activeSentence = active,
        )
        val before = composeRule.runOnIdle { harness.view.width to harness.view.height }

        composeRule.runOnIdle {
            active.value = SentenceRange(1, 16, text.length)
        }
        composeRule.waitForIdle()

        val after = composeRule.runOnIdle { harness.view.width to harness.view.height }
        val highlightCount = composeRule.runOnIdle {
            val spanned = harness.view.text as Spanned
            spanned.getSpans(0, spanned.length, BackgroundColorSpan::class.java).size
        }
        assertEquals(before, after)
        assertEquals(1, highlightCount)
    }

    @Test
    fun selectionStateRoundTripDoesNotReplaceNativeText() {
        val selected = mutableStateOf<ReaderTextRange?>(null)
        val text = "Keep the native selection action mode alive."
        composeRule.setContent {
            MkreadTheme {
                SelectablePageText(
                    chapterText = text,
                    pageRange = PageRange(0, 0, text.length),
                    selectedRange = selected.value,
                    activeSentenceRange = null,
                    spec = SPEC,
                    onSelectionChanged = { selected.value = it },
                    onEditChapter = {},
                    onReadFromHere = {},
                    onCopied = {},
                    onPageTap = {},
                )
            }
        }
        composeRule.waitForIdle()
        val view = findTextView()
        val originalText = composeRule.runOnIdle { view.text }

        composeRule.runOnIdle {
            view.setReaderSelection(0, 4)
        }
        composeRule.waitForIdle()

        assertSame(originalText, composeRule.runOnIdle { view.text })
    }

    private fun launch(
        text: String,
        page: PageRange,
        activeSentence: androidx.compose.runtime.State<SentenceRange?> = mutableStateOf(null),
    ): Harness {
        val selection = AtomicReference<ReaderTextRange?>()
        val editSelection = AtomicReference<ReaderTextRange?>()
        val readSelection = AtomicReference<ReaderTextRange?>()
        val copied = AtomicBoolean(false)
        val pageTaps = mutableListOf<ReaderPageTap>()
        composeRule.setContent {
            MkreadTheme {
                SelectablePageText(
                    chapterText = text,
                    pageRange = page,
                    selectedRange = null,
                    activeSentenceRange = activeSentence.value,
                    spec = SPEC,
                    onSelectionChanged = selection::set,
                    onEditChapter = editSelection::set,
                    onReadFromHere = readSelection::set,
                    onCopied = { copied.set(true) },
                    onPageTap = pageTaps::add,
                    modifier = Modifier,
                )
            }
        }
        composeRule.waitForIdle()
        val view = findTextView()
        assertTrue(view.height > 0)
        return Harness(view, selection, editSelection, readSelection, copied, pageTaps)
    }

    private fun dispatchTap(view: SelectableReaderTextView, x: Float) {
        val time = SystemClock.uptimeMillis()
        val y = view.height / 2f
        MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).also { event ->
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        MotionEvent.obtain(time, time + 16, MotionEvent.ACTION_UP, x, y, 0).also { event ->
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun findTextView(): SelectableReaderTextView {
        val view = AtomicReference<SelectableReaderTextView>()
        onView(isAssignableFrom(SelectableReaderTextView::class.java)).check { candidate, failure ->
            if (failure != null) throw failure
            view.set(candidate as SelectableReaderTextView)
        }
        return view.get()
    }

    private data class Harness(
        val view: SelectableReaderTextView,
        val selection: AtomicReference<ReaderTextRange?>,
        val editSelection: AtomicReference<ReaderTextRange?>,
        val readSelection: AtomicReference<ReaderTextRange?>,
        val copied: AtomicBoolean,
        val pageTaps: MutableList<ReaderPageTap>,
    )

    private companion object {
        val SPEC = PaginationSpec(
            widthPx = 720,
            heightPx = 900,
            densityDpi = 320,
            fontFamilyId = "sans-serif",
            fontSizeSp = 18f,
            lineSpacingMultiplier = 1.3f,
            horizontalMarginPx = 32,
        )
    }
}
