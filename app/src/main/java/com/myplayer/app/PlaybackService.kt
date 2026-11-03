package com.myplayer.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * الخدمة اللي بتشغّل الصوت والفيديو في الخلفية.
 * MediaSessionService بيعمل لوحده: إشعار التحكم، شاشة القفل، أزرار البلوتوث.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val closeCommand = SessionCommand(CLOSE_ACTION, Bundle.EMPTY)

    private val callback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(closeCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(ImmutableList.of(buildCloseButton()))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CLOSE_ACTION) {
                closePlayback("close button")
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private fun buildCloseButton(): CommandButton {
        return CommandButton.Builder()
            .setDisplayName(getString(R.string.notif_close))
            .setIconResId(R.drawable.ic_close)
            .setSessionCommand(closeCommand)
            .build()
    }

    /** بيوقف التشغيل ويشيل الإشعار. */
    private fun closePlayback(reason: String) {
        AppLog.log("SERVICE", "closing playback: $reason")
        mediaSession?.player?.let {
            it.pause()
            it.clearMediaItems()
        }
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        AudioFx.init(applicationContext)
        AppLog.log("SERVICE", "created")

        // مشغّلات الصوت الإضافية (لو اتضافت لاحقاً) بتتحمل تلقائياً، وبنسمح بالرجوع لمفكك بديل عند الفشل
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val dataSourceFactory = DataSource.Factory {
            FdAwareDataSource(DefaultDataSource.Factory(applicationContext).createDataSource())
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // ملف جاي من برا التطبيق وخلص: نشيل الإشعار تماماً
                if (playbackState == Player.STATE_ENDED) {
                    val external = player.currentMediaItem?.mediaId?.startsWith("ext:") == true
                    if (external && !player.hasNextMediaItem() && player.repeatMode == Player.REPEAT_MODE_OFF) {
                        closePlayback("external file finished")
                    }
                }
            }
        })
        AudioFx.attach(player.audioSessionId)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(callback)
            .build()
        session.setCustomLayout(ImmutableList.of(buildCloseButton()))
        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val keep = player != null &&
            player.playWhenReady &&
            player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_ENDED
        AppLog.log("SERVICE", "task removed, keep playing = $keep")
        if (!keep) stopSelf()
    }

    override fun onDestroy() {
        AppLog.log("SERVICE", "destroyed")
        AudioFx.release()
        mediaSession?.let {
            it.player.release()
            it.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CLOSE_ACTION = "com.khereba1.quickplayer.CLOSE"
    }
}
