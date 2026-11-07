package com.ahmadnayfeh.reel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Size
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Artwork for the lock screen / notification / Now Bar:
 * embedded cover → album or video thumbnail → a generated Reel cover (never the empty grey note).
 */
class ArtLoader(private val ctx: Context) : BitmapLoader {
    private val exec = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String) = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = exec.submit<Bitmap> {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: cover(null, null)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = exec.submit<Bitmap> { thumb(uri) ?: cover(null, null) }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> = exec.submit<Bitmap> {
        metadata.artworkData?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: metadata.artworkUri?.let { thumb(it) }
            ?: cover(metadata.title?.toString(), (metadata.albumTitle ?: metadata.artist)?.toString())
    }

    private fun thumb(uri: Uri): Bitmap? = if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content")
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(640, 640), null) }.getOrNull() else null

    companion object {
        /** Dark card with a soft coral glow, the Reel mark, and the folder / album name. */
        fun cover(title: String?, subtitle: String?): Bitmap {
            val n = 640
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            // stable hue per folder so different folders look different
            val seed = (subtitle ?: title ?: "Reel").hashCode()
            val hues = intArrayOf(0xFF8A5C.toInt(), 0x7C83FF, 0x2EC4B6, 0xF15BB5, 0xFFC857)
            val accent = hues[Math.floorMod(seed, hues.size)] or 0xFF000000.toInt()
            p.shader = LinearGradient(0f, 0f, n.toFloat(), n.toFloat(), Color.parseColor("#1E2129"), Color.parseColor("#0B0C10"), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
            p.shader = RadialGradient(n * 0.78f, n * 0.18f, n * 0.85f, (accent and 0x00FFFFFF) or 0x99000000.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
            p.shader = null
            // Reel mark: ring + rounded play triangle
            val cx = n / 2f; val cy = n * 0.42f; val r = n * 0.17f
            p.style = Paint.Style.STROKE; p.strokeWidth = n * 0.018f; p.color = Color.WHITE
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL; p.color = accent
            val t = Path().apply {
                moveTo(cx - r * 0.32f, cy - r * 0.48f); lineTo(cx + r * 0.52f, cy); lineTo(cx - r * 0.32f, cy + r * 0.48f); close()
            }
            p.pathEffect = android.graphics.CornerPathEffect(n * 0.012f)
            c.drawPath(t, p)
            p.pathEffect = null
            // label
            val label = subtitle?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: "Reel"
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = n * 0.07f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val w = (n * 0.84f).toInt()
            val layout = StaticLayout.Builder.obtain(label, 0, label.length, tp, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.translate((n - w) / 2f, n * 0.70f); layout.draw(c); c.restore()
            return b
        }
    }
}
