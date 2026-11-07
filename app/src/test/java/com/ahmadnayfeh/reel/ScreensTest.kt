package com.ahmadnayfeh.reel

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Opens each screen (catches crashes) and saves screenshots to build/shots for a visual check. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreensTest {

    private fun fake() {
        val now = System.currentTimeMillis() / 1000
        val names = listOf("Interstellar.2014.1080p.mkv", "Family trip - Abha.mp4", "Lecture 07 - Power systems.mp4",
            "Dune Part Two (2024) 4K HDR.mkv", "WhatsApp Video 2026-10-01.mp4", "Football highlights.mp4")
        MediaRepo.fakeVideos = names.mapIndexed { i, n ->
            val folder = listOf("Movies", "Camera", "Lectures", "Movies", "WhatsApp Video", "Download")[i]
            Video(i.toLong(), Uri.parse("content://media/external/video/media/$i"), n, (i + 1) * 1_430_000L, (i + 1) * 380_000_000L,
                now - i * 86_400L * 5, folder, folder, if (i == 3) 3840 else 1920, if (i == 3) 2160 else 1080, "$folder/", "/sdcard/$folder/$n")
        }
        MediaRepo.fakeSongs = (1..5).map { i ->
            val fo = if (i <= 3) "Quran" else "Podcasts"
            Song(100L + i, Uri.parse("content://media/external/audio/media/$i"), "Song number $i", "Artist $i", "Album", 200_000L + i * 10_000, 5_000_000, now, null, fo, fo, "Music/$fo/")
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app, PlaybackService::class.java), null)
        Resume.save(app, MediaRepo.fakeVideos!![2].key, 2_000_000L, 4_290_000L)
    }

    private fun settle() = repeat(15) { Thread.sleep(40); shadowOf(Looper.getMainLooper()).idle() }

    private fun shot(a: Activity, name: String) {
        val v: View = a.window.decorView
        val w = v.width.takeIf { it > 0 } ?: 1200
        val h = v.height.takeIf { it > 0 } ?: 2580
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun library() {
        fake()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        val a = c.get()
        val nav = a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav)
        val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        fun layout() = a.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2580, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1200, 2580)
        }
        nav.selectedItemId = R.id.nav_videos; settle(); layout()
        shot(a, "1_video_folders")
        list.getChildAt(0).performClick(); settle(); layout()
        shot(a, "2_video_folder_open")
        list.getChildAt(0).performLongClick(); settle(); layout()
        shot(a, "3_selection")
        fun call(name: String, vararg args: Any?): Any? {
            val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            m.isAccessible = true; val r = m.invoke(a, *args); settle(); return r
        }
        call("moveSelected")                       // move sheet with "New folder" + existing folders
        a.onBackPressedDispatcher.onBackPressed(); settle()
        a.onBackPressedDispatcher.onBackPressed(); settle(); layout()
        val before = list.adapter!!.itemCount
        call("setPending", setOf("Movies/My new folder/")); call("render"); layout()
        org.junit.Assert.assertEquals(before + 1, list.adapter!!.itemCount)   // empty folder is listed
        shot(a, "3b_new_empty_folder")
        val folderCls = MainActivity::class.java.declaredClasses.first { it.simpleName == "Folder" }
        val empty = folderCls.constructors.first { it.parameterCount == 6 }
            .newInstance("empty:Movies/My new folder/", "My new folder", "Movies/My new folder/", false, emptyList<Any>(), emptyList<Any>())
        call("renameFolder", empty); call("deleteFolder", empty); call("createFolder")
        org.junit.Assert.assertEquals(before, list.adapter!!.itemCount)       // deleting the empty folder removes it
        a.onBackPressedDispatcher.onBackPressed(); settle()
        a.onBackPressedDispatcher.onBackPressed(); settle()
        nav.selectedItemId = R.id.nav_music; settle(); layout()
        shot(a, "4_music_folders")
        list.getChildAt(0).performClick(); settle(); layout()
        shot(a, "5_music_folder_open")
    }

    @Test fun player() {
        fake()
        val intent = Intent(ApplicationProvider.getApplicationContext(), PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, arrayListOf("file:///nonexistent/a.mp4", "file:///nonexistent/b.mp4"))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf("Interstellar 2014 1080p", "Next one"))
        val c = Robolectric.buildActivity(PlayerActivity::class.java, intent).setup()
        settle()
        val a = c.get()
        val pv = a.findViewById<androidx.media3.ui.PlayerView>(R.id.pv)
        pv.showController()
        settle(); shot(a, "5_player_controls")
        a.findViewById<View>(R.id.reel_more).performClick()
        settle()
        a.findViewById<View>(R.id.reel_subs).performClick(); settle()
        a.findViewById<View>(R.id.reel_audio).performClick(); settle()
        a.findViewById<View>(R.id.reel_speed).performClick(); settle()
        a.findViewById<View>(R.id.reel_queue).performClick(); settle()
        a.findViewById<View>(R.id.reel_shot).performClick(); settle()
        fun call(name: String, vararg args: Any) {
            val m = PlayerActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            m.isAccessible = true; m.invoke(a, *args); settle()
        }
        call("subDelaySheet"); call("applySubDelay", 500L); call("applySubDelay", -500L); call("applySubDelay", 0L)
        call("subtitleSheet"); call("moreSheet")
        c.pause().stop().destroy()
    }

    @Test fun nowPlayingAndSettings() {
        fake()
        val n = Robolectric.buildActivity(NowPlayingActivity::class.java).setup()
        settle(); shot(n.get(), "6_now_playing")
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        settle(); shot(s.get(), "7_settings")
    }
}
