package com.mkread.app.feature.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.ui.theme.MkreadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FragmentAssemblyDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dialogNaturallyOrdersFragmentsAndAllowsManualAdjustment() {
        var confirmedTitle: String? = null
        var confirmedFragments = emptyList<BookFragmentSource>()
        composeRule.setContent {
            MkreadTheme {
                FragmentAssemblyDialog(
                    initialTitle = "first",
                    fragments = listOf(
                        BookFragmentSource("ten", "10 第十章"),
                        BookFragmentSource("two", "02 第二章"),
                        BookFragmentSource("one", "01 第一章"),
                    ),
                    onConfirm = { title, ordered ->
                        confirmedTitle = title
                        confirmedFragments = ordered
                    },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("整理章节片段").assertIsDisplayed()
        composeRule.onNodeWithText("成功后只移除书架中的片段副本，不删除手机原文件。").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("上移 02 第二章").performClick()
        composeRule.onNodeWithText("first").performTextReplacement("完整小说")
        composeRule.onNodeWithText("开始编排").performClick()

        composeRule.runOnIdle {
            assertEquals("完整小说", confirmedTitle)
            assertEquals(listOf("two", "one", "ten"), confirmedFragments.map(BookFragmentSource::bookId))
        }
    }
}
