package com.ahmadnayfeh.reel

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class Video(
    val id: Long, val uri: Uri, val name: String, val duration: Long, val size: Long, val date: Long,
    val folder: String, val folderId: String, val width: Int, val height: Int,
    /** e.g. "Movies/Series/" (Android 10+) — used for moving files. */
    val relPath: String, val path: String?,
) {
    val key get() = uri.toString()
    val title get() = name.substringBeforeLast('.')
    val quality: String
        get() {
            val s = minOf(width, height).takeIf { it > 0 } ?: return ""
            return when {
                s >= 2100 -> "4K"; s >= 1400 -> "1440p"; s >= 1000 -> "1080p"; s >= 700 -> "720p"
                s >= 470 -> "480p"; else -> "${s}p"
            }
        }
}

data class Song(
    val id: Long, val uri: Uri, val title: String, val artist: String,
    val album: String, val duration: Long, val size: Long, val date: Long, val path: String?,
    val folder: String = "Music", val folderId: String = "music", val relPath: String = "Music/",
) { val key get() = uri.toString() }

object MediaRepo {
    /** Test hook. */
    var fakeVideos: List<Video>? = null
    var fakeSongs: List<Song>? = null

    private fun videoCollection(): Uri = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    @Suppress("DEPRECATION")
    fun videos(ctx: Context): List<Video> {
        fakeVideos?.let { return it }
        val out = ArrayList<Video>()
        val coll = videoCollection()
        val cols = mutableListOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= 29) cols += MediaStore.Video.Media.RELATIVE_PATH
        runCatching {
            ctx.contentResolver.query(coll, cols.toTypedArray(), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val path = c.getString(9)
                    val rel = if (Build.VERSION.SDK_INT >= 29) c.getString(10) ?: "" else relFromPath(path)
                    out += Video(
                        id, ContentUris.withAppendedId(coll, id), c.getString(1) ?: "Video", c.getLong(2), c.getLong(3),
                        c.getLong(4), c.getString(5) ?: "Internal storage", c.getString(6) ?: "0", c.getInt(7), c.getInt(8),
                        rel, path,
                    )
                }
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    fun songs(ctx: Context): List<Song> {
        fakeSongs?.let { return it }
        val out = ArrayList<Song>()
        val coll = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= 29) cols += listOf(
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME, MediaStore.Audio.Media.BUCKET_ID, MediaStore.Audio.Media.RELATIVE_PATH,
        )
        runCatching {
            ctx.contentResolver.query(coll, cols.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val artist = c.getString(2)?.takeUnless { it == "<unknown>" } ?: ""
                    val path = c.getString(7)
                    val parent = path?.let { File(it).parentFile }
                    val folder = (if (Build.VERSION.SDK_INT >= 29) c.getString(8) else null) ?: parent?.name ?: "Music"
                    val folderId = (if (Build.VERSION.SDK_INT >= 29) c.getString(9) else null) ?: parent?.path?.hashCode()?.toString() ?: "music"
                    val rel = (if (Build.VERSION.SDK_INT >= 29) c.getString(10) else null) ?: relFromPath(path)
                    out += Song(id, ContentUris.withAppendedId(coll, id), c.getString(1) ?: "Track", artist,
                        c.getString(3) ?: "", c.getLong(4), c.getLong(5), c.getLong(6), path, folder, folderId, rel)
                }
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun relFromPath(path: String?): String {
        if (path == null) return ""
        val root = android.os.Environment.getExternalStorageDirectory().path
        val parent = File(path).parent ?: return ""
        return if (parent.startsWith(root)) parent.removePrefix(root).trim('/') + "/" else parent
    }
}

fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtSize(ctx: Context, b: Long): String = Formatter.formatShortFileSize(ctx, b)

/** Remembers where you stopped in every video (stored inside the app, per file). */
object Resume {
    private fun p(c: Context) = c.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): Long = p(ctx).getLong("pos:$key", 0L)

    fun save(ctx: Context, key: String, pos: Long, dur: Long) {
        val e = p(ctx).edit().putBoolean("seen:$key", true)
        if (dur <= 0 || pos < 5_000 || dur - pos < 15_000) e.remove("pos:$key").remove("dur:$key")
        else e.putLong("pos:$key", pos).putLong("dur:$key", dur)
        e.apply()
    }

    fun clear(ctx: Context, key: String) = save(ctx, key, 0, 0)
    fun markSeen(ctx: Context, key: String) = p(ctx).edit().putBoolean("seen:$key", true).apply()
    fun seen(ctx: Context, key: String) = p(ctx).getBoolean("seen:$key", false)

    /** 0..1000, or -1 when there is no saved position. */
    fun progress(ctx: Context, key: String, dur: Long): Int {
        val pos = get(ctx, key)
        val d = if (dur > 0) dur else p(ctx).getLong("dur:$key", 0L)
        return if (pos <= 0 || d <= 0) -1 else (pos * 1000 / d).toInt().coerceIn(0, 1000)
    }
}

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val io = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun load(iv: ImageView, uri: Uri, id: Long, video: Boolean, placeholder: Int) {
        val key = uri.toString()
        iv.tag = key
        cache.get(key)?.let { show(iv, it); return }
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.setImageResource(placeholder)
        scope.launch {
            val bmp = withContext(io) { fetch(iv.context, uri, id, video) }
            if (bmp != null) {
                cache.put(key, bmp)
                if (iv.tag == key) show(iv, bmp)
            }
        }
    }

    fun fetch(ctx: Context, uri: Uri, id: Long, video: Boolean): Bitmap? = runCatching {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) cr.loadThumbnail(uri, Size(480, 270), null)
        else if (video) @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        else null
    }.getOrNull()

    private fun show(iv: ImageView, b: Bitmap) {
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setImageBitmap(b)
    }
}
