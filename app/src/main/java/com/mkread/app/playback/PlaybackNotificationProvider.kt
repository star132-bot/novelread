package com.mkread.app.playback

import android.content.Context
import androidx.media3.session.DefaultMediaNotificationProvider
import com.mkread.app.R

object PlaybackNotificationProvider {
    const val CHANNEL_ID = "mkread_playback"

    fun create(context: Context): DefaultMediaNotificationProvider =
        DefaultMediaNotificationProvider.Builder(context)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_notification_channel_name)
            .build()
}
