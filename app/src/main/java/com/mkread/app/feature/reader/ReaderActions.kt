package com.mkread.app.feature.reader

import com.mkread.app.playback.SentenceId

sealed interface ReaderAction {
    data object NextPage : ReaderAction

    data object PreviousPage : ReaderAction

    data class GoToPage(val pageIndex: Int) : ReaderAction

    data object NextChapter : ReaderAction

    data object PreviousChapter : ReaderAction

    data class GoToChapter(val chapterId: String) : ReaderAction

    data class SelectionChanged(
        val startInclusive: Int,
        val endExclusive: Int,
    ) : ReaderAction

    data object ClearSelection : ReaderAction

    data object ReadFromSelection : ReaderAction

    data class PlaybackSentenceChanged(val sentenceId: SentenceId?) : ReaderAction

    data object OpenEditor : ReaderAction

    data class PrepareEditor(val chapterId: String) : ReaderAction

    data class EditDraft(val text: String) : ReaderAction

    data object CloseEditor : ReaderAction

    data class SaveEdit(val text: String) : ReaderAction

    data object Undo : ReaderAction

    data class LayoutChanged(val spec: PaginationSpec) : ReaderAction

    data object Retry : ReaderAction

    data object Checkpoint : ReaderAction

    data object Exit : ReaderAction
}

sealed interface ReaderEvent {
    data class ReadFromHere(
        val chapterId: String,
        val sentence: SentenceRange,
    ) : ReaderEvent

    data class OpenEditor(
        val chapterId: String,
        val text: String,
        val undoAvailable: Boolean,
    ) : ReaderEvent

    data class ShowMessage(val message: String) : ReaderEvent

    data object CloseReader : ReaderEvent
}
