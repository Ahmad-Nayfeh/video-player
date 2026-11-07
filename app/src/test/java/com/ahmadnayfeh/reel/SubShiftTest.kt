package com.ahmadnayfeh.reel

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubShiftTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    private fun shift(name: String, text: String, ms: Long): String {
        val f = File(ctx.cacheDir, name).apply { writeText(text) }
        val out = SubShift.shifted(ctx, Uri.fromFile(f), name, ms)!!
        return File(out.path!!).readText()
    }

    @Test fun srtLaterAndEarlier() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHello\n\n2\n00:59:59,900 --> 01:00:00,400\nBye\n"
        val later = shift("a.srt", srt, 1_500)
        assertTrue(later, later.contains("00:00:03,000 --> 00:00:04,500"))
        assertTrue(later, later.contains("01:00:01,400 --> 01:00:01,900"))
        val earlier = shift("b.srt", srt, -2_000)
        assertTrue(earlier, earlier.contains("00:00:00,000 --> 00:00:01,000")) // clamped at zero
        assertTrue(earlier, earlier.contains("Hello"))
    }

    @Test fun vtt() {
        val vtt = "WEBVTT\n\n00:01:02.250 --> 00:01:04.000\nHi\n"
        assertTrue(shift("c.vtt", vtt, 750).contains("00:01:03.000 --> 00:01:04.750"))
    }

    @Test fun assOnlyDialogueLinesChange() {
        val ass = "[Script Info]\nTitle: 0:00:01.00 test\n[Events]\nDialogue: 0,0:00:01.50,0:00:02.75,Default,,0,0,0,,Hi\n"
        val out = shift("d.ass", ass, 1_250)
        assertTrue(out, out.contains("Dialogue: 0,0:00:02.75,0:00:04.00,Default"))
        assertTrue(out, out.contains("Title: 0:00:01.00 test"))
        assertEquals(ass.lines().size, out.lines().size)
    }
}

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CoverTest {
    @Test fun generatedCovers() {
        val dir = File("build/shots").apply { mkdirs() }
        listOf("آية من سورتي العنكبوت والروم" to "القرآن الكريم", "Lecture 07" to "Podcasts", "Song" to null).forEachIndexed { i, (t, s) ->
            val b = ArtLoader.cover(t, s)
            assertEquals(640, b.width)
            File(dir, "cover_$i.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
