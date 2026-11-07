package com.ahmadnayfeh.reel

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    data class Folder(
        val id: String, val name: String, val relPath: String, val music: Boolean,
        val videos: List<Video> = emptyList(), val songs: List<Song> = emptyList(),
    ) {
        val count get() = videos.size + songs.size
        val size get() = videos.sumOf { it.size } + songs.sumOf { it.size }
        val latest get() = maxOf(videos.maxOfOrNull { it.date } ?: 0L, songs.maxOfOrNull { it.date } ?: 0L)
        val cover get() = videos.maxByOrNull { it.date }
        val isEmpty get() = count == 0
        val uris get() = videos.map { it.uri to it.path } + songs.map { it.uri to it.path }
    }

    private sealed class Row {
        data class F(val f: Folder) : Row()
        data class V(val v: Video) : Row()
        data class S(val s: Song) : Row()
    }

    private companion object {
        const val VIDEOS = 0; const val MUSIC = 1
        const val SORT_NAME = 0; const val SORT_DATE = 1; const val SORT_SIZE = 2; const val SORT_DURATION = 3
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyIcon: ImageView
    private lateinit var emptyBtn: View
    private lateinit var nav: BottomNavigationView
    private lateinit var mini: View
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniArt: ImageView
    private lateinit var grid: GridLayoutManager

    private var videos: List<Video> = emptyList()
    private var songs: List<Song> = emptyList()
    private var rows: List<Row> = emptyList()
    private var tab = VIDEOS
    private var folder: String? = null
    private var query = ""
    private var menu: Menu? = null

    private val selected = LinkedHashSet<String>()
    private var actionMode: ActionMode? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val adapter = Adapter()
    private val fileOps by lazy { FileOps(this) { endSelection(); scan() } }
    private val handler = Handler(Looper.getMainLooper())
    private val rescan = Runnable { scan() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { handler.removeCallbacks(rescan); handler.postDelayed(rescan, 800) }
    }

    private val permissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { scan() }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { openFolder(null) }
    }

    private fun atRoot() = folder == null && query.isBlank()
    private fun level() = if (atRoot()) "root" else "in"
    private fun sortKey() = "sort_${tab}_${level()}"
    private fun sort() = Settings.getInt(this, sortKey(), if (atRoot() || tab == MUSIC) SORT_NAME else SORT_DATE)
    private fun desc() = Settings.getBool(this, sortKey() + "_desc", !atRoot() && tab == VIDEOS)
    private fun colsKey() = "cols_${tab}_${level()}"
    private fun maxCols() = maxOf(2, resources.configuration.screenWidthDp / 100)
    private fun cols() = Settings.getInt(this, colsKey(), 2).coerceIn(1, maxCols())
    private fun gridMode() = cols() > 1

    private var pinching = false
    private var pinchAcc = 1f
    private val scaler by lazy {
        android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { pinchAcc = 1f; return true }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                pinchAcc *= d.scaleFactor
                if (pinchAcc > 1.25f) { stepCols(-1); pinchAcc = 1f } else if (pinchAcc < 0.8f) { stepCols(+1); pinchAcc = 1f }
                return true
            }
        })
    }

    private fun pinchAllowed() = rows.isNotEmpty()

    private fun inList(ev: android.view.MotionEvent): Boolean {
        val r = android.graphics.Rect(); list.getGlobalVisibleRect(r)
        return r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    /** Two fingers anywhere on the list resize the tiles; we see the touch before pull-to-refresh or the items do. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (pinchAllowed() || pinching) {
            scaler.onTouchEvent(ev)
            if (!pinching && ev.pointerCount > 1 && inList(ev)) {
                pinching = true
                val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel); cancel.recycle()
            }
            if (pinching) {
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) pinching = false
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Pinch on the list: spread fingers = bigger tiles (fewer columns), pinch = smaller tiles (more columns). */
    private fun stepCols(delta: Int) {
        val n = (cols() + delta).coerceIn(1, maxCols())
        if (n == cols()) return
        Settings.putInt(this, colsKey(), n)
        if (n > 1) Settings.putInt(this, colsKey() + "_last", n)
        android.transition.TransitionManager.beginDelayedTransition(list, android.transition.AutoTransition().setDuration(180))
        render()
        list.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
    private fun pendingKey() = "empty_folders_$tab"
    private fun pending(): Set<String> = Settings.p(this).getStringSet(pendingKey(), emptySet()) ?: emptySet()
    private fun setPending(v: Set<String>) = Settings.p(this).edit().putStringSet(pendingKey(), v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.applyTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, 0)
            nav.setPadding(0, 0, 0, i.bottom)
            ins
        }
        toolbar = findViewById(R.id.toolbar)
        list = findViewById(R.id.list)
        refresh = findViewById(R.id.refresh)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        emptyIcon = findViewById(R.id.emptyIcon)
        emptyBtn = findViewById(R.id.emptyBtn)
        nav = findViewById(R.id.nav)
        mini = findViewById(R.id.mini)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlay = findViewById(R.id.miniPlay)
        miniArt = findViewById(R.id.miniArt)
        miniArt.clipToOutline = true

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { openFolder(null) }
        refresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        refresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.surface2))
        refresh.setOnRefreshListener { scan() }

        grid = GridLayoutManager(this, 1)
        list.layoutManager = grid
        list.adapter = adapter
        list.itemAnimator = null

        tab = Settings.getInt(this, "tab2", VIDEOS).coerceIn(VIDEOS, MUSIC)
        nav.selectedItemId = if (tab == MUSIC) R.id.nav_music else R.id.nav_videos
        nav.setOnItemSelectedListener { item ->
            val t = if (item.itemId == R.id.nav_music) MUSIC else VIDEOS
            if (t != tab) {
                tab = t; folder = null
                Settings.putInt(this, "tab2", tab)
                endSelection(); render(); list.scrollToPosition(0)
            }
            true
        }
        nav.setOnItemReselectedListener { if (folder != null) openFolder(null) else list.smoothScrollToPosition(0) }

        emptyBtn.setOnClickListener { askPermission.launch(permissions) }
        onBackPressedDispatcher.addCallback(this, back)
        mini.setOnClickListener { startActivity(Intent(this, NowPlayingActivity::class.java)) }
        miniPlay.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        findViewById<View>(R.id.miniNext).setOnClickListener { controller?.seekToNext() }

        fileOps // register the result launcher before STARTED
        if (!hasPermission()) askPermission.launch(permissions)
    }

    override fun onStart() {
        super.onStart()
        scan()
        runCatching {
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
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
        runCatching { contentResolver.unregisterContentObserver(observer) }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun hasPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return needed.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun scan() {
        if (!hasPermission()) { refresh.isRefreshing = false; render(); return }
        lifecycleScope.launch {
            val (v, s) = withContext(Dispatchers.IO) { MediaRepo.videos(this@MainActivity) to MediaRepo.songs(this@MainActivity) }
            videos = v; songs = s
            refresh.isRefreshing = false
            render()
        }
    }

    // ---------------- list building ----------------

    private fun sortVideos(l: List<Video>): List<Video> {
        val s = when (sort()) {
            SORT_DATE -> l.sortedBy { it.date }
            SORT_SIZE -> l.sortedBy { it.size }
            SORT_DURATION -> l.sortedBy { it.duration }
            else -> l.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        return if (desc()) s.reversed() else s
    }

    private fun sortSongs(l: List<Song>): List<Song> {
        val s = when (sort()) {
            SORT_DATE -> l.sortedBy { it.date }; SORT_SIZE -> l.sortedBy { it.size }; SORT_DURATION -> l.sortedBy { it.duration }
            else -> l.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
        return if (desc()) s.reversed() else s
    }

    private fun folders(): List<Folder> {
        val music = tab == MUSIC
        val real = if (music) songs.groupBy { it.folderId }.map { (id, l) -> Folder(id, l.first().folder, l.first().relPath, true, songs = l) }
        else videos.groupBy { it.folderId }.map { (id, l) -> Folder(id, l.first().folder, l.first().relPath, false, videos = l) }
        // Folders you created that have no files yet (Android only shows folders that contain media).
        val realPaths = real.map { it.relPath.trimEnd('/') }.toSet()
        val p = pending()
        val stillEmpty = p.filter { it.trimEnd('/') !in realPaths }.toSet()
        if (stillEmpty.size != p.size) setPending(stillEmpty)
        val all = real + stillEmpty.map { Folder("empty:$it", it.trimEnd('/').substringAfterLast('/'), it, music) }
        val s = when (sort()) {
            SORT_DATE -> all.sortedBy { it.latest }
            SORT_SIZE -> all.sortedBy { it.size }
            SORT_DURATION -> all.sortedBy { it.count }
            else -> all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        return if (desc()) s.reversed() else s
    }

    private fun render() {
        val q = query.trim()
        val f = folder
        val music = tab == MUSIC
        rows = when {
            q.isNotEmpty() && music -> sortSongs(songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) || it.folder.contains(q, true) }).map { Row.S(it) }
            q.isNotEmpty() -> sortVideos(videos.filter { it.name.contains(q, true) || it.folder.contains(q, true) }).map { Row.V(it) }
            f == null -> folders().map { Row.F(it) }
            music -> sortSongs(songs.filter { it.folderId == f }).map { Row.S(it) }
            else -> sortVideos(videos.filter { it.folderId == f }).map { Row.V(it) }
        }

        val inFolder = f != null
        if (inFolder) {
            toolbar.title = if (music) songs.firstOrNull { it.folderId == f }?.folder ?: f!!.removePrefix("empty:").trimEnd('/').substringAfterLast('/')
            else videos.firstOrNull { it.folderId == f }?.folder ?: f!!.removePrefix("empty:").trimEnd('/').substringAfterLast('/')
            toolbar.subtitle = resources.getQuantityString(if (music) R.plurals.n_songs else R.plurals.n_videos, rows.size, rows.size)
            toolbar.setNavigationIcon(R.drawable.ic_back)
        } else {
            toolbar.title = getString(if (music) R.string.tab_music else R.string.tab_videos)
            val n = if (q.isEmpty()) rows.size else folders().size
            val total = if (music) songs.size else videos.size
            toolbar.subtitle = resources.getQuantityString(R.plurals.n_folders, n, n) + "  ·  " +
                resources.getQuantityString(if (music) R.plurals.n_songs else R.plurals.n_videos, total, total)
            toolbar.navigationIcon = null
        }
        back.isEnabled = inFolder

        grid.spanCount = if (gridMode()) cols() else 1
        menu?.findItem(R.id.view)?.apply {
            isVisible = true
            setIcon(if (gridMode()) R.drawable.ic_list else R.drawable.ic_grid)
        }
        menu?.findItem(R.id.new_folder)?.isVisible = !inFolder

        val perm = hasPermission()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        emptyBtn.visibility = if (perm) View.GONE else View.VISIBLE
        emptyIcon.setImageResource(if (music) R.drawable.ic_music else R.drawable.ic_video_lib)
        emptyText.text = getString(when {
            !perm -> R.string.grant_access; q.isNotEmpty() -> R.string.no_results
            inFolder && rows.isEmpty() -> R.string.empty_folder; else -> R.string.no_media
        })
        adapter.notifyDataSetChanged()
    }

    private fun openFolder(id: String?) {
        endSelection()
        folder = id
        render()
        list.scrollToPosition(0)
    }

    // ---------------- playback ----------------

    private fun playVideos(l: List<Video>, index: Int) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(l.map { it.key }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(l.map { it.title }))
            .putExtra(PlayerActivity.EXTRA_INDEX, index))
    }

    private fun playSongs(l: List<Song>, index: Int, open: Boolean = true) {
        val c = controller ?: return
        val items = l.map { s ->
            MediaItem.Builder().setMediaId(s.key).setUri(s.uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(s.uri).build())
                .setMediaMetadata(songMeta(s))
                .build()
        }
        c.setMediaItems(items, index, 0)
        c.prepare(); c.play()
        if (open) startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun updateMini() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) { mini.visibility = View.GONE; return }
        mini.visibility = View.VISIBLE
        val m = c.mediaMetadata
        miniTitle.text = m.title ?: ""
        miniSub.text = m.artist ?: ""
        miniPlay.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        c.currentMediaItem?.mediaId?.let { Thumbs.load(miniArt, Uri.parse(it), 0, false, R.drawable.ic_music) }
    }

    // ---------------- selection & file actions ----------------

    private fun selectedVideos() = videos.filter { it.key in selected }
    private fun selectedSongs() = songs.filter { it.key in selected }

    private fun toggle(key: String) {
        if (!selected.add(key)) selected.remove(key)
        if (selected.isEmpty()) { endSelection(); return }
        if (actionMode == null) actionMode = startSupportActionMode(selectionCallback)
        actionMode?.title = getString(R.string.n_selected, selected.size)
        actionMode?.invalidate()
        adapter.notifyDataSetChanged()
    }

    private fun endSelection() {
        selected.clear()
        actionMode?.finish()
        actionMode = null
        adapter.notifyDataSetChanged()
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, m: Menu): Boolean { mode.menuInflater.inflate(R.menu.select, m); return true }
        override fun onPrepareActionMode(mode: ActionMode, m: Menu): Boolean {
            m.findItem(R.id.sel_rename).isVisible = selected.size == 1
            m.findItem(R.id.sel_info).isVisible = selected.size == 1
            m.findItem(R.id.sel_move).isVisible = true
            return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.sel_all -> {
                    rows.forEach { r -> when (r) { is Row.V -> selected += r.v.key; is Row.S -> selected += r.s.key; else -> {} } }
                    mode.title = getString(R.string.n_selected, selected.size); mode.invalidate(); adapter.notifyDataSetChanged()
                }
                R.id.sel_share -> shareSelected()
                R.id.sel_delete -> confirmDelete()
                R.id.sel_rename -> renameSelected()
                R.id.sel_move -> moveSelected()
                R.id.sel_info -> infoSelected()
            }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            if (selected.isNotEmpty()) { selected.clear(); adapter.notifyDataSetChanged() }
        }
    }

    private fun uriList(): ArrayList<Uri> = ArrayList((selectedVideos().map { it.uri }) + selectedSongs().map { it.uri })

    private fun shareSelected() {
        val uris = uriList()
        if (uris.isEmpty()) return
        val type = if (tab == MUSIC) "audio/*" else "video/*"
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        startActivity(Intent.createChooser(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.share)))
    }

    private fun confirmDelete() {
        val v = selectedVideos(); val s = selectedSongs()
        val n = v.size + s.size
        val doIt = { fileOps.delete(v.map { it.uri } + s.map { it.uri }, v.map { it.path } + s.map { it.path }) }
        if (Build.VERSION.SDK_INT >= 30) { doIt(); return } // the system asks for confirmation itself
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.delete_q, n))
            .setPositiveButton(R.string.delete) { _, _ -> doIt() }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun renameSelected() {
        val v = selectedVideos().firstOrNull()
        val s = selectedSongs().firstOrNull()
        val name = v?.name ?: s?.path?.substringAfterLast('/') ?: return
        val uri = v?.uri ?: s!!.uri
        val path = v?.path ?: s?.path
        val ext = name.substringAfterLast('.', "")
        val input = EditText(this).apply { setText(name.substringBeforeLast('.')); setSingleLine(); selectAll() }
        MaterialAlertDialogBuilder(this).setTitle(R.string.rename).setView(padded(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val base = input.text.toString().trim().replace("/", "_")
                if (base.isNotEmpty()) fileOps.rename(uri, path, if (ext.isNotEmpty()) "$base.$ext" else base)
            }
            .setNegativeButton(R.string.cancel, null).show()
        input.requestFocus()
    }

    private fun defaultRoot() = if (tab == MUSIC) "Music/" else "Movies/"

    private fun moveSelected() {
        val items = selectedVideos().map { it.uri to it.path } + selectedSongs().map { it.uri to it.path }
        if (items.isEmpty()) return
        val existing = (if (tab == MUSIC) songs.map { it.relPath } else videos.map { it.relPath }) + pending()
        val targets = existing.filter { it.isNotBlank() }.map { if (it.endsWith("/")) it else "$it/" }.distinct().sorted()
        val s = Sheet(this, getString(R.string.move_to))
        s.row(R.drawable.ic_folder_open, getString(R.string.new_folder)) {
            askFolderName(R.string.new_folder, "") { n -> fileOps.move(items, "${defaultRoot()}$n/") }
        }
        targets.forEach { t ->
            s.row(R.drawable.ic_folder, t.trimEnd('/').substringAfterLast('/'), t.trimEnd('/')) { fileOps.move(items, t) }
        }
        s.show()
    }

    private fun askFolderName(title: Int, current: String, onName: (String) -> Unit) {
        val input = EditText(this).apply { hint = getString(R.string.folder_name); setSingleLine(); setText(current); selectAll() }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(padded(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().trim().replace("/", "_").replace("\\", "_")
                if (n.isNotEmpty()) onName(n)
            }.setNegativeButton(R.string.cancel, null).show()
        input.requestFocus()
    }

    private fun createFolder() {
        askFolderName(R.string.new_folder, "") { n ->
            val rel = "${defaultRoot()}$n/"
            runCatching {
                @Suppress("DEPRECATION")
                java.io.File(android.os.Environment.getExternalStorageDirectory(), rel).mkdirs()
            }
            setPending(pending() + rel)
            render()
            android.widget.Toast.makeText(this, R.string.folder_created, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun renameFolder(f: Folder) {
        askFolderName(R.string.rename_folder, f.name) { n ->
            val parent = f.relPath.trimEnd('/').substringBeforeLast('/', "")
            val newRel = (if (parent.isEmpty()) defaultRoot() else "$parent/") + "$n/"
            if (f.isEmpty) { setPending(pending() - f.relPath + newRel); render() }
            else fileOps.move(f.uris, newRel)
        }
    }

    private fun deleteFolder(f: Folder) {
        if (f.isEmpty) { setPending(pending() - f.relPath); render(); return }
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.delete_folder_q, f.name, f.count))
            .setPositiveButton(R.string.delete) { _, _ -> fileOps.delete(f.uris.map { it.first }, f.uris.map { it.second }) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun infoSelected() {
        val v = selectedVideos().firstOrNull()
        val sg = selectedSongs().firstOrNull()
        val s = Sheet(this, getString(R.string.properties))
        if (v != null) {
            s.text(getString(R.string.file_name), v.name)
            s.text(getString(R.string.location), v.path ?: v.relPath)
            s.text(getString(R.string.size), fmtSize(this, v.size))
            s.text(getString(R.string.duration), fmtTime(v.duration))
            if (v.width > 0) s.text(getString(R.string.resolution), "${v.width}×${v.height}  ${v.quality}")
            s.text(getString(R.string.date_added), DateUtils.formatDateTime(this, v.date * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR))
        } else if (sg != null) {
            s.text(getString(R.string.title), sg.title)
            if (sg.artist.isNotBlank()) s.text(getString(R.string.artist), sg.artist)
            if (sg.album.isNotBlank()) s.text(getString(R.string.album), sg.album)
            s.text(getString(R.string.location), sg.path ?: "—")
            s.text(getString(R.string.size), fmtSize(this, sg.size))
            s.text(getString(R.string.duration), fmtTime(sg.duration))
        }
        s.show()
    }

    private fun itemMenu(r: Row) {
        when (r) {
            is Row.V -> {
                val v = r.v
                val s = Sheet(this, v.title)
                s.row(R.drawable.ic_play, getString(R.string.play)) { val l = rows.filterIsInstance<Row.V>().map { it.v }; playVideos(l, l.indexOf(v)) }
                if (Resume.get(this, v.key) > 0) s.row(R.drawable.ic_refresh, getString(R.string.play_from_start)) { Resume.clear(this, v.key); playVideos(listOf(v), 0) }
                s.row(R.drawable.ic_headphones, getString(R.string.play_audio_only)) { playAsAudio(v) }
                s.row(R.drawable.ic_select_all, getString(R.string.select)) { toggle(v.key) }
                s.row(R.drawable.ic_edit, getString(R.string.rename)) { selected.clear(); selected += v.key; renameSelected(); selected.clear() }
                s.row(R.drawable.ic_move, getString(R.string.move)) { selected.clear(); selected += v.key; moveSelected(); selected.clear() }
                s.row(R.drawable.ic_share, getString(R.string.share)) { selected.clear(); selected += v.key; shareSelected(); selected.clear() }
                s.row(R.drawable.ic_info, getString(R.string.properties)) { selected.clear(); selected += v.key; infoSelected(); selected.clear() }
                s.row(R.drawable.ic_delete, getString(R.string.delete)) { selected.clear(); selected += v.key; confirmDelete() }
                s.show()
            }
            is Row.S -> {
                val sg = r.s
                val s = Sheet(this, sg.title)
                s.row(R.drawable.ic_play, getString(R.string.play)) { val l = rows.filterIsInstance<Row.S>().map { it.s }; playSongs(l, l.indexOf(sg)) }
                s.row(R.drawable.ic_queue, getString(R.string.play_next)) { controller?.let { c -> c.addMediaItem(minOf(c.currentMediaItemIndex + 1, c.mediaItemCount), songItem(sg)); if (c.mediaItemCount == 1) { c.prepare(); c.play() } } }
                s.row(R.drawable.ic_select_all, getString(R.string.select)) { toggle(sg.key) }
                s.row(R.drawable.ic_edit, getString(R.string.rename)) { selected.clear(); selected += sg.key; renameSelected(); selected.clear() }
                s.row(R.drawable.ic_share, getString(R.string.share)) { selected.clear(); selected += sg.key; shareSelected(); selected.clear() }
                s.row(R.drawable.ic_info, getString(R.string.properties)) { selected.clear(); selected += sg.key; infoSelected(); selected.clear() }
                s.row(R.drawable.ic_delete, getString(R.string.delete)) { selected.clear(); selected += sg.key; confirmDelete() }
                s.show()
            }
            is Row.F -> {
                val f = r.f
                val s = Sheet(this, f.name)
                if (!f.isEmpty) {
                    s.row(R.drawable.ic_play, getString(R.string.play_all)) {
                        if (f.music) playSongs(sortSongs(f.songs), 0) else playVideos(sortVideos(f.videos), 0)
                    }
                    if (!f.music) s.row(R.drawable.ic_headphones, getString(R.string.play_audio_only)) {
                        val l = sortVideos(f.videos)
                        controller?.let { c -> c.setMediaItems(l.map { videoAsAudio(it) }); c.prepare(); c.play() }
                    }
                }
                s.row(R.drawable.ic_edit, getString(R.string.rename_folder)) { renameFolder(f) }
                s.row(R.drawable.ic_delete, getString(R.string.delete_folder)) { deleteFolder(f) }
                s.text(getString(R.string.size), fmtSize(this, f.size) + "  ·  " +
                    resources.getQuantityString(if (f.music) R.plurals.n_songs else R.plurals.n_videos, f.count, f.count))
                if (f.relPath.isNotBlank()) s.text(getString(R.string.location), f.relPath)
                s.show()
            }
        }
    }

    private fun songItem(s: Song) = MediaItem.Builder().setMediaId(s.key).setUri(s.uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(s.uri).build())
        .setMediaMetadata(songMeta(s)).build()

    private fun songMeta(s: Song) = MediaMetadata.Builder().setTitle(s.title)
        .setArtist(s.artist.ifBlank { s.folder }).setAlbumTitle(s.album.ifBlank { s.folder })
        .setArtworkUri(s.uri).build()

    private fun videoAsAudio(v: Video) = MediaItem.Builder().setMediaId(v.key).setUri(v.uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(v.uri).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).setArtist(v.folder).setAlbumTitle(v.folder).setArtworkUri(v.uri).build()).build()

    private fun playAsAudio(v: Video) {
        val c = controller ?: return
        c.setMediaItem(videoAsAudio(v), Resume.get(this, v.key)); c.prepare(); c.play()
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun padded(v: View): View = FrameLayout(this).apply {
        setPadding(dp(22), dp(10), dp(22), 0); addView(v)
    }

    // ---------------- menu ----------------

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        menuInflater.inflate(R.menu.main, m)
        menu = m
        (m.findItem(R.id.search).actionView as? SearchView)?.apply {
            queryHint = getString(R.string.search_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean { this@MainActivity.query = q.orEmpty(); render(); return true }
            })
        }
        render()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort -> sortSheet()
            R.id.view -> {
                Settings.putInt(this, colsKey(), if (gridMode()) 1 else Settings.getInt(this, colsKey() + "_last", 2))
                render()
            }
            R.id.refresh -> { refresh.isRefreshing = true; scan() }
            R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.new_folder -> createFolder()
            R.id.url -> {
                val input = EditText(this).apply { hint = getString(R.string.url_hint); setSingleLine() }
                MaterialAlertDialogBuilder(this).setTitle(R.string.open_url).setView(padded(input))
                    .setPositiveButton(R.string.play) { _, _ ->
                        val u = input.text.toString().trim()
                        if (u.isNotEmpty()) startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(u)))
                    }.setNegativeButton(R.string.cancel, null).show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun sortSheet() {
        val s = Sheet(this, getString(R.string.sort))
        val names = listOf(R.string.sort_name, R.string.sort_date, R.string.sort_size, if (atRoot()) R.string.sort_count else R.string.sort_duration)
        names.forEachIndexed { i, n ->
            s.row(R.drawable.ic_sort, getString(n), checked = sort() == i) { Settings.putInt(this, sortKey(), i); render() }
        }
        s.divider()
        s.row(R.drawable.ic_sort, getString(R.string.ascending), checked = !desc()) { Settings.putBool(this, sortKey() + "_desc", false); render() }
        s.row(R.drawable.ic_sort, getString(R.string.descending), checked = desc()) { Settings.putBool(this, sortKey() + "_desc", true); render() }
        s.show()
    }

    // ---------------- adapter ----------------

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val badge: TextView = v.findViewById(R.id.badge)
        val progress: ProgressBar? = v.findViewById(R.id.progress)
        val newTag: TextView? = v.findViewById(R.id.newTag)
        val check: View = v.findViewById(R.id.check)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val more: View = v.findViewById(R.id.more)
        val box: View = v.findViewById(R.id.thumbBox)
        val mosaic: View? = v.findViewById(R.id.mosaic)
        val quality: TextView? = v.findViewById(R.id.quality)
        val playing: View? = v.findViewById(R.id.playing)
        val tiles: List<ImageView> = listOfNotNull(v.findViewById(R.id.m1), v.findViewById(R.id.m2), v.findViewById(R.id.m3), v.findViewById(R.id.m4))
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when {
            rows[position] is Row.S -> if (grid.spanCount > 1) R.layout.item_song_grid else R.layout.item_song
            grid.spanCount > 1 && rows[position] is Row.F -> R.layout.item_folder
            grid.spanCount > 1 -> R.layout.item_grid
            else -> R.layout.item_list
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false)).also { it.box.clipToOutline = true }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val ctx = this@MainActivity
            val r = rows[position]
            val key = when (r) { is Row.V -> r.v.key; is Row.S -> r.s.key; is Row.F -> "" }
            val isSel = key.isNotEmpty() && key in selected
            h.check.visibility = if (isSel) View.VISIBLE else View.GONE
            h.itemView.setBackgroundResource(if (isSel) R.drawable.selected_bg else 0)
            h.more.setOnClickListener { itemMenu(r) }
            h.more.visibility = if (selected.isEmpty()) View.VISIBLE else View.INVISIBLE
            when (r) {
                is Row.F -> {
                    val f = r.f
                    h.title.text = f.name
                    val countText = resources.getQuantityString(if (f.music) R.plurals.n_songs else R.plurals.n_videos, f.count, f.count)
                    h.sub.text = when {
                        f.isEmpty -> getString(R.string.empty_short)
                        grid.spanCount >= 3 -> countText
                        else -> countText + "  ·  " + fmtSize(ctx, f.size)
                    }
                    h.badge.visibility = if (f.isEmpty) View.GONE else View.VISIBLE
                    h.badge.text = f.count.toString()
                    h.progress?.visibility = View.GONE
                    val fresh = Settings.showNew(ctx) && f.videos.any { isNew(it) }
                    h.newTag?.visibility = if (fresh) View.VISIBLE else View.GONE
                    val latest = f.videos.sortedByDescending { it.date }
                    val song = f.songs.firstOrNull()
                    val useMosaic = h.mosaic != null && latest.size >= 4 && grid.spanCount <= 3
                    h.mosaic?.visibility = if (useMosaic) View.VISIBLE else View.GONE
                    h.thumb.visibility = if (useMosaic) View.GONE else View.VISIBLE
                    val folderName = f.name
                    when {
                        useMosaic -> latest.take(4).forEachIndexed { i, v -> Thumbs.load(h.tiles[i], v.uri, v.id, true, R.drawable.ic_play) }
                        latest.isNotEmpty() -> Thumbs.load(h.thumb, latest[0].uri, latest[0].id, true, R.drawable.ic_folder)
                        song != null -> Thumbs.load(h.thumb, song.uri, song.id, false, R.drawable.ic_folder) { ArtLoader.cover(null, folderName, label = false) }
                        f.music -> Thumbs.load(h.thumb, Uri.parse("reel://folder/" + f.relPath), 0, false, R.drawable.ic_folder) { ArtLoader.cover(null, folderName, label = false) }
                        else -> { h.thumb.tag = null; h.thumb.scaleType = ImageView.ScaleType.CENTER; h.thumb.setImageResource(R.drawable.ic_folder) }
                    }
                    h.itemView.setOnClickListener { openFolder(f.id) }
                    h.itemView.setOnLongClickListener { itemMenu(r); true }
                }
                is Row.V -> {
                    val v = r.v
                    h.title.text = v.title
                    val meta = ArrayList<String>()
                    h.quality?.visibility = if (v.quality.isNotEmpty()) View.VISIBLE else View.GONE
                    h.quality?.text = v.quality
                    meta += fmtSize(ctx, v.size)
                    if (grid.spanCount >= 3) Unit
                    else if (folder == null || query.isNotBlank()) meta += v.folder
                    else meta += DateUtils.getRelativeTimeSpanString(v.date * 1000, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
                    h.sub.text = meta.joinToString("  ·  ")
                    h.badge.visibility = if (v.duration > 0) View.VISIBLE else View.GONE
                    h.badge.text = fmtTime(v.duration)
                    val p = Resume.progress(ctx, v.key, v.duration)
                    h.progress?.visibility = if (p >= 0) View.VISIBLE else View.GONE
                    h.progress?.progress = p.coerceAtLeast(0)
                    h.newTag?.visibility = if (Settings.showNew(ctx) && isNew(v)) View.VISIBLE else View.GONE
                    Thumbs.load(h.thumb, v.uri, v.id, true, R.drawable.ic_play)
                    h.itemView.setOnClickListener {
                        if (selected.isNotEmpty()) toggle(v.key)
                        else { val l = rows.filterIsInstance<Row.V>().map { it.v }; playVideos(l, l.indexOf(v).coerceAtLeast(0)) }
                    }
                    h.itemView.setOnLongClickListener { toggle(v.key); true }
                }
                is Row.S -> {
                    val s = r.s
                    h.title.text = s.title
                    val gridCard = grid.spanCount > 1
                    val artist = s.artist.takeIf { it.isNotBlank() && it != s.folder }
                    h.sub.text = when {
                        gridCard -> artist ?: fmtSize(ctx, s.size)
                        artist != null -> "$artist  ·  ${fmtTime(s.duration)}"
                        else -> "${fmtTime(s.duration)}  ·  ${fmtSize(ctx, s.size)}"
                    }
                    h.badge.text = fmtTime(s.duration)
                    val playing = controller?.currentMediaItem?.mediaId == s.key
                    h.playing?.visibility = if (playing && h.check.visibility != View.VISIBLE) View.VISIBLE else View.GONE
                    h.title.setTextColor(ContextCompat.getColor(ctx, if (playing) R.color.accent else R.color.ink))
                    val title = s.title
                    Thumbs.load(h.thumb, s.uri, s.id, false, R.drawable.ic_music) { ArtLoader.cover(title, null, label = false) }
                    h.itemView.setOnClickListener {
                        if (selected.isNotEmpty()) toggle(s.key)
                        else { val l = rows.filterIsInstance<Row.S>().map { it.s }; playSongs(l, l.indexOf(s).coerceAtLeast(0)) }
                    }
                    h.itemView.setOnLongClickListener { toggle(s.key); true }
                }
            }
        }
    }

    /** "NEW": added in the last 14 days and never opened. */
    private fun isNew(v: Video) = !Resume.seen(this, v.key) && System.currentTimeMillis() / 1000 - v.date < 14 * 86_400
}
