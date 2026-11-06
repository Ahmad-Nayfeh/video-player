package com.ahmadnayfeh.reel

import androidx.media3.common.Effect
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter

/** Picture modes, applied on the GPU while the video plays. */
object ColorModes {
    data class Mode(val key: String, val label: Int, val effects: () -> List<Effect>)

    private fun sat(v: Float) = HslAdjustment.Builder().adjustSaturation(v).build()
    private fun rgb(r: Float, g: Float, b: Float) = RgbAdjustment.Builder().setRedScale(r).setGreenScale(g).setBlueScale(b).build()

    val all = listOf(
        Mode("normal", R.string.cm_normal) { emptyList() },
        Mode("enhance", R.string.cm_enhance) { listOf(Contrast(0.10f), sat(14f), Brightness(0.02f)) },
        Mode("vivid", R.string.cm_vivid) { listOf(Contrast(0.12f), sat(32f)) },
        Mode("sport", R.string.cm_sport) { listOf(Contrast(0.20f), sat(24f), Brightness(0.03f)) },
        Mode("cinema", R.string.cm_cinema) { listOf(Contrast(0.14f), sat(-10f), rgb(1.05f, 1.0f, 0.92f)) },
        Mode("night", R.string.cm_night) { listOf(rgb(1.0f, 0.88f, 0.68f), Brightness(-0.06f)) },
        Mode("warm", R.string.cm_warm) { listOf(rgb(1.08f, 1.0f, 0.86f)) },
        Mode("cool", R.string.cm_cool) { listOf(rgb(0.9f, 0.98f, 1.1f)) },
        Mode("bright", R.string.cm_bright) { listOf(Brightness(0.10f), Contrast(-0.04f)) },
        Mode("mono", R.string.cm_mono) { listOf(RgbFilter.createGrayscaleFilter(), Contrast(0.08f)) },
    )

    fun byKey(k: String?) = all.firstOrNull { it.key == k } ?: all[0]
}
