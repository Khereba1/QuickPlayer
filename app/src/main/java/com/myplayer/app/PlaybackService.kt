package com.myplayer.app

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
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
    private val seekParamsCommand = SessionCommand(SEEK_PARAMS_ACTION, Bundle.EMPTY)
    private var exo: ExoPlayer? = null

    // ---- إيقاف التشغيل وقت المكالمات عشان الرنة تتسمع ----
    private val handler = Handler(Looper.getMainLooper())
    private var pausedByCall = false
    private var overrideCall = false
    private val callPoll = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            val mode = am?.mode ?: AudioManager.MODE_NORMAL
            val inCall = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_CALL_SCREENING
            val enabled = getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean("pause_on_call", true)
            if (inCall) {
                if (pausedByCall && p.playWhenReady) {
                    // أنت شغّلته بإيدك وقت المكالمة: نسيبه
                    pausedByCall = false
                    overrideCall = true
                } else if (!overrideCall && enabled && p.isPlaying) {
                    pausedByCall = true
                    p.pause()
                    AppLog.log("AUDIO", "paused because the phone is ringing or in a call (audio mode $mode)")
                }
            } else {
                overrideCall = false
                if (pausedByCall) {
                    pausedByCall = false
                    if (!p.playWhenReady) {
                        AppLog.log("AUDIO", "call ended: resuming")
                        p.play()
                    }
                }
            }
            if (p.isPlaying || pausedByCall) handler.postDelayed(this, 700L)
        }
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(closeCommand)
                .add(seekParamsCommand)
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
            if (customCommand.customAction == SEEK_PARAMS_ACTION) {
                // أثناء السحب على شريط التقدم: قفزات سريعة لأقرب إطار مفتاحي، وبعدها دقيق
                val fast = args.getBoolean("fast", false)
                exo?.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
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

    private fun markServiceAlive(alive: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("svc_alive", alive).commit()
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
        markServiceAlive(true)

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

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    handler.removeCallbacks(callPoll)
                    handler.post(callPoll)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    AppLog.log("AUDIO", "paused by audio focus loss (another app or a call took the audio)")
                }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    AppLog.log("AUDIO", "playback suppressed, reason=" + playbackSuppressionReason)
                }
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
        exo = player

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

    /**
     * بنمنع إعادة تشغيل الخدمة تلقائياً بعد ما النظام يقفلها (START_STICKY):
     * الإعادة كانت بتطلع خدمة فاضية وممكن تسبب مشاكل عند فتح التطبيق بعدها.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: Exception) {
            AppLog.log("SERVICE", "onStartCommand failed", e)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val count = player?.mediaItemCount ?: 0
        val playing = player != null && player.playWhenReady && player.playbackState != Player.STATE_ENDED
        AppLog.log("SERVICE", "task removed (items=$count, playing=$playing)")
        // بنقفل بس لو فيه إشعار واقف (متوقف مؤقتاً). لو لسه بنجهّز التشغيل أو شغّال نسيبه.
        if (count > 0 && !playing) stopSelf()
    }

    override fun onDestroy() {
        AppLog.log("SERVICE", "destroyed")
        markServiceAlive(false)
        AudioFx.release()
        mediaSession?.let {
            it.player.release()
            it.release()
        }
        handler.removeCallbacksAndMessages(null)
        mediaSession = null
        exo = null
        super.onDestroy()
    }

    companion object {
        const val CLOSE_ACTION = "com.khereba1.quickplayer.CLOSE"
        const val SEEK_PARAMS_ACTION = "com.khereba1.quickplayer.SEEK_PARAMS"
    }
}
