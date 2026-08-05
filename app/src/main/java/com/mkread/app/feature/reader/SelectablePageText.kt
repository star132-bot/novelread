package com.mkread.app.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.mkread.app.R

enum class ReaderPageTap {
    Previous,
    Center,
    Next,
}

@Composable
fun SelectablePageText(
    chapterText: String,
    pageRange: PageRange,
    selectedRange: ReaderTextRange?,
    activeSentenceRange: SentenceRange?,
    spec: PaginationSpec,
    onSelectionChanged: (ReaderTextRange?) -> Unit,
    onEditChapter: (ReaderTextRange) -> Unit,
    onReadFromHere: (ReaderTextRange) -> Unit,
    onCopied: () -> Unit,
    onPageTap: (ReaderPageTap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSelectionChanged = rememberUpdatedState(onSelectionChanged)
    val currentEditChapter = rememberUpdatedState(onEditChapter)
    val currentReadFromHere = rememberUpdatedState(onReadFromHere)
    val currentCopied = rememberUpdatedState(onCopied)
    val currentPageTap = rememberUpdatedState(onPageTap)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
    val highlightColor = MaterialTheme.colorScheme.secondaryContainer.toArgb()
    val editLabel = stringResource(R.string.reader_edit_chapter)
    val readLabel = stringResource(R.string.reader_read_from_here)

    AndroidView(
        factory = { context ->
            SelectableReaderTextView(context).apply {
                configure()
                selectionChangedListener = { currentSelectionChanged.value(it) }
                editChapterListener = { currentEditChapter.value(it) }
                readFromHereListener = { currentReadFromHere.value(it) }
                copiedListener = { currentCopied.value() }
                pageTapListener = { currentPageTap.value(it) }
            }
        },
        update = { view ->
            view.selectionChangedListener = { currentSelectionChanged.value(it) }
            view.editChapterListener = { currentEditChapter.value(it) }
            view.readFromHereListener = { currentReadFromHere.value(it) }
            view.copiedListener = { currentCopied.value() }
            view.pageTapListener = { currentPageTap.value(it) }
            view.bind(
                chapterText = chapterText,
                pageRange = pageRange,
                selectedRange = selectedRange,
                activeSentenceRange = activeSentenceRange,
                spec = spec,
                textColor = textColor,
                backgroundColor = backgroundColor,
                highlightColor = highlightColor,
                editLabel = editLabel,
                readLabel = readLabel,
            )
        },
        modifier = modifier,
    )
}

class SelectableReaderTextView(context: Context) : TextView(context) {
    var selectionChangedListener: (ReaderTextRange?) -> Unit = {}
    var editChapterListener: (ReaderTextRange) -> Unit = {}
    var readFromHereListener: (ReaderTextRange) -> Unit = {}
    var copiedListener: () -> Unit = {}
    var pageTapListener: (ReaderPageTap) -> Unit = {}

    private var pageStartOffset: Int = 0
    private var suppressSelectionCallback = false
    private var editLabel: String = ""
    private var readLabel: String = ""
    private var renderedPageText: String? = null
    private var renderedHighlightStart = NO_HIGHLIGHT
    private var renderedHighlightEnd = NO_HIGHLIGHT
    private var renderedHighlightColor = 0
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (hasReaderSelection()) return false
                val zone = when {
                    event.x < width * PREVIOUS_TAP_FRACTION -> ReaderPageTap.Previous
                    event.x > width * NEXT_TAP_FRACTION -> ReaderPageTap.Next
                    else -> ReaderPageTap.Center
                }
                performClick()
                pageTapListener(zone)
                return true
            }
        },
    )

    fun configure() {
        setTextIsSelectable(true)
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        includeFontPadding = true
        breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
        gravity = Gravity.TOP or Gravity.START
        letterSpacing = 0f
        customSelectionActionModeCallback = ReaderSelectionActionModeCallback()
    }

    fun bind(
        chapterText: String,
        pageRange: PageRange,
        selectedRange: ReaderTextRange?,
        activeSentenceRange: SentenceRange?,
        spec: PaginationSpec,
        textColor: Int,
        backgroundColor: Int,
        highlightColor: Int,
        editLabel: String,
        readLabel: String,
    ) {
        require(pageRange.start <= chapterText.length && pageRange.endExclusive <= chapterText.length) {
            "Page range must remain inside chapter text"
        }
        pageStartOffset = pageRange.start
        this.editLabel = editLabel
        this.readLabel = readLabel
        setTextColor(textColor)
        setBackgroundColor(backgroundColor)
        setPadding(spec.horizontalMarginPx, 0, spec.horizontalMarginPx, 0)
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            spec.fontSizeSp * spec.fontScale * (spec.densityDpi / BASE_DENSITY_DPI),
        )
        typeface = Typeface.create(spec.fontFamilyId, Typeface.NORMAL)
        setLineSpacing(0f, spec.lineSpacingMultiplier)

        val localSelection = selectedRange?.intersection(pageRange)
        val active = activeSentenceRange?.intersection(pageRange)
        val activeStart = active?.let { it.startInclusive - pageRange.start } ?: NO_HIGHLIGHT
        val activeEnd = active?.let { it.endExclusive - pageRange.start } ?: NO_HIGHLIGHT
        val pageText = chapterText.substring(pageRange.start, pageRange.endExclusive)
        val replaceText = renderedPageText != pageText ||
            renderedHighlightStart != activeStart ||
            renderedHighlightEnd != activeEnd ||
            (active != null && renderedHighlightColor != highlightColor)

        suppressSelectionCallback = true
        if (replaceText) {
            val spannable = SpannableString(pageText)
            if (active != null) {
                spannable.setSpan(
                    BackgroundColorSpan(highlightColor),
                    activeStart,
                    activeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            setText(spannable, BufferType.SPANNABLE)
            renderedPageText = pageText
            renderedHighlightStart = activeStart
            renderedHighlightEnd = activeEnd
            renderedHighlightColor = highlightColor
        }
        synchronizeSelection(localSelection, pageRange.start)
        suppressSelectionCallback = false
    }

    private fun synchronizeSelection(selection: ReaderTextRange?, pageStart: Int) {
        val current = selectedChapterRange()
        if (selection == current) return
        if (selection != null) {
            setSelectionInternal(
                selection.startInclusive - pageStart,
                selection.endExclusive - pageStart,
            )
        } else {
            (text as? Spannable)?.let(Selection::removeSelection)
        }
    }

    fun setReaderSelection(localStart: Int, localEndExclusive: Int) {
        setSelectionInternal(localStart, localEndExclusive)
        dispatchSelectionChanged()
    }

    fun performReaderContextAction(actionId: Int): Boolean = when (actionId) {
        android.R.id.copy -> copySelection()
        ACTION_EDIT_CHAPTER -> selectedChapterRange()?.let {
            editChapterListener(it)
            true
        } ?: false
        ACTION_READ_FROM_HERE -> selectedChapterRange()?.let {
            readFromHereListener(it)
            true
        } ?: false
        else -> onTextContextMenuItem(actionId)
    }

    override fun onSelectionChanged(selectionStart: Int, selectionEnd: Int) {
        super.onSelectionChanged(selectionStart, selectionEnd)
        if (!suppressSelectionCallback) dispatchSelectionChanged()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val textHandled = super.onTouchEvent(event)
        val tapHandled = gestureDetector.onTouchEvent(event)
        return textHandled || tapHandled
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun setSelectionInternal(localStart: Int, localEndExclusive: Int) {
        val spannable = text as? Spannable ?: return
        val start = minOf(localStart, localEndExclusive).coerceIn(0, spannable.length)
        val end = maxOf(localStart, localEndExclusive).coerceIn(0, spannable.length)
        if (end > start) Selection.setSelection(spannable, start, end)
    }

    private fun dispatchSelectionChanged() {
        selectionChangedListener(selectedChapterRange())
    }

    private fun selectedChapterRange(): ReaderTextRange? {
        val start = selectionStart
        val end = selectionEnd
        if (start < 0 || end < 0 || start == end) return null
        return ReaderTextRange(
            startInclusive = pageStartOffset + minOf(start, end),
            endExclusive = pageStartOffset + maxOf(start, end),
        )
    }

    private fun hasReaderSelection(): Boolean = selectedChapterRange() != null

    private fun copySelection(): Boolean {
        val localStart = minOf(selectionStart, selectionEnd)
        val localEnd = maxOf(selectionStart, selectionEnd)
        if (localStart < 0 || localEnd <= localStart || localEnd > text.length) return false
        val selectedText = text.subSequence(localStart, localEnd).toString()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("MKread", selectedText))
        copiedListener()
        return true
    }

    private fun ReaderTextRange.intersection(page: PageRange): ReaderTextRange? {
        val start = maxOf(startInclusive, page.start)
        val end = minOf(endExclusive, page.endExclusive)
        return if (end > start) ReaderTextRange(start, end) else null
    }

    private fun SentenceRange.intersection(page: PageRange): ReaderTextRange? {
        val start = maxOf(startInclusive, page.start)
        val end = minOf(endExclusive, page.endExclusive)
        return if (end > start) ReaderTextRange(start, end) else null
    }

    private inner class ReaderSelectionActionModeCallback : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, ACTION_EDIT_CHAPTER, EDIT_ORDER, editLabel)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(Menu.NONE, ACTION_READ_FROM_HERE, READ_ORDER, readLabel)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val handled = performReaderContextAction(item.itemId)
            if (handled && item.itemId in FINISHING_ACTIONS) mode.finish()
            return handled
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            super.onGetContentRect(mode, view, outRect)
        }
    }

    companion object {
        const val ACTION_EDIT_CHAPTER = 0x4D4B_0001
        const val ACTION_READ_FROM_HERE = 0x4D4B_0002
        private const val EDIT_ORDER = 100
        private const val READ_ORDER = 101
        private const val PREVIOUS_TAP_FRACTION = 0.28f
        private const val NEXT_TAP_FRACTION = 0.72f
        private const val BASE_DENSITY_DPI = 160f
        private const val NO_HIGHLIGHT = -1
        private val FINISHING_ACTIONS = setOf(
            android.R.id.copy,
            ACTION_EDIT_CHAPTER,
            ACTION_READ_FROM_HERE,
        )
    }
}
