package com.mkread.app.speech

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mkread.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpikeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun debugScreen_generatesSpeechAndEnablesReplay() {
        composeRule.onNodeWithText(SMOKE_SENTENCE).assertIsDisplayed()
        composeRule.onNodeWithText("生成并试听").performClick()

        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodesWithText("正在生成").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("正在生成").assertIsDisplayed()

        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodesWithText("生成完成").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("生成完成").assertIsDisplayed()
        composeRule.onNodeWithText("重播").assertIsEnabled()
    }

    private companion object {
        const val SMOKE_SENTENCE = "窗外下着小雨，她轻声说：“我们回家吧。”"
    }
}
