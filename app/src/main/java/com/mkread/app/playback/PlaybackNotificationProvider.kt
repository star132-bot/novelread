package com.mkread.app.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.mkread.app.R

@OptIn(UnstableApi::class)
object PlaybackNotificationProvider {
    const val CHANNEL_ID = "mkread_playback"
    const val NOTIFICATION_ID = 1_001

    fun create(context: Context): DefaultMediaNotificationProvider =
        DefaultMediaNotificationProvider.Builder(context)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_notification_channel_name)
            .build()
}
