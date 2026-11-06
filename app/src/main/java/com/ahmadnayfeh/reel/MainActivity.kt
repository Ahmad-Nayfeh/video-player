package com.ahmadnayfeh.reel

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private sealed class Row {
        data class Folder(val id: String, val name: String, val count: Int, val cover: Video?, val special: Boolean = false) : Row()
        data class VideoRow(val v: Video) : Row()
        data class SongRow(val s: Song) : Row()
    }

    private companion object {
        const val CONTINUE = "__continue__"
        const val SORT_NAME = 0; const val SORT_DATE = 1; const val SORT_SIZE = 2; const val SORT_DURATION = 3
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var mini: View
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton

    private var videos: List<Video> = emptyList()
    private var songs: List<Song> = emptyList()
    private var rows: List<Row> = emptyList()
    private var tab = 0
    private var folder: String? = null
    private var query = ""
    private var sort = SORT_NAME
    private var searchItem: MenuItem? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val adapter = Adapter()

    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { scan() }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { openFolder(null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            ins
        }
        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        mini = findViewById(R.id.mini)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlay = findViewById(R.id.miniPlay)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { openFolder(null) }
        sort = prefs().getInt("sort", SORT_NAME)

        tabs.addTab(tabs.newTab().setText(R.string.tab_videos))
        tabs.addTab(tabs.newTab().setText(R.string.tab_music))
        tab = prefs().getInt("tab", 0)
        tabs.getTabAt(tab)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                prefs().edit().putInt("tab", tab).apply()
                folder = null
                render()
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) { list.smoothScrollToPosition(0) }
        })

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        empty.setOnClickListener { if (!hasPermission()) askPermission.launch(permissions) }
        onBackPressedDispatcher.addCallback(this, back)

        mini.setOnClickListener { startActivity(Intent(this, NowPlayingActivity::class.java)) }
        miniPlay.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }

        if (!hasPermission()) askPermission.launch(permissions)
    }

    override fun onStart() {
        super.onStart()
        scan()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            controller = runCatching { f.get() }.getOrNull()
            controller?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateMini()
            })
            updateMini()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun hasPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return needed.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun scan() {
        if (!hasPermission()) { render(); return }
        lifecycleScope.launch {
            val (v, s) = withContext(Dispatchers.IO) { MediaRepo.videos(this@MainActivity) to MediaRepo.songs(this@MainActivity) }
            videos = v; songs = s
            render()
        }
    }

    // ---- building the list ----

    private fun <T> sorted(items: List<T>, name: (T) -> String, date: (T) -> Long, size: (T) -> Long, dur: (T) -> Long): List<T> =
        when (sort) {
            SORT_DATE -> items.sortedByDescending(date)
            SORT_SIZE -> items.sortedByDescending(size)
            SORT_DURATION -> items.sortedByDescending(dur)
            else -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, name))
        }

    private fun sortVideos(l: List<Video>) = sorted(l, { it.name }, { it.date }, { it.size }, { it.duration })

    private fun continueList() = videos
        .filter { Resume.progress(this, it.uri.toString(), it.duration) >= 0 }
        .sortedByDescending { Resume.lastWatched(this, it.uri.toString()) }

    private fun render() {
        val q = query.trim()
        rows = if (tab == 0) {
            val f = folder
            when {
                q.isNotEmpty() -> sortVideos(videos.filter { it.name.contains(q, true) || it.folder.contains(q, true) }).map { Row.VideoRow(it) }
                f == CONTINUE -> continueList().map { Row.VideoRow(it) }
                f != null -> sortVideos(videos.filter { it.folderId == f }).map { Row.VideoRow(it) }
                else -> {
                    val out = ArrayList<Row>()
                    val cont = continueList()
                    if (cont.isNotEmpty()) out += Row.Folder(CONTINUE, getString(R.string.continue_watching), cont.size, cont.first(), true)
                    val groups = videos.groupBy { it.folderId }.map { (id, vs) ->
                        Row.Folder(id, vs.first().folder, vs.size, vs.maxByOrNull { it.date })
                    }
                    out += sorted(groups, { it.name }, { it.cover?.date ?: 0 }, { it.count.toLong() }, { it.count.toLong() })
                    out
                }
            }
        } else {
            val l = if (q.isEmpty()) songs else songs.filter {
                it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
            }
            sorted(l, { it.title }, { it.date }, { it.size }, { it.duration }).map { Row.SongRow(it) }
        }

        val f = folder
        if (tab == 0 && f != null) {
            toolbar.title = if (f == CONTINUE) getString(R.string.continue_watching) else videos.firstOrNull { it.folderId == f }?.folder
            toolbar.setNavigationIcon(R.drawable.ic_back)
        } else {
            toolbar.title = getString(R.string.app_name)
            toolbar.navigationIcon = null
        }
        back.isEnabled = tab == 0 && f != null

        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(if (hasPermission()) R.string.no_media else R.string.grant_access)
        adapter.notifyDataSetChanged()
    }

    private fun openFolder(id: String?) {
        folder = id
        render()
        list.scrollToPosition(0)
    }

    // ---- actions ----

    private fun playVideos(list: List<Video>, index: Int) {
        val i = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(list.map { it.uri.toString() }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(list.map { it.name.substringBeforeLast('.') }))
            .putExtra(PlayerActivity.EXTRA_INDEX, index)
        startActivity(i)
    }

    private fun playSongs(list: List<Song>, index: Int) {
        val c = controller ?: return
        val items = list.map { s ->
            MediaItem.Builder()
                .setMediaId(s.uri.toString())
                .setUri(s.uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(s.uri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
                        .setIsPlayable(true).build()
                ).build()
        }
        c.setMediaItems(items, index, 0)
        c.prepare()
        c.play()
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun share(uri: Uri, type: String) {
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun updateMini() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) { mini.visibility = View.GONE; return }
        mini.visibility = View.VISIBLE
        val m = c.mediaMetadata
        miniTitle.text = m.title ?: ""
        miniSub.text = m.artist ?: ""
        miniPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        searchItem = menu.findItem(R.id.search)
        (searchItem?.actionView as? SearchView)?.apply {
            queryHint = getString(R.string.search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean { this@MainActivity.query = q.orEmpty(); render(); return true }
            })
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort -> {
                val labels = arrayOf(getString(R.string.sort_name), getString(R.string.sort_date), getString(R.string.sort_size), getString(R.string.sort_duration))
                MaterialAlertDialogBuilder(this).setTitle(R.string.sort)
                    .setSingleChoiceItems(labels, sort) { d, which ->
                        sort = which
                        prefs().edit().putInt("sort", sort).apply()
                        render(); d.dismiss()
                    }.show()
            }
            R.id.url -> {
                val input = EditText(this).apply { hint = getString(R.string.url_hint); setSingleLine() }
                val pad = (20 * resources.displayMetrics.density).toInt()
                val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
                MaterialAlertDialogBuilder(this).setTitle(R.string.open_url).setView(box)
                    .setPositiveButton(R.string.play) { _, _ ->
                        val u = input.text.toString().trim()
                        if (u.isNotEmpty()) startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(u)))
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---- adapter ----

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val badge: TextView = v.findViewById(R.id.badge)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val box: View = v.findViewById(R.id.thumbBox)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val ctx = this@MainActivity
            h.box.clipToOutline = true
            when (val r = rows[position]) {
                is Row.Folder -> {
                    h.title.text = r.name
                    h.sub.text = getString(R.string.n_videos, r.count)
                    h.badge.visibility = View.GONE
                    h.progress.visibility = View.GONE
                    val icon = if (r.special) R.drawable.ic_history else R.drawable.ic_folder
                    if (r.cover != null) Thumbs.load(h.thumb, r.cover.uri, r.cover.id, true, icon)
                    else { h.thumb.setImageResource(icon); h.thumb.scaleType = ImageView.ScaleType.CENTER }
                    h.itemView.setOnClickListener { openFolder(r.id) }
                    h.itemView.setOnLongClickListener(null)
                }
                is Row.VideoRow -> {
                    val v = r.v
                    h.title.text = v.name.substringBeforeLast('.')
                    val ago = DateUtils.getRelativeTimeSpanString(v.date * 1000, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
                    h.sub.text = if (query.isNotBlank() || folder == CONTINUE) "${v.folder} · ${fmtSize(ctx, v.size)}"
                    else "${fmtSize(ctx, v.size)} · $ago"
                    h.badge.visibility = if (v.duration > 0) View.VISIBLE else View.GONE
                    h.badge.text = fmtTime(v.duration)
                    val p = Resume.progress(ctx, v.uri.toString(), v.duration)
                    h.progress.visibility = if (p >= 0) View.VISIBLE else View.GONE
                    h.progress.progress = p.coerceAtLeast(0)
                    Thumbs.load(h.thumb, v.uri, v.id, true, R.drawable.ic_play)
                    h.itemView.setOnClickListener {
                        val vids = rows.filterIsInstance<Row.VideoRow>().map { it.v }
                        playVideos(vids, vids.indexOf(v).coerceAtLeast(0))
                    }
                    h.itemView.setOnLongClickListener { share(v.uri, "video/*"); true }
                }
                is Row.SongRow -> {
                    val s = r.s
                    h.title.text = s.title
                    h.sub.text = listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · ")
                    h.badge.visibility = if (s.duration > 0) View.VISIBLE else View.GONE
                    h.badge.text = fmtTime(s.duration)
                    h.progress.visibility = View.GONE
                    Thumbs.load(h.thumb, s.uri, s.id, false, R.drawable.ic_music)
                    h.itemView.setOnClickListener {
                        val all = rows.filterIsInstance<Row.SongRow>().map { it.s }
                        playSongs(all, all.indexOf(s).coerceAtLeast(0))
                    }
                    h.itemView.setOnLongClickListener { share(s.uri, "audio/*"); true }
                }
            }
        }
    }
}
