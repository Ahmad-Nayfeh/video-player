package com.ahmadnayfeh.reel

import android.content.ComponentName
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture

class NowPlayingActivity : AppCompatActivity() {

    private var future: ListenableFuture<MediaController>? = null
    private lateinit var pv: PlayerView
    private lateinit var controls: PlayerControlView
    private lateinit var title: TextView
    private lateinit var artist: TextView

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = bind(mediaMetadata)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            ins
        }
        pv = findViewById(R.id.pv)
        controls = findViewById(R.id.controls)
        title = findViewById(R.id.songTitle)
        artist = findViewById(R.id.songArtist)
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btnSleep).setOnClickListener { sleepDialog() }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            pv.player = c
            controls.player = c
            controls.show()
            c.addListener(listener)
            bind(c.mediaMetadata)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        pv.player = null
        controls.player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    private fun bind(m: MediaMetadata) {
        title.text = m.title ?: m.displayTitle ?: ""
        artist.text = listOfNotNull(m.artist, m.albumTitle).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun sleepDialog() {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90, -1)
        val labels = mins.map {
            when {
                it == 0 -> getString(R.string.sleep_off)
                it < 0 -> getString(R.string.end_of_track)
                else -> getString(R.string.minutes, it)
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels) { _, which ->
                val m = mins[which]
                PlaybackService.setSleepTimer(m)
                val msg = when {
                    m == 0 -> getString(R.string.sleep_cancelled)
                    m < 0 -> getString(R.string.end_of_track)
                    else -> getString(R.string.sleep_in, m)
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
