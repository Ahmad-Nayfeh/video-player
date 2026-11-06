package com.ahmadnayfeh.reel

import android.content.ComponentName
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import com.google.common.util.concurrent.ListenableFuture

class NowPlayingActivity : AppCompatActivity() {

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var controls: PlayerControlView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var art: ImageView

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = bind()
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = bind()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, i.bottom); ins
        }
        controls = findViewById(R.id.controls)
        title = findViewById(R.id.songTitle)
        artist = findViewById(R.id.songArtist)
        art = findViewById(R.id.art)
        art.clipToOutline = true
        art.setImageResource(R.drawable.art_placeholder)
        findViewById<android.view.View>(R.id.artBox).clipToOutline = true
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btnSleep).setOnClickListener { sleepSheet() }
        findViewById<android.view.View>(R.id.btnLockCtl).setOnClickListener {
            val simple = Settings.lockSimple(this)
            Sheet(this, getString(R.string.lock_controls))
                .row(R.drawable.ic_fast, getString(R.string.lock_full), checked = !simple) { setLock(false) }
                .row(R.drawable.ic_lock, getString(R.string.lock_simple), checked = simple) { setLock(true) }
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            controls.player = c
            controls.show()
            c.addListener(listener)
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controls.player = null
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    private fun bind() {
        val c = controller ?: return
        val m = c.mediaMetadata
        title.text = m.title ?: ""
        artist.text = listOfNotNull(m.artist, m.albumTitle).filter { it.isNotBlank() }.joinToString(" · ")
        val data = m.artworkData
        if (data != null) {
            art.scaleType = ImageView.ScaleType.CENTER_CROP
            art.setImageBitmap(BitmapFactory.decodeByteArray(data, 0, data.size))
        } else {
            val id = c.currentMediaItem?.mediaId
            if (id != null) Thumbs.load(art, Uri.parse(id), 0, false, R.drawable.art_placeholder)
            else art.setImageResource(R.drawable.art_placeholder)
            if (art.drawable != null && art.scaleType == ImageView.ScaleType.CENTER) art.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun setLock(on: Boolean) {
        Settings.setLockSimple(this, on)
        PlaybackService.applyWidgetCommands(this)
        Toast.makeText(this, if (on) R.string.lock_simple else R.string.lock_full, Toast.LENGTH_SHORT).show()
    }

    private fun sleepSheet() {
        val s = Sheet(this, getString(R.string.sleep_timer))
        s.row(R.drawable.ic_close, getString(R.string.sleep_off), checked = PlaybackService.sleepAt == 0L) { set(0) }
        intArrayOf(15, 30, 45, 60, 90).forEach { m -> s.row(R.drawable.ic_timer, getString(R.string.minutes, m)) { set(m) } }
        s.row(R.drawable.ic_music, getString(R.string.end_of_track), checked = PlaybackService.sleepAt < 0) { set(-1) }
        s.show()
    }

    private fun set(m: Int) {
        PlaybackService.setSleepTimer(m)
        val msg = when { m == 0 -> getString(R.string.sleep_cancelled); m < 0 -> getString(R.string.end_of_track); else -> getString(R.string.sleep_in, m) }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
