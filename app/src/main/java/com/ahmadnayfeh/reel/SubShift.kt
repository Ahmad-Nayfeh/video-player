package com.ahmadnayfeh.reel

import android.content.Context
import android.net.Uri
import java.io.File

/** Writes a copy of a subtitle file with every timestamp moved by [shiftMs] (negative = earlier). */
object SubShift {
    private val srt = Regex("""(\d{1,2}):(\d{2}):(\d{2})([,.])(\d{3})""")
    private val ass = Regex("""(\d{1,2}):(\d{2}):(\d{2})\.(\d{2})(?!\d)""")

    fun shifted(ctx: Context, src: Uri, name: String, shiftMs: Long): Uri? = runCatching {
        val text = ctx.contentResolver.openInputStream(src)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val lower = name.lowercase()
        val out = if (lower.endsWith(".ass") || lower.endsWith(".ssa")) {
            text.lineSequence().joinToString("\n") { line ->
                if (!line.startsWith("Dialogue:")) line else ass.replace(line) { m ->
                    val (h, mi, s, cs) = m.destructured
                    val t = ((h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + cs.toLong() * 10 + shiftMs).coerceAtLeast(0)
                    "%d:%02d:%02d.%02d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000 / 10)
                }
            }
        } else {
            srt.replace(text) { m ->
                val (h, mi, s, sep, ms) = m.destructured
                val t = ((h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + ms.toLong() + shiftMs).coerceAtLeast(0)
                "%02d:%02d:%02d%s%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
            }
        }
        val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
        val ext = name.substringAfterLast('.', "srt")
        val f = File(dir, "${src.toString().hashCode()}_${shiftMs}.$ext")
        f.writeText(out)
        Uri.fromFile(f)
    }.getOrNull()
}
