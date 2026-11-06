package com.ahmadnayfeh.reel

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity(), GestureLayout.Listener {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var player: ExoPlayer
    private lateinit var pv: PlayerView
    private lateinit var gestures: GestureLayout
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var info: TextView
    private lateinit var unlockBtn: ImageButton

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())
    private var enhancer: LoudnessEnhancer? = null
    private var boost = 0f
    private var startVol = 0f
    private var startBright = 0.5f
    private var seekStart = 0L
    private var seekTarget = -1L
    private var resizeIdx = 0
    private var userRotated = false
    private var autoOriented = false
    private var locked = false

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val resizeLabels by lazy { arrayOf(getString(R.string.fit), getString(R.string.crop), getString(R.string.stretch)) }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addSubtitle(uri)
    }

    private val saver = object : Runnable {
        override fun run() {
            saveResume()
            handler.postDelayed(this, 4_000)
        }
    }
    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val hideUnlock = Runnable { unlockBtn.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        pv = findViewById(R.id.pv)
        gestures = findViewById(R.id.gestures)
        topBar = findViewById(R.id.topBar)
        titleView = findViewById(R.id.title)
        info = findViewById(R.id.info)
        unlockBtn = findViewById(R.id.btnUnlock)
        gestures.listener = this

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left + dp(6), i.top + dp(6), i.right + dp(6), dp(22))
            ins
        }

        player = Players.build(this, music = false)
        pv.player = player
        pv.setShowSubtitleButton(true)
        pv.setShowNextButton(true)
        pv.setShowPreviousButton(true)
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            if (!locked) topBar.visibility = vis
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSub).setOnClickListener { pickSubtitle.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.btnAspect).setOnClickListener {
            resizeIdx = (resizeIdx + 1) % resizeModes.size
            pv.resizeMode = resizeModes[resizeIdx]
            flash(resizeLabels[resizeIdx])
        }
        findViewById<View>(R.id.btnRotate).setOnClickListener {
            userRotated = true
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPip() }
        findViewById<View>(R.id.btnLock).setOnClickListener { setLocked(true) }
        unlockBtn.setOnClickListener { setLocked(false) }

        player.addListener(listener)
        player.playbackParameters = PlaybackParameters(prefs().getFloat("speed", 1f))
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            enhancer = runCatching { LoudnessEnhancer(player.audioSessionId) }.getOrNull()
        }
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveResume()
        setIntent(intent)
        userRotated = false; autoOriented = false
        load(intent)
    }

    private fun load(intent: Intent) {
        val items = ArrayList<MediaItem>()
        var index = 0
        val data = intent.data
        if (data != null) {
            items += item(data, displayName(data))
        } else {
            val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
            uris.forEachIndexed { i, u -> items += item(Uri.parse(u), titles.getOrNull(i) ?: "") }
            index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
        if (items.isEmpty()) { finish(); return }
        player.setMediaItems(items, index, Resume.get(this, items[index].mediaId))
        player.prepare()
        player.play()
        titleView.text = items[index].mediaMetadata.title
    }

    private fun item(uri: Uri, title: String) = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(uri.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            titleView.text = mediaItem?.mediaMetadata?.title
            if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                val p = Resume.get(this@PlayerActivity, mediaItem.mediaId)
                if (p > 0) player.seekTo(p)
            }
            autoOriented = false
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                old.mediaItem?.let { Resume.clear(this@PlayerActivity, it.mediaId) }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                player.currentMediaItem?.let { Resume.clear(this@PlayerActivity, it.mediaId) }
                if (!isInPip()) finish()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            if (!userRotated && !autoOriented) {
                autoOriented = true
                val wide = videoSize.width * videoSize.pixelWidthHeightRatio >= videoSize.height
                requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            updatePipParams()
        }

        override fun onPlaybackParametersChanged(params: PlaybackParameters) {
            prefs().edit().putFloat("speed", params.speed).apply()
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, getString(R.string.cant_play, error.errorCodeName), Toast.LENGTH_LONG).show()
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem(); player.prepare(); player.play()
            }
        }
    }

    private fun addSubtitle(uri: Uri) {
        val name = displayName(uri).lowercase()
        val mime = when {
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val current = player.currentMediaItem ?: return
        val sub = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .setLabel(displayName(uri))
            .build()
        val existing = current.localConfiguration?.subtitleConfigurations ?: emptyList()
        val updated = current.buildUpon().setSubtitleConfigurations(existing + sub).build()
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        player.replaceMediaItem(idx, updated)
        player.seekTo(idx, pos)
        player.prepare()
        player.play()
        flash(getString(R.string.subtitle_added))
    }

    // ---- gestures ----

    override fun onGestureStart(mode: Int) {
        when (mode) {
            GestureLayout.BRIGHT -> {
                val cur = window.attributes.screenBrightness
                startBright = if (cur >= 0) cur
                else Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            }
            GestureLayout.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC) + boost * max
            }
            GestureLayout.SEEK -> { seekStart = player.currentPosition; seekTarget = -1 }
        }
    }

    override fun onGestureMove(mode: Int, fraction: Float) {
        when (mode) {
            GestureLayout.BRIGHT -> {
                val b = (startBright + fraction * 1.4f).coerceIn(0.01f, 1f)
                window.attributes = window.attributes.apply { screenBrightness = b }
                show(getString(R.string.brightness, (b * 100).roundToInt()))
            }
            GestureLayout.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                val v = (startVol + fraction * max * 1.4f).coerceIn(0f, max * 2f)
                val vol = v.coerceAtMost(max).roundToInt()
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != vol) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                }
                setBoost(((v - max) / max).coerceIn(0f, 1f))
                val pct = (v / max * 100).roundToInt()
                show(if (boost > 0f) getString(R.string.boost, pct) else getString(R.string.volume, pct))
            }
            GestureLayout.SEEK -> {
                val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: return
                seekTarget = (seekStart + (fraction * 120_000).toLong()).coerceIn(0, dur)
                val delta = (seekTarget - seekStart) / 1000
                val sign = if (delta >= 0) "+" else "−"
                show("$sign${fmtTime(kotlin.math.abs(delta) * 1000)}\n${fmtTime(seekTarget)} / ${fmtTime(dur)}")
            }
        }
    }

    override fun onGestureEnd(mode: Int) {
        if (mode == GestureLayout.SEEK && seekTarget >= 0) player.seekTo(seekTarget)
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 700)
    }

    override fun onDoubleTapAt(x: Float, width: Int) {
        when {
            x < width / 3f -> { player.seekBack(); flash("−10s") }
            x > width * 2f / 3f -> { player.seekForward(); flash("+10s") }
            else -> if (player.isPlaying) player.pause() else player.play()
        }
    }

    override fun onPinch(scale: Float) {
        val newIdx = when {
            scale > 1.12f -> 1
            scale < 0.9f -> 0
            else -> return
        }
        if (newIdx != resizeIdx) {
            resizeIdx = newIdx
            pv.resizeMode = resizeModes[resizeIdx]
            show(resizeLabels[resizeIdx])
        }
    }

    override fun onLockedTap() {
        unlockBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 2_500)
    }

    private fun setLocked(on: Boolean) {
        locked = on
        gestures.locked = on
        pv.useController = !on
        if (on) {
            pv.hideController()
            topBar.visibility = View.GONE
            flash(getString(R.string.locked))
            onLockedTap()
        } else {
            unlockBtn.visibility = View.GONE
            pv.showController()
        }
    }

    private fun setBoost(b: Float) {
        boost = b
        val e = enhancer ?: return
        runCatching {
            e.setTargetGain((b * 1000).toInt()) // up to +10 dB ≈ 200% loudness
            e.enabled = b > 0f
        }
    }

    // ---- picture in picture ----

    private fun isInPip() = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val vs = player.videoSize
        val b = PictureInPictureParams.Builder()
        if (vs.width > 0 && vs.height > 0) {
            val w = (vs.width * vs.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
            val r = w.toFloat() / vs.height
            val clamped = r.coerceIn(1 / 2.3f, 2.3f)
            b.setAspectRatio(Rational((clamped * 1000).toInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(player.isPlaying).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26) runCatching { pipParams()?.let { setPictureInPictureParams(it) } }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) runCatching { pipParams()?.let { enterPictureInPictureMode(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        gestures.gesturesEnabled = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            pv.useController = false
            topBar.visibility = View.GONE
            info.visibility = View.GONE
        } else if (!locked) {
            pv.useController = true
        }
    }

    // ---- lifecycle ----

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        handler.post(saver)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        saveResume()
        if (!isInPip()) handler.removeCallbacks(saver)
    }

    override fun onStop() {
        super.onStop()
        saveResume()
        handler.removeCallbacks(saver)
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveResume()
        runCatching { enhancer?.release() }
        player.release()
        super.onDestroy()
    }

    private fun saveResume() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        val dur = player.duration
        if (dur == C.TIME_UNSET || player.playbackState == Player.STATE_ENDED) return
        Resume.save(this, item.mediaId, player.currentPosition, dur)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun show(text: String) {
        handler.removeCallbacks(hideInfo)
        info.text = text
        info.visibility = View.VISIBLE
    }

    private fun flash(text: String) {
        show(text)
        handler.postDelayed(hideInfo, 900)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
