package com.ahmadnayfeh.reel

import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import java.util.concurrent.Executors

/** Small frame + time that follows your finger while you drag the progress bar. */
class SeekPreview(
    private val act: Activity,
    private val card: View,
    private val timeBar: DefaultTimeBar,
    private val uri: () -> Uri?,
    private val duration: () -> Long,
) : TimeBar.OnScrubListener {

    private val image: ImageView = card.findViewById(R.id.previewImage)
    private val time: TextView = card.findViewById(R.id.previewTime)
    private val exec = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var pending = -1L
    private var busy = false
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUri: Uri? = null

    init {
        image.clipToOutline = true
        timeBar.addListener(this)
    }

    override fun onScrubStart(timeBar: TimeBar, position: Long) = update(position)
    override fun onScrubMove(timeBar: TimeBar, position: Long) = update(position)
    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { card.visibility = View.GONE }

    private fun update(pos: Long) {
        val dur = duration().takeIf { it > 0 } ?: return
        time.text = fmtTime(pos)
        card.visibility = View.VISIBLE
        val parent = card.parent as View
        val pLoc = IntArray(2).also { parent.getLocationInWindow(it) }
        val tLoc = IntArray(2).also { timeBar.getLocationInWindow(it) }
        val w = card.width.takeIf { it > 0 } ?: act.dp(168)
        val h = card.height.takeIf { it > 0 } ?: act.dp(118)
        val inset = act.dp(8)
        val x = tLoc[0] - pLoc[0] + inset + (timeBar.width - 2 * inset) * (pos.toFloat() / dur) - w / 2f
        card.translationX = x.coerceIn(act.dp(8).toFloat(), (parent.width - w - act.dp(8)).toFloat())
        card.translationY = (tLoc[1] - pLoc[1] - h - act.dp(10)).toFloat()
        request(pos)
    }

    private fun request(pos: Long) {
        synchronized(lock) {
            pending = pos
            if (busy) return
            busy = true
        }
        val u = uri() ?: run { synchronized(lock) { busy = false }; return }
        exec.execute {
            while (true) {
                val p = synchronized(lock) { val v = pending; pending = -1; if (v < 0) busy = false; v }
                if (p < 0) break
                val bmp = frame(u, p)
                if (bmp != null) act.runOnUiThread { image.setImageBitmap(bmp) }
            }
        }
    }

    private fun frame(u: Uri, posMs: Long): Bitmap? = runCatching {
        if (retrieverUri != u) {
            retriever?.release()
            retriever = MediaMetadataRetriever().apply {
                if (u.scheme == "http" || u.scheme == "https") setDataSource(u.toString(), HashMap()) else setDataSource(act, u)
            }
            retrieverUri = u
        }
        val r = retriever!!
        if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
        else r.getFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }.getOrNull()

    fun release() {
        timeBar.removeListener(this)
        exec.execute { runCatching { retriever?.release() }; retriever = null }
        exec.shutdown()
    }
}
