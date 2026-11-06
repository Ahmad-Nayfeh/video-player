package com.ahmadnayfeh.reel

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Background music: keeps playing with the screen off, with lock-screen / notification / headset controls. */
class PlaybackService : MediaSessionService() {

    companion object {
        private var instance: PlaybackService? = null
        private val handler = Handler(Looper.getMainLooper())
        /** Epoch ms when music will stop, 0 = off, -1 = at end of current track. */
        var sleepAt = 0L
            private set

        fun setSleepTimer(minutes: Int) {
            handler.removeCallbacksAndMessages(null)
            instance?.player?.removeListener(endOfTrack)
            when {
                minutes > 0 -> {
                    sleepAt = System.currentTimeMillis() + minutes * 60_000L
                    handler.postDelayed({ instance?.player?.pause(); sleepAt = 0 }, minutes * 60_000L)
                }
                minutes < 0 -> {
                    sleepAt = -1
                    instance?.player?.addListener(endOfTrack)
                }
                else -> sleepAt = 0
            }
        }

        private val endOfTrack = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    instance?.player?.let { it.pause(); it.removeListener(this) }
                    sleepAt = 0
                }
            }
        }
    }

    var player: ExoPlayer? = null
        private set
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val p = Players.build(this, music = true)
        player = p
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, NowPlayingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, p)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { m ->
                        if (m.localConfiguration != null) m
                        else m.buildUpon().setUri(m.requestMetadata.mediaUri).build()
                    }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run { player.release(); release() }
        session = null
        player = null
        instance = null
        super.onDestroy()
    }
}
