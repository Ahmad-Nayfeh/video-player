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
            Song(100L + i, Uri.parse("content://media/external/audio/media/$i"), "Song number $i", "Artist $i", "Album", 200_000L + i * 10_000, 5_000_000, now, null)
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
        Settings.putInt(a, "tab", 0)
        shot(a, "1_folders")
        a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav).selectedItemId = R.id.nav_videos
        settle(); shot(a, "2_videos")
        a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav).selectedItemId = R.id.nav_music
        settle(); shot(a, "3_music")
        a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav).selectedItemId = R.id.nav_videos
        settle()
        a.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2580, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1200, 2580)
        }
        val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        list.getChildAt(0).performLongClick()
        list.getChildAt(1).performClick()
        settle(); shot(a, "4_selection")
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
        a.findViewById<View>(R.id.reel_color).performClick(); settle()
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
