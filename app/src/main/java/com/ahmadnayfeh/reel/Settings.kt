package com.ahmadnayfeh.reel

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object Settings {
    fun p(c: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(c)
    private fun b(c: Context, k: String, d: Boolean) = p(c).getBoolean(k, d)
    private fun s(c: Context, k: String, d: String) = p(c).getString(k, d) ?: d

    fun applyTheme(a: Activity) { if (s(a, "theme", "dark") == "amoled") a.setTheme(R.style.Theme_Reel_Amoled) }
    fun showNew(c: Context) = b(c, "show_new", true)
    fun resume(c: Context) = b(c, "resume", true)
    fun autoNext(c: Context) = b(c, "auto_next", true)
    fun bgPlay(c: Context) = b(c, "bg_play", true)
    fun lockSimple(c: Context) = s(c, "lock_controls", "full") == "simple"
    fun setLockSimple(c: Context, on: Boolean) = p(c).edit().putString("lock_controls", if (on) "simple" else "full").apply()
    fun orientation(c: Context) = s(c, "orientation", "video")
    fun softwareAudio(c: Context) = b(c, "sw_audio", false)
    fun keepBrightness(c: Context) = b(c, "keep_brightness", false)
    fun gSeek(c: Context) = b(c, "g_seek", true)
    fun gBright(c: Context) = b(c, "g_bright", true)
    fun gVolume(c: Context) = b(c, "g_volume", true)
    fun gDouble(c: Context) = b(c, "g_double", true)
    fun gPinch(c: Context) = b(c, "g_pinch", true)
    /** Milliseconds covered by one full-width horizontal swipe. */
    fun seekRangeMs(c: Context) = (s(c, "seek_speed", "120").toLongOrNull() ?: 120L) * 1000L
    fun doubleTapMs(c: Context) = (s(c, "double_tap", "10").toLongOrNull() ?: 10L) * 1000L
    fun subScale(c: Context) = s(c, "sub_size", "1.0").toFloatOrNull() ?: 1f
    fun subColor(c: Context) = runCatching { android.graphics.Color.parseColor(s(c, "sub_color", "#FFFFFF")) }.getOrDefault(-1)
    fun subBg(c: Context) = b(c, "sub_bg", false)

    fun getInt(c: Context, k: String, d: Int) = p(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).apply()
    fun getBool(c: Context, k: String, d: Boolean) = p(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun getFloat(c: Context, k: String, d: Float) = p(c).getFloat(k, d)
    fun putFloat(c: Context, k: String, v: Float) = p(c).edit().putFloat(k, v).apply()
}
