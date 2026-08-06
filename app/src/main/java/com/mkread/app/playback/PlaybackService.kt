package com.mkread.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.mkread.app.R

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
            }
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(
                object : MediaSession.Callback {
                    override fun onPlayerCommandRequest(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        playerCommand: Int,
                    ): Int {
                        if (
                            playerCommand == Player.COMMAND_PLAY_PAUSE &&
                            !session.player.isPlaying
                        ) {
                            promoteForPlayback(session)
                        }
                        return SessionResult.RESULT_SUCCESS
                    }
                },
            )
            .build()
        setMediaNotificationProvider(PlaybackNotificationProvider.create(this))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private fun promoteForPlayback(session: MediaSession) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                PlaybackNotificationProvider.CHANNEL_ID,
                getText(R.string.playback_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
            },
        )
        val metadata = session.player.currentMediaItem?.mediaMetadata
        val notification = Notification.Builder(this, PlaybackNotificationProvider.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(metadata?.title ?: getText(R.string.app_name))
            .setContentText(metadata?.subtitle)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.platformToken))
            .build()
        startForeground(
            PlaybackNotificationProvider.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }
}
