package com.mkread.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bookshelfIsTheAppStartDestination() {
        composeRule.onNodeWithText("MKread").assertIsDisplayed()
        composeRule.onNodeWithText("书架中还没有小说").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索").assertIsDisplayed()
        composeRule.onNodeWithText("导入小说").assertIsDisplayed()
    }
}
