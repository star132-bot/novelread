package com.mkread.app.feature.reader

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationAutoPlayPolicyTest {
    @Test
    fun userPauseFromNotificationDisablesAutomaticStarvationResume() {
        assertFalse(
            updatedAutoPlayRequest(
                current = true,
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            ),
        )
    }

    @Test
    fun nonUserSuppressionKeepsTheExistingPlaybackIntent() {
        assertTrue(
            updatedAutoPlayRequest(
                current = true,
                playWhenReady = false,
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            ),
        )
    }
}
