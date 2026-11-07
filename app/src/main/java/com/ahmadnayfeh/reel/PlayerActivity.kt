package com.ahmadnayfeh.reel

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity(), GestureLayout.Listener {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
    }

    private lateinit var player: ExoPlayer
    private lateinit var pv: PlayerView
    private lateinit var gestures: GestureLayout
    private lateinit var info: View
    private lateinit var infoIcon: ImageView
    private lateinit var infoText: TextView
    private lateinit var infoBar: ProgressBar
    private lateinit var unlockBtn: ImageButton
    private lateinit var resumeChip: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var speedBtn: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private var session: MediaSession? = null
    private lateinit var subs: SubtitleView
    private var seekPreview: SeekPreview? = null

    // subtitle delay: loaded files are shifted on disk (both directions); embedded cues are queued (later only)
    private data class ExtSub(val uri: Uri, val name: String, val mime: String)
    private val extSubs = HashMap<String, MutableList<ExtSub>>()
    private var subDelayMs = 0L
    private var cueDelayMs = 0L
    private val cueQueue = ArrayDeque<Pair<Long, List<androidx.media3.common.text.Cue>>>()
    private val cuePump = object : Runnable {
        override fun run() {
            val now = player.currentPosition
            while (cueQueue.isNotEmpty() && cueQueue.first().first <= now) subs.setCues(cueQueue.removeFirst().second)
            if (cueQueue.isNotEmpty()) handler.postDelayed(this, 40)
        }
    }

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())
    private var enhancer: LoudnessEnhancer? = null
    private var boost = 0f
    private var startVol = 0f
    private var startBright = 0.5f
    private var seekStart = 0L
    private var seekTarget = -1L
    private var lastScrub = -1L
    private var wasPlaying = false
    private var resizeIdx = 0
    private var zoom = 1f
    private var zoomStart = 1f
    private var userRotated = false
    private var autoOriented = false
    private var locked = false
    private var sleepAt = 0L

    // background play
    private var bgFuture: ListenableFuture<MediaController>? = null
    private var bg: MediaController? = null
    private var handedOffId: String? = null
    private var artBytes: ByteArray? = null
    private var artFor: String? = null

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val resizeLabels by lazy { arrayOf(getString(R.string.fit), getString(R.string.crop), getString(R.string.stretch)) }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addSubtitle(uri)
    }

    private val saver = object : Runnable {
        override fun run() { saveResume(); handler.postDelayed(this, 4_000) }
    }
    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val hideUnlock = Runnable { unlockBtn.visibility = View.GONE }
    private val hideResume = Runnable { resumeChip.visibility = View.GONE }
    private val sleepRun = Runnable { player.pause(); sleepAt = 0; flash(R.drawable.ic_timer, getString(R.string.sleep_done)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        pv = findViewById(R.id.pv)
        gestures = findViewById(R.id.gestures)
        info = findViewById(R.id.info)
        infoIcon = findViewById(R.id.infoIcon)
        infoText = findViewById(R.id.infoText)
        infoBar = findViewById(R.id.infoBar)
        unlockBtn = findViewById(R.id.btnUnlock)
        resumeChip = findViewById(R.id.resumeChip)
        titleView = pv.findViewById(R.id.reel_title)
        subtitleView = pv.findViewById(R.id.reel_subtitle)
        speedBtn = pv.findViewById(R.id.reel_speed)
        topBar = pv.findViewById(R.id.reel_top)
        bottomBar = pv.findViewById(R.id.reel_bottom)
        subs = findViewById(R.id.subs)

        val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(gestures) { _, ins ->
            val i = ins.getInsets(bars)
            topBar.setPadding(i.left + dp(8), i.top + dp(8), i.right + dp(8), dp(28))
            bottomBar.setPadding(i.left + dp(12), dp(36), i.right + dp(12), i.bottom + dp(6))
            ins
        }

        player = Players.build(this, music = false)
        pv.player = player
        pv.setTimeBarScrubbingEnabled(true) // the picture follows your finger while dragging the bar
        session = runCatching { MediaSession.Builder(this, player).setId("video").build() }.getOrNull()
        pv.subtitleView?.visibility = View.GONE // we draw subtitles ourselves so they can be delayed
        seekPreview = SeekPreview(this, findViewById(R.id.seekPreview), pv.findViewById(androidx.media3.ui.R.id.exo_progress),
            { player.currentMediaItem?.localConfiguration?.uri }, { player.duration })

        gestures.listener = this
        gestures.allowSeek = Settings.gSeek(this)
        gestures.allowBright = Settings.gBright(this)
        gestures.allowVolume = Settings.gVolume(this)
        gestures.allowDouble = Settings.gDouble(this)
        gestures.allowPinch = Settings.gPinch(this)
        gestures.blocker = { _, y ->
            pv.isControllerFullyVisible && (y > bottomBar.top + dp(20) || y < topBar.bottom - dp(20))
        }

        pv.findViewById<View>(R.id.reel_back).setOnClickListener { finish() }
        pv.findViewById<View>(R.id.reel_audio).setOnClickListener { audioSheet() }
        pv.findViewById<View>(R.id.reel_subs).setOnClickListener { subtitleSheet() }
        pv.findViewById<View>(R.id.reel_more).setOnClickListener { moreSheet() }
        pv.findViewById<View>(R.id.reel_lock).setOnClickListener { setLocked(true) }
        pv.findViewById<View>(R.id.reel_rotate).setOnClickListener { toggleRotation() }
        pv.findViewById<View>(R.id.reel_queue).setOnClickListener { queueSheet() }
        pv.findViewById<View>(R.id.reel_pip).setOnClickListener { enterPip() }
        pv.findViewById<View>(R.id.reel_color).setOnClickListener { colorModeSheet() }
        pv.findViewById<View>(R.id.reel_shot).setOnClickListener { screenshot() }
        pv.findViewById<View>(R.id.reel_aspect).setOnClickListener { setResize((resizeIdx + 1) % resizeModes.size, true) }
        speedBtn.setOnClickListener { speedSheet() }
        unlockBtn.setOnClickListener { setLocked(false) }
        resumeChip.setOnClickListener { player.seekTo(0); resumeChip.visibility = View.GONE }

        applySubtitleStyle()
        applyOrientationSetting()
        if (Settings.keepBrightness(this)) {
            val b = Settings.getFloat(this, "brightness", -1f)
            if (b > 0) window.attributes = window.attributes.apply { screenBrightness = b }
        }

        player.addListener(listener)
        player.playbackParameters = PlaybackParameters(Settings.getFloat(this, "speed", 1f))
        player.pauseAtEndOfMediaItems = !Settings.autoNext(this)
        updateSpeedLabel()
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            enhancer = runCatching { LoudnessEnhancer(player.audioSessionId) }.getOrNull()
        }
        if (Settings.bgPlay(this)) connectBackground()
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
            items += item(data, displayName(data).substringBeforeLast('.'))
        } else {
            val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
            uris.forEachIndexed { i, u -> items += item(Uri.parse(u), titles.getOrNull(i) ?: "") }
            index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
        if (items.isEmpty()) { finish(); return }
        val start = if (Settings.resume(this)) Resume.get(this, items[index].mediaId) else 0L
        player.setMediaItems(items, index, start)
        val fx = currentEffects()
        if (fx.isNotEmpty() || effectsReady) { player.setVideoEffects(fx); effectsReady = true }
        player.prepare()
        player.play()
        if (start > 0) showResumeChip(start)
        loadArt(items[index])
        bindTitle()
    }

    private fun item(uri: Uri, title: String) = MediaItem.Builder()
        .setUri(uri).setMediaId(uri.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    private fun bindTitle() {
        titleView.text = player.currentMediaItem?.mediaMetadata?.title
        val parts = ArrayList<String>()
        if (player.mediaItemCount > 1) parts += "${player.currentMediaItemIndex + 1} / ${player.mediaItemCount}"
        player.videoFormat?.let { f -> if (f.height > 0) parts += "${minOf(f.width, f.height)}p" }
        if (sleepAt > 0) parts += "⏾ " + fmtTime(sleepAt - System.currentTimeMillis())
        subtitleView.text = parts.joinToString("  ·  ")
        subtitleView.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showResumeChip(pos: Long) {
        resumeChip.text = getString(R.string.resumed_at, fmtTime(pos))
        resumeChip.visibility = View.VISIBLE
        handler.removeCallbacks(hideResume)
        handler.postDelayed(hideResume, 6_000)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            bindTitle()
            if (mediaItem != null && Settings.resume(this@PlayerActivity) &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                val p = Resume.get(this@PlayerActivity, mediaItem.mediaId)
                if (p > 0) { player.seekTo(p); showResumeChip(p) }
            }
            mediaItem?.let { Resume.markSeen(this@PlayerActivity, it.mediaId); loadArt(it) }
            subDelayMs = 0; cueDelayMs = 0; cueQueue.clear()
            autoOriented = false
        }

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            if (cueDelayMs <= 0) { subs.setCues(cueGroup.cues); return }
            cueQueue.addLast(player.currentPosition + cueDelayMs to cueGroup.cues)
            handler.removeCallbacks(cuePump); handler.post(cuePump)
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) { cueQueue.clear(); subs.setCues(emptyList()) }
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) old.mediaItem?.let { Resume.clear(this@PlayerActivity, it.mediaId) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                player.currentMediaItem?.let { Resume.clear(this@PlayerActivity, it.mediaId) }
                if (!isInPip() && player.repeatMode == Player.REPEAT_MODE_OFF) finish()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            if (!userRotated && !autoOriented && Settings.orientation(this@PlayerActivity) == "video") {
                autoOriented = true
                val wide = videoSize.width * videoSize.pixelWidthHeightRatio >= videoSize.height
                requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            bindTitle()
            updatePipParams()
        }

        override fun onPlaybackParametersChanged(params: PlaybackParameters) {
            Settings.putFloat(this@PlayerActivity, "speed", params.speed)
            updateSpeedLabel()
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, getString(R.string.cant_play, error.errorCodeName), Toast.LENGTH_LONG).show()
            if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
        }
    }

    // ---------------- gestures ----------------

    override fun onGestureStart(mode: Int) {
        when (mode) {
            GestureLayout.BRIGHT -> {
                val cur = window.attributes.screenBrightness
                startBright = if (cur >= 0) cur
                else android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            }
            GestureLayout.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC) + boost * max
            }
            GestureLayout.SEEK -> {
                seekStart = player.currentPosition; seekTarget = -1; lastScrub = -1
                wasPlaying = player.isPlaying
                player.pause()
                runCatching { player.isScrubbingModeEnabled = true }
            }
            GestureLayout.PINCH -> zoomStart = zoom
        }
    }

    override fun onGestureMove(mode: Int, fraction: Float) {
        when (mode) {
            GestureLayout.BRIGHT -> {
                val b = (startBright + fraction * 1.3f).coerceIn(0.01f, 1f)
                window.attributes = window.attributes.apply { screenBrightness = b }
                show(R.drawable.ic_brightness, "${(b * 100).roundToInt()}%", (b * 100).roundToInt())
            }
            GestureLayout.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                val v = (startVol + fraction * max * 1.3f).coerceIn(0f, max * 2f)
                val vol = v.coerceAtMost(max).roundToInt()
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != vol) audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                setBoost(((v - max) / max).coerceIn(0f, 1f))
                val pct = (v / max * 100).roundToInt()
                show(R.drawable.ic_volume, if (boost > 0f) getString(R.string.boost, pct) else "$pct%", (pct / 2).coerceAtMost(100))
            }
            GestureLayout.SEEK -> {
                val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
                seekTarget = (seekStart + (fraction * Settings.seekRangeMs(this)).toLong()).coerceIn(0, dur)
                if (lastScrub < 0 || abs(seekTarget - lastScrub) > 250) { player.seekTo(seekTarget); lastScrub = seekTarget }
                val delta = seekTarget - seekStart
                val sign = if (delta >= 0) "+" else "−"
                show(if (delta >= 0) R.drawable.ic_fast else R.drawable.ic_fast,
                    "${fmtTime(seekTarget)}   $sign${fmtTime(abs(delta))}", (seekTarget * 100 / dur).toInt())
                infoIcon.scaleX = if (delta >= 0) 1f else -1f
            }
        }
    }

    override fun onGestureEnd(mode: Int) {
        if (mode == GestureLayout.SEEK) {
            runCatching { player.isScrubbingModeEnabled = false }
            if (seekTarget >= 0) player.seekTo(seekTarget)
            if (wasPlaying) player.play()
            infoIcon.scaleX = 1f
        }
        if (mode == GestureLayout.BRIGHT && Settings.keepBrightness(this)) {
            Settings.putFloat(this, "brightness", window.attributes.screenBrightness)
        }
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 700)
    }

    override fun onDoubleTapAt(x: Float, width: Int) {
        val step = Settings.doubleTapMs(this) / 1000
        when {
            x < width / 3f -> { player.seekBack(); flash(R.drawable.exo_styled_controls_simple_rewind, "−${step}s") }
            x > width * 2f / 3f -> { player.seekForward(); flash(R.drawable.exo_styled_controls_simple_fastforward, "+${step}s") }
            else -> if (player.isPlaying) player.pause() else player.play()
        }
    }

    override fun onPinch(scale: Float) {
        zoom = (zoomStart * scale).coerceIn(0.5f, 4f)
        contentFrame()?.let { it.scaleX = zoom; it.scaleY = zoom }
        show(R.drawable.ic_zoom, "${(zoom * 100).roundToInt()}%", ((zoom - 0.5f) / 3.5f * 100).roundToInt())
    }

    override fun onLockedTap() {
        unlockBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 2_500)
    }

    private fun contentFrame(): View? = pv.findViewById(androidx.media3.ui.R.id.exo_content_frame)

    private fun setResize(i: Int, announce: Boolean) {
        resizeIdx = i
        pv.resizeMode = resizeModes[i]
        zoom = 1f
        contentFrame()?.let { it.scaleX = 1f; it.scaleY = 1f }
        if (announce) flash(R.drawable.ic_aspect_w, resizeLabels[i])
    }

    private fun setLocked(on: Boolean) {
        locked = on
        gestures.locked = on
        pv.useController = !on
        if (on) {
            pv.hideController()
            flash(R.drawable.ic_lock_w, getString(R.string.locked))
            onLockedTap()
        } else {
            unlockBtn.visibility = View.GONE
            pv.showController()
        }
    }

    private fun setBoost(b: Float) {
        boost = b
        val e = enhancer ?: return
        runCatching { e.setTargetGain((b * 1000).toInt()); e.enabled = b > 0f } // up to +10 dB
    }

    private fun applyOrientationSetting() {
        requestedOrientation = when (Settings.orientation(this)) {
            "sensor" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> requestedOrientation
        }
    }

    private fun toggleRotation() {
        userRotated = true
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ---------------- menus ----------------

    private fun trackLabel(f: androidx.media3.common.Format, n: Int): String {
        val parts = ArrayList<String>()
        f.language?.takeIf { it != "und" }?.let { parts += Locale.forLanguageTag(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }
        f.label?.takeIf { it.isNotBlank() && !parts.contains(it) }?.let { parts += it }
        if (f.channelCount > 0) parts += when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch" }
        codecName(f.sampleMimeType)?.let { parts += it }
        return if (parts.isEmpty()) getString(R.string.track_n, n) else parts.joinToString(" · ")
    }

    private fun codecName(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.AUDIO_AC3 -> "Dolby AC3"; MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby E-AC3"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"; MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_MPEG -> "MP3"; MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.VIDEO_H264 -> "H.264"; MimeTypes.VIDEO_H265 -> "HEVC"; MimeTypes.VIDEO_VP9 -> "VP9"; MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.TEXT_SSA -> "ASS"; MimeTypes.APPLICATION_SUBRIP -> "SRT"; MimeTypes.TEXT_VTT -> "VTT"; MimeTypes.APPLICATION_PGS -> "PGS"
        else -> mime.substringAfter('/').uppercase()
    }

    private fun tracksOf(type: Int): List<Pair<Tracks.Group, Int>> =
        player.currentTracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }

    private fun audioSheet() {
        val s = Sheet(this, getString(R.string.audio_track))
        val list = tracksOf(C.TRACK_TYPE_AUDIO)
        if (list.isEmpty()) s.row(R.drawable.ic_audiotrack, getString(R.string.none)) {}
        list.forEachIndexed { n, (g, i) ->
            s.row(R.drawable.ic_audiotrack, trackLabel(g.getTrackFormat(i), n + 1), checked = g.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            }
        }
        s.show()
    }

    private fun subtitleSheet() {
        val s = Sheet(this, getString(R.string.subtitles))
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val list = tracksOf(C.TRACK_TYPE_TEXT)
        val anySelected = list.any { (g, i) -> g.isTrackSelected(i) }
        s.row(R.drawable.ic_close, getString(R.string.off), checked = disabled || !anySelected) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        list.forEachIndexed { n, (g, i) ->
            val f = g.getTrackFormat(i)
            val label = listOfNotNull(trackLabel(f, n + 1).takeIf { f.language != null || f.label != null }, codecName(f.sampleMimeType))
                .distinct().joinToString(" · ").ifBlank { getString(R.string.track_n, n + 1) }
            s.row(R.drawable.ic_subtitle, label, checked = !disabled && g.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            }
        }
        s.divider()
        s.row(R.drawable.ic_folder_open, getString(R.string.load_subtitle)) { pickSubtitle.launch(arrayOf("*/*")) }
        s.row(R.drawable.ic_timer, getString(R.string.sub_delay), fmtDelay(subDelayMs)) { subDelaySheet() }
        s.row(R.drawable.ic_edit, getString(R.string.subtitle_style)) { subtitleStyleSheet() }
        s.show()
    }

    private fun subtitleStyleSheet() {
        val s = Sheet(this, getString(R.string.subtitle_style))
        val sizes = resources.getStringArray(R.array.subsize_values)
        val sizeNames = resources.getStringArray(R.array.subsize_names)
        val cur = Settings.p(this).getString("sub_size", "1.0")
        s.view(chips(sizeNames.toList(), sizes.indexOf(cur)) { i ->
            Settings.p(this).edit().putString("sub_size", sizes[i]).apply(); applySubtitleStyle()
        })
        val colors = resources.getStringArray(R.array.subcolor_values)
        val colorNames = resources.getStringArray(R.array.subcolor_names)
        s.view(chips(colorNames.toList(), colors.indexOf(Settings.p(this).getString("sub_color", "#FFFFFF"))) { i ->
            Settings.p(this).edit().putString("sub_color", colors[i]).apply(); applySubtitleStyle()
        })
        s.row(R.drawable.ic_subtitle, getString(R.string.pref_sub_bg), checked = Settings.subBg(this)) {
            Settings.putBool(this, "sub_bg", !Settings.subBg(this)); applySubtitleStyle()
        }
        s.show()
    }

    private fun chips(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val g = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        labels.forEachIndexed { i, l ->
            g.addView(Chip(this).apply {
                text = l; isCheckable = true; isChecked = i == selected
                setOnClickListener { onPick(i) }
            })
        }
        return g
    }

    private fun applySubtitleStyle() {
        subs.apply {
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(false)
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.subScale(this@PlayerActivity))
            setStyle(CaptionStyleCompat(
                Settings.subColor(this@PlayerActivity),
                if (Settings.subBg(this@PlayerActivity)) 0x99000000.toInt() else Color.TRANSPARENT,
                Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
            ))
            setBottomPaddingFraction(0.07f)
        }
    }

    private fun speedSheet() {
        val s = Sheet(this, getString(R.string.speed))
        val label = TextView(this).apply {
            textSize = 30f; setTextColor(ContextCompat.getColor(context, R.color.ink)); paint.isFakeBoldText = true
            gravity = android.view.Gravity.CENTER
            text = fmtSpeed(player.playbackParameters.speed)
        }
        val slider = Slider(this).apply {
            valueFrom = 0.25f; valueTo = 4f; stepSize = 0.05f
            value = (player.playbackParameters.speed.coerceIn(0.25f, 4f) / 0.05f).roundToInt() * 0.05f
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) { player.setPlaybackSpeed(v); label.text = fmtSpeed(v) }
            }
            setPadding(dp(16), 0, dp(16), 0)
        }
        val presets = SPEEDS.map { fmtSpeed(it) }
        val chipView = chips(presets, SPEEDS.indexOfFirst { abs(it - player.playbackParameters.speed) < 0.01f }) { i ->
            player.setPlaybackSpeed(SPEEDS[i]); slider.value = SPEEDS[i]; label.text = fmtSpeed(SPEEDS[i])
        }
        s.view(label).view(slider).view(chipView).show()
    }

    private fun fmtSpeed(v: Float) = (if (v == v.toInt().toFloat()) "${v.toInt()}.0" else "%.2f".format(Locale.US, v).trimEnd('0')) + "×"

    private fun updateSpeedLabel() { speedBtn.text = fmtSpeed(player.playbackParameters.speed) }

    private fun queueSheet() {
        val s = Sheet(this, getString(R.string.playlist))
        for (i in 0 until player.mediaItemCount) {
            val m = player.getMediaItemAt(i)
            s.row(if (i == player.currentMediaItemIndex) R.drawable.ic_play else R.drawable.ic_video_lib,
                m.mediaMetadata.title ?: "", checked = i == player.currentMediaItemIndex) {
                saveResume()
                player.seekTo(i, if (Settings.resume(this)) Resume.get(this, m.mediaId) else 0L)
                player.play()
            }
        }
        s.show()
    }

    private fun moreSheet() {
        val s = Sheet(this, null)
        s.row(R.drawable.ic_speed, getString(R.string.speed), fmtSpeed(player.playbackParameters.speed)) { speedSheet() }
        s.row(R.drawable.ic_palette, getString(R.string.color_mode),
            getString(ColorModes.byKey(Settings.p(this).getString("color_mode", "normal")).label)) { colorModeSheet() }
        s.row(R.drawable.ic_tune, getString(R.string.adjust)) { adjustSheet() }
        s.row(R.drawable.ic_camera, getString(R.string.screenshot)) { screenshot() }
        s.row(R.drawable.ic_aspect, getString(R.string.aspect), resizeLabels[resizeIdx]) {
            val a = Sheet(this, getString(R.string.aspect))
            resizeLabels.forEachIndexed { i, l -> a.row(R.drawable.ic_aspect, l, checked = i == resizeIdx) { setResize(i, true) } }
            a.show()
        }
        val sleepVal = if (sleepAt > 0) fmtTime(sleepAt - System.currentTimeMillis()) else getString(R.string.sleep_off)
        s.row(R.drawable.ic_timer, getString(R.string.sleep_timer), sleepVal) { sleepSheet() }
        val repeatNames = arrayOf(getString(R.string.repeat_off), getString(R.string.repeat_one), getString(R.string.repeat_all))
        s.row(R.drawable.ic_repeat, getString(R.string.repeat), repeatNames[player.repeatMode]) {
            player.repeatMode = (player.repeatMode + 1) % 3
            player.pauseAtEndOfMediaItems = !Settings.autoNext(this) && player.repeatMode == Player.REPEAT_MODE_OFF
            flash(R.drawable.ic_repeat, repeatNames[player.repeatMode])
        }
        s.row(R.drawable.ic_headphones, getString(R.string.pref_bg_play), checked = Settings.bgPlay(this)) {
            val on = !Settings.bgPlay(this)
            Settings.putBool(this, "bg_play", on)
            if (on) connectBackground()
            flash(R.drawable.ic_headphones, getString(if (on) R.string.bg_on else R.string.bg_off))
        }
        s.row(R.drawable.ic_lock, getString(R.string.lock_controls),
            getString(if (Settings.lockSimple(this)) R.string.lock_simple else R.string.lock_full)) { lockControlsSheet() }
        s.row(R.drawable.ic_rotate, getString(R.string.rotate)) { toggleRotation() }
        s.row(R.drawable.ic_pip, getString(R.string.pip)) { enterPip() }
        s.row(R.drawable.ic_info, getString(R.string.properties)) { infoSheet() }
        s.row(R.drawable.ic_share, getString(R.string.share)) {
            player.currentMediaItem?.localConfiguration?.uri?.let { u ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.share)))
            }
        }
        s.row(R.drawable.ic_settings, getString(R.string.settings)) { startActivity(Intent(this, SettingsActivity::class.java)) }
        s.show()
    }

    private fun lockControlsSheet() {
        val simple = Settings.lockSimple(this)
        Sheet(this, getString(R.string.lock_controls))
            .row(R.drawable.ic_fast, getString(R.string.lock_full), checked = !simple) { setLockSimple(false) }
            .row(R.drawable.ic_lock, getString(R.string.lock_simple), checked = simple) { setLockSimple(true) }
            .show()
    }

    private fun setLockSimple(on: Boolean) {
        Settings.setLockSimple(this, on)
        PlaybackService.applyWidgetCommands(this)
        flash(R.drawable.ic_lock_w, getString(if (on) R.string.lock_simple else R.string.lock_full))
    }

    private fun sleepSheet() {
        val s = Sheet(this, getString(R.string.sleep_timer))
        s.row(R.drawable.ic_close, getString(R.string.sleep_off), checked = sleepAt == 0L) { setSleep(0) }
        intArrayOf(15, 30, 45, 60, 90, 120).forEach { m -> s.row(R.drawable.ic_timer, getString(R.string.minutes, m)) { setSleep(m) } }
        s.show()
    }

    private fun setSleep(min: Int) {
        handler.removeCallbacks(sleepRun)
        sleepAt = if (min > 0) System.currentTimeMillis() + min * 60_000L else 0L
        if (min > 0) {
            handler.postDelayed(sleepRun, min * 60_000L)
            flash(R.drawable.ic_timer, getString(R.string.sleep_video_in, min))
        }
        bindTitle()
    }

    private fun infoSheet() {
        val s = Sheet(this, getString(R.string.properties))
        val item = player.currentMediaItem
        val uri = item?.localConfiguration?.uri
        s.text(getString(R.string.file_name), uri?.let { displayName(it) } ?: "—")
        if (uri != null && uri.scheme == "content") runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    s.text(getString(R.string.size), fmtSize(this, c.getLong(0)))
                    c.getString(1)?.let { s.text(getString(R.string.location), it) }
                }
            }
        }
        if (player.duration > 0) s.text(getString(R.string.duration), fmtTime(player.duration))
        player.videoFormat?.let { f ->
            val v = listOfNotNull(
                if (f.width > 0) "${f.width}×${f.height}" else null,
                codecName(f.sampleMimeType),
                if (f.frameRate > 0) "%.2f fps".format(Locale.US, f.frameRate) else null,
                if (f.bitrate > 0) "${f.bitrate / 1000} kbps" else null,
            ).joinToString(" · ")
            s.text(getString(R.string.video), v)
        }
        player.audioFormat?.let { f ->
            val a = listOfNotNull(codecName(f.sampleMimeType),
                if (f.channelCount > 0) "${f.channelCount} ch" else null,
                if (f.sampleRate > 0) "${f.sampleRate / 1000.0} kHz" else null,
                f.language?.takeIf { it != "und" }).joinToString(" · ")
            s.text(getString(R.string.audio), a)
        }
        s.show()
    }

    private fun addSubtitle(uri: Uri) {
        val name = displayName(uri).lowercase()
        val mime = when {
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ttml") || name.endsWith(".xml") || name.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val current = player.currentMediaItem ?: return
        extSubs.getOrPut(current.mediaId) { mutableListOf() }.add(ExtSub(uri, displayName(uri), mime))
        subDelayMs = 0; cueDelayMs = 0
        val sub = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setLabel(displayName(uri)).build()
        val updated = current.buildUpon()
            .setSubtitleConfigurations((current.localConfiguration?.subtitleConfigurations ?: emptyList()) + sub).build()
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
        player.replaceMediaItem(idx, updated)
        player.seekTo(idx, pos)
        player.prepare(); player.play()
        flash(R.drawable.ic_subtitle_w, getString(R.string.subtitle_added))
    }

    // ---------------- background play (screen off / leaving the app) ----------------

    private fun connectBackground() {
        if (bgFuture != null) return
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        bgFuture = f
        f.addListener({ bg = runCatching { f.get() }.getOrNull() }, ContextCompat.getMainExecutor(this))
    }

    private fun loadArt(item: MediaItem) {
        val uri = item.localConfiguration?.uri ?: return
        if (uri.scheme != "content") return
        val id = item.mediaId
        Thread {
            val bmp = Thumbs.fetch(this, uri, uri.lastPathSegment?.toLongOrNull() ?: 0L, true) ?: return@Thread
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            runOnUiThread { artBytes = out.toByteArray(); artFor = id }
        }.start()
    }

    private fun handOff(): Boolean {
        val c = bg ?: return false
        val cur = player.currentMediaItem ?: return false
        val uri = cur.localConfiguration?.uri ?: return false
        val item = MediaItem.Builder().setMediaId(cur.mediaId).setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(cur.mediaMetadata.title).setArtist(getString(R.string.app_name))
                .apply { if (artFor == cur.mediaId) artBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build())
            .build()
        c.setMediaItem(item, player.currentPosition)
        c.setPlaybackSpeed(player.playbackParameters.speed)
        c.prepare(); c.play()
        handedOffId = cur.mediaId
        player.pause()
        return true
    }

    private fun reclaim() {
        val id = handedOffId ?: return
        handedOffId = null
        val c = bg ?: return
        if (c.currentMediaItem?.mediaId != id) return
        val pos = c.currentPosition
        val playing = c.isPlaying
        c.stop(); c.clearMediaItems()
        player.seekTo(pos)
        if (playing) player.play()
    }

    // ---------------- picture in picture ----------------

    private fun isInPip() = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val vs = player.videoSize
        val b = PictureInPictureParams.Builder()
        if (vs.width > 0 && vs.height > 0) {
            val r = (vs.width * vs.pixelWidthHeightRatio / vs.height).coerceIn(1 / 2.3f, 2.3f)
            b.setAspectRatio(Rational((r * 1000).toInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(player.isPlaying && !Settings.bgPlay(this)).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePipParams() { if (Build.VERSION.SDK_INT >= 26) runCatching { pipParams()?.let { setPictureInPictureParams(it) } } }
    private fun enterPip() { if (Build.VERSION.SDK_INT >= 26) runCatching { pipParams()?.let { enterPictureInPictureMode(it) } } }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying && !Settings.bgPlay(this)) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        gestures.gesturesEnabled = !isInPictureInPictureMode
        if (!isInPictureInPictureMode && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED && screenOn()) {
            closeFromPip() // the user swiped away / tapped X on the floating window
            return
        }
        if (isInPictureInPictureMode) {
            pv.useController = false
            info.visibility = View.GONE; resumeChip.visibility = View.GONE
        } else if (!locked) pv.useController = true
    }

    // ---------------- lifecycle ----------------

    override fun onStart() {
        super.onStart()
        reclaim()
    }

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
        // Closing the floating (PiP) window means "stop", not "keep playing in the background".
        if (isInPip() && screenOn()) { closeFromPip(); return }
        val keepGoing = Settings.bgPlay(this) && player.isPlaying && !isFinishing && !isChangingConfigurations
        if (!(keepGoing && handOff())) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveResume()
        runCatching { enhancer?.release() }
        session?.release()
        seekPreview?.release()
        player.release()
        bgFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    // ---------------- subtitle delay ----------------

    private fun fmtDelay(ms: Long) = (if (ms > 0) "+" else if (ms < 0) "−" else "") + "%.1f s".format(Locale.US, abs(ms) / 1000f)

    private fun selectedTextIsExternal(): Boolean {
        val names = extSubs[player.currentMediaItem?.mediaId]?.map { it.name } ?: return false
        return tracksOf(C.TRACK_TYPE_TEXT).any { (g, i) -> g.isTrackSelected(i) && g.getTrackFormat(i).label in names }
    }

    private fun subDelaySheet() {
        val s = Sheet(this, getString(R.string.sub_delay))
        val label = TextView(this).apply {
            textSize = 30f; setTextColor(ContextCompat.getColor(context, R.color.ink)); paint.isFakeBoldText = true
            gravity = android.view.Gravity.CENTER; text = fmtDelay(subDelayMs)
        }
        val slider = Slider(this).apply {
            valueFrom = -10f; valueTo = 10f; stepSize = 0.1f
            value = (subDelayMs / 100L).toFloat() / 10f
            setPadding(dp(16), 0, dp(16), 0)
            addOnChangeListener { _, v, _ -> label.text = fmtDelay((v * 1000).roundToInt().toLong()) }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(sl: Slider) {}
                override fun onStopTrackingTouch(sl: Slider) { applySubDelay((sl.value * 1000).roundToInt().toLong()) }
            })
        }
        val steps = listOf(-500L, -100L, 0L, 100L, 500L)
        val chipView = chips(listOf("−0.5", "−0.1", getString(R.string.reset), "+0.1", "+0.5"), -1) { i ->
            val v = if (steps[i] == 0L) 0L else (subDelayMs + steps[i]).coerceIn(-10_000, 10_000)
            slider.value = (v / 100L).toFloat() / 10f; label.text = fmtDelay(v); applySubDelay(v)
        }
        val note = TextView(this).apply {
            text = getString(R.string.sub_delay_embedded_note); textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.muted)); setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        s.view(label).view(slider).view(chipView).view(note).show()
    }

    private fun applySubDelay(ms: Long) {
        subDelayMs = ms
        val item = player.currentMediaItem ?: return
        val ext = extSubs[item.mediaId]
        if (ext != null && selectedTextIsExternal()) {
            // Rewrite the loaded subtitle files with shifted times (works both ways).
            cueDelayMs = 0
            val selectedName = tracksOf(C.TRACK_TYPE_TEXT).firstOrNull { (g, i) -> g.isTrackSelected(i) }?.let { (g, i) -> g.getTrackFormat(i).label }
            val configs = ext.map { e ->
                val u = if (ms == 0L) e.uri else SubShift.shifted(this, e.uri, e.name, -ms) ?: e.uri
                MediaItem.SubtitleConfiguration.Builder(u).setMimeType(e.mime).setLabel(e.name)
                    .setSelectionFlags(if (e.name == selectedName) C.SELECTION_FLAG_DEFAULT else 0).build()
            }
            val idx = player.currentMediaItemIndex
            val pos = player.currentPosition
            val playing = player.playWhenReady
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            player.replaceMediaItem(idx, item.buildUpon().setSubtitleConfigurations(configs).build())
            player.seekTo(idx, pos); player.prepare(); player.playWhenReady = playing
            subDelayMs = ms
        } else {
            cueDelayMs = ms.coerceAtLeast(0)
            if (ms < 0) Toast.makeText(this, R.string.sub_delay_embedded_note, Toast.LENGTH_LONG).show()
            cueQueue.clear()
        }
        flash(R.drawable.ic_subtitle_w, getString(R.string.sub_delay) + "  " + fmtDelay(if (cueDelayMs > 0 || selectedTextIsExternal()) ms else cueDelayMs))
    }

    // ---------------- screenshot ----------------

    private fun screenshot() {
        val sv = pv.videoSurfaceView as? android.view.SurfaceView
        val vs = player.videoSize
        if (sv == null || vs.width <= 0 || vs.height <= 0 || Build.VERSION.SDK_INT < 24) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show(); return
        }
        val bmp = android.graphics.Bitmap.createBitmap(vs.width, vs.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(sv, bmp, { r ->
            if (r != android.view.PixelCopy.SUCCESS) { Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show(); return@request }
            val title = (player.currentMediaItem?.mediaMetadata?.title ?: "Reel").toString().replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(40)
            val name = "Reel_${title}_${fmtTime(player.currentPosition).replace(':', '-')}.jpg"
            Thread {
                val ok = saveImage(bmp, name)
                runOnUiThread {
                    if (ok) flash(R.drawable.ic_camera, getString(R.string.screenshot_saved))
                    else Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }, handler)
    }

    private fun saveImage(bmp: android.graphics.Bitmap, name: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Reel")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)!!
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 94, it) }
            contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Reel").apply { mkdirs() }
            val f = java.io.File(dir, name)
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 94, it) }
            android.media.MediaScannerConnection.scanFile(this, arrayOf(f.path), null, null)
        }
        true
    }.getOrDefault(false)

    // ---------------- picture adjustments ----------------

    private fun adj(k: String) = Settings.getInt(this, k, 0)

    private fun currentEffects(): List<androidx.media3.common.Effect> {
        val list = ArrayList(ColorModes.byKey(Settings.p(this).getString("color_mode", "normal")).effects())
        val b = adj("adj_b"); val c = adj("adj_c"); val sa = adj("adj_s")
        if (b != 0) list += androidx.media3.effect.Brightness(b / 100f)
        if (c != 0) list += androidx.media3.effect.Contrast(c / 100f)
        if (sa != 0) list += androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(sa.toFloat()).build()
        return list
    }

    private fun applyEffects() {
        val list = currentEffects()
        if (!effectsReady) {
            if (list.isEmpty()) return
            val pos = player.currentPosition
            val idx = player.currentMediaItemIndex
            val playing = player.playWhenReady
            player.stop()
            player.setVideoEffects(list)
            effectsReady = true
            player.seekTo(idx, pos)
            player.prepare()
            player.playWhenReady = playing
        } else player.setVideoEffects(list)
    }

    private var lastLive = 0L
    private fun adjustSheet() {
        val s = Sheet(this, getString(R.string.adjust))
        listOf(Triple("adj_b", R.string.adj_brightness, 50), Triple("adj_c", R.string.adj_contrast, 50), Triple("adj_s", R.string.adj_saturation, 100))
            .forEach { (key, label, range) ->
                val title = TextView(this).apply {
                    textSize = 14f; setTextColor(ContextCompat.getColor(context, R.color.muted)); setPadding(dp(24), dp(8), dp(24), 0)
                    text = getString(label) + "  " + adj(key)
                }
                val slider = Slider(this).apply {
                    valueFrom = -range.toFloat(); valueTo = range.toFloat(); stepSize = 1f; value = adj(key).toFloat()
                    setPadding(dp(16), 0, dp(16), 0)
                    addOnChangeListener { _, v, fromUser ->
                        title.text = getString(label) + "  " + v.toInt()
                        if (fromUser) {
                            Settings.putInt(context, key, v.toInt())
                            val now = System.currentTimeMillis()
                            if (effectsReady && now - lastLive > 150) { lastLive = now; applyEffects() }
                        }
                    }
                    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(sl: Slider) {}
                        override fun onStopTrackingTouch(sl: Slider) { applyEffects() }
                    })
                }
                s.view(title).view(slider)
            }
        s.row(R.drawable.ic_refresh, getString(R.string.reset)) {
            listOf("adj_b", "adj_c", "adj_s").forEach { Settings.putInt(this, it, 0) }; applyEffects()
        }
        s.show()
    }

    private fun screenOn() = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive

    private var closing = false
    private var effectsReady = false

    private fun colorModeSheet() {
        val cur = Settings.p(this).getString("color_mode", "normal")
        val s = Sheet(this, getString(R.string.color_mode))
        ColorModes.all.forEach { m ->
            s.row(R.drawable.ic_palette, getString(m.label), checked = m.key == cur) { setColorMode(m.key) }
        }
        s.show()
    }

    private fun setColorMode(key: String) {
        Settings.p(this).edit().putString("color_mode", key).apply()
        val m = ColorModes.byKey(key)
        applyEffects()
        flash(R.drawable.ic_palette, getString(m.label))
    }
    private fun closeFromPip() {
        if (closing) return
        closing = true
        saveResume()
        player.pause()
        player.stop()
        finishAndRemoveTask()
    }

    private fun saveResume() {
        if (!::player.isInitialized || handedOffId != null) return
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

    private fun show(icon: Int, text: String, progress: Int = -1) {
        handler.removeCallbacks(hideInfo)
        infoIcon.setImageResource(icon)
        infoText.text = text
        infoBar.visibility = if (progress >= 0) View.VISIBLE else View.GONE
        if (progress >= 0) infoBar.progress = progress
        info.visibility = View.VISIBLE
    }

    private fun flash(icon: Int, text: String) {
        show(icon, text)
        handler.postDelayed(hideInfo, 1_000)
    }

}
