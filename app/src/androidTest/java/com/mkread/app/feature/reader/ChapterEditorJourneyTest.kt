package com.mkread.app.feature.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.core.database.ChapterEntity
import com.mkread.app.ui.theme.MkreadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterEditorJourneyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chapterListMarksCurrentChapterAndNavigatesByStableId() {
        val chapters = chapters()
        var selectedId: String? = null
        composeRule.setContent {
            MkreadTheme {
                ChapterListSheet(
                    chapters = chapters,
                    currentChapterId = "chapter-2",
                    onSelectChapter = { selectedId = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("第二章 清晨").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("当前章节：第二章 清晨").assertIsDisplayed()
        composeRule.onNodeWithText("第二章 清晨").performClick()

        assertEquals("chapter-2", selectedId)
    }

    @Test
    fun dirtyEditorRequiresDiscardConfirmationAndKeepsDraftUntilConfirmed() {
        var state by mutableStateOf(editorState("初始内容"))
        var closed = false
        composeRule.setContent {
            MkreadTheme {
                ChapterEditorScreen(
                    state = state,
                    onTextChanged = { state = state.copy(draftText = it) },
                    onSave = {},
                    onUndo = {},
                    onClose = { closed = true },
                )
            }
        }

        composeRule.onNodeWithTag("chapter-editor-text").performTextInput(" 新增")
        composeRule.onNodeWithContentDescription("关闭编辑器").performClick()
        composeRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        assertTrue(state.dirty)
        assertTrue(!closed)
    }

    @Test
    fun blankSaveShowsActionableErrorAndRetainsDraft() {
        var state by mutableStateOf(editorState("初始内容").copy(draftText = ""))
        var saves = 0
        composeRule.setContent {
            MkreadTheme {
                ChapterEditorScreen(
                    state = state,
                    onTextChanged = { state = state.copy(draftText = it) },
                    onSave = { saves += 1 },
                    onUndo = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("保存章节").performClick()
        composeRule.onNodeWithText("章节内容不能为空").assertIsDisplayed()
        assertEquals("", state.draftText)
        assertEquals(0, saves)
    }

    @Test
    fun overLimitSaveShowsLimitAndDoesNotStartTransaction() {
        val state = editorState("初始内容").copy(draftText = "12345")
        var saves = 0
        composeRule.setContent {
            MkreadTheme {
                ChapterEditorScreen(
                    state = state,
                    onTextChanged = {},
                    onSave = { saves += 1 },
                    onUndo = {},
                    onClose = {},
                    maxCharacters = 4,
                )
            }
        }

        composeRule.onNodeWithContentDescription("保存章节").performClick()
        composeRule.onNodeWithText("章节内容不能超过 4 个字符").assertIsDisplayed()
        assertEquals(0, saves)
    }

    @Test
    fun storageFailureMessageKeepsUnsavedTextVisible() {
        val state = editorState("保留的草稿").copy(errorMessage = "无法写入章节文件，请检查存储空间后重试")
        composeRule.setContent {
            MkreadTheme {
                ChapterEditorScreen(state, {}, {}, {}, {})
            }
        }

        composeRule.onNodeWithText("无法写入章节文件，请检查存储空间后重试").assertIsDisplayed()
        composeRule.onNodeWithText("保留的草稿").assertIsDisplayed()
    }

    @Test
    fun roomFailureMessageKeepsUnsavedTextVisible() {
        val state = editorState("保留的草稿").copy(errorMessage = "无法更新章节数据库，修改未保存，请重试")
        composeRule.setContent {
            MkreadTheme {
                ChapterEditorScreen(state, {}, {}, {}, {})
            }
        }

        composeRule.onNodeWithText("无法更新章节数据库，修改未保存，请重试").assertIsDisplayed()
        composeRule.onNodeWithText("保留的草稿").assertIsDisplayed()
    }

    @Test
    fun savingDisablesEditorActionsAndShowsProgress() {
        val state = editorState("正文").copy(isSaving = true)
        composeRule.setContent {
            MkreadTheme {
                ChapterEditorScreen(
                    state = state,
                    onTextChanged = {},
                    onSave = {},
                    onUndo = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("保存章节").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("关闭编辑器").assertIsNotEnabled()
        composeRule.onNodeWithTag("chapter-editor-progress").assertIsDisplayed()
    }

    private fun editorState(text: String) = ChapterEditorUiState(
        chapterId = "chapter-1",
        title = "第一章 夜色中的车站",
        originalText = text,
        draftText = text,
        undoAvailable = true,
    )

    private fun chapters() = listOf(
        ChapterEntity("chapter-1", "book-1", 0, "第一章 夜色中的车站", "1.txt", 120, "hash-1"),
        ChapterEntity("chapter-2", "book-1", 1, "第二章 清晨", "2.txt", 340, "hash-2"),
        ChapterEntity("chapter-3", "book-1", 2, "第三章 远方", "3.txt", 56, "hash-3"),
    )
}
